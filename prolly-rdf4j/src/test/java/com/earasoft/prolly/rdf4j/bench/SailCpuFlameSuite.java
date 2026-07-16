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

import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 *
 * <h3>Comprehensive CPU flame-graph suite over ProllySail's hot paths.</h3>
 *
 * <p>Phase 1 Step 5 / the L2-CPU rung of {@code plans/benchmarking-and-bottleneck-methodology.md},
 * applied across the whole engine rather than one query. Drives the existing JMH benchmark
 * workloads ({@link SailReadBenchmark}, {@link SailComparisonBenchmark}) + the WCOJ triejoin under
 * {@link CpuFlameProfiler}, writing one flame graph per path to {@code target/flames/} plus an
 * {@code index.html} and a printed combined top-frames summary.
 *
 * <p><b>A tool, not a surefire test</b> (run via {@code main}, like {@link JmhRunner}). It needs
 * JFR's repository + RocksDB's native-lib extraction + temp dirs all on <i>real disk</i> from JVM
 * startup — which surefire can't give (its fork's {@code java.io.tmpdir} is the small {@code /tmp}
 * tmpfs, and JFR fixes its repository before any in-process {@code setProperty} can move it, so a
 * multi-recording run crashes the fork on the tmpfs quota). Run it directly:
 *
 * <pre>
 *   T=$(pwd)/prolly-rdf4j/target/benchtmp
 *   java --enable-preview --enable-native-access=ALL-UNNAMED \
 *        -Djava.io.tmpdir=$T -XX:FlightRecorderOptions=repository=$T/jfr \
 *        -cp "$CP" com.earasoft.prolly.rdf4j.bench.SailCpuFlameSuite
 * </pre>
 *
 * <h4>Paths profiled (ProllySail)</h4>
 *
 * {@code ingest} · {@code pointLookup} · {@code predicateScan} · {@code fullScan} · {@code
 * sparqlJoin} · {@code triejoin} (the standalone WCOJ solve).
 *
 * <h4>The wart, per path</h4>
 *
 * <p>JFR {@code ExecutionSample} sees <b>Java on-CPU only</b>; RocksDB native/JNI time is
 * invisible. So the I/O-touching paths ({@code ingest}, scans, {@code pointLookup}) show the
 * Java-side hot spots — dictionary encode, tuple build/decode, cursor logic, node (de)serialize,
 * BindingSet build — but <b>not</b> the native get/put/iterate. For native-inclusive attribution
 * (and the flatsail JNI-per-key question) run async-profiler: {@code JmhRunner -prof
 * "async:output=flamegraph;event=cpu" Sail}. The compute-bound paths ({@code triejoin}, {@code
 * sparqlJoin}) are well-covered by JFR.
 */
public final class SailCpuFlameSuite {

    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    public static void main(String[] args) throws Exception {
        Duration warm = Duration.ofMillis(600), measure = Duration.ofMillis(2500);
        Map<String, CpuFlameProfiler.Result> results = new LinkedHashMap<>();

        // --- read paths: one shared ProllySail dataset (10k subjects) ---
        SailReadBenchmark rb = new SailReadBenchmark();
        rb.engine = "prolly";
        rb.datasetSize = 10_000;
        rb.setUp();
        try {
            results.put(
                    "pointLookup",
                    CpuFlameProfiler.profile("sail-pointLookup", warm, measure, rb::pointLookup));
            results.put(
                    "predicateScan",
                    CpuFlameProfiler.profile(
                            "sail-predicateScan", warm, measure, rb::predicateScan));
            results.put(
                    "fullScan",
                    CpuFlameProfiler.profile("sail-fullScan", warm, measure, rb::fullScan));
            results.put(
                    "sparqlJoin",
                    CpuFlameProfiler.profile("sail-sparqlJoin", warm, measure, rb::sparqlJoin));
        } finally {
            rb.tearDown();
        }

        // --- ingest path: fresh ProllySail per run (5k triples) ---
        results.put(
                "ingest",
                CpuFlameProfiler.profile(
                        "sail-ingest",
                        warm,
                        measure,
                        () -> {
                            SailComparisonBenchmark cb = new SailComparisonBenchmark();
                            cb.engine = "prolly";
                            cb.batchSize = 5_000;
                            try {
                                cb.setUp();
                                cb.ingestBatch();
                                cb.tearDown();
                            } catch (IOException | org.rocksdb.RocksDBException e) {
                                throw new RuntimeException(e);
                            }
                        }));

        // --- WCOJ triejoin path (standalone, compute-bound — JFR sees it well) ---
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        var edges = TriejoinVsRdf4jBenchmark.denseCore(380);
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
            StaticMap spoc = idx[0], posc = idx[1];
            results.put(
                    "triejoin",
                    CpuFlameProfiler.profile(
                            "triejoin-triangle",
                            warm,
                            measure,
                            () ->
                                    new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool)
                                            .solve()
                                            .size()));
        }

        // --- index + summary ---
        Path index = writeIndex(results);
        System.out.println(
                "\n========== COMPREHENSIVE CPU FLAME SUITE (ProllySail, JFR — native invisible) ==========");
        System.out.println("index: " + index);
        results.forEach(
                (path, r) -> {
                    System.out.printf(
                            "%n[%s]  %d samples → %s%n", path, r.samples(), r.svg().getFileName());
                    r.topSelf().stream()
                            .limit(6)
                            .forEach(
                                    fc ->
                                            System.out.printf(
                                                    "    %5d self  %5d total  %s%n",
                                                    fc.selfSamples(),
                                                    fc.totalSamples(),
                                                    fc.frame()));
                });

        results.forEach(
                (path, r) -> {
                    if (r.samples() == 0) System.err.println("WARN: no CPU samples for " + path);
                });
    }

    /**
     * A tiny static index linking every path's flame graph with its sample count + top-3 self
     * frames.
     */
    private static Path writeIndex(Map<String, CpuFlameProfiler.Result> results)
            throws IOException {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><meta charset=utf-8><title>ProllySail CPU flame graphs</title>")
                .append(
                        "<style>body{font:14px system-ui,sans-serif;max-width:900px;margin:2rem auto;padding:0 1rem}")
                .append(
                        "h1{font-size:1.3rem}li{margin:.6rem 0}code{background:#f0f0f0;padding:1px 4px}")
                .append(".note{color:#666;font-size:.9rem}</style>")
                .append("<h1>ProllySail — CPU flame graphs by hot path</h1>")
                .append(
                        "<p class=note>JFR <code>ExecutionSample</code> — Java on-CPU only; RocksDB native/JNI time is NOT shown. ")
                .append(
                        "For native-inclusive CPU run async-profiler via <code>JmhRunner -prof async:output=flamegraph</code>.</p><ul>");
        for (var e : results.entrySet()) {
            var r = e.getValue();
            String top =
                    r.topSelf().stream()
                            .limit(3)
                            .map(fc -> fc.frame() + " (" + fc.selfSamples() + ")")
                            .reduce((a, b) -> a + ", " + b)
                            .orElse("—");
            h.append("<li><a href='")
                    .append(r.svg().getFileName())
                    .append("'><b>")
                    .append(e.getKey())
                    .append("</b></a> — ")
                    .append(r.samples())
                    .append(" samples")
                    .append("<br><span class=note>top self: ")
                    .append(esc(top))
                    .append("</span></li>");
        }
        h.append("</ul>");
        Path index = Path.of(System.getProperty("user.dir"), "target", "flames", "index.html");
        Files.writeString(index, h.toString());
        return index;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
