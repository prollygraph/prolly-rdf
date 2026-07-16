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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.bench.BenchGraphs.Edge;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 Step 15 of {@code multi-variable-leapfrog-triejoin.md} — the <b>deterministic asymptotic
 * evidence</b> (the CI-stable backstop for Gains 2 &amp; 3).
 *
 * <p><b>Renamed 2026-06-18</b> from {@code TriejoinScalingEvidence} → {@code …EvidenceTest} so
 * Surefire actually runs it: the old name matched no {@code <include>} glob, so it was
 * <b>dormant</b> (a "CI-stable backstop" that never ran). While dormant, its work-slope assertion
 * silently rotted — {@code triejoin-performance}'s positioned-forward-seek amortized {@code
 * seekWork} <i>below</i> the output size, so the seek count is no longer the {@code N^1.5}
 * quantity. The fix re-bases the evidence on what genuinely <i>is</i> {@code Θ(N^1.5)} — the
 * <b>result (output) count</b> — and asserts {@code seekWork} only sub-quadratic (work ≪ N²),
 * recording the amortization as a win.
 *
 * <p>Three sweeps, slopes fitted by least squares on log-log:
 *
 * <ol>
 *   <li><b>Dense core</b> (complete digraph on ~√N vertices): the triangle OUTPUT is {@code
 *       Θ(N^1.5)} — the worst-case-optimal quantity — while the materialized intermediate stays
 *       {@code O(N)}. The seek count is now sub-output (~N) after forward-seek amortization.
 *   <li><b>Star</b> (hub + m leaves, triangle-free): the binary-join baseline materializes {@code
 *       m×m = Θ(N²)} at the hub, while the triejoin's work stays sub-quadratic.
 *   <li><b>Hub bowtie</b> (the Goal-5 cyclic worst-case the 2026-06-20 wall-time crossover used):
 *       the binary 2-path intermediate is {@code Θ(N²)} even though there are {@code Θ(N)}
 *       <i>real</i> triangles — worst-case-optimal wins <i>with</i> output, not only on a
 *       triangle-free blowup.
 * </ol>
 */
class TriejoinScalingEvidenceTest {

    /** One sweep point: the solved join plus its result (output) count. */
    private record Run(LeapfrogTriejoin lftj, int resultCount) {}

    @Test
    void triangleOutputIsNToThe1point5WorkSubQuadraticSpaceLinearOnDenseCore() {
        int[] budgets = {30, 90, 180, 380, 650, 1000};
        List<double[]> output = new ArrayList<>(); // (ln N, ln resultCount) — the Θ(N^1.5) quantity
        List<double[]> work = new ArrayList<>(); // (ln N, ln seekWork) — amortized to ~N
        List<double[]> space = new ArrayList<>(); // (ln N, ln materializedRows) — O(N)

        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int b : budgets) {
                Set<Edge> edges = BenchGraphs.denseCore(b);
                int n = edges.size();
                Run r = run(edges, pool);
                output.add(new double[] {Math.log(n), Math.log(r.resultCount())});
                work.add(new double[] {Math.log(n), Math.log(r.lftj().seekWork())});
                space.add(new double[] {Math.log(n), Math.log(r.lftj().materializedRows())});
            }
        }

        double outputSlope = slope(output);
        double workSlope = slope(work);
        double spaceSlope = slope(space);
        System.out.printf(
                "[scaling/dense] output slope=%.3f, seek-work slope=%.3f, materialized-space slope=%.3f%n",
                outputSlope, workSlope, spaceSlope);

        // The OUTPUT is the worst-case-optimal Θ(N^1.5) quantity (the dense core's triangle count).
        assertTrue(
                outputSlope > 1.2 && outputSlope < 1.85,
                "dense-core triangle OUTPUT should grow ~N^1.5, got " + outputSlope);
        // Work is sub-quadratic (≪ N²). After the positioned-forward-seek amortization it is in
        // fact
        // ~N — BELOW the output — so the seek count is no longer the dominant term; assert only the
        // ceiling that defines worst-case-optimality versus a binary plan.
        assertTrue(
                workSlope < 1.85, "triejoin seek-work should be sub-quadratic, got " + workSlope);
        // Materialized intermediate is linear — the Gain-2 space win (no O(N²) join table).
        assertTrue(
                spaceSlope < 1.25, "materialized intermediate should be ~O(N), got " + spaceSlope);
    }

    @Test
    void binaryJoinIsQuadraticWhereTriejoinIsSubQuadraticOnStar() {
        int[] leaves = {8, 16, 32, 48, 64, 96};
        List<double[]> triejoinWork = new ArrayList<>();
        List<double[]> binaryIntermediate = new ArrayList<>();

        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int m : leaves) {
                Set<Edge> star = bidirectionalStar(m);
                int n = star.size(); // 2m
                Run r = run(star, pool);
                triejoinWork.add(new double[] {Math.log(n), Math.log(r.lftj().seekWork())});
                binaryIntermediate.add(new double[] {Math.log(n), Math.log((long) m * m + m)});
            }
        }

        double triejoinSlope = slope(triejoinWork);
        double binarySlope = slope(binaryIntermediate);
        System.out.printf(
                "[scaling/star] triejoin work slope=%.3f, binary intermediate slope=%.3f%n",
                triejoinSlope, binarySlope);

        assertTrue(
                binarySlope > 1.9, "binary-join intermediate should grow ~N^2, got " + binarySlope);
        assertTrue(
                triejoinSlope < 1.5, "triejoin work should be sub-quadratic, got " + triejoinSlope);
        assertTrue(
                binarySlope - triejoinSlope > 0.5,
                "triejoin must be asymptotically cheaper; triejoin="
                        + triejoinSlope
                        + " binary="
                        + binarySlope);
    }

    @Test
    void binaryBlowsUpEvenWithRealOutputOnHubBowtie() {
        // The Goal-5 worst-case + the 2026-06-20 wall-time crossover's shape: a single-hub bowtie
        // whose
        // binary 2-path intermediate is Θ(N²) DESPITE Θ(N) real triangle output — so
        // worst-case-optimal
        // wins even when results exist (the triangle-free star above blows up for ZERO output; this
        // is
        // the stronger, non-degenerate case). Deterministic slopes — machine-independent (no
        // wall-time).
        int[] fans = {16, 32, 64, 96, 128, 192};
        List<double[]> triejoinWork = new ArrayList<>();
        List<double[]> binaryIntermediate = new ArrayList<>();
        List<double[]> output = new ArrayList<>();

        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int m : fans) {
                Set<Edge> edges = hubBowtie(m);
                int n = edges.size(); // 3m
                Run r = run(edges, pool);
                triejoinWork.add(new double[] {Math.log(n), Math.log(r.lftj().seekWork())});
                binaryIntermediate.add(new double[] {Math.log(n), Math.log((long) m * m + 2L * m)});
                output.add(new double[] {Math.log(n), Math.log(r.resultCount())});
            }
        }

        double triejoinSlope = slope(triejoinWork);
        double binarySlope = slope(binaryIntermediate);
        double outputSlope = slope(output);
        System.out.printf(
                "[scaling/hub] triejoin work slope=%.3f, binary intermediate slope=%.3f, output slope=%.3f%n",
                triejoinSlope, binarySlope, outputSlope);

        // Binary 2-path intermediate is ~N² (every binary join order routes through the hub).
        assertTrue(
                binarySlope > 1.9,
                "binary 2-path intermediate should grow ~N^2, got " + binarySlope);
        // The worst-case-optimal triejoin stays sub-quadratic.
        assertTrue(
                triejoinSlope < 1.5, "triejoin work should be sub-quadratic, got " + triejoinSlope);
        // ...and the output is genuinely Θ(N): the blowup is over REAL triangles, not a
        // triangle-free
        // query — the distinction from the star sweep.
        assertTrue(
                outputSlope > 0.7 && outputSlope < 1.3,
                "triangle output should be ~Θ(N) (real results, not a triangle-free blowup), got "
                        + outputSlope);
        // The asymptotic gap IS the worst-case-optimal win.
        assertTrue(
                binarySlope - triejoinSlope > 0.5,
                "worst-case-optimal must be asymptotically cheaper; triejoin="
                        + triejoinSlope
                        + " binary="
                        + binarySlope);
    }

    @Test
    void triejoinStaysSubQuadraticWorkSpaceLinearOnScaleFree() {
        // The realistic-RDF family (power-law degree, hub-heavy). Unlike the hand-built dense-core
        // /
        // star / bowtie shapes, this is the topology real ontologies have — so it is where the
        // engine's invariants matter most. The triangle output count is topology-dependent (not
        // asserted on), but the two engine invariants must still hold on a skewed graph: seek-work
        // stays sub-quadratic and the materialized intermediate stays ~O(N) (no O(N^2) join table,
        // even when a hub's adjacency list is large).
        int[] budgets = {250, 500, 1000, 2000, 4000};
        List<double[]> work = new ArrayList<>(); // (ln N, ln seekWork)
        List<double[]> space = new ArrayList<>(); // (ln N, ln materializedRows)

        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int b : budgets) {
                Set<Edge> edges = BenchGraphs.scaleFree(b, 1234L);
                int n = edges.size();
                Run r = run(edges, pool);
                work.add(new double[] {Math.log(n), Math.log(Math.max(1, r.lftj().seekWork()))});
                space.add(
                        new double[] {
                            Math.log(n), Math.log(Math.max(1, r.lftj().materializedRows()))
                        });
            }
        }

        double workSlope = slope(work);
        double spaceSlope = slope(space);
        System.out.printf(
                "[scaling/scale-free] seek-work slope=%.3f, materialized-space slope=%.3f%n",
                workSlope, spaceSlope);

        assertTrue(
                workSlope < 1.85,
                "triejoin seek-work should stay sub-quadratic on a power-law graph, got "
                        + workSlope);
        assertTrue(
                spaceSlope < 1.25,
                "materialized intermediate should stay ~O(N) on a power-law graph, got "
                        + spaceSlope);
    }

    private static Run run(Set<Edge> edges, DirectBufferPool pool) {
        StaticMap spoc = BenchGraphs.buildSpoc(edges, pool);
        StaticMap posc = BenchGraphs.buildPosc(edges, pool);
        LeapfrogTriejoin lftj =
                new LeapfrogTriejoin(
                        BenchGraphs.triangle(),
                        List.of("?x", "?y", "?z"),
                        spoc,
                        posc,
                        BenchGraphs.SPOC,
                        pool);
        int resultCount = lftj.solve().size();
        return new Run(lftj, resultCount);
    }

    /** Hub 0 + m leaves, edges in both directions (triangle-free; 2m edges). */
    private static Set<Edge> bidirectionalStar(int m) {
        Set<Edge> edges = new LinkedHashSet<>();
        for (int i = 1; i <= m; i++) {
            edges.add(new Edge(0, i));
            edges.add(new Edge(i, 0));
        }
        return edges;
    }

    /**
     * Single-hub bowtie: {@code A={1..m} → hub(0) → B={m+1..2m}} + diagonal closing {@code
     * B[i]→A[i]}. {@code N=3m} edges; {@code m²} 2-paths through the hub (every binary join order
     * routes through it, so none escapes the {@code Θ(N²)} intermediate); {@code Θ(N)} triangles
     * (exactly {@code 3m} ordered matches — the 3 rotations of each {@code a_i→hub→b_i→a_i} cycle).
     */
    private static Set<Edge> hubBowtie(int m) {
        Set<Edge> edges = new LinkedHashSet<>();
        for (int i = 1; i <= m; i++) edges.add(new Edge(i, 0)); // a_i → hub
        for (int j = 1; j <= m; j++) edges.add(new Edge(0, m + j)); // hub → b_j
        for (int i = 1; i <= m; i++) edges.add(new Edge(m + i, i)); // b_i → a_i (closing)
        return edges;
    }

    private static double slope(List<double[]> pts) {
        int n = pts.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx);
    }
}
