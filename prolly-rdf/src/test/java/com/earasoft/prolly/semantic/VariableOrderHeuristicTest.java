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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/**
 * Phase 4 Step 13 of {@code multi-variable-leapfrog-triejoin.md} — pins the {@link
 * SelectivityVariableOrder} heuristic and <b>quantifies its effect</b> deterministically (the
 * CI-stable form of "benchmark with vs without"; JMH wall-time is Step 16).
 *
 * <p>Skewed graph: a <i>rare</i> anchor predicate (one edge) plus a <i>dense</i> predicate ({@code
 * M×M} edges). Query {@code (?x rare ?y) . (?y common ?z)}. A good order binds the rare-anchored
 * variable first (a one-value outer loop); a bad order binds {@code ?z} first (a wide outer loop
 * over the dense relation). The heuristic picks the good order, and its instrumented seek-work is
 * far below the bad order's — while both produce identical bindings (order is correctness- neutral,
 * performance-only).
 */
class VariableOrderHeuristicTest {

    private static final int M = 12;
    private static final String RARE = "p-rare";
    private static final String COMMON = "p-common";
    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    @Test
    void heuristicBindsSelectiveAnchorFirstAndCutsWork() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            // SPOC + POSC over: x0 -rare-> y0, and yi -common-> zj for all i,j.
            InMemoryNodeStore sStore = new InMemoryNodeStore();
            MutableMap smm = new MutableMap(new StaticMap(sStore, null, SPOC), sStore, SPOC, pool);
            InMemoryNodeStore pStore = new InMemoryNodeStore();
            MutableMap pmm = new MutableMap(new StaticMap(pStore, null, SPOC), pStore, SPOC, pool);

            put(smm, pmm, pool, "x0", RARE, "y0");
            for (int i = 0; i < M; i++)
                for (int j = 0; j < M; j++) put(smm, pmm, pool, "y" + i, COMMON, "z" + j);

            StaticMap spoc = smm.flush();
            StaticMap posc = pmm.flush();

            List<QuadPattern> patterns =
                    List.of(
                            QuadPattern.of("?x", RARE, "?y", G),
                            QuadPattern.of("?y", COMMON, "?z", G));

            // The heuristic must NOT bind ?z (the dense end) first.
            List<String> heuristicOrder =
                    new SelectivityVariableOrder(spoc, posc, pool).order(patterns);
            assertEquals(
                    3, new HashSet<>(heuristicOrder).size(), "order covers every variable once");
            assertNotEquals(
                    "?z",
                    heuristicOrder.get(0),
                    "most-constrained-first must not start with the dense-relation variable; got "
                            + heuristicOrder);

            // Work: heuristic order vs the pessimal ?z-first order.
            List<String> pessimal = List.of("?z", "?y", "?x");
            Run good = run(patterns, heuristicOrder, spoc, posc, pool);
            Run bad = run(patterns, pessimal, spoc, posc, pool);

            // Same bindings regardless of order (correctness), fewer seeks with the heuristic.
            System.out.printf(
                    "[var-order] heuristic %s seek-work=%d ; pessimal %s seek-work=%d%n",
                    heuristicOrder, good.seekWork, pessimal, bad.seekWork);
            assertEquals(
                    bad.bindings, good.bindings, "variable order must not change the result set");
            assertEquals(M, good.bindings.size(), "x0,y0 with each of the M z's");
            assertTrue(
                    good.seekWork < bad.seekWork,
                    "heuristic order should cut seek-work; good="
                            + good.seekWork
                            + " bad="
                            + bad.seekWork);
        }
    }

    private record Run(Set<Map<String, String>> bindings, long seekWork) {}

    private static Run run(
            List<QuadPattern> patterns,
            List<String> order,
            StaticMap spoc,
            StaticMap posc,
            DirectBufferPool pool) {
        LeapfrogTriejoin lftj = new LeapfrogTriejoin(patterns, order, spoc, posc, SPOC, pool);
        Set<Map<String, String>> out = new HashSet<>();
        for (Map<String, byte[]> row : lftj.solve()) {
            Map<String, String> r = new LinkedHashMap<>();
            row.forEach((k, v) -> r.put(k, new String(v, StandardCharsets.UTF_8)));
            out.add(r);
        }
        return new Run(out, lftj.seekWork());
    }

    private static void put(
            MutableMap spoc, MutableMap posc, DirectBufferPool pool, String s, String p, String o) {
        spoc.put(tuple(pool, s, p, o, G), MemorySegment.NULL); // SPOC = (s,p,o,c)
        posc.put(tuple(pool, p, o, s, G), MemorySegment.NULL); // POSC = (p,o,s,c)
    }

    private static MemorySegment tuple(
            DirectBufferPool pool, String a, String b, String c, String d) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, a.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, b.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, c.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, d.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
