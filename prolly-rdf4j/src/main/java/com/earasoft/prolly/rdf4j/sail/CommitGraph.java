/*
 * Copyright 2026 Earasoft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.earasoft.prolly.rdf4j.sail;

import com.dolthub.prolly.HashUtils;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Ancestry queries over a {@link CommitLog}'s commit DAG — the single shared ancestry API for the
 * project.
 *
 * <p>A {@code CommitGraph} snapshots a {@code CommitLog} at construction into hash → parents and
 * hash → timestamp maps, then answers {@link #ancestors}, {@link #isAncestor} and {@link
 * #mergeBase} in memory. {@link MergeEngine}'s 3-way merge and the distributed-sync fast-forward
 * check / pull-merge all route through it, so there is one implementation of "is X an ancestor of
 * Y" and "what is the merge base of X and Y". See {@code plans/distributed-sync.md} Phase 1.
 *
 * <p>Lives in the {@code sail} package (next to {@code CommitLog} and {@code MergeEngine}) rather
 * than {@code sync}: it is a {@code CommitLog} query that a {@code sail} class consumes, and
 * placing it here keeps the {@code sail} → {@code sync} package dependency one-directional.
 */
public final class CommitGraph {

    /** commit hash (hex) → its parent hashes. */
    private final Map<String, List<byte[]>> parents;

    /** commit hash (hex) → its wall-clock timestamp. */
    private final Map<String, Instant> timestamps;

    /** Snapshot {@code log}'s DAG. Subsequent appends to the log are not seen. */
    public CommitGraph(CommitLog log) throws IOException {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        List<CommitLog.Entry> entries = log.entries();
        this.parents = new HashMap<>(entries.size() * 2);
        this.timestamps = new HashMap<>(entries.size() * 2);
        for (CommitLog.Entry e : entries) {
            // Key the DAG by commit id (ADR-0071), not the tree hash: parents/a/b are commit ids,
            // and two distinct commits can share a tree hash. The merge-base result is therefore a
            // commit id (a handle the caller resolves to a tree to open).
            String hex = HashUtils.toHex(e.id());
            parents.put(hex, e.parents());
            timestamps.put(hex, e.timestamp());
        }
    }

    /**
     * Every commit reachable from {@code hash} by parent-walk, {@code hash} inclusive. A {@code
     * hash} (or parent) absent from the graph contributes only itself — its own ancestors are
     * unknown here.
     */
    public List<byte[]> ancestors(byte[] hash) {
        if (hash == null) {
            throw new IllegalArgumentException("hash must not be null");
        }
        List<byte[]> out = new ArrayList<>();
        for (String hex : ancestorHexSet(hash)) {
            out.add(HashUtils.fromHex(hex));
        }
        return out;
    }

    /**
     * True iff {@code a} is an ancestor of {@code b} — inclusive, so a commit is its own ancestor
     * ({@code a == b} ⇒ true). This is the fast-forward predicate: advancing a branch from {@code
     * a} to {@code b} is a fast-forward iff {@code isAncestor(a, b)}.
     */
    public boolean isAncestor(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        if (Arrays.equals(a, b)) {
            return true;
        }
        return ancestorHexSet(b).contains(HashUtils.toHex(a));
    }

    /**
     * The lowest common ancestor (merge base) of {@code a} and {@code b}, or empty if either is
     * unknown or they share no ancestor.
     *
     * <p>The merge base is the common ancestor that is not itself a (proper) ancestor of another
     * common ancestor — a plain "closest by hop count" walk is wrong once the DAG has merge
     * commits. Criss-cross merges can leave several candidate bases; the most recent by timestamp
     * is chosen.
     */
    public Optional<byte[]> mergeBase(byte @Nullable [] a, byte @Nullable [] b) {
        if (a == null || b == null) {
            return Optional.empty();
        }
        if (Arrays.equals(a, b)) {
            return Optional.of(a.clone());
        }

        Set<String> common = new HashSet<>(ancestorHexSet(a));
        common.retainAll(ancestorHexSet(b));
        if (common.isEmpty()) {
            return Optional.empty();
        }

        // Drop any common ancestor that is itself an ancestor of another
        // common ancestor — what remains is the lowest common ancestor(s).
        Set<String> dominated = new HashSet<>();
        for (String c : common) {
            List<byte[]> ps = parents.get(c);
            if (ps == null) {
                continue;
            }
            for (byte[] p : ps) {
                for (String anc : ancestorHexSet(p)) {
                    if (common.contains(anc)) {
                        dominated.add(anc);
                    }
                }
            }
        }
        Set<String> bases = new HashSet<>(common);
        bases.removeAll(dominated);
        // Normally one commit; >1 only for criss-cross merges. Break ties (and
        // the defensive empty case) by most-recent timestamp.
        Set<String> pick = bases.isEmpty() ? common : bases;
        String best = null;
        Instant bestTs = null;
        for (String c : pick) {
            Instant ts = timestamps.get(c);
            if (best == null || (ts != null && (bestTs == null || ts.isAfter(bestTs)))) {
                best = c;
                bestTs = ts;
            }
        }
        // pick is non-empty (common is non-empty — checked above — and pick falls back to it), so
        // the loop assigned best at least once.
        return Optional.of(HashUtils.fromHex(Objects.requireNonNull(best)));
    }

    /** The hex hashes reachable from {@code start} by parent-walk, start inclusive. */
    private Set<String> ancestorHexSet(byte[] start) {
        Set<String> out = new LinkedHashSet<>();
        Deque<byte[]> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            byte[] curr = queue.poll();
            String hex = HashUtils.toHex(curr);
            if (!out.add(hex)) {
                continue;
            }
            List<byte[]> ps = parents.get(hex);
            if (ps != null) {
                queue.addAll(ps);
            }
        }
        return out;
    }
}
