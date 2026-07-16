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
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.semantic.GraphPatternEngine;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.LongSupplier;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 Step 2 of {@code prolly-rdf/plans/triejoin-performance.md} — the <b>allocation
 * scorecard</b>, split <b>projection (constructor) vs descent (solve)</b> so the plan optimizes the
 * dominant phase, not the assumed one.
 *
 * <p>Measures {@link com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes()} deltas
 * (heap; off-heap pool buffers don't count) around a query, reusing {@link
 * TriejoinVsRdf4jBenchmark}'s builders. Indicative (min-of-k), seconds not minutes (vs JMH {@code
 * -prof gc}). Prints numbers for the plan; no pass/fail assertion — the phase split is the decision
 * input for Phase 1 (streaming projection) vs Phases 2/3 (per-row maps + seek-path scratch).
 */
class TriejoinAllocationProfileTest {

    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void allocationProfile_triangle() {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        String sparql = TriejoinVsRdf4jBenchmark.sparqlFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        System.out.println("[alloc bytes/query — triangle, min-of-6]");
        System.out.printf(
                "  %-9s %16s %16s %16s %16s%n",
                "N(edges)",
                "triejoin-TOTAL",
                "  projection(ctor)",
                "  descent(solve)",
                "rdf4j-mem");
        for (int n : new int[] {90, 380}) {
            var edges = TriejoinVsRdf4jBenchmark.denseCore(n);
            try (DirectBufferPool pool = new DirectBufferPool()) {
                StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
                StaticMap spoc = idx[0], posc = idx[1];
                GraphPatternEngine eng = TriejoinVsRdf4jBenchmark.buildNativeEngine(edges, pool);
                Repository mem = TriejoinVsRdf4jBenchmark.buildMemory(edges);
                try {
                    for (int i = 0; i < 5; i++) { // warm up
                        eng.executeMulti(patterns, ORDER).size();
                        new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool)
                                .solve()
                                .size();
                        TriejoinVsRdf4jBenchmark.countSparql(mem, sparql);
                    }
                    long total = minAlloc(() -> eng.executeMulti(patterns, ORDER).size());
                    long[] split =
                            minSplit(spoc, posc, desc, pool, patterns); // {projection, descent}
                    long mm = minAlloc(() -> TriejoinVsRdf4jBenchmark.countSparql(mem, sparql));
                    System.out.printf(
                            "  %-9d %,16d %,16d %,16d %,16d%n",
                            edges.size(), total, split[0], split[1], mm);
                } finally {
                    mem.shutDown();
                }
            }
        }
    }

    /**
     * Phase-0 baseline for {@code prolly-rdf/plans/triejoin-streaming-results.md} Step 1 — the
     * <b>output-path</b> allocation the streaming cursor must drive toward zero. Reports the
     * descent (solve) bytes/query <b>and bytes/row</b> at increasing output size (dense-core
     * triangle, output Θ(N^1.5)): the per-row cost is the {@code List<Map<String,byte[]>>} the
     * cursor eliminates. The AFTER (Step 5) re-runs this and expects bytes/row → ~0 (flat total in
     * result count).
     */
    @Test
    void outputPathBaseline_denseCore() {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        System.out.println(
                "[output-path baseline — solve() descent (incl. per-row maps), dense-core triangle, min-of-6]");
        System.out.printf(
                "  %-9s %14s %18s %14s%n", "N(edges)", "rows", "descent bytes/q", "bytes/row");
        for (int n : new int[] {380, 650, 1000}) {
            var edges = TriejoinVsRdf4jBenchmark.denseCore(n);
            try (DirectBufferPool pool = new DirectBufferPool()) {
                StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
                StaticMap spoc = idx[0], posc = idx[1];
                for (int i = 0; i < 5; i++) {
                    new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool).solve().size();
                }
                long rows =
                        new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool)
                                .solve()
                                .size();
                long descent = minSplit(spoc, posc, desc, pool, patterns)[1];
                System.out.printf(
                        "  %-9d %,14d %,18d %,14d%n",
                        edges.size(), rows, descent, rows == 0 ? 0 : descent / rows);
            }
        }
    }

    /**
     * Step-2 end-to-end baseline (Sail path): the flag-ON SPARQL cyclic triangle <b>through
     * ProllySail</b> (the engine triejoin + the {@code evaluateTriejoin} consumer — per-field
     * TermId decode + {@code ListBindingSet}). Reports output allocation bytes/query + bytes/row at
     * growing output — the consumer-inclusive counterpart to {@link #outputPathBaseline_denseCore}
     * (engine-only). Wall-time is the wiring plan's already-measured ~1.12×
     * (`SparqlTriejoinBenchmark`), not re-run here.
     */
    @Test
    void outputPathBaseline_sailFlagOn() {
        String ed = "<" + TriejoinVsRdf4jBenchmark.EDGE + ">";
        String sparql =
                "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z . ?z " + ed + " ?x }";
        System.out.println(
                "[output-path baseline — flag-ON SPARQL through ProllySail (engine + consumer), min-of-6]");
        System.out.printf(
                "  %-9s %14s %18s %14s%n", "N(edges)", "rows", "sail bytes/q", "bytes/row");
        for (int n : new int[] {380, 650, 1000}) {
            var edges = TriejoinVsRdf4jBenchmark.denseCore(n);
            ProllySail sail = new ProllySail();
            sail.setTriejoinEnabled(true);
            SailRepository repo = new SailRepository(sail);
            repo.init();
            try {
                ValueFactory vf = repo.getValueFactory();
                IRI e = vf.createIRI(TriejoinVsRdf4jBenchmark.EDGE);
                try (RepositoryConnection conn = repo.getConnection()) {
                    conn.begin();
                    for (var edge : edges) {
                        conn.add(
                                vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.from())),
                                e,
                                vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.to())));
                    }
                    conn.commit();
                }
                for (int i = 0; i < 5; i++) {
                    TriejoinVsRdf4jBenchmark.countSparql(repo, sparql);
                }
                long rows = TriejoinVsRdf4jBenchmark.countSparql(repo, sparql);
                long bytes = minAlloc(() -> TriejoinVsRdf4jBenchmark.countSparql(repo, sparql));
                System.out.printf(
                        "  %-9d %,14d %,18d %,14d%n",
                        edges.size(), rows, bytes, rows == 0 ? 0 : bytes / rows);
            } finally {
                repo.shutDown();
            }
        }
    }

    /**
     * Phase-1 AFTER for {@code triejoin-streaming-results.md} Step 5: the <b>count-only cursor</b>
     * drain ({@code while (c.next()) n++}) — no per-row {@code Map}. Measures the descent
     * allocation (cursor created outside the timed region; constructor/projection excluded) at the
     * same N as Step 1's {@code outputPathBaseline_denseCore}, so the bytes/row delta isolates
     * exactly what dropping the per-row {@code LinkedHashMap} bought. (The remaining per-value
     * {@code getField} byte[] is the not-yet-flat residual — buffer reuse is a later lever.)
     */
    @Test
    void cursorCountOnly_denseCore() {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        System.out.println(
                "[cursor count-only descent — no per-row Map, dense-core triangle, min-of-6]");
        System.out.printf(
                "  %-9s %14s %18s %14s%n", "N(edges)", "rows", "drain bytes/q", "bytes/row");
        for (int n : new int[] {380, 650, 1000}) {
            var edges = TriejoinVsRdf4jBenchmark.denseCore(n);
            try (DirectBufferPool pool = new DirectBufferPool()) {
                StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
                StaticMap spoc = idx[0], posc = idx[1];
                long rows = 0;
                long best = Long.MAX_VALUE;
                for (int i = 0; i < 6; i++) {
                    LeapfrogTriejoin tj =
                            new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool);
                    long a1 = TMX.getCurrentThreadAllocatedBytes();
                    long c = 0;
                    var cur = tj.cursor();
                    while (cur.next()) c++;
                    long a2 = TMX.getCurrentThreadAllocatedBytes();
                    rows = c;
                    best = Math.min(best, a2 - a1);
                }
                System.out.printf(
                        "  %-9d %,14d %,18d %,14d%n",
                        edges.size(), rows, best, rows == 0 ? 0 : best / rows);
            }
        }
    }

    /** Min thread-allocated bytes across a few runs (min ≈ steady-state, GC-noise-free). */
    private static long minAlloc(LongSupplier work) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            long before = TMX.getCurrentThreadAllocatedBytes();
            work.getAsLong();
            best = Math.min(best, TMX.getCurrentThreadAllocatedBytes() - before);
        }
        return best;
    }

    /**
     * Per iteration: alloc around the constructor (projection) and around solve (descent), min of
     * each.
     */
    private static long[] minSplit(
            StaticMap spoc,
            StaticMap posc,
            TupleDescriptor desc,
            DirectBufferPool pool,
            List<QuadPattern> patterns) {
        long bestProj = Long.MAX_VALUE, bestDesc = Long.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            long a0 = TMX.getCurrentThreadAllocatedBytes();
            LeapfrogTriejoin tj = new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool);
            long a1 = TMX.getCurrentThreadAllocatedBytes();
            tj.solve().size();
            long a2 = TMX.getCurrentThreadAllocatedBytes();
            bestProj = Math.min(bestProj, a1 - a0);
            bestDesc = Math.min(bestDesc, a2 - a1);
        }
        return new long[] {bestProj, bestDesc};
    }
}
