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
package com.earasoft.prolly.semantic;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.util.*;

/**
 * Evaluates a basic graph pattern — a set of triple/quad patterns sharing variables — by building
 * and running a join plan over the permutation indexes.
 *
 * <p>This is the query engine's join orchestrator. It offers two strategies: a single-variable
 * <em>star</em> join ({@link #execute}) that intersects every pattern on one shared variable via
 * {@link LeapfrogJoin}, and a multi-variable join (the {@link LeapfrogTriejoin} path) that binds a
 * full variable order depth-first and so can evaluate cyclic patterns — the triangle — that no
 * single star join can. Each pattern is turned into a sorted iterator over its matching keys, and
 * the join intersects them.
 *
 * @apiNote {@link #execute} takes the patterns plus the shared join variable and returns a {@link
 *     MapIterator} over the joined bindings. Each per-pattern projection is wrapped in {@code
 *     SortedProjection} because {@link LeapfrogJoin} requires sorted, deduplicated inputs and a raw
 *     projection is not sorted when an unbound position sits between the bound prefix and the join
 *     column — without the wrapper the join silently drops matches (ADR-0033). The multi-variable
 *     path uses the {@code SPOC} index and, in its current form, requires the variable order to be
 *     consistent with each pattern's column order.
 * @implNote <b>Collaborators:</b> the per-permutation {@link StaticMap} indexes (the {@code
 *     indices} map keyed by index name), {@link NodeStore} + {@link DirectBufferPool} (read nodes /
 *     scratch buffers), {@link TupleDescriptor} (key layout), {@link QuadPattern} (the input
 *     patterns), {@code SortedProjection} / {@code ProjectingIterator} (per-pattern sorted
 *     projection), {@link LeapfrogJoin} (single-variable intersection), and {@link
 *     LeapfrogTriejoin} (multi-variable binding). <b>Dependents:</b> the RDF4J query-evaluation
 *     path that routes a cyclic basic graph pattern to the worst-case-optimal join engine.
 */
public class GraphPatternEngine {
    private final NodeStore store;
    private final DirectBufferPool pool;
    private final Map<String, StaticMap> indices;
    private final TupleDescriptor descriptor;

    public GraphPatternEngine(
            NodeStore store,
            DirectBufferPool pool,
            TupleDescriptor descriptor,
            Map<String, StaticMap> indices) {
        this.store = store;
        this.pool = pool;
        this.descriptor = descriptor;
        this.indices = indices;
    }

    /**
     * The named permutation index, asserted present. The engine is constructed with its permutation
     * indexes in the {@code indices} map, so a missing one is a construction bug, not a runtime
     * condition — fail loud here rather than as a deeper NullPointerException inside an iterator.
     * The optional POSC index is the exception: paths that tolerate its absence read {@code
     * indices.get("POSC")} directly (a {@code @Nullable} the receiver handles).
     */
    private StaticMap index(String name) {
        return Objects.requireNonNull(
                indices.get(name), () -> "GraphPatternEngine: missing index " + name);
    }

    /** Executes a list of patterns and returns an iterator over the joined variables. */
    public MapIterator execute(List<QuadPattern> patterns, String joinVar) {
        TupleDescriptor joinDesc = new TupleDescriptor(List.of(new Type(Encoding.IRI, false)));
        List<MapIterator> iterators = new ArrayList<>();

        for (QuadPattern pattern : patterns) {
            // Wrap each per-pattern projection in SortedProjection: LeapfrogJoin
            // requires inputs sorted (+ deduped) by the join key, but a raw
            // ProjectingIterator emits the join column in index order — unsorted
            // whenever an unbound position sits between the bound prefix and the
            // join column (e.g. (s,?w,?j)). Without this the join silently misses
            // matches. See SortedProjection / ADR-0033.
            iterators.add(
                    new SortedProjection(createIteratorForPattern(pattern, joinVar), joinDesc));
        }

        return new LeapfrogJoin(iterators, joinDesc);
    }

    /**
     * Multi-variable BGP evaluation via the hierarchical {@link LeapfrogTriejoin}
     * (multi-variable-leapfrog-triejoin.md, Phase 1). Unlike {@link #execute} (a single-variable
     * star join), this binds the full {@code varOrder} and returns complete bindings. Uses the SPOC
     * index; the variable order must be consistent with each pattern's SPOC column order (Phase-A
     * constraint).
     */
    public java.util.List<java.util.Map<String, byte[]>> executeMulti(
            List<QuadPattern> patterns, List<String> varOrder) {
        return new LeapfrogTriejoin(
                        patterns,
                        varOrder,
                        index("SPOC"),
                        indices.get("POSC"), // optional: LeapfrogTriejoin handles a null POSC
                        descriptor,
                        pool)
                .solve();
    }

    /**
     * Multi-variable BGP evaluation choosing the variable order via a {@link
     * VariableOrderHeuristic} (Phase 4 Step 13) — most-constrained-first by default ({@link
     * SelectivityVariableOrder}). Use this when the caller has no preferred order; pass an explicit
     * order to {@link #executeMulti(List, List)} to override the heuristic.
     */
    public java.util.List<java.util.Map<String, byte[]>> executeMulti(List<QuadPattern> patterns) {
        VariableOrderHeuristic heuristic =
                new SelectivityVariableOrder(
                        index("SPOC"),
                        indices.get(
                                "POSC"), // optional: SelectivityVariableOrder handles a null POSC
                        pool);
        return executeMulti(patterns, heuristic.order(patterns));
    }

    private MapIterator createIteratorForPattern(QuadPattern pattern, String joinVar) {
        boolean sConst = !pattern.s().isVar();
        boolean pConst = !pattern.p().isVar();
        boolean oConst = !pattern.o().isVar();

        if (sConst) {
            List<String> prefix = new ArrayList<>();
            prefix.add(pattern.s().value());
            if (pConst) prefix.add(pattern.p().value());

            int joinIdx = pattern.findVarIdx(joinVar);
            return new ProjectingIterator(index("SPOC"), descriptor, pool, prefix, joinIdx);
        } else if (pConst) {
            List<String> prefix = new ArrayList<>();
            prefix.add(pattern.p().value());
            if (oConst) prefix.add(pattern.o().value());

            // POSC mapping: P=0, O=1, S=2
            int joinIdx = 2; // Default to Subject if P and O are const
            if (pattern.o().value().equals(joinVar)) joinIdx = 1;
            if (pattern.p().value().equals(joinVar)) joinIdx = 0;

            return new ProjectingIterator(index("POSC"), descriptor, pool, prefix, joinIdx);
        }

        throw new UnsupportedOperationException(
                "Pattern not supported by current indices: " + pattern);
    }
}
