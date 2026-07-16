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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Phase 1 Step 5b of {@code plans/unify-rdf-encoding-on-term-codec.md} (ADR-0036) — proves the
 * leapfrog triejoin runs <b>TermId-native</b>. The triangle {@code ?x e ?y . ?y e ?z . ?z e ?x} is
 * evaluated over a {@code TermId}-keyed (Int64×4) SPOC/POSC built through the shared {@link
 * Dictionary}, and must equal the brute-force directed triangles. This exercises the whole TermId
 * path: fixed-width keys (Step-4 successor), type-generic projections (Step-5a), and constant
 * encoding via {@link TermEncoder} → {@code Dictionary} (the resolved fork — option (a)).
 */
class TermIdTriejoinProperty {

    private static final List<String> V = List.of("urn:v0", "urn:v1", "urn:v2", "urn:v3");
    private static final String EDGE = "urn:e";
    private static final String GRAPH = "urn:g";
    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final TupleDescriptor I64x4 =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.Int64, false), new Type(Encoding.Int64, false),
                            new Type(Encoding.Int64, false), new Type(Encoding.Int64, false)));

    record Edge(String from, String to) {}

    @Provide
    Arbitrary<Set<Edge>> graphs() {
        return Combinators.combine(Arbitraries.of(V), Arbitraries.of(V))
                .as(Edge::new)
                .set()
                .ofMinSize(1)
                .ofMaxSize(16);
    }

    @Property(tries = 80)
    void termIdTriangleEqualsBruteForce(@ForAll @From("graphs") Set<Edge> edges) {
        try (DirectBufferPool pool = new DirectBufferPool();
                Arena arena = Arena.ofShared()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            Dictionary dict = new Dictionary(store, pool, HashFunctions.defaultHash());

            // TermId-keyed SPOC + POSC, terms encoded through the shared dictionary.
            InMemoryNodeStore sStore = new InMemoryNodeStore();
            MutableMap smm =
                    new MutableMap(new StaticMap(sStore, null, I64x4), sStore, I64x4, pool);
            InMemoryNodeStore pStore = new InMemoryNodeStore();
            MutableMap pmm =
                    new MutableMap(new StaticMap(pStore, null, I64x4), pStore, I64x4, pool);
            long te = tid(dict, arena, EDGE), tg = tid(dict, arena, GRAPH);
            for (Edge e : edges) {
                long ts = tid(dict, arena, e.from()), to = tid(dict, arena, e.to());
                smm.put(row(pool, ts, te, to, tg), MemorySegment.NULL); // SPOC
                pmm.put(row(pool, te, to, ts, tg), MemorySegment.NULL); // POSC
            }
            StaticMap spoc = smm.flush(), posc = pmm.flush();

            List<QuadPattern> tri =
                    List.of(
                            QuadPattern.of("?x", EDGE, "?y", GRAPH),
                            QuadPattern.of("?y", EDGE, "?z", GRAPH),
                            QuadPattern.of("?z", EDGE, "?x", GRAPH));

            Set<List<Long>> got = new HashSet<>();
            for (Map<String, byte[]> r :
                    new LeapfrogTriejoin(
                                    tri, List.of("?x", "?y", "?z"), spoc, posc, I64x4, pool, dict)
                            .solve()) {
                got.add(List.of(asLong(r.get("?x")), asLong(r.get("?y")), asLong(r.get("?z"))));
            }

            // Oracle: brute-force directed triangles, mapped into TermId space.
            Set<List<Long>> oracle = new HashSet<>();
            for (String x : V)
                for (String y : V)
                    for (String z : V) {
                        if (edges.contains(new Edge(x, y))
                                && edges.contains(new Edge(y, z))
                                && edges.contains(new Edge(z, x))) {
                            oracle.add(
                                    List.of(
                                            tid(dict, arena, x),
                                            tid(dict, arena, y),
                                            tid(dict, arena, z)));
                        }
                    }
            assertEquals(
                    oracle, got, "TermId triangle must equal the brute-force directed triangles");
        }
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

    private static long asLong(byte[] b) {
        return TypeCodec.readInt64(MemorySegment.ofArray(b));
    }
}
