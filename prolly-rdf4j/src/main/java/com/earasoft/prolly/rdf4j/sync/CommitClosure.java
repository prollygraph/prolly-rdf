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
package com.earasoft.prolly.rdf4j.sync;

import com.dolthub.prolly.HashUtils;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the <em>commit closure</em> of a branch head over a {@link CommitLog} — the set of
 * commits reachable by walking parent edges. This is the <b>anti-entropy / set-reconciliation</b>
 * primitive of distributed sync: given a remote {@code want} head and the commits a receiver
 * already {@code have}s, it computes the history delta {@code ancestors(want) \ ancestors(have)}
 * that one replica must transfer to reconcile its commit DAG with another's <b>divergent
 * history</b>.
 *
 * <p>This is the "history" half of a transfer: a fetch or push carries the chunk pack (the data,
 * Merkle-walkable from a RootMetaTree hash) <em>and</em> this closure of {@link CommitLog.Entry}s —
 * the DAG edges, timestamps and messages, which live in the non-content-addressed {@code
 * commits.log} sidecar. See {@code plans/distributed-sync.md} Phase 1.
 *
 * <p>Results are returned <b>ancestors-first</b> — a parent always precedes every child that names
 * it. That ordering falls out of the {@code CommitLog}'s append-only nature (a child is only
 * appended after its parents), so the closure is the log filtered in its natural order. Ordering
 * lets a receiver ingest the batch top to bottom without forward-referencing a not-yet-appended
 * parent.
 *
 * @implNote <b>Ordering is not completeness — and completeness is the subtle one.</b> A receiver
 *     sees no <i>dangling</i> parent only when every parent of every returned commit is itself in
 *     the batch or already in the receiver's log — the anti-entropy invariant {@link
 *     CommitLogSync#mergeInto} enforces. Because the {@code have} a receiver advertises is its
 *     <b>ref-heads</b> (not its full log), that completeness does <b>not</b> hold unconditionally
 *     under <b>multi-peer divergence</b> (notably around merge commits' second-parent lineage): a
 *     <b>known gap</b> the convergence property {@code SyncFuzzTest} exposes and the
 *     set-reconciliation hardening ({@code plans/prepublic/sync-anti-entropy-completeness.md})
 *     closes. Until then this returns an <i>ancestors-first ordered</i> delta, not a <i>guaranteed
 *     ancestor-complete</i> one across divergent peers.
 */
public final class CommitClosure {

    private CommitClosure() {}

    /**
     * Every commit reachable from {@code head} by parent-walk, {@code head} inclusive, ordered
     * ancestors-first.
     *
     * @throws IllegalArgumentException if {@code head} is null or not present in {@code log}
     */
    public static List<CommitLog.Entry> reachable(CommitLog log, byte[] head) throws IOException {
        return reachable(log, head, List.of());
    }

    /**
     * The commits reachable from {@code head} but from none of {@code have} — the history delta a
     * fetch or push must transfer.
     *
     * <p>A {@code have} hash absent from {@code log} excludes only itself — its ancestors cannot be
     * known from this log. That is safe: over-sending a few history entries is cheap and the
     * receiver's merge dedups them.
     *
     * @throws IllegalArgumentException if {@code head} is null or not present in {@code log}
     */
    public static List<CommitLog.Entry> reachable(
            CommitLog log, byte[] head, Collection<byte[]> have) throws IOException {
        if (head == null) {
            throw new IllegalArgumentException("head must not be null");
        }
        List<CommitLog.Entry> all = log.entries();
        // Key by commit id (ADR-0071), not the tree hash: head/have/parents are all commit ids, and
        // two distinct commits can share a tree hash — keying by tree hash collapses them (the
        // dangling-parent bug). The id is the unambiguous graph handle.
        Map<String, CommitLog.Entry> byHash = new HashMap<>(all.size() * 2);
        for (CommitLog.Entry e : all) {
            byHash.put(HashUtils.toHex(e.id()), e);
        }
        String headHex = HashUtils.toHex(head);
        if (!byHash.containsKey(headHex)) {
            throw new IllegalArgumentException("commit not in log: " + headHex);
        }

        Set<String> excluded = ancestorHashes(have, byHash);
        Set<String> wanted = ancestorHashes(List.of(head), byHash);
        wanted.removeAll(excluded);

        // The CommitLog is append-only and a child is always appended after its
        // parents, so filtering the log in its own order yields ancestors-first.
        List<CommitLog.Entry> out = new ArrayList<>(wanted.size());
        Set<String> emitted = new HashSet<>();
        for (CommitLog.Entry e : all) {
            String hex = HashUtils.toHex(e.id());
            if (wanted.contains(hex) && emitted.add(hex)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The set of commit hashes reachable from {@code roots} by parent-walk, roots inclusive. A root
     * or parent absent from {@code byHash} contributes only itself — its own ancestors cannot be
     * known from this log.
     */
    private static Set<String> ancestorHashes(
            Collection<byte[]> roots, Map<String, CommitLog.Entry> byHash) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (byte[] r : roots) {
            queue.add(HashUtils.toHex(r));
        }
        while (!queue.isEmpty()) {
            String hex = queue.poll();
            if (!seen.add(hex)) {
                continue;
            }
            CommitLog.Entry e = byHash.get(hex);
            if (e == null) {
                continue; // not in this log — a `have` we lack, or a torn parent
            }
            for (byte[] p : e.parents()) {
                queue.add(HashUtils.toHex(p));
            }
        }
        return seen;
    }
}
