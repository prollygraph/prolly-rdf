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
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.sync.SyncCommitEntry;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Receive-side merge of a pack's commit-history batch into the sail's {@link CommitLog} — the rdf4j
 * face's history sink. The wire text itself is owned by {@code SyncPackCodec} (it moved there with
 * the pack ownership — extract-prolly-sync-module D-1); this class holds only the CommitLog-coupled
 * half: {@link #mergeInto}.
 *
 * <h2>Merge</h2>
 *
 * <p>{@link #mergeInto} folds a received batch into a local {@code CommitLog}: it appends entries
 * the local log lacks (dedup by {@code metaTreeHash}, preserving each entry's own timestamp /
 * parents / message) and <b>rejects the whole batch</b> — appending nothing — if it has a dangling
 * parent or a cycle. CommitLog entries are not themselves content-addressed; a batch entry whose
 * hash the local log already holds is skipped (local wins), so a remote cannot rewrite an existing
 * commit's parentage.
 */
public final class CommitLogSync {

    /**
     * Fold {@code batch} into {@code local}: append every entry {@code local} does not already
     * hold, in an ancestors-first order, preserving each entry's own timestamp / parents / message.
     *
     * <p>The whole batch is validated <em>before</em> anything is appended, so a rejected batch
     * leaves {@code local} untouched. A batch is rejected if it has a <b>dangling parent</b> (a
     * parent that resolves to neither the local log nor the batch itself) or a <b>cycle</b>.
     *
     * <p>This rejection is the <b>receiver-side enforcement of the anti-entropy completeness
     * invariant</b>: a set-reconciliation batch must be <i>ancestor-closed</i> relative to the
     * local commit-log (every parent already held, or present in the batch). It is the
     * <i>specification</i> of completeness — the sender's closure ({@link CommitClosure#reachable})
     * must satisfy it, and under <b>multi-peer divergence</b> that is a known, in-progress
     * guarantee (see {@code plans/prepublic/sync-anti-entropy-completeness.md}). Keep this guard
     * even once the sender is fixed: it is the defense-in-depth that turns a silent non-convergence
     * into a loud, safe abort.
     *
     * @return the number of entries actually appended (batch size minus the entries the local log
     *     already had)
     * @throws IllegalArgumentException if the batch has a dangling parent or a cycle
     */
    public static int mergeInto(CommitLog local, List<SyncCommitEntry> batch) throws IOException {
        return mergeInto(local, batch, null);
    }

    /**
     * As {@link #mergeInto(CommitLog, List)} but additionally verifies — when {@code store} is
     * non-null — that every fresh entry's {@code metaTreeHash} resolves to a present {@link
     * RootMetaTree} chunk in {@code store} (plan Step 22, integrity & anti-tamper).
     *
     * <p>CommitLog entries are <em>not</em> themselves content-addressed in Design A — their
     * identity is their {@code metaTreeHash}, not a hash of their own bytes. Without this check, a
     * malicious sender could craft a history entry referencing a metaTreeHash the pack never
     * delivered, leaving the local commit DAG with a phantom commit that {@code
     * RootMetaTree.readFrom} would later trip over. Verification runs <i>after</i> the
     * parent-resolution + topological-order checks but <i>before</i> any append, so a bad batch
     * leaves {@code local} untouched.
     *
     * @throws IllegalArgumentException if any fresh entry references a metaTreeHash that does not
     *     resolve to a {@link RootMetaTree} in {@code store}
     */
    public static int mergeInto(
            CommitLog local, List<SyncCommitEntry> batch, @Nullable NodeStore store)
            throws IOException {
        Set<String> localHashes = new HashSet<>();
        for (CommitLog.Entry e : local.entries()) {
            localHashes.add(e.hashHex());
        }

        // Index the batch by hash (dedup within the batch — first wins; entries
        // sharing a metaTreeHash are the same commit by id).
        Map<String, SyncCommitEntry> batchByHash = new LinkedHashMap<>();
        for (SyncCommitEntry e : batch) {
            batchByHash.putIfAbsent(e.hashHex(), e);
        }

        // Dangling-parent check: every parent must resolve to the local log or
        // to the batch. Done up front so a bad batch appends nothing.
        for (SyncCommitEntry e : batchByHash.values()) {
            for (byte[] p : e.parents()) {
                String ph = HashUtils.toHex(p);
                if (!localHashes.contains(ph) && !batchByHash.containsKey(ph)) {
                    throw new IllegalArgumentException(
                            "commit-log batch has a dangling parent "
                                    + ph
                                    + " for commit "
                                    + e.hashHex());
                }
            }
        }

        // The entries the local log does not already have.
        Map<String, SyncCommitEntry> fresh = new LinkedHashMap<>();
        for (Map.Entry<String, SyncCommitEntry> e : batchByHash.entrySet()) {
            if (!localHashes.contains(e.getKey())) {
                fresh.put(e.getKey(), e.getValue());
            }
        }

        // Topologically order the fresh entries (a parent before every child);
        // an unresolvable remainder is a cycle.
        List<SyncCommitEntry> ordered = ancestorsFirst(fresh);

        // Integrity check (Step 22): every fresh entry's metaTreeHash must
        // resolve to a present RootMetaTree chunk. Skipped when store==null
        // for backwards compatibility with non-fetch call sites (unit tests).
        if (store != null) {
            for (SyncCommitEntry e : ordered) {
                boolean present;
                try {
                    present = RootMetaTree.readFrom(store, e.metaTreeHash()).isPresent();
                } catch (RuntimeException malformed) {
                    // RootMetaTree.readFrom can throw on a corrupt chunk; treat
                    // "corrupt" as an integrity failure no different from
                    // "missing". (Wrap with our own message so callers see a
                    // consistent prefix to match on.)
                    throw new IllegalArgumentException(
                            "commit-log entry "
                                    + e.hashHex()
                                    + " references metaTreeHash that did not parse as a RootMetaTree: "
                                    + malformed.getMessage(),
                            malformed);
                }
                if (!present) {
                    throw new IllegalArgumentException(
                            "commit-log entry "
                                    + e.hashHex()
                                    + " references metaTreeHash that is missing from the chunk store");
                }
            }
        }

        for (SyncCommitEntry e : ordered) {
            // Preserve the received commit id verbatim (wholesale-adopt) — the id is the cross-peer
            // identity and must not be re-derived (ADR-0071 D-4).
            local.append(
                    e.timestamp(), e.id(), e.metaTreeHash(), e.parents(), e.message(), e.author());
        }
        return ordered.size();
    }

    /**
     * Kahn topological sort of {@code fresh} (parent before child). Only edges <em>within</em>
     * {@code fresh} constrain order — parents already in the local log are satisfied. Throws if a
     * cycle leaves entries unresolved.
     */
    private static List<SyncCommitEntry> ancestorsFirst(Map<String, SyncCommitEntry> fresh) {
        Map<String, List<String>> children = new HashMap<>();
        Map<String, Integer> pendingParents = new HashMap<>();
        for (Map.Entry<String, SyncCommitEntry> ent : fresh.entrySet()) {
            String hex = ent.getKey();
            int count = 0;
            for (byte[] p : ent.getValue().parents()) {
                String ph = HashUtils.toHex(p);
                if (fresh.containsKey(ph)) { // intra-batch edge → an ordering constraint
                    count++;
                    children.computeIfAbsent(ph, k -> new ArrayList<>()).add(hex);
                }
            }
            pendingParents.put(hex, count);
        }
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : pendingParents.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<SyncCommitEntry> out = new ArrayList<>(fresh.size());
        while (!ready.isEmpty()) {
            String hex = ready.poll();
            out.add(fresh.get(hex));
            for (String child : children.getOrDefault(hex, List.of())) {
                if (pendingParents.merge(child, -1, Integer::sum) == 0) {
                    ready.add(child);
                }
            }
        }
        if (out.size() != fresh.size()) {
            throw new IllegalArgumentException(
                    "commit-log batch contains a cycle ("
                            + (fresh.size() - out.size())
                            + " entries unresolvable)");
        }
        return out;
    }
}
