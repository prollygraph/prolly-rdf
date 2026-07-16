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
package com.earasoft.prolly.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.bench.BenchGraphs.Edge;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import com.earasoft.prolly.semantic.SelectivityVariableOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Engine-level tests for {@code plans/prepublic/sparql-baseline-cardinality-aware.md}: Step 1 (the
 * <b>realizability gate</b>), Step 2 (the <b>seekWork measurement</b>), and Step 5 (the Phase 2
 * <b>lock-in</b> — {@link #cardinalityOrderLocksInSelectiveCyclicSeekWorkWin}, turning Step 2's
 * measured win into a hard assertion so a future refactor cannot silently revert the routed
 * triejoin to first-appearance ordering).
 *
 * <p>The SPARQL triejoin currently orders variables by first-appearance ({@code
 * TriejoinRoutingOptimizer.naiveVarOrder}); the plan would replace that with the existing
 * cardinality-aware {@link SelectivityVariableOrder}. Before wiring it in, prove its chosen order
 * is <b>realizable</b> with the maintained SPOC/POSC permutations (the triejoin can actually bind
 * it) and <b>answer-equivalent</b> to first-appearance (ordering is correctness-neutral). If
 * selectivity ever emitted an unrealizable order, {@code solve()} would throw or return a different
 * count here — so a green run IS the realizability evidence (D-3).
 *
 * <p>The run also <b>prints</b> both orders per family — the measure-the-real-thing check on
 * whether the BenchGraphs families (single predicate {@code e}, all-variable patterns) even carry a
 * selectivity signal for {@code SelectivityVariableOrder} to act on (see the wrap-up).
 */
class SelectivityOrderRealizabilityTest {

    /** First-appearance variable order — the current SPARQL baseline ({@code naiveVarOrder}). */
    private static List<String> firstAppearance(List<QuadPattern> patterns) {
        Set<String> order = new LinkedHashSet<>();
        for (QuadPattern q : patterns) {
            if (q.s().isVar()) order.add(q.s().value());
            if (q.p().isVar()) order.add(q.p().value());
            if (q.o().isVar()) order.add(q.o().value());
            if (q.c() != null && q.c().startsWith("?")) order.add(q.c());
        }
        return new ArrayList<>(order);
    }

    private static int solveCount(
            List<QuadPattern> patterns,
            List<String> order,
            StaticMap spoc,
            StaticMap posc,
            DirectBufferPool pool) {
        return new LeapfrogTriejoin(patterns, order, spoc, posc, BenchGraphs.SPOC, pool)
                .solve()
                .size();
    }

    @Test
    void selectivityOrderIsRealizableAndAnswerEquivalentAcrossQueryFamilies() {
        record Query(String name, List<QuadPattern> patterns) {}
        List<Query> queries =
                List.of(
                        new Query("triangle", BenchGraphs.triangle()),
                        new Query("path2", BenchGraphs.path2()),
                        new Query("star", BenchGraphs.star()));

        try (DirectBufferPool pool = new DirectBufferPool()) {
            Set<Edge> edges =
                    BenchGraphs.scaleFree(800, 1234L); // power-law: where order matters most
            StaticMap spoc = BenchGraphs.buildSpoc(edges, pool);
            StaticMap posc = BenchGraphs.buildPosc(edges, pool);

            for (Query q : queries) {
                List<String> firstApp = firstAppearance(q.patterns());
                List<String> selectivity =
                        new SelectivityVariableOrder(spoc, posc, pool).order(q.patterns());

                int faCount = solveCount(q.patterns(), firstApp, spoc, posc, pool);
                // (a) realizable — solve() does not throw; (b) answer-equivalent to the trusted
                // first-appearance order. A mismatch (or throw) is the D-3 finding.
                int selCount = solveCount(q.patterns(), selectivity, spoc, posc, pool);

                System.out.printf(
                        "[realizability/%s] first-appearance=%s -> %d ; selectivity=%s -> %d ; differ=%b%n",
                        q.name(),
                        firstApp,
                        faCount,
                        selectivity,
                        selCount,
                        !firstApp.equals(selectivity));
                assertEquals(
                        faCount,
                        selCount,
                        q.name()
                                + ": selectivity order "
                                + selectivity
                                + " must be realizable + answer-equivalent to first-appearance "
                                + firstApp);
            }
        }
    }

    /**
     * Phase 0 Step 2 (the gate measurement). The triejoin serves CYCLIC BGPs (ADR-0065), so the
     * production-relevant comparison is on cyclic queries: a symmetric single-predicate triangle
     * (expected neutral — Step 1) and a SELECTIVE cyclic triangle (triangle + a bound-subject
     * constraint {@code <leaf> e ?z} on a low-out-degree vertex, placed last so first-appearance
     * does not bind the selective {@code ?z} first). Reports {@code seekWork} per order; asserts
     * only answer-equivalence (the measurement verdict goes to the plan Status, not a hard assert).
     */
    @Test
    void seekWorkFirstAppearanceVsSelectivityOnCyclicQueries() {
        int[] budgets = {500, 1000, 2000};
        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int b : budgets) {
                Set<Edge> edges = BenchGraphs.scaleFree(b, 1234L);
                StaticMap spoc = BenchGraphs.buildSpoc(edges, pool);
                StaticMap posc = BenchGraphs.buildPosc(edges, pool);

                // Symmetric cyclic triangle — production-relevant; expected neutral.
                report(
                        "triangle-symmetric/N=" + edges.size(),
                        BenchGraphs.triangle(),
                        spoc,
                        posc,
                        pool);

                // Selective cyclic triangle: + <leaf> e ?z (leaf = a low-out-degree vertex), placed
                // LAST so first-appearance does NOT bind the selective ?z first.
                int leaf = lowOutDegreeVertex(edges);
                List<QuadPattern> selective = new ArrayList<>(BenchGraphs.triangle());
                selective.add(
                        QuadPattern.of(
                                BenchGraphs.iri(leaf), BenchGraphs.EDGE, "?z", BenchGraphs.GRAPH));
                report(
                        "triangle-selective(leaf=" + leaf + ")/N=" + edges.size(),
                        selective,
                        spoc,
                        posc,
                        pool);
            }
        }
    }

    /**
     * Phase 2 Step 5 — the <b>lock-in</b>. Step 2 <em>measured</em> the selective-cyclic seekWork
     * win (and only asserted answer-equivalence, sending the magnitude to the plan Status). This
     * turns that measurement into a hard <b>assertion</b> on a deterministic input (fixed size +
     * seed → deterministic seekWork), so a future refactor that reverts the routed triejoin's
     * variable order to first-appearance <b>fails the build</b>. Three pins matching Step 5's
     * clauses:
     *
     * <ol>
     *   <li><b>cardinality-aware (not first-appearance):</b> on the selective query {@link
     *       SelectivityVariableOrder} returns a different order than first-appearance, binding the
     *       most-constrained variable ({@code ?z}, anchored by the low-out-degree {@code <leaf>})
     *       <em>first</em> where first-appearance binds it <em>last</em>.
     *   <li><b>result set unchanged:</b> equal result counts under both orders (ordering is
     *       answer-invariant — it changes only cost).
     *   <li><b>seekWork no-regression + locked-in win:</b> the neutral symmetric triangle does no
     *       <em>more</em> seekWork under the cardinality order; the selective triangle does at
     *       least <b>2× less</b> (Step 2 measured ~14–32× — large headroom; a revert to
     *       first-appearance is exactly 1× and fails this bar).
     * </ol>
     *
     * <p><b>Honest scope (answer-invariance limits what is observable):</b> this asserts the engine
     * <em>heuristic</em> (where {@code seekWork()} lives) — it catches a regression of
     * cardinality-awareness itself. The SPARQL path's <em>use</em> of the flag cannot be asserted
     * through query <em>results</em> (the orders are answer-equivalent by construction — the very
     * property that makes the flip safe to ship makes the chosen order invisible to a {@code
     * SELECT}); that link is guarded instead by {@code ProllySailPropertiesTest} (the default ships
     * ON), {@code MultiTenantTriejoinFlagTest} (the flag reaches every Sail), and {@code
     * TriejoinAgreementProperty} (the flag-ON path runs + stays answer-correct).
     */
    @Test
    void cardinalityOrderLocksInSelectiveCyclicSeekWorkWin() {
        final int n =
                1000; // ~1002 edges after the BA budget loop (Step 2's N=1002 row, ratio ~0.047)
        final long seed = 1234L;
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Set<Edge> edges = BenchGraphs.scaleFree(n, seed);
            StaticMap spoc = BenchGraphs.buildSpoc(edges, pool);
            StaticMap posc = BenchGraphs.buildPosc(edges, pool);

            // (3a) Symmetric cyclic triangle — neutral (no selectivity signal); the cardinality
            // order
            // must not do MORE work than first-appearance.
            List<QuadPattern> symmetric = BenchGraphs.triangle();
            long[] symFa = solveWork(symmetric, firstAppearance(symmetric), spoc, posc, pool);
            long[] symSel =
                    solveWork(
                            symmetric,
                            new SelectivityVariableOrder(spoc, posc, pool).order(symmetric),
                            spoc,
                            posc,
                            pool);
            assertEquals(
                    symFa[0], symSel[0], "symmetric triangle: orders must be answer-equivalent");
            assertTrue(
                    symSel[1] <= symFa[1],
                    "symmetric triangle: cardinality order must not do MORE seekWork than"
                            + " first-appearance (no-regression) — selectivity="
                            + symSel[1]
                            + " first-appearance="
                            + symFa[1]);

            // Selective cyclic triangle: + <leaf> e ?z (leaf = lowest out-degree vertex), placed
            // LAST
            // so first-appearance binds ?z last; the cardinality order binds the constrained ?z
            // first.
            int leaf = lowOutDegreeVertex(edges);
            List<QuadPattern> selective = new ArrayList<>(BenchGraphs.triangle());
            selective.add(
                    QuadPattern.of(
                            BenchGraphs.iri(leaf), BenchGraphs.EDGE, "?z", BenchGraphs.GRAPH));
            List<String> fa = firstAppearance(selective);
            List<String> sel = new SelectivityVariableOrder(spoc, posc, pool).order(selective);

            // (1) cardinality-aware: the order genuinely differs from first-appearance, and the
            // selective ?z leads under selectivity but trails under first-appearance.
            assertNotEquals(
                    fa,
                    sel,
                    "selective cyclic: cardinality order must differ from first-appearance (it is"
                            + " cardinality-aware, not naive) — both were "
                            + fa);
            assertEquals(
                    "?z",
                    sel.get(0),
                    "selective cyclic: cardinality order must bind the most-constrained ?z first —"
                            + " was "
                            + sel);
            assertEquals(
                    "?z",
                    fa.get(fa.size() - 1),
                    "selective cyclic: first-appearance binds ?z last (the setup that exposes the"
                            + " win) — was "
                            + fa);

            long[] selFa = solveWork(selective, fa, spoc, posc, pool);
            long[] selSel = solveWork(selective, sel, spoc, posc, pool);
            // (2) result set unchanged.
            assertEquals(selFa[0], selSel[0], "selective cyclic: orders must be answer-equivalent");
            // (3b) locked-in win: at least 2× less seekWork (Step 2 measured ~21× at this N; a
            // silent
            // revert to first-appearance is 1× and fails here).
            assertTrue(
                    selSel[1] * 2 < selFa[1],
                    "selective cyclic: cardinality order must do at least 2x LESS seekWork than"
                            + " first-appearance (locks in the win; a silent revert to first-appearance"
                            + " fails) — selectivity="
                            + selSel[1]
                            + " first-appearance="
                            + selFa[1]);
        }
    }

    private static void report(
            String label,
            List<QuadPattern> patterns,
            StaticMap spoc,
            StaticMap posc,
            DirectBufferPool pool) {
        List<String> fa = firstAppearance(patterns);
        List<String> sel = new SelectivityVariableOrder(spoc, posc, pool).order(patterns);
        long[] faR = solveWork(patterns, fa, spoc, posc, pool);
        long[] selR = solveWork(patterns, sel, spoc, posc, pool);
        assertEquals(faR[0], selR[0], label + ": orders must be answer-equivalent");
        double ratio = faR[1] == 0 ? 1.0 : (double) selR[1] / faR[1];
        System.out.printf(
                "[seekwork/%s] results=%d | first-app %s seekWork=%d | selectivity %s seekWork=%d |"
                        + " sel/fa=%.3f%n",
                label, faR[0], fa, faR[1], sel, selR[1], ratio);
    }

    /** {@code [resultCount, seekWork]} for solving {@code patterns} under {@code order}. */
    private static long[] solveWork(
            List<QuadPattern> patterns,
            List<String> order,
            StaticMap spoc,
            StaticMap posc,
            DirectBufferPool pool) {
        LeapfrogTriejoin t =
                new LeapfrogTriejoin(patterns, order, spoc, posc, BenchGraphs.SPOC, pool);
        int count = t.solve().size();
        return new long[] {count, t.seekWork()};
    }

    /** A vertex with the smallest non-zero out-degree — the selective bound subject. */
    private static int lowOutDegreeVertex(Set<Edge> edges) {
        Map<Integer, Integer> outDeg = new HashMap<>();
        for (Edge e : edges) outDeg.merge(e.from(), 1, Integer::sum);
        return outDeg.entrySet().stream()
                .min(Comparator.comparingInt(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse(0);
    }
}
