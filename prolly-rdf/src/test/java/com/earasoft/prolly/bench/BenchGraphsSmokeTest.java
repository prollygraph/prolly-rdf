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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.bench.BenchGraphs.Edge;
import com.earasoft.prolly.bench.BenchGraphs.Family;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Phase 4 Step 14 — smoke test for the {@link BenchGraphs} harness: every family generates, loads
 * into the prolly SPOC/POSC indexes, and answers all three query shapes. The triangle answer is
 * checked against the brute-force oracle, tying the benchmark harness to correctness before Steps
 * 15–16 measure on it.
 */
class BenchGraphsSmokeTest {

    @Test
    void everyFamilyGeneratesLoadsAndQueries() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (Family family : Family.values()) {
                Set<Edge> edges = BenchGraphs.generate(family, 200, 42L);
                assertTrue(edges.size() > 0, family + " generated no edges");

                StaticMap spoc = BenchGraphs.buildSpoc(edges, pool);
                StaticMap posc = BenchGraphs.buildPosc(edges, pool);

                // Triangle: triejoin result count must equal the brute-force oracle.
                long got =
                        solveCount(
                                BenchGraphs.triangle(),
                                List.of("?x", "?y", "?z"),
                                spoc,
                                posc,
                                pool);
                assertEquals(
                        BenchGraphs.bruteForceTriangles(edges),
                        got,
                        family + ": triangle count must match the brute-force oracle");

                // Path + star just need to run (acyclic / single-var shapes).
                assertTrue(
                        solveCount(BenchGraphs.path2(), List.of("?x", "?y", "?z"), spoc, posc, pool)
                                >= 0);
                assertTrue(
                        solveCount(BenchGraphs.star(), List.of("?x", "?y", "?z"), spoc, posc, pool)
                                >= 0);
            }
        }
    }

    @Test
    void denseCoreIsTriangleHeavyAndStarPathIsTriangleFree() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            // A complete digraph on ~sqrt(N) vertices has many triangles.
            assertTrue(
                    BenchGraphs.bruteForceTriangles(BenchGraphs.denseCore(200)) > 0,
                    "dense core should be triangle-heavy");
            // A star + path is triangle-free.
            assertEquals(
                    0,
                    BenchGraphs.bruteForceTriangles(BenchGraphs.starPlusPath(200)),
                    "star + path should be triangle-free");
        }
    }

    @Test
    void scaleFreeIsPowerLawSkewedAndDeterministic() {
        // Determinism: same seed -> byte-identical edge set (a benchmark input must be
        // reproducible).
        assertEquals(
                BenchGraphs.scaleFree(2000, 7L),
                BenchGraphs.scaleFree(2000, 7L),
                "scaleFree must be deterministic given the seed");

        Set<Edge> edges = BenchGraphs.scaleFree(2000, 7L);
        // Budget: preferential attachment grows by a fixed step until it reaches n (n >> m).
        assertTrue(
                edges.size() >= 2000 && edges.size() <= 2000 + 2 * 3,
                "scaleFree should produce ~n edges; got " + edges.size());

        // Power-law signature: the hub's degree dwarfs the average. A uniform family (denseCore)
        // has max == avg; a power-law family has a heavy tail, so max >> avg.
        Map<Integer, Integer> degree = new java.util.HashMap<>();
        for (Edge e : edges) {
            degree.merge(e.from(), 1, Integer::sum);
            degree.merge(e.to(), 1, Integer::sum);
        }
        int maxDegree = degree.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double avgDegree = degree.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        assertTrue(
                maxDegree > 4 * avgDegree,
                "power-law hub degree must dwarf the average; max="
                        + maxDegree
                        + " avg="
                        + avgDegree);
        // Sanity contrast: denseCore is uniform, so its max degree ~= its average (no hub).
        Set<Edge> dense = BenchGraphs.denseCore(2000);
        Map<Integer, Integer> denseDeg = new java.util.HashMap<>();
        for (Edge e : dense) {
            denseDeg.merge(e.from(), 1, Integer::sum);
            denseDeg.merge(e.to(), 1, Integer::sum);
        }
        int denseMax = denseDeg.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        double denseAvg =
                denseDeg.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        assertTrue(
                denseMax < 2 * denseAvg,
                "denseCore is uniform (no hub); max=" + denseMax + " avg=" + denseAvg);
    }

    @Test
    void scaleFreeIsTriangleBearing() {
        // Unlike starPlusPath (triangle-free), the symmetric scale-free graph must contain
        // triangles, or it would be a false-negative workload for the headline triangle query.
        assertTrue(
                BenchGraphs.bruteForceTriangles(BenchGraphs.scaleFree(200, 7L)) > 0,
                "symmetric scale-free graph should be triangle-bearing");
    }

    private static long solveCount(
            List<QuadPattern> q,
            List<String> order,
            StaticMap spoc,
            StaticMap posc,
            DirectBufferPool pool) {
        List<Map<String, byte[]>> rows =
                new LeapfrogTriejoin(q, order, spoc, posc, BenchGraphs.SPOC, pool).solve();
        return rows.size();
    }
}
