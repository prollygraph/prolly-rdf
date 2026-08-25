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

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.sail.eval.TriejoinNode;
import com.earasoft.prolly.rdf4j.sail.eval.TriejoinRoutingOptimizer;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.value.DictionaryTermResolver;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import com.earasoft.prolly.semantic.SelectivityVariableOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.eclipse.rdf4j.query.impl.ListBindingSet;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>ProllySail's SPARQL evaluation strategy — routes cyclic BGPs through the WCOJ triejoin.</h3>
 *
 * <p>Phase 2 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md}. Constructed by {@link
 * ProllySailConnection#evaluateInternal} only when {@link ProllySail#triejoinEnabled()} is on. Two
 * overrides over {@link DefaultEvaluationStrategy}:
 *
 * <ul>
 *   <li>{@link #evaluate} runs {@link TriejoinRoutingOptimizer} first, rewriting each eligible
 *       cyclic BGP subtree into a {@link TriejoinNode} (acyclic BGPs stay on the bind-join — D-2);
 *       then delegates.
 *   <li>{@link #precompile} returns a custom {@link QueryEvaluationStep} for a {@code
 *       TriejoinNode}: it runs the {@code LeapfrogTriejoin} over the connection's live (flushed)
 *       SPOC/POSC TermId indexes and emits decoded {@code BindingSet}s; every other node defers to
 *       the default.
 * </ul>
 *
 * <p>The triejoin result for the cyclic BGP must be identical to the bind-join's — pinned by the
 * seam test and (Phase 3) the W3C suite with the flag on. MVP: default-graph BGPs (D-7); the
 * patterns' {@code c} is {@code null} → the {@code TermId.ZERO} default-graph context.
 */
public final class ProllyEvaluationStrategy extends ProllyDefaultEvaluationStrategy {

    private final ProllySailConnection conn;

    public ProllyEvaluationStrategy(
            TripleSource tripleSource, Dataset dataset, ProllySailConnection conn) {
        super(tripleSource, dataset, conn);
        this.conn = conn;
    }

    @Override
    public CloseableIteration<BindingSet> evaluate(TupleExpr expr, BindingSet bindings)
            throws QueryEvaluationException {
        // Rewrite eligible cyclic BGPs → TriejoinNode in place, then evaluate (precompile catches
        // the node).
        new TriejoinRoutingOptimizer()
                .optimize(expr, null, bindings); // optimizer ignores the dataset
        return super.evaluate(expr, bindings);
    }

    @Override
    public QueryEvaluationStep precompile(TupleExpr expr, QueryEvaluationContext context) {
        if (expr instanceof TriejoinNode node) {
            return bindings -> evaluateTriejoin(node, bindings);
        }
        return super.precompile(expr, context);
    }

    /**
     * Evaluate a routed cyclic BGP: run the WCOJ triejoin over the live indexes, decode + merge
     * bindings.
     */
    private CloseableIteration<BindingSet> evaluateTriejoin(
            TriejoinNode node, BindingSet incoming) {
        List<QuadPattern> patterns = new ArrayList<>(node.patterns().size());
        for (StatementPattern sp : node.patterns()) {
            patterns.add(
                    QuadPattern.of(
                            token(sp.getSubjectVar()),
                            token(sp.getPredicateVar()),
                            token(sp.getObjectVar()),
                            null)); // c = null → default graph (TermId.ZERO); MVP D-7
        }

        Dictionary dict = conn.triejoinDict();
        DictionaryTermResolver resolver = conn.triejoinResolver();
        StaticMap spoc = conn.triejoinIndexRoot(QuadOrder.SPOC);
        StaticMap posc = conn.triejoinIndexRoot(QuadOrder.POSC);

        boolean merge =
                incoming.iterator()
                        .hasNext(); // enclosing bindings to join with (rare for a top BGP)

        // Step 6 (triejoin-streaming-results): a LAZY pull consumer. The per-eval DirectBufferPool
        // and the cursor live for the lifetime of the returned iteration (the cursor's trie
        // navigation borrows pool buffers); both are released in handleClose() — which RDF4J calls
        // on full drain (getNextElement → null auto-closes) AND on early close (LIMIT / consumer
        // close), giving the O(k) short-circuit (Step 7). The pool is created BEFORE the variable
        // order because cardinality-ordering (below) borrows it for the estimator's rank queries.
        DirectBufferPool pool = new DirectBufferPool();
        List<String> varOrder;
        LeapfrogTriejoin.BindingCursor cursor;
        try {
            // Baseline plan (sparql-baseline-cardinality-aware): order the routed triejoin's
            // variables by cardinality (SelectivityVariableOrder) when the flag is on, else the
            // provisional first-appearance order (node.varOrder()). Answer-invariant — ordering
            // changes only cost; the triejoin realizes ANY order (it projects each pattern in the
            // chosen global order, so there is no realizability constraint — D-3 dissolved).
            varOrder =
                    conn.triejoinCardinalityOrder()
                            ? new SelectivityVariableOrder(spoc, posc, pool).order(patterns)
                            : node.varOrder();
            cursor =
                    new LeapfrogTriejoin(
                                    patterns, varOrder, spoc, posc, SpocKey.DESCRIPTOR, pool, dict)
                            .cursor();
        } catch (RuntimeException e) {
            pool.close();
            throw e;
        }

        // Fixed output schema (the BGP's variables, "?"-stripped), built ONCE per query (not per
        // row). A ListBindingSet per row avoids the per-binding string-hashing a MapBindingSet pays
        // — the per-row map churn the flag-ON CPU flame graph flagged as the dominant
        // end-to-end-dilution cost (Phase 4 measurement). Each pull builds exactly one
        // ListBindingSet, reading by index via cursor.termId(i); resolve() is pool-independent (a
        // dictionary lookup) and the decoded BindingSets hold only RDF4J Values, so they outlive
        // the
        // pool.
        List<String> names = new ArrayList<>(varOrder.size());
        for (String v : varOrder) names.add(v.startsWith("?") ? v.substring(1) : v);

        // Step 11 (D-8): a per-query TermId→Value cache. evaluateTriejoin's per-row allocation is
        // dominated by resolve() (decode + wrap + IRI String, 3 per row) — NOT the removed
        // List<Map>
        // (Step 9 measured that as ~3%). Cyclic results are term-skewed (a hub/vertex recurs in
        // many
        // rows — ~1,026× in a K20 dense digraph), so memoizing collapses resolve to one per
        // DISTINCT
        // term. Scoped to this evaluation (GC'd when the iteration closes); bounded by distinct
        // terms
        // (≤ rows, ≪ for skew — the WCOJ-win regime). Keyed by the long TermId.
        HashMap<Long, Value> termValueCache = new HashMap<>();
        return new LookAheadIteration<BindingSet>() {
            @Override
            protected @Nullable BindingSet getNextElement() {
                while (cursor.next()) {
                    Value[] vals = new Value[names.size()];
                    for (int i = 0; i < varOrder.size(); i++) {
                        Long tid = cursor.termId(i); // one box per field, reused for get + put
                        Value v = termValueCache.get(tid);
                        if (v == null) {
                            v = resolver.resolve(new TermId(tid));
                            termValueCache.put(tid, v);
                        }
                        vals[i] = v;
                    }
                    if (!merge) { // common case: no incoming bindings → emit directly
                        return new ListBindingSet(names, vals);
                    }
                    MapBindingSet bs =
                            new MapBindingSet(); // join with enclosing bindings; drop conflicts
                    incoming.forEach(b -> bs.addBinding(b.getName(), b.getValue()));
                    boolean compatible = true;
                    for (int i = 0; i < names.size(); i++) {
                        Value bound = bs.getValue(names.get(i));
                        if (bound != null) {
                            if (!bound.equals(vals[i])) {
                                compatible = false;
                                break;
                            }
                        } else bs.addBinding(names.get(i), vals[i]);
                    }
                    if (compatible) return bs;
                    // incompatible merge → skip this row, look ahead to the next
                }
                return null; // cursor exhausted → base auto-closes → handleClose releases
                // pool+cursor
            }

            @Override
            protected void handleClose() {
                try {
                    cursor.close();
                } finally {
                    pool.close();
                }
            }
        };
    }

    /**
     * A {@link StatementPattern} position → QuadPattern token: {@code "?"+name} for a variable,
     * else the bound constant's lexical value (an IRI for s/p, IRI or literal for o).
     */
    private static String token(Var v) {
        // A StatementPattern always carries non-null s/p/o vars (the only callers), so v is
        // non-null.
        return v.hasValue() ? v.getValue().stringValue() : "?" + v.getName();
    }
}
