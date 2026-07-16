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
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.sync.SyncCommitEntry;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the {@link SyncPack} for one direction of a transfer — everything reachable from commit
 * {@code want} that is not reachable from {@code have}.
 *
 * <p>The same computation serves both directions: a fetch runs it on the <em>remote</em> (walk what
 * the client wants, prune what it has); a push runs it on the <em>local</em> repo (walk the local
 * head, prune what the remote has). The caller supplies whichever store + commit log is its own.
 * See {@code plans/distributed-sync.md} Phase 2.
 */
public final class PackBuilder {

    private PackBuilder() {}

    /**
     * The data + history delta from {@code have} to {@code want}.
     *
     * @param store the chunk store to walk (the builder's own side)
     * @param log the commit log to walk (the builder's own side)
     * @param want the commit whose closure is wanted
     * @param have commit hashes the other side already holds (pruned out)
     */
    public static SyncPack build(
            NodeStore store, CommitLog log, byte[] want, Collection<byte[]> have)
            throws IOException {
        return build(store, log, want, have, Set.of());
    }

    /**
     * Graph-aware overload — same shape as the 4-arg method but additionally drops CSPO leaf chunks
     * whose every row's context-column is in {@code excludedContextTermIds}. Used by the auth-graph
     * syncpack filter (plans/auth-graph-syncpack-filter.md) — operators on {@code
     * auth.backend=sparql} pass the resolved TermIds for {@code <urn:prolly-rdf4j:auth/users>} +
     * {@code <urn:prolly-rdf4j:auth/pseudonyms>} so a clone doesn't inherit the host's user table.
     *
     * <p><b>Best-effort filter:</b> the protection applies only to CSPO leaves; SPOC/POSC/OSPC ship
     * auth-graph rows interleaved with data-graph rows at the chunk level. See {@link
     * ChunkGraphFilter} javadoc + the Step 1 investigative test for the chunk-layout rationale. The
     * dict + namespaces + stats + prefixes + provenance subtrees are NEVER filtered (graph-shared
     * state).
     *
     * @param excludedContextTermIds resolved {@code TermId.value()} numeric values for the graph
     *     IRIs to filter — empty for the legacy behavior (no filtering).
     */
    public static SyncPack build(
            NodeStore store,
            CommitLog log,
            byte[] want,
            Collection<byte[]> have,
            Set<Long> excludedContextTermIds)
            throws IOException {
        if (want == null) {
            throw new IllegalArgumentException("want must not be null");
        }
        Collection<byte[]> haveOrEmpty = (have == null) ? List.of() : have;
        Set<Long> filterTermIds =
                (excludedContextTermIds == null) ? Set.of() : excludedContextTermIds;

        // Everything the other side already has — and its subtrees — is pruned.
        Set<String> excluded = new HashSet<>();
        for (byte[] h : haveOrEmpty) {
            excluded.addAll(ChunkReachability.from(store, h, Set.of()));
        }

        // The commits in the delta. Every commit's RootMetaTree closure must
        // be in the pack — not just the head's — so the receiver's commit DAG
        // resolves cleanly (plan Step 22's metaTreeHash integrity check
        // depends on this). Ancestor commits' trees are not reachable from
        // {@code want}'s tree (they're disjoint snapshots), so a walk from
        // {@code want} alone would leave the receiver with phantom history.
        List<CommitLog.Entry> commits = CommitClosure.reachable(log, want, haveOrEmpty);

        Set<String> packHexes = new HashSet<>();
        for (CommitLog.Entry e : commits) {
            // ADR-0073 Phase 3: the commit's own content-addressed chunk travels in the pack, so
            // the
            // receiver holds each pulled commit as a chunk (not only a log row) — the precondition
            // for reading commit content from chunks. Guarded by presence: a commit predating
            // chunk-storage has no chunk yet (Phase 4 migration backfills those); the receiver
            // still
            // reconstructs it from the fat log row it also receives.
            if (store.read(e.id()).isPresent()) {
                packHexes.add(HashUtils.toHex(e.id()));
            }
            Set<String> prune = unionForCommit(store, e.metaTreeHash(), excluded, filterTermIds);
            packHexes.addAll(ChunkReachability.from(store, e.metaTreeHash(), prune));
        }
        // Fall through for the want-already-have case: no delta commits, no
        // delta chunks (we may still hand back a non-empty commits list if
        // the receiver explicitly asked for the head it lacks — but
        // CommitClosure handles that already).
        if (commits.isEmpty()) {
            Set<String> prune = unionForCommit(store, want, excluded, filterTermIds);
            packHexes.addAll(ChunkReachability.from(store, want, prune));
        }

        List<byte[]> chunks = new ArrayList<>(packHexes.size());
        for (String hex : packHexes) {
            chunks.add(readChunk(store, HashUtils.fromHex(hex)));
        }
        // The one adapter seam of extract-prolly-sync-module D-1: the sail-owned
        // CommitLog.Entry maps field-for-field onto the sync-owned wire entry.
        List<SyncCommitEntry> wireEntries = new ArrayList<>(commits.size());
        for (CommitLog.Entry e : commits) {
            wireEntries.add(
                    new SyncCommitEntry(
                            e.timestamp(),
                            e.id(),
                            e.metaTreeHash(),
                            e.parents(),
                            e.message(),
                            e.author()));
        }
        return new SyncPack(chunks, wireEntries);
    }

    /**
     * Union of the receiver's already-has prune set with the auth-only leaves of this commit
     * (CSPO-only per the filter scope). Returns the existing {@code haveExcluded} set as-is when
     * {@code filterTermIds} is empty — zero-cost for the legacy code path.
     */
    private static Set<String> unionForCommit(
            NodeStore store, byte[] commitHash, Set<String> haveExcluded, Set<Long> filterTermIds) {
        if (filterTermIds.isEmpty()) return haveExcluded;
        Set<String> authOnly = ChunkGraphFilter.authOnlyLeaves(store, commitHash, filterTermIds);
        if (authOnly.isEmpty()) return haveExcluded;
        Set<String> union = new HashSet<>(haveExcluded);
        union.addAll(authOnly);
        return union;
    }

    private static byte[] readChunk(NodeStore store, byte[] hash) {
        MemorySegment seg =
                store.read(hash)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "chunk missing from store: "
                                                        + HashUtils.toHex(hash)));
        return seg.toArray(ValueLayout.JAVA_BYTE);
    }
}
