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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Demonstrator for {@link CpuFlameProfiler} (Phase 1 Step 5, CPU variant, of {@code
 * plans/benchmarking-and-bottleneck-methodology.md}): a CPU flame graph of the triangle triejoin
 * {@code solve()} — the L2-CPU rung beside {@link TriejoinAllocSiteProfileTest}'s L1/L2-alloc.
 *
 * <p>Writes {@code target/flames/triejoin-triangle-solve.svg} + {@code .collapsed.txt} and prints
 * the top self-CPU frames. No hard numeric assertion (the ranking is the signal); asserts only that
 * samples were captured and the artifact exists. Remember the wart: native/JNI (RocksDB) CPU is
 * invisible here — for that, run async-profiler via {@code JmhRunner -prof
 * "async:output=flamegraph;event=cpu"}.
 */
class TriejoinCpuFlameProfileTest {

    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void cpuFlame_triangle_solve() throws Exception {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        var edges = TriejoinVsRdf4jBenchmark.denseCore(380);

        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
            StaticMap spoc = idx[0], posc = idx[1];
            Runnable work =
                    () ->
                            new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool)
                                    .solve()
                                    .size();

            CpuFlameProfiler.Result res =
                    CpuFlameProfiler.profile(
                            "triejoin-triangle-solve",
                            Duration.ofMillis(800),
                            Duration.ofSeconds(3),
                            work);

            System.out.printf("[cpu flame] %d samples → %s%n", res.samples(), res.svg());
            System.out.println(
                    "[top self-CPU frames (JFR ExecutionSample — Java only, native invisible)]");
            res.topSelf()
                    .forEach(
                            fc ->
                                    System.out.printf(
                                            "  %6d self  %6d total  %s%n",
                                            fc.selfSamples(), fc.totalSamples(), fc.frame()));

            assertTrue(res.samples() > 0, "expected CPU samples to be captured");
            assertTrue(Files.exists(res.svg()), "expected the flame-graph SVG artifact");
            assertTrue(Files.exists(res.collapsed()), "expected the collapsed-stacks artifact");
        }
    }
}
