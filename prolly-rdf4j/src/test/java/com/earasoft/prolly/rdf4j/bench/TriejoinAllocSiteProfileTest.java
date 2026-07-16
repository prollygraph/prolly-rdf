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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 Step 2 (frame-level) of {@code prolly-rdf/plans/triejoin-performance.md} — pinpoints
 * <b>which allocation sites</b> in the descent produce the ~918 MB/query (Phase-3 targeting). The
 * bytes-split test localized it to {@code solve()}; this JFR {@code jdk.ObjectAllocationSample}
 * profile attributes it to specific class@method frames so Phase 3 fixes the dominant allocator,
 * not a guessed one.
 *
 * <p>Sampled (throttled) — the <i>ranking</i> is the signal, not absolute bytes. Prints the top
 * sites; no assertion.
 */
class TriejoinAllocSiteProfileTest {

    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void allocationSites_triangle_descent() throws Exception {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        var edges = TriejoinVsRdf4jBenchmark.denseCore(380);

        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
            StaticMap spoc = idx[0], posc = idx[1];
            for (int i = 0; i < 3; i++) { // warm up / JIT
                new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool).solve().size();
            }

            Path jfr = Files.createTempFile("triejoin-alloc", ".jfr");
            Map<String, long[]> bySite = new LinkedHashMap<>(); // site -> {weightSum, samples}
            try (Recording rec = new Recording()) {
                rec.enable("jdk.ObjectAllocationSample").withStackTrace();
                rec.start();
                for (int i = 0; i < 40; i++) {
                    new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool).solve().size();
                }
                rec.stop();
                rec.dump(jfr);
            }

            try (RecordingFile rf = new RecordingFile(jfr)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent e = rf.readEvent();
                    if (!e.getEventType().getName().equals("jdk.ObjectAllocationSample")) continue;
                    String cls =
                            e.getClass("objectClass") != null
                                    ? e.getClass("objectClass").getName()
                                    : "?";
                    long weight = e.hasField("weight") ? e.getLong("weight") : 0;
                    String frame = topAppFrame(e);
                    String site = frame + "  ==>  " + shorten(cls);
                    bySite.computeIfAbsent(site, k -> new long[2]);
                    bySite.get(site)[0] += weight;
                    bySite.get(site)[1] += 1;
                }
            }
            Files.deleteIfExists(jfr);

            bySite.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(18)
                    .forEach(
                            en ->
                                    System.out.printf(
                                            "  %,14d B  (%4d smp)  %s%n",
                                            en.getValue()[0], en.getValue()[1], en.getKey()));
            System.out.println(
                    "[triejoin descent allocation sites — sampled, ranked by est. bytes]");
        }
    }

    /** First stack frame in our packages (the app-level allocation site), else the top frame. */
    private static String topAppFrame(RecordedEvent e) {
        if (e.getStackTrace() == null) return "(no stack)";
        RecordedFrame top = null;
        for (RecordedFrame f : e.getStackTrace().getFrames()) {
            if (f.getMethod() == null || f.getMethod().getType() == null) continue;
            if (top == null) top = f;
            String t = f.getMethod().getType().getName();
            if (t.startsWith("com.earasoft.") || t.startsWith("com.dolthub.")) {
                return shorten(t) + "." + f.getMethod().getName() + ":" + f.getLineNumber();
            }
        }
        return top == null
                ? "(?)"
                : shorten(top.getMethod().getType().getName()) + "." + top.getMethod().getName();
    }

    private static String shorten(String fqcn) {
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }
}
