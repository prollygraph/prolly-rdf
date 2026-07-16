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
package com.earasoft.prolly.rdf4j.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.bench.TriejoinVsRdf4jBenchmark.Edge;
import com.earasoft.prolly.semantic.GraphPatternEngine;
import com.earasoft.prolly.semantic.QuadPattern;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 4 Step 16 — the CI-stable correctness cross-check for the JMH benchmark {@link
 * TriejoinVsRdf4jBenchmark}. For each query shape on the same graph, the three engines must return
 * the <b>identical</b> binding set:
 *
 * <ul>
 *   <li>native leapfrog triejoin (over hand-built prolly indexes),
 *   <li>RDF4J bind-join over a {@code MemoryStore},
 *   <li>RDF4J bind-join over a {@code ProllySail}.
 * </ul>
 *
 * <p>This is also a <b>preview of Phase 5</b>: it is the first place the native triejoin's output
 * is compared head-to-head against RDF4J's SPARQL evaluation. Agreement here is a strong signal
 * that wiring the triejoin into BGP evaluation (Step 17) won't change results — short of the full
 * W3C suite, which adds the algebra on top.
 */
class TriejoinVsRdf4jAgreementTest {

    private static final List<String> VARS = List.of("x", "y", "z");
    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void allThreeEnginesAgreeOnEveryQueryShape(@TempDir Path dir) {
        Set<Edge> edges = TriejoinVsRdf4jBenchmark.denseCore(30); // complete digraph on ~6 vertices

        try (DirectBufferPool pool = new DirectBufferPool()) {
            GraphPatternEngine native_ = TriejoinVsRdf4jBenchmark.buildNativeEngine(edges, pool);
            Repository memory = TriejoinVsRdf4jBenchmark.buildMemory(edges);
            Repository prollySail = TriejoinVsRdf4jBenchmark.buildProllySail(edges, dir);
            try {
                for (String q : List.of("triangle", "path2", "star")) {
                    List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor(q);
                    String sparql = TriejoinVsRdf4jBenchmark.sparqlFor(q);

                    Set<List<String>> nat =
                            TriejoinVsRdf4jBenchmark.bindingsNative(native_, patterns, ORDER, VARS);
                    Set<List<String>> mem =
                            TriejoinVsRdf4jBenchmark.bindingsSparql(memory, sparql, VARS);
                    Set<List<String>> sail =
                            TriejoinVsRdf4jBenchmark.bindingsSparql(prollySail, sparql, VARS);

                    assertTrue(nat.size() > 0, q + ": expected non-empty result on the dense core");
                    assertEquals(mem, sail, q + ": MemoryStore and ProllySail SPARQL must agree");
                    assertEquals(
                            mem,
                            nat,
                            q + ": native triejoin must agree with RDF4J SPARQL evaluation");
                }
            } finally {
                memory.shutDown();
                prollySail.shutDown();
            }
        }
    }

    /**
     * Indicative triangle crossover (NOT JMH-rigorous — no fork/warmup isolation; the rigorous
     * numbers come from {@link TriejoinVsRdf4jBenchmark} via {@code org.openjdk.jmh.Main}). Asserts
     * the three arms agree at each size and prints a directional wall-time comparison so the
     * crossover is visible.
     */
    @Test
    void indicativeTriangleCrossover(@TempDir Path dir) throws java.io.IOException {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        String sparql = TriejoinVsRdf4jBenchmark.sparqlFor("triangle");
        System.out.println(
                "[triangle crossover — indicative µs, min of 4]   N   native   rdf4j-mem   prolly-sail");

        for (int budget : new int[] {30, 90, 180, 380}) {
            Set<Edge> edges = TriejoinVsRdf4jBenchmark.denseCore(budget);
            try (DirectBufferPool pool = new DirectBufferPool()) {
                GraphPatternEngine nativeEng =
                        TriejoinVsRdf4jBenchmark.buildNativeEngine(edges, pool);
                Repository memory = TriejoinVsRdf4jBenchmark.buildMemory(edges);
                Repository sail =
                        TriejoinVsRdf4jBenchmark.buildProllySail(
                                edges,
                                java.nio.file.Files.createDirectories(dir.resolve("n" + budget)));
                try {
                    long cNat = nativeEng.executeMulti(patterns, ORDER).size();
                    long cMem = TriejoinVsRdf4jBenchmark.countSparql(memory, sparql);
                    long cSail = TriejoinVsRdf4jBenchmark.countSparql(sail, sparql);
                    assertEquals(cMem, cNat, "native vs memory count @N=" + edges.size());
                    assertEquals(cMem, cSail, "memory vs prolly-sail count @N=" + edges.size());

                    long tNat = timeUs(() -> nativeEng.executeMulti(patterns, ORDER).size());
                    long tMem = timeUs(() -> TriejoinVsRdf4jBenchmark.countSparql(memory, sparql));
                    long tSail = timeUs(() -> TriejoinVsRdf4jBenchmark.countSparql(sail, sparql));
                    System.out.printf(
                            "                                      %4d   %6d   %9d   %11d%n",
                            edges.size(), tNat, tMem, tSail);
                } finally {
                    memory.shutDown();
                    sail.shutDown();
                }
            }
        }
    }

    /** Min wall-time in microseconds over a short warmup + 4 timed runs. */
    private static long timeUs(java.util.function.LongSupplier work) {
        for (int i = 0; i < 2; i++) work.getAsLong(); // warmup
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            long t0 = System.nanoTime();
            work.getAsLong();
            best = Math.min(best, (System.nanoTime() - t0) / 1_000);
        }
        return best;
    }
}
