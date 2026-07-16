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
import java.util.ArrayList;
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
 * Phase 1 Step 6 of {@code multi-variable-leapfrog-triejoin.md} — the {@link LeapfrogTriejoin}
 * driver vs a nested-loop multi-way-join oracle, over generated acyclic BGPs with shared variables
 * (PATH {@code ?x→?y→?z}, STAR on {@code ?x}, and single two-variable patterns). The set of full
 * variable bindings the triejoin produces must equal the oracle's. Drives the real {@code
 * TrieIterator}/{@code LeapfrogJoin}; oracle = brute-force over the entity domain. (Cyclic queries
 * are Phase 2; this is acyclic.)
 */
class MultiVarTriejoinProperty {

    private static final List<String> E = List.of("e0", "e1", "e2");
    private static final List<String> P = List.of("p0", "p1");
    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    record Quad(String s, String p, String o) {}

    /** A BGP shape + the predicate constants it uses. */
    record Bgp(String shape, String pa, String pb) {}

    @Provide
    Arbitrary<Set<Quad>> quads() {
        return Combinators.combine(Arbitraries.of(E), Arbitraries.of(P), Arbitraries.of(E))
                .as(Quad::new)
                .set()
                .ofMinSize(1)
                .ofMaxSize(18);
    }

    @Provide
    Arbitrary<Bgp> bgps() {
        return Combinators.combine(
                        Arbitraries.of("PATH", "STAR", "SINGLE"),
                        Arbitraries.of(P),
                        Arbitraries.of(P))
                .as(Bgp::new);
    }

    @Property(tries = 100)
    void triejoinEqualsNestedLoopOracle(
            @ForAll @From("quads") Set<Quad> quads, @ForAll @From("bgps") Bgp bgp) {
        // Build the patterns (as [s,p,o] with vars "?x"/"?y"/"?z") + the global var order.
        List<String[]> pats = new ArrayList<>();
        List<String> varOrder;
        switch (bgp.shape()) {
            case "PATH" -> { // (?x,pa,?y) (?y,pb,?z) — share y
                pats.add(new String[] {"?x", bgp.pa(), "?y"});
                pats.add(new String[] {"?y", bgp.pb(), "?z"});
                varOrder = List.of("?x", "?y", "?z");
            }
            case "STAR" -> { // (?x,pa,?y) (?x,pb,?z) — share x
                pats.add(new String[] {"?x", bgp.pa(), "?y"});
                pats.add(new String[] {"?x", bgp.pb(), "?z"});
                varOrder = List.of("?x", "?y", "?z");
            }
            default -> { // SINGLE (?x,pa,?y)
                pats.add(new String[] {"?x", bgp.pa(), "?y"});
                varOrder = List.of("?x", "?y");
            }
        }

        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
            for (Quad q : quads) mm.put(spoc(pool, q.s(), q.p(), q.o(), G), MemorySegment.NULL);
            StaticMap index = mm.flush();

            // POSC (p,o,s,c) so p-bound patterns seek-scope (ADR-0034 Option B).
            InMemoryNodeStore pStore = new InMemoryNodeStore();
            MutableMap pmm = new MutableMap(new StaticMap(pStore, null, SPOC), pStore, SPOC, pool);
            for (Quad q : quads) pmm.put(spoc(pool, q.p(), q.o(), q.s(), G), MemorySegment.NULL);
            StaticMap posc = pmm.flush();

            // Driver.
            List<QuadPattern> qps = new ArrayList<>();
            for (String[] p : pats) qps.add(QuadPattern.of(p[0], p[1], p[2], G));
            Set<Map<String, String>> got = new HashSet<>();
            for (Map<String, byte[]> row :
                    new LeapfrogTriejoin(qps, varOrder, index, posc, SPOC, pool).solve()) {
                Map<String, String> r = new LinkedHashMap<>();
                row.forEach((k, v) -> r.put(k, new String(v, StandardCharsets.UTF_8)));
                got.add(r);
            }

            // Oracle: brute-force assignments over the entity domain.
            Set<Map<String, String>> oracle = new HashSet<>();
            for (String x : E)
                for (String y : E)
                    for (String z : E) {
                        Map<String, String> asg = Map.of("?x", x, "?y", y, "?z", z);
                        boolean all = true;
                        for (String[] p : pats) {
                            String s = resolve(p[0], asg),
                                    pr = resolve(p[1], asg),
                                    o = resolve(p[2], asg);
                            if (!quads.contains(new Quad(s, pr, o))) {
                                all = false;
                                break;
                            }
                        }
                        if (all) {
                            Map<String, String> r = new LinkedHashMap<>();
                            for (String v : varOrder) r.put(v, asg.get(v));
                            oracle.add(r);
                        }
                    }

            assertEquals(
                    oracle, got, "triejoin bindings must equal the nested-loop oracle; bgp=" + bgp);
        }
    }

    private static String resolve(String term, Map<String, String> asg) {
        return term.startsWith("?") ? asg.get(term) : term;
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
