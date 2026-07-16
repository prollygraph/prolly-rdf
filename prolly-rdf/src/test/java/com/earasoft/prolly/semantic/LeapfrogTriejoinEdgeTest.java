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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Deterministic edge-branch coverage for {@link LeapfrogTriejoin} — the paths the randomized {@link
 * MultiVarTriejoinProperty} reliably leaves uncovered: the all-constant existence-filter
 * (satisfiable + unsatisfiable), a bound-subject projection, a fully-variable whole-scan
 * projection, the default-graph sentinel, the metric accessors, and the "variable in no pattern"
 * misuse contract.
 *
 * <p>Fixed 3-quad store so every expected binding is hand-verifiable: {@code (e0,p0,e1) (e0,p1,e2)
 * (e1,p0,e2)} all in graph {@code g}.
 */
class LeapfrogTriejoinEdgeTest {

    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    private record Quad(String s, String p, String o) {}

    private static final List<Quad> STORE =
            List.of(
                    new Quad("e0", "p0", "e1"),
                    new Quad("e0", "p1", "e2"),
                    new Quad("e1", "p0", "e2"));

    @Test
    void allConstantPattern_thatExists_actsAsSatisfiableFilter() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            // (e0,p0,e1) exists -> the filter passes; (?x,p1,?y) then binds (e0,e2).
            List<QuadPattern> qps =
                    List.of(
                            QuadPattern.of("e0", "p0", "e1", G),
                            QuadPattern.of("?x", "p1", "?y", G));
            Set<Map<String, String>> got = solve(qps, List.of("?x", "?y"), idx, pool);
            assertEquals(
                    Set.of(Map.of("?x", "e0", "?y", "e2")),
                    got,
                    "an existing all-constant pattern is a satisfied filter; the var pattern still binds");
        }
    }

    @Test
    void allConstantPattern_thatIsAbsent_makesBgpUnsatisfiable() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            // (e0,p0,e2) is NOT in the store -> the whole conjunctive BGP is empty.
            List<QuadPattern> qps =
                    List.of(
                            QuadPattern.of("e0", "p0", "e2", G),
                            QuadPattern.of("?x", "p1", "?y", G));
            assertTrue(
                    solve(qps, List.of("?x", "?y"), idx, pool).isEmpty(),
                    "an absent all-constant pattern makes the BGP unsatisfiable");
        }
    }

    @Test
    void boundSubjectPattern_projectsViaSpoc() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            // Constant subject -> projectScoped seeks the SPOC s-prefix.
            Set<Map<String, String>> got =
                    solve(List.of(QuadPattern.of("e0", "p0", "?y", G)), List.of("?y"), idx, pool);
            assertEquals(Set.of(Map.of("?y", "e1")), got, "bound subject e0 over p0 yields o=e1");
        }
    }

    @Test
    void fullyVariablePattern_scansWholeRelation() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            // No bound column -> the else branch + empty seek-prefix (atStart whole scan).
            Set<Map<String, String>> got =
                    solve(
                            List.of(QuadPattern.of("?x", "?p", "?o", G)),
                            List.of("?x", "?p", "?o"),
                            idx,
                            pool);
            Set<Map<String, String>> oracle =
                    STORE.stream()
                            .map(
                                    q ->
                                            (Map<String, String>)
                                                    new LinkedHashMap<>(
                                                            Map.of(
                                                                    "?x", q.s(), "?p", q.p(), "?o",
                                                                    q.o())))
                            .collect(Collectors.toSet());
            assertEquals(oracle, got, "a fully-variable pattern reproduces every stored triple");
        }
    }

    @Test
    void defaultGraphPattern_usesZeroSentinel_andMatchesNoNamedGraphQuad() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            // null graph -> the le8(0) default-graph sentinel; the store holds only
            // named-graph "g" quads, so nothing matches (the sentinel path executes).
            Set<Map<String, String>> got =
                    solve(
                            List.of(QuadPattern.of("?x", "p0", "?y", null)),
                            List.of("?x", "?y"),
                            idx,
                            pool);
            assertTrue(
                    got.isEmpty(),
                    "the default-graph sentinel matches no named-graph quad in this store");
        }
    }

    @Test
    void metricAccessors_areExposedAfterSolve() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            LeapfrogTriejoin tj =
                    new LeapfrogTriejoin(
                            List.of(QuadPattern.of("?x", "p0", "?y", G)),
                            List.of("?x", "?y"),
                            idx.spoc,
                            idx.posc,
                            SPOC,
                            pool);
            tj.solve();
            assertTrue(
                    tj.materializedRows() > 0, "the p0 projection materialized at least one row");
            assertTrue(
                    tj.projScanRows() >= tj.materializedRows(),
                    "scanned rows is an upper bound on materialized rows");
            assertTrue(tj.seekWork() >= 0, "seek-work is a non-negative counter");
        }
    }

    @Test
    void variableInNoPattern_throwsIllegalState() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            Idx idx = build(pool);
            // ?z is in the global order but no pattern binds it -> misuse contract.
            LeapfrogTriejoin tj =
                    new LeapfrogTriejoin(
                            List.of(QuadPattern.of("?x", "p0", "?y", G)),
                            List.of("?x", "?y", "?z"),
                            idx.spoc,
                            idx.posc,
                            SPOC,
                            pool);
            assertThrows(
                    IllegalStateException.class,
                    tj::solve,
                    "a variable in the order but in no pattern is unbindable");
        }
    }

    // ---- harness -------------------------------------------------------------

    private record Idx(StaticMap spoc, StaticMap posc) {}

    private static Idx build(DirectBufferPool pool) {
        InMemoryNodeStore sStore = new InMemoryNodeStore();
        MutableMap smm = new MutableMap(new StaticMap(sStore, null, SPOC), sStore, SPOC, pool);
        for (Quad q : STORE) smm.put(row(pool, q.s(), q.p(), q.o(), G), MemorySegment.NULL);
        InMemoryNodeStore pStore = new InMemoryNodeStore();
        MutableMap pmm = new MutableMap(new StaticMap(pStore, null, SPOC), pStore, SPOC, pool);
        for (Quad q : STORE) pmm.put(row(pool, q.p(), q.o(), q.s(), G), MemorySegment.NULL); // POSC
        return new Idx(smm.flush(), pmm.flush());
    }

    private static Set<Map<String, String>> solve(
            List<QuadPattern> qps, List<String> varOrder, Idx idx, DirectBufferPool pool) {
        Set<Map<String, String>> out = new java.util.HashSet<>();
        for (Map<String, byte[]> r :
                new LeapfrogTriejoin(qps, varOrder, idx.spoc, idx.posc, SPOC, pool).solve()) {
            Map<String, String> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, new String(v, StandardCharsets.UTF_8)));
            out.add(m);
        }
        return out;
    }

    private static MemorySegment row(
            DirectBufferPool pool, String a, String b, String c, String d) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, a.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, b.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, c.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, d.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
