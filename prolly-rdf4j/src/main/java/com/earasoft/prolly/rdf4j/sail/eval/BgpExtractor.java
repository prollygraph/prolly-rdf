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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;

/**
 *
 *
 * <h3>Finds the maximal basic graph patterns (BGPs) eligible for triejoin routing in a SPARQL
 * algebra.</h3>
 *
 * <p>Step 3 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md}. Walks a {@code TupleExpr}
 * and returns each <b>maximal</b> subtree built purely of {@code Join} + {@code StatementPattern}
 * nodes (a BGP), leaving everything else (FILTER, OPTIONAL/{@code LeftJoin}, UNION, projection,
 * paths, subqueries) to RDF4J. A BGP that touches a non-default graph or contains a repeated
 * variable within one pattern (e.g. {@code ?x p ?x}) is rejected wholesale (D-7) — when in doubt,
 * don't route, the bind-join is always correct.
 *
 * <p>The returned BGPs feed {@link CyclicBgpDetector} (route only the cyclic ones) and, in Phase 2,
 * the rewrite into a triejoin node. This class only <i>extracts</i> — it does not rewrite or
 * evaluate.
 */
public final class BgpExtractor {

    private BgpExtractor() {}

    /** A maximal pure-join BGP: its statement patterns + their per-pattern join-variable sets. */
    public record Bgp(List<StatementPattern> patterns) {
        /**
         * One set per pattern: the names of its variable (non-constant) positions — the GYO
         * hyperedges.
         */
        public List<Set<String>> varSets() {
            List<Set<String>> out = new ArrayList<>(patterns.size());
            for (StatementPattern p : patterns) out.add(joinVars(p));
            return out;
        }
    }

    /**
     * Maximal D-7-valid pure-join BGPs in {@code expr} (singletons included; the caller filters by
     * size/cyclicity).
     */
    public static List<Bgp> extract(TupleExpr expr) {
        List<Bgp> out = new ArrayList<>();
        expr.visit(
                new AbstractQueryModelVisitor<RuntimeException>() {
                    @Override
                    public void meet(Join join) {
                        if (isPureBgp(join)) {
                            List<StatementPattern> sps = new ArrayList<>();
                            flatten(join, sps);
                            if (d7valid(sps)) out.add(new Bgp(sps));
                            // pure subtree handled here — do NOT descend (its patterns are not
                            // separate BGPs)
                        } else {
                            super.meet(join); // mixed subtree — descend to find smaller pure BGPs
                        }
                    }

                    @Override
                    public void meet(StatementPattern sp) {
                        // A lone pattern (top-level, or a direct child of a non-pure node).
                        // Singleton BGP.
                        List<StatementPattern> one = List.of(sp);
                        if (d7valid(one)) out.add(new Bgp(one));
                    }
                });
        return out;
    }

    /**
     * A subtree built purely of {@code Join} + {@code StatementPattern} (a BGP). Shared with the
     * optimizer.
     */
    static boolean isPureBgp(TupleExpr e) {
        if (e instanceof StatementPattern) return true;
        if (e instanceof Join j) return isPureBgp(j.getLeftArg()) && isPureBgp(j.getRightArg());
        return false;
    }

    static void flatten(TupleExpr e, List<StatementPattern> acc) {
        if (e instanceof StatementPattern sp) {
            acc.add(sp);
        } else if (e instanceof Join j) {
            flatten(j.getLeftArg(), acc);
            flatten(j.getRightArg(), acc);
        }
    }

    /**
     * D-7: default-graph only, and no variable repeated across positions within one pattern. Shared
     * with the optimizer.
     */
    static boolean d7valid(List<StatementPattern> patterns) {
        for (StatementPattern sp : patterns) {
            if (sp.getContextVar() != null)
                return false; // named/var graph — MVP is default-graph only
            List<String> names = new ArrayList<>(3);
            for (Var v : new Var[] {sp.getSubjectVar(), sp.getPredicateVar(), sp.getObjectVar()}) {
                if (v != null && !v.hasValue()) names.add(v.getName());
            }
            if (new HashSet<>(names).size() != names.size()) return false; // ?x p ?x style
        }
        return true;
    }

    /** Variable (non-constant) position names of a pattern — the join-relevant vertices for GYO. */
    private static Set<String> joinVars(StatementPattern sp) {
        Set<String> vars = new LinkedHashSet<>();
        for (Var v : new Var[] {sp.getSubjectVar(), sp.getPredicateVar(), sp.getObjectVar()}) {
            if (v != null && !v.hasValue()) vars.add(v.getName());
        }
        return vars;
    }
}
