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
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/**
 * Phase 2 Step 8 of {@code multi-variable-leapfrog-triejoin.md} — the <b>triangle</b> query {@code
 * ?x :e ?y . ?y :e ?z . ?z :e ?x}, a <i>cyclic</i> BGP. This is the canonical
 * worst-case-optimal-join case, and it's the one the old SPOC-trie driver <i>could not run</i>: the
 * triangle has no SPOC-consistent variable order (`x<y<z<x`). The variable-only-trie rewrite
 * handles it by projecting each pattern in the chosen global order.
 *
 * <p>This runs on the materializing v1 trie, so it proves <b>correctness + the Gain-2 space win</b>
 * (no O(N²) intermediate) before any seek optimization; the O(N^1.5) <i>time</i> win is Phase 2
 * Step 9.
 */
class TriangleTriejoinProperty {

    private static final List<String> E = List.of("e0", "e1", "e2", "e3");
    private static final String EDGE = "e";
    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    record Edge(String from, String to) {}

    @Provide
    Arbitrary<Set<Edge>> graphs() {
        return Combinators.combine(Arbitraries.of(E), Arbitraries.of(E))
                .as(Edge::new)
                .set()
                .ofMinSize(1)
                .ofMaxSize(16);
    }

    @Property(tries = 100)
    void triangleEqualsOracle(@ForAll @From("graphs") Set<Edge> edges) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
            for (Edge e : edges) mm.put(spoc(pool, e.from(), EDGE, e.to(), G), MemorySegment.NULL);
            StaticMap index = mm.flush();

            // POSC (p,o,s,c) so the p-bound triangle patterns seek-scope (ADR-0034 Option B).
            InMemoryNodeStore pStore = new InMemoryNodeStore();
            MutableMap pmm = new MutableMap(new StaticMap(pStore, null, SPOC), pStore, SPOC, pool);
            for (Edge e : edges) pmm.put(spoc(pool, EDGE, e.to(), e.from(), G), MemorySegment.NULL);
            StaticMap posc = pmm.flush();

            // Triangle: ?x :e ?y . ?y :e ?z . ?z :e ?x
            List<QuadPattern> tri =
                    List.of(
                            QuadPattern.of("?x", EDGE, "?y", G),
                            QuadPattern.of("?y", EDGE, "?z", G),
                            QuadPattern.of("?z", EDGE, "?x", G));
            List<String> varOrder = List.of("?x", "?y", "?z");

            Set<Map<String, String>> got = new HashSet<>();
            for (Map<String, byte[]> row :
                    new LeapfrogTriejoin(tri, varOrder, index, posc, SPOC, pool).solve()) {
                Map<String, String> r = new LinkedHashMap<>();
                row.forEach((k, v) -> r.put(k, new String(v, StandardCharsets.UTF_8)));
                got.add(r);
            }

            // Oracle: brute-force directed triangles.
            Set<Map<String, String>> oracle = new HashSet<>();
            for (String x : E)
                for (String y : E)
                    for (String z : E) {
                        if (edges.contains(new Edge(x, y))
                                && edges.contains(new Edge(y, z))
                                && edges.contains(new Edge(z, x))) {
                            Map<String, String> r = new LinkedHashMap<>();
                            r.put("?x", x);
                            r.put("?y", y);
                            r.put("?z", z);
                            oracle.add(r);
                        }
                    }

            assertEquals(
                    oracle, got, "triejoin triangle must equal the brute-force directed triangles");
        }
    }

    private static MemorySegment spoc(
            DirectBufferPool pool, String s, String p, String o, String c) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, p.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, o.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, c.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
