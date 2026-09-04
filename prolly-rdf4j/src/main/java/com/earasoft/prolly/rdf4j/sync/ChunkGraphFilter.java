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

import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.gc.PackedChunkSet;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.term.Layouts;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 0 Step 2 of {@code plans/auth-graph-syncpack-filter.md} — the "best-effort graph-aware"
 * chunk filter the future {@code PackBuilder} integration (Step 3) calls into.
 *
 * <h2>Scope refinement vs the plan's original premise</h2>
 *
 * <p>Phase 0 Step 1's investigative test ({@code AuthGraphChunkIdentifierTest}) refined the plan's
 * D-3 premise from "chunks are identifiable + disjoint" to "best-effort per chunk". This class
 * implements the <em>narrow effective slice</em>:
 *
 * <ul>
 *   <li><b>CSPO leaves are filtered.</b> CSPO is the only index keyed by context-first ({@code (c,
 *       s, p, o)}). A leaf chunk whose every row has a context-column value in {@code
 *       excludedContextTermIds} contributes nothing the receiver needs — it's dropped from the
 *       reachable set. Boundary-straddling leaves (containing both an excluded and a non-excluded
 *       context) stay.
 *   <li><b>SPOC / POSC / OSPC leaves are NOT filtered.</b> These indexes key context last;
 *       auth-graph and data-graph rows interleave at the row level. At small repo sizes every leaf
 *       mixes graphs and the filter would drop nothing anyway. At large repo sizes identifying
 *       "graph-pure" leaves requires reading every leaf — a full-tree scan. Deferred to a future
 *       plan that justifies the cost.
 *   <li><b>Dict, namespaces, stats, prefixes, provenance, provenance-events are NEVER filtered.</b>
 *       The dict subtree maps shared IRIs (e.g. {@code rdf:type}) across all graphs; filtering a
 *       dict chunk would orphan refs in data-graph rows. The other subtrees are graph-agnostic
 *       state.
 * </ul>
 *
 * <p>Operational consequence: for small repos the filter drops nothing (everything fits in a single
 * CSPO leaf mixing graphs); for large repos it drops the CSPO leaves dedicated to excluded graphs,
 * which is the load-bearing value (most auth-graph triples live on their own pages in a real
 * deployment). Operators reading {@code ?include_auth=true} docs should know the filter is
 * <em>partial</em> — sensitive deployments with strict exfiltration concerns should still air-gap.
 *
 * <h2>Caller contract</h2>
 *
 * <p>Caller is responsible for resolving graph IRIs to {@link
 * com.earasoft.prolly.rdf4j.term.TermId} numeric values via the dictionary before invoking this
 * filter. Phase 0 Step 3 ({@code PackBuilder} wire-up) ships an adapter that does this resolution
 * lookup; this class deliberately takes raw long values so it stays a pure algorithm independent of
 * the dict implementation.
 */
public final class ChunkGraphFilter {

    private ChunkGraphFilter() {}

    /**
     * Identify the set of CSPO leaf chunk hashes whose every row's context column is in {@code
     * excludedContextTermIds}. Returns an empty set when the input is null, empty, or when the
     * commit's RootMetaTree is absent from the store.
     *
     * <p>This is the load-bearing primitive — callers compose the returned set into their own prune
     * set (e.g. {@link PackBuilder} unions it with the receiver's already-has set before walking).
     */
    public static ChunkSet authOnlyLeaves(
            NodeStore store, byte[] commitHash, Set<Long> excludedContextTermIds) {
        ChunkSet out = new PackedChunkSet();
        if (excludedContextTermIds == null || excludedContextTermIds.isEmpty()) {
            return out;
        }
        Optional<RootMetaTree> rmt = RootMetaTree.readFrom(store, commitHash);
        if (rmt.isEmpty()) return out;

        byte[] cspoRoot = rmt.get().entries().get(RootMetaTree.NAME_CSPO);
        if (cspoRoot != null) {
            collectAuthOnlyLeavesCspo(store, cspoRoot, excludedContextTermIds, out);
        }
        return out;
    }

    /**
     * Compute the set of chunk hashes reachable from {@code commitHash} EXCLUDING leaves whose
     * every row's context column is in {@code excludedContextTermIds}.
     *
     * <p>Returns the full reachable set when {@code excludedContextTermIds} is empty — equivalent
     * to {@link ChunkReachability#from(NodeStore, byte[], ChunkSet)}.
     */
    public static ChunkSet chunksReachableExcludingGraphs(
            NodeStore store, byte[] commitHash, Set<Long> excludedContextTermIds) {
        ChunkSet authOnly = authOnlyLeaves(store, commitHash, excludedContextTermIds);
        return ChunkReachability.from(store, commitHash, authOnly);
    }

    /**
     * DFS the CSPO subtree. For every LEAF page, check whether all its row keys have column-0 (the
     * context TermId) in {@code excludedContextTermIds}; if so, add the leaf's hash to {@code out}.
     * Boundary-straddling leaves and internal nodes are not collected — the prune is purely
     * leaf-granular.
     */
    private static void collectAuthOnlyLeavesCspo(
            NodeStore store, byte[] nodeHash, Set<Long> excludedContextTermIds, ChunkSet out) {
        MemorySegment seg = store.read(nodeHash).orElse(null);
        if (seg == null) return;
        Node node = Objects.requireNonNull(Node.fromBytes(seg));
        if (!node.isLeaf()) {
            for (int i = 0; i < node.count(); i++) {
                collectAuthOnlyLeavesCspo(
                        store,
                        Objects.requireNonNull(node.getValue(i)),
                        excludedContextTermIds,
                        out);
            }
            return;
        }
        // Leaf: every key is a 4-column Int64 tuple
        // (c_termid, s_termid, p_termid, o_termid). Column 0 lives
        // at byte 0 of the 42-byte SpocKey tuple wire layout.
        if (isLeafEntirelyInExcludedContexts(node, excludedContextTermIds)) {
            out.add(nodeHash);
        }
    }

    /**
     * True iff every row in this leaf has its column-0 (context) in {@code excludedContextTermIds}.
     * An empty leaf returns true vacuously — empty leaves are filterable.
     */
    private static boolean isLeafEntirelyInExcludedContexts(
            Node leaf, Set<Long> excludedContextTermIds) {
        for (int i = 0; i < leaf.count(); i++) {
            MemorySegment key = leaf.getKeySegment(i);
            long contextTermId = key.get(Layouts.LE64_U, 0);
            if (!excludedContextTermIds.contains(contextTermId)) {
                return false;
            }
        }
        return true;
    }
}
