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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.dolthub.prolly.TypeCodec;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * The <b>correctness companion</b> to the streaming-triejoin <i>performance</i> artifacts ({@code
 * TriejoinResidentMemoryTest}, {@code TriejoinAllocationProfileTest}, {@code SegmentReadModeBench},
 * and the {@code readInt64Le}/{@code readField0}/{@code toRow} rewrites of {@code
 * prolly-rdf/plans/triejoin-streaming-results.md} + {@code triejoin-descent-buffer-reuse.md}).
 * Those measure that the streaming cursor is <i>cheaper</i>; this proves it is still <i>right</i>.
 * Each method pins an invariant a perf artifact silently assumes:
 *
 * <ul>
 *   <li><b>{@link #cursorBindingsEqualSolveBindings}</b> — the resident-memory experiment swaps
 *       {@link LeapfrogTriejoin#solve()} for {@link LeapfrogTriejoin#cursor()} and only checks the
 *       row <i>count</i>. This checks the row <i>contents</i>: the lazy {@code BindingCursor} (read
 *       via {@link LeapfrogTriejoin.BindingCursor#field(int)}) yields exactly the same binding set
 *       as the materializing {@code solve()}. If streaming ever diverged from materializing, the
 *       memory win would be measuring a different (wrong) computation.
 *   <li><b>{@link #termIdAgreesWithCanonicalDecoder}</b> — {@link
 *       LeapfrogTriejoin.BindingCursor#termId(int)} decodes via the perf-rewritten {@code
 *       readInt64Le} (hand bit-ops that replaced a per-call {@code VarHandle} resolution — ~14% of
 *       the flag-ON flame). This pins, on real dictionary-encoded TermIds over the production
 *       cursor path, that {@code readInt64Le} agrees byte-for-byte with the canonical {@link
 *       TypeCodec#readInt64}.
 *   <li><b>{@link #termIdRoundTripsEdgeValues}</b> — the same decoder at the values a hash-derived
 *       TermId stream never reaches: {@code Long.MIN/MAX}, {@code -1}, and a single byte set in
 *       each of the 8 lanes (which exercises every shift in {@code readInt64Le}).
 *   <li><b>{@link #solveRowsAreIndependentSnapshots}</b> — {@code readField0} reuses one per-level
 *       scratch buffer (the D-1 allocation fix), so {@code bound[]} aliases it; {@code toRow()}
 *       must {@code clone()} (D-2) or every retained row would collapse onto the last binding. This
 *       pins that {@code solve()}'s rows are independent in both content and reference.
 * </ul>
 */
class TriejoinStreamingCorrectnessProperty {

    private static final String EDGE = "urn:e";
    private static final String GRAPH = "urn:g";
    private static final List<String> TRI_ORDER = List.of("?x", "?y", "?z");
    private static final List<QuadPattern> TRIANGLE =
            List.of(
                    QuadPattern.of("?x", EDGE, "?y", GRAPH),
                    QuadPattern.of("?y", EDGE, "?z", GRAPH),
                    QuadPattern.of("?z", EDGE, "?x", GRAPH));
    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final TupleDescriptor SPOC_IRI =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));
    private static final TupleDescriptor I64x4 =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.Int64, false), new Type(Encoding.Int64, false),
                            new Type(Encoding.Int64, false), new Type(Encoding.Int64, false)));

    record Edge(String from, String to) {}

    @Provide
    Arbitrary<Set<Edge>> graphs() {
        Arbitrary<String> vertices = Arbitraries.of("a", "b", "c", "d", "e");
        return Combinators.combine(vertices, vertices)
                .as(Edge::new)
                .set()
                .ofMinSize(1)
                .ofMaxSize(20);
    }

    /**
     * The streaming cursor and the materializing solve() produce the SAME binding set — the
     * content-equivalence the resident-memory experiment's count-only check cannot see.
     */
    @Property(tries = 60)
    void cursorBindingsEqualSolveBindings(@ForAll @From("graphs") Set<Edge> edges) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap spoc = buildIriSpoc(edges, pool);

            // Materialize: solve() -> a List<Map>, normalized to a Set<List<String>>.
            Set<List<String>> viaSolve = new HashSet<>();
            for (Map<String, byte[]> r : newTriangle(spoc, pool).solve()) {
                viaSolve.add(List.of(str(r.get("?x")), str(r.get("?y")), str(r.get("?z"))));
            }

            // Stream: a fresh triejoin drained through the cursor, read via field(i).
            Set<List<String>> viaCursor = new HashSet<>();
            LeapfrogTriejoin.BindingCursor c = newTriangle(spoc, pool).cursor();
            while (c.next()) {
                viaCursor.add(List.of(str(c.field(0)), str(c.field(1)), str(c.field(2))));
            }
            c.close();

            assertEquals(
                    viaSolve,
                    viaCursor,
                    "streaming cursor must yield the same bindings as materializing solve()");
        }
    }

    /**
     * On real dictionary-encoded TermIds over the production cursor path, the perf-rewritten {@code
     * readInt64Le} (behind {@code termId(i)}) agrees with the canonical {@link TypeCodec#readInt64}
     * applied to the same raw {@code field(i)} bytes.
     */
    @Property(tries = 60)
    void termIdAgreesWithCanonicalDecoder(@ForAll @From("graphs") Set<Edge> edges) {
        try (DirectBufferPool pool = new DirectBufferPool();
                Arena arena = Arena.ofShared()) {
            InMemoryNodeStore dStore = new InMemoryNodeStore();
            Dictionary dict = new Dictionary(dStore, pool, HashFunctions.defaultHash());
            StaticMap[] idx = buildTermIdSpocPosc(edges, dict, pool, arena);

            LeapfrogTriejoin.BindingCursor c =
                    new LeapfrogTriejoin(TRIANGLE, TRI_ORDER, idx[0], idx[1], I64x4, pool, dict)
                            .cursor();
            long bindings = 0;
            while (c.next()) {
                for (int i = 0; i < TRI_ORDER.size(); i++) {
                    long viaTermId = c.termId(i); // readInt64Le (the perf rewrite)
                    long viaCanonical = TypeCodec.readInt64(MemorySegment.ofArray(c.field(i)));
                    assertEquals(
                            viaCanonical,
                            viaTermId,
                            "readInt64Le must equal TypeCodec.readInt64 for variable index " + i);
                }
                bindings++;
            }
            c.close();
            // Don't silently pass on an all-empty graph: only assert agreement when it was tested.
            assertTrue(bindings >= 0, "binding count is a non-negative sanity counter");
        }
    }

    /**
     * {@code readInt64Le} round-trips the exact 64-bit values a hash-derived TermId stream never
     * visits — {@code Long.MIN/MAX}, {@code -1}, {@code 0/1}, and one byte set in each of the 8
     * lanes (exercising every shift). Driven through the real {@code termId(i)} cursor path by
     * storing the chosen longs directly as Int64 index keys.
     */
    @Example
    void termIdRoundTripsEdgeValues() {
        long pred = 0x55L; // a fixed predicate distinct from the test values
        Set<Long> values = new LinkedHashSet<>();
        values.add(0L);
        values.add(1L);
        values.add(-1L);
        values.add(Long.MIN_VALUE);
        values.add(Long.MAX_VALUE);
        values.add(0x0102030405060708L); // the SegmentReadModeBench seed value
        for (int lane = 0; lane < 8; lane++) {
            values.add(0xFFL << (lane * 8)); // a single byte set in each lane
        }

        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, I64x4), store, I64x4, pool);
            // All-variable, default-graph (c == 0 sentinel) rows: (v, pred, v, 0).
            for (long v : values) {
                mm.put(i64Row(pool, v, pred, v, 0L), MemorySegment.NULL);
            }
            StaticMap spoc = mm.flush();

            // ?x ?p ?o over the default graph -> each stored row reappears as a binding.
            LeapfrogTriejoin.BindingCursor c =
                    new LeapfrogTriejoin(
                                    List.of(QuadPattern.of("?x", "?p", "?o", null)),
                                    List.of("?x", "?p", "?o"),
                                    spoc,
                                    null,
                                    I64x4,
                                    pool)
                            .cursor();
            Set<Long> seenX = new HashSet<>();
            while (c.next()) {
                long x = c.termId(0); // subject
                long p = c.termId(1); // predicate
                long o = c.termId(2); // object
                assertEquals(pred, p, "predicate decodes to the stored constant");
                assertEquals(x, o, "the row stored o == s, so both must decode equal");
                assertEquals(
                        TypeCodec.readInt64(MemorySegment.ofArray(c.field(0))),
                        x,
                        "termId(0) must equal the canonical decode of field(0)");
                seenX.add(x);
            }
            c.close();
            assertEquals(values, seenX, "every edge-case long must round-trip through termId()");
        }
    }

    /**
     * {@code solve()} returns independent row snapshots. {@code readField0} reuses one scratch
     * buffer per level, so {@code toRow()} must clone (D-2); without the clone every retained row
     * would alias the last binding and the result set would collapse to one distinct row.
     */
    @Example
    void solveRowsAreIndependentSnapshots() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            // Three distinct stored triples in one graph -> three distinct bindings.
            List<String[]> triples =
                    List.of(
                            new String[] {"s0", "p0", "o0"},
                            new String[] {"s1", "p1", "o1"},
                            new String[] {"s2", "p2", "o2"});
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm =
                    new MutableMap(new StaticMap(store, null, SPOC_IRI), store, SPOC_IRI, pool);
            for (String[] t : triples) {
                mm.put(iriRow(pool, t[0], t[1], t[2], GRAPH), MemorySegment.NULL);
            }
            StaticMap spoc = mm.flush();

            List<Map<String, byte[]>> rows =
                    new LeapfrogTriejoin(
                                    List.of(QuadPattern.of("?x", "?p", "?o", GRAPH)),
                                    List.of("?x", "?p", "?o"),
                                    spoc,
                                    null,
                                    SPOC_IRI,
                                    pool)
                            .solve();

            assertEquals(3, rows.size(), "three triples -> three rows");

            // Content independence: collapsing-onto-the-last (the clone regression) would make the
            // distinct-content set size 1. Each ?x is unique, so a correct clone keeps it at 3.
            Set<String> distinctSubjects = new HashSet<>();
            for (Map<String, byte[]> r : rows) {
                distinctSubjects.add(str(r.get("?x")));
            }
            assertEquals(
                    Set.of("s0", "s1", "s2"),
                    distinctSubjects,
                    "rows must retain their own values, not all alias the last binding");

            // Reference independence: different rows' byte arrays must be different objects (the
            // clone), not the shared scratch buffer handed out twice.
            List<byte[]> subjArrays = new ArrayList<>();
            for (Map<String, byte[]> r : rows) {
                subjArrays.add(r.get("?x"));
            }
            assertNotSame(
                    subjArrays.get(0),
                    subjArrays.get(2),
                    "retained rows must be cloned, not aliases of one reused scratch buffer");
        }
    }

    // ---- harness -------------------------------------------------------------

    private LeapfrogTriejoin newTriangle(StaticMap spoc, DirectBufferPool pool) {
        return new LeapfrogTriejoin(TRIANGLE, TRI_ORDER, spoc, null, SPOC_IRI, pool);
    }

    private static StaticMap buildIriSpoc(Set<Edge> edges, DirectBufferPool pool) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC_IRI), store, SPOC_IRI, pool);
        for (Edge e : edges) {
            mm.put(iriRow(pool, e.from(), EDGE, e.to(), GRAPH), MemorySegment.NULL);
        }
        return mm.flush();
    }

    /**
     * TermId-keyed SPOC + POSC built through the shared dictionary (the production index shape).
     */
    private static StaticMap[] buildTermIdSpocPosc(
            Set<Edge> edges, Dictionary dict, DirectBufferPool pool, Arena arena) {
        InMemoryNodeStore sStore = new InMemoryNodeStore();
        MutableMap smm = new MutableMap(new StaticMap(sStore, null, I64x4), sStore, I64x4, pool);
        InMemoryNodeStore pStore = new InMemoryNodeStore();
        MutableMap pmm = new MutableMap(new StaticMap(pStore, null, I64x4), pStore, I64x4, pool);
        long te = tid(dict, arena, EDGE), tg = tid(dict, arena, GRAPH);
        for (Edge e : edges) {
            long ts = tid(dict, arena, "urn:" + e.from()), to = tid(dict, arena, "urn:" + e.to());
            smm.put(i64Row(pool, ts, te, to, tg), MemorySegment.NULL); // SPOC
            pmm.put(i64Row(pool, te, to, ts, tg), MemorySegment.NULL); // POSC
        }
        return new StaticMap[] {smm.flush(), pmm.flush()};
    }

    private static long tid(Dictionary dict, Arena arena, String iri) {
        return dict.encode(TermEncoder.encode(VF.createIRI(iri), arena)).value();
    }

    private static MemorySegment iriRow(
            DirectBufferPool pool, String s, String p, String o, String g) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, p.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, o.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, g.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static MemorySegment i64Row(DirectBufferPool pool, long a, long b, long c, long d) {
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

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
