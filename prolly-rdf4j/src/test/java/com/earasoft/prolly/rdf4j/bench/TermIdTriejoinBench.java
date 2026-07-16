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

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.bench.TriejoinVsRdf4jBenchmark.Edge;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 *
 *
 * <h3>Indicative head-to-head of the triejoin's two key encodings on the cyclic triangle.</h3>
 *
 * <p>The {@link TriejoinVsRdf4jBenchmark} (and the head-to-head it feeds) is <b>IRI-keyed</b> — the
 * legacy standalone encoding. <b>Production ProllySail keys by dictionary-encoded {@code TermId}
 * (Int64)</b> (ADR-0036), which is what a wired-in triejoin (Phase 6) would actually run on. This
 * tool measures <i>both</i> encodings through the <b>same</b> direct {@code
 * LeapfrogTriejoin.solve()} path on the same dense-core triangle, so the comparison is
 * apples-to-apples — closing the IRI-vs-production evidence gap and giving Step 10 (the
 * TermId-{@code long} fast-path) a measured baseline.
 *
 * <p>A {@code main()} tool (not JMH — avoids entangling the {@code @Param} matrix). Min-of-k wall +
 * {@code ThreadMXBean} heap-alloc: <b>indicative/directional, not a JMH verdict</b> (methodology
 * D-6). Run: {@code java --enable-preview --enable-native-access=ALL-UNNAMED -cp $CP
 * …TermIdTriejoinBench [edges]}.
 */
public final class TermIdTriejoinBench {

    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final List<String> ORDER = List.of("?x", "?y", "?z");
    private static final TupleDescriptor I64x4 =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.Int64, false), new Type(Encoding.Int64, false),
                            new Type(Encoding.Int64, false), new Type(Encoding.Int64, false)));

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 380;
        Set<Edge> edges = TriejoinVsRdf4jBenchmark.denseCore(n);
        List<QuadPattern> tri = TriejoinVsRdf4jBenchmark.patternsFor("triangle");

        try (DirectBufferPool pool = new DirectBufferPool();
                Arena arena = Arena.ofShared()) {
            // --- IRI arm: the legacy encoding (same path the scorecard/head-to-head measure) ---
            StaticMap[] iri = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
            TupleDescriptor iriDesc = TriejoinVsRdf4jBenchmark.spocDescriptor();

            // --- TermId arm: dictionary-encoded Int64×4, the production encoding (ADR-0036) ---
            InMemoryNodeStore dstore = new InMemoryNodeStore();
            Dictionary dict = new Dictionary(dstore, pool, HashFunctions.defaultHash());
            InMemoryNodeStore sStore = new InMemoryNodeStore();
            MutableMap smm =
                    new MutableMap(new StaticMap(sStore, null, I64x4), sStore, I64x4, pool);
            InMemoryNodeStore pStore = new InMemoryNodeStore();
            MutableMap pmm =
                    new MutableMap(new StaticMap(pStore, null, I64x4), pStore, I64x4, pool);
            long te = tid(dict, arena, TriejoinVsRdf4jBenchmark.EDGE);
            long tg = tid(dict, arena, TriejoinVsRdf4jBenchmark.GRAPH);
            for (Edge e : edges) {
                long ts = tid(dict, arena, TriejoinVsRdf4jBenchmark.vIri(e.from()));
                long to = tid(dict, arena, TriejoinVsRdf4jBenchmark.vIri(e.to()));
                smm.put(row(pool, ts, te, to, tg), MemorySegment.NULL); // SPOC
                pmm.put(row(pool, te, to, ts, tg), MemorySegment.NULL); // POSC
            }
            StaticMap tSpoc = smm.flush(), tPosc = pmm.flush();

            long[] iriR =
                    measure(
                            () ->
                                    new LeapfrogTriejoin(tri, ORDER, iri[0], iri[1], iriDesc, pool)
                                            .solve()
                                            .size());
            long[] termR =
                    measure(
                            () ->
                                    new LeapfrogTriejoin(
                                                    tri, ORDER, tSpoc, tPosc, I64x4, pool, dict)
                                            .solve()
                                            .size());

            System.out.printf(
                    "[triejoin key-encoding head-to-head — triangle @N=%d edges, min-of-8 INDICATIVE]%n",
                    edges.size());
            System.out.printf(
                    "  %-14s %5d results   wall %7.2f ms   heap-alloc %,12d B (%.2f MB)%n",
                    "IRI (legacy)", iriR[2], iriR[0] / 1e6, iriR[1], iriR[1] / 1048576.0);
            System.out.printf(
                    "  %-14s %5d results   wall %7.2f ms   heap-alloc %,12d B (%.2f MB)%n",
                    "TermId/Int64", termR[2], termR[0] / 1e6, termR[1], termR[1] / 1048576.0);
            System.out.printf(
                    "  ratio (Int64 / IRI): wall %.2fx, alloc %.2fx%n",
                    (double) termR[0] / iriR[0], (double) termR[1] / iriR[1]);
            if (iriR[2] != termR[2])
                System.out.println("  WARN: result counts differ — encodings disagree!");
        }
    }

    /** {@code {minWallNs, minHeapAllocBytes, resultCount}} over a warmed min-of-8. */
    private static long[] measure(java.util.function.LongSupplier work) {
        for (int i = 0; i < 10; i++) work.getAsLong(); // warm / JIT
        long bestNs = Long.MAX_VALUE, bestAlloc = Long.MAX_VALUE, count = 0;
        for (int i = 0; i < 8; i++) {
            long a0 = TMX.getCurrentThreadAllocatedBytes(), t0 = System.nanoTime();
            count = work.getAsLong();
            bestNs = Math.min(bestNs, System.nanoTime() - t0);
            bestAlloc = Math.min(bestAlloc, TMX.getCurrentThreadAllocatedBytes() - a0);
        }
        return new long[] {bestNs, bestAlloc, count};
    }

    private static long tid(Dictionary dict, Arena arena, String iri) {
        return dict.encode(TermEncoder.encode(VF.createIRI(iri), arena)).value();
    }

    private static MemorySegment row(DirectBufferPool pool, long a, long b, long c, long d) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, le8(a));
        tb.putField(1, le8(b));
        tb.putField(2, le8(c));
        tb.putField(3, le8(d));
        return tb.build().segment();
    }

    private static byte[] le8(long x) {
        byte[] b = new byte[8];
        MemorySegment.ofArray(b)
                .set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, x);
        return b;
    }

    private TermIdTriejoinBench() {}
}
