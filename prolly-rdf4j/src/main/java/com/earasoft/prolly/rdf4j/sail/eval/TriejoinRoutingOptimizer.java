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
package com.earasoft.prolly.rdf4j.sail.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryOptimizer;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Rewrites each eligible cyclic BGP into a {@link TriejoinNode}.</h3>
 *
 * <p>Step 5 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md}. Eligibility = a
 * <b>maximal</b> pure-{@code Join} subtree of ≥2 {@linkplain BgpExtractor#d7valid default-graph,
 * non-self} {@link StatementPattern}s whose join hypergraph is {@linkplain
 * CyclicBgpDetector#isCyclic cyclic}. Acyclic / ineligible BGPs are left untouched → RDF4J's
 * bind-join (which wins on those — the no-regression contract, exposed via {@link #routedCount()}).
 *
 * <p>Registered + run by {@code ProllyEvaluationStrategy} when {@code
 * prolly.rdf4j.triejoin-enabled} is on — single-tenant via {@code ProllySailAutoConfiguration}, and
 * per-repo/multi-tenant via its {@code repoSailFactory} (wired in {@code triejoin-default-on.md}
 * Step 1). Eligible roots are collected in a read-only pass, then replaced, to avoid mutating the
 * tree mid-visit.
 */
public final class TriejoinRoutingOptimizer implements QueryOptimizer {

    private int routed = 0;

    /**
     * Process-wide count of BGPs routed to the triejoin — telemetry for {@code
     * prolly.query.triejoin.routed} (how often the worst-case-optimal join fires; static so it
     * aggregates across the per-query optimizer instances). Stays 0 when {@code
     * prolly.rdf4j.triejoin-enabled} is off (the default).
     */
    private static final java.util.concurrent.atomic.LongAdder TOTAL_ROUTED =
            new java.util.concurrent.atomic.LongAdder();

    /** Total BGPs routed to the triejoin since process start (telemetry). */
    public static long totalRouted() {
        return TOTAL_ROUTED.sum();
    }

    /**
     * How many BGPs were rewritten to the triejoin (for the "acyclic is not routed" no-regression
     * check).
     */
    public int routedCount() {
        return routed;
    }

    @Override
    public void optimize(TupleExpr expr, @Nullable Dataset dataset, BindingSet bindings) {
        List<Join> eligible = new ArrayList<>();
        expr.visit(
                new AbstractQueryModelVisitor<RuntimeException>() {
                    @Override
                    public void meet(Join join) {
                        if (BgpExtractor.isPureBgp(join)) {
                            List<StatementPattern> sps = new ArrayList<>();
                            BgpExtractor.flatten(join, sps);
                            if (sps.size() >= 2
                                    && BgpExtractor.d7valid(sps)
                                    && CyclicBgpDetector.isCyclic(
                                            new BgpExtractor.Bgp(sps).varSets())) {
                                eligible.add(join);
                            }
                            // maximal pure BGP — do not descend (no nested BGP roots)
                        } else {
                            super.meet(join);
                        }
                    }
                });
        for (Join root : eligible) {
            List<StatementPattern> sps = new ArrayList<>();
            BgpExtractor.flatten(root, sps);
            root.replaceWith(new TriejoinNode(sps, naiveVarOrder(sps)));
            routed++;
            TOTAL_ROUTED.increment();
        }
    }

    /**
     * First-appearance variable order across the patterns. <b>Provisional</b>: Step 6 replaces this
     * with a var-order proven realizable by the maintained SPOC/POSC permutations (and gates
     * eligibility on it).
     */
    static List<String> naiveVarOrder(List<StatementPattern> patterns) {
        Set<String> order = new LinkedHashSet<>();
        for (StatementPattern p : patterns) {
            for (Var v : new Var[] {p.getSubjectVar(), p.getPredicateVar(), p.getObjectVar()}) {
                if (v != null && !v.hasValue())
                    order.add("?" + v.getName()); // QuadPattern var-token convention
            }
        }
        return new ArrayList<>(order);
    }
}
