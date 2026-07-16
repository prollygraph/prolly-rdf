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
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
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

/**
 * Phase 2 Step 14 of prolly-rdf-test-strategy — BGP == relational-algebra oracle (R-6), through the
 * real {@link GraphPatternEngine}.
 *
 * <p>Per ADR-0037 the native {@code VersionedQuadStore} query entry point was retired, so this
 * property now drives {@link GraphPatternEngine} <b>directly</b>: it builds the raw-IRI SPOC + POSC
 * permutation indexes in-memory (the same shape the deleted store built at query time) and calls
 * {@code execute}/{@code executeMulti}. The engine + its sorted-projection contract are unchanged —
 * this is still the property that found Bug 2.
 *
 * <p><b>Engine semantics (verified by reading the source):</b> {@code GraphPatternEngine} performs
 * a <i>single-variable star join</i> — it projects every pattern onto the chosen {@code joinVar}
 * column and LeapfrogJoins those projections. So the answer is the <b>intersection</b>, over all
 * patterns, of the joinVar values each pattern matches (non-join vars are wildcards, bound
 * positions are filters). The oracle is exactly that nested-loop computation.
 *
 * <p>Patterns are generated from four engine-safe templates (the bound positions always form a
 * valid index prefix, so no unsupported "trailing bound after the join column" shape): each covers
 * partly-bound and out-of-scope-var cases. A shared entity vocab for subject+object positions makes
 * star joins non-trivial (a value can be both a subject and an object, like {@code ?y} in {@code ?x
 * follows ?y . ?y worksAt ?z}).
 */
class GraphPatternBgpProperty {

    private static final List<String> E = List.of("e0", "e1", "e2", "e3");
    private static final List<String> P = List.of("p0", "p1");
    private static final String G = "g";
    private static final String J = "?j";

    record Triple(String s, String p, String o) {}

    /** template: A=(?j,p,o) B=(s,p,?j) C=(?j,p,?w) D=(s,?w,?j) */
    record Spec(char tmpl, String ent, String pred) {}

    @Provide
    Arbitrary<Set<Triple>> quadSets() {
        Arbitrary<Triple> t =
                Combinators.combine(Arbitraries.of(E), Arbitraries.of(P), Arbitraries.of(E))
                        .as(Triple::new);
        return t.set().ofMaxSize(24);
    }

    // All four templates. A/B's join column immediately follows the bound
    // prefix (sorted projection); C/D put an UNBOUND position between the prefix
    // and the join column, so the raw projection is unsorted + may duplicate.
    // GraphPatternEngine now wraps every per-pattern projection in
    // SortedProjection (sort + dedup), satisfying LeapfrogJoin's precondition —
    // so C/D are exercised here too. (Before that fix they silently missed
    // matches; this property is what found the bug. See ADR-0033 /
    // the-leapfrog-join-contract.)
    @Provide
    Arbitrary<List<Spec>> bgps() {
        Arbitrary<Spec> spec =
                Combinators.combine(
                                Arbitraries.of('A', 'B', 'C', 'D'),
                                Arbitraries.of(E),
                                Arbitraries.of(P))
                        .as(Spec::new);
        return spec.list().ofMinSize(1).ofMaxSize(3);
    }

    @Property(tries = 60)
    void bgpEqualsNestedLoopStarJoinOracle(
            @ForAll @From("quadSets") Set<Triple> quads, @ForAll @From("bgps") List<Spec> bgp) {
        TupleDescriptor desc = spocDescriptor();
        try (DirectBufferPool pool = new DirectBufferPool()) {
            NodeStore store = new InMemoryNodeStore();

            // Oracle: intersection over patterns of the joinVar values each matches.
            Set<String> oracle = null;
            List<QuadPattern> patterns = new ArrayList<>();
            for (Spec s : bgp) {
                patterns.add(toPattern(s));
                Set<String> matches = matchJoinVar(quads, s);
                if (oracle == null) oracle = matches;
                else oracle.retainAll(matches);
            }

            GraphPatternEngine engine = buildEngine(quads, store, pool, desc);
            Set<String> actual = new HashSet<>();
            MapIterator it = engine.execute(patterns, J);
            while (it.next())
                actual.add(new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8));

            assertEquals(
                    oracle, actual, "BGP star-join on " + J + " must equal the nested-loop oracle");
        }
    }

    // ---- multi-variable BGP (the leapfrog triejoin, R-6 extension) --------

    /**
     * Multi-variable coverage through {@link GraphPatternEngine#executeMulti} → seek-scoped
     * projection → leapfrog trie — the complement to the single-variable star join above. The 2-hop
     * path {@code (?x p0 ?y)(?y p0 ?z)} must equal the nested-loop join oracle.
     */
    @Property(tries = 40)
    void multiVarPathEqualsNestedLoopOracle(@ForAll @From("quadSets") Set<Triple> quads) {
        String p = "p0";
        List<QuadPattern> patterns =
                List.of(QuadPattern.of("?x", p, "?y", G), QuadPattern.of("?y", p, "?z", G));
        Set<List<String>> actual = runMulti(quads, patterns, List.of("?x", "?y", "?z"));

        Set<List<String>> oracle = new HashSet<>();
        for (Triple a : quads)
            if (a.p().equals(p))
                for (Triple b : quads)
                    if (b.p().equals(p) && a.o().equals(b.s()))
                        oracle.add(List.of(a.s(), a.o(), b.o()));

        assertEquals(oracle, actual, "multi-var 2-hop path BGP must equal the nested-loop oracle");
    }

    /**
     * Deterministic triangle regression seed: a directed 3-cycle (+ a noise edge) must yield
     * exactly its three rotations. Locks the cyclic WCOJ case so it is always exercised, not only
     * by random tries.
     */
    @Example
    void triangleRegressionSeed() {
        Set<Triple> g =
                new HashSet<>(
                        List.of(
                                new Triple("e0", "p0", "e1"), new Triple("e1", "p0", "e2"),
                                new Triple("e2", "p0", "e0"), new Triple("e0", "p0", "e3")));
        List<QuadPattern> tri =
                List.of(
                        QuadPattern.of("?x", "p0", "?y", G),
                        QuadPattern.of("?y", "p0", "?z", G),
                        QuadPattern.of("?z", "p0", "?x", G));
        Set<List<String>> actual = runMulti(g, tri, List.of("?x", "?y", "?z"));
        Set<List<String>> expected =
                Set.of(
                        List.of("e0", "e1", "e2"),
                        List.of("e1", "e2", "e0"),
                        List.of("e2", "e0", "e1"));
        assertEquals(expected, actual, "directed 3-cycle must yield its three rotations");
    }

    /**
     * Build the raw-IRI SPOC + POSC indexes in-memory and run the multi-variable triejoin,
     * returning the bindings as {@code [order...]} string rows.
     */
    private Set<List<String>> runMulti(
            Set<Triple> quads, List<QuadPattern> patterns, List<String> order) {
        TupleDescriptor desc = spocDescriptor();
        try (DirectBufferPool pool = new DirectBufferPool()) {
            NodeStore store = new InMemoryNodeStore();
            GraphPatternEngine engine = buildEngine(quads, store, pool, desc);
            Set<List<String>> out = new HashSet<>();
            for (Map<String, byte[]> m : engine.executeMulti(patterns, order)) {
                List<String> row = new ArrayList<>();
                for (String v : order) row.add(new String(m.get(v), StandardCharsets.UTF_8));
                out.add(row);
            }
            return out;
        }
    }

    // ---- in-memory index construction (replaces the retired VersionedQuadStore) ----

    private static TupleDescriptor spocDescriptor() {
        return new TupleDescriptor(
                List.of(
                        new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                        new Type(Encoding.IRI, false), new Type(Encoding.String, false)));
    }

    /**
     * Build SPOC from the quads (in graph {@link #G}) + derive POSC, then wrap a
     * GraphPatternEngine.
     */
    private static GraphPatternEngine buildEngine(
            Set<Triple> quads, NodeStore store, DirectBufferPool pool, TupleDescriptor desc) {
        MutableMap spocMap = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
        byte[] g = G.getBytes(StandardCharsets.UTF_8);
        for (Triple t : quads) {
            put(
                    spocMap,
                    pool,
                    t.s().getBytes(StandardCharsets.UTF_8),
                    t.p().getBytes(StandardCharsets.UTF_8),
                    t.o().getBytes(StandardCharsets.UTF_8),
                    g);
        }
        StaticMap spoc = spocMap.flush();

        MutableMap poscMap = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
        MapIterator it = spoc.iter();
        while (it.next()) {
            Tuple q = new Tuple(it.key());
            put(poscMap, pool, q.getField(1), q.getField(2), q.getField(0), q.getField(3));
        }
        StaticMap posc = poscMap.flush();

        return new GraphPatternEngine(store, pool, desc, Map.of("SPOC", spoc, "POSC", posc));
    }

    private static void put(
            MutableMap m, DirectBufferPool pool, byte[] f0, byte[] f1, byte[] f2, byte[] f3) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, f0);
        tb.putField(1, f1);
        tb.putField(2, f2);
        tb.putField(3, f3);
        m.put(tb.build().segment(), MemorySegment.NULL);
    }

    private static QuadPattern toPattern(Spec s) {
        return switch (s.tmpl()) {
            case 'A' -> QuadPattern.of(J, s.pred(), s.ent(), G); // (?j, p, o)
            case 'B' -> QuadPattern.of(s.ent(), s.pred(), J, G); // (s, p, ?j)
            case 'C' -> QuadPattern.of(J, s.pred(), "?w", G); // (?j, p, ?w) — out-of-scope var
            default -> QuadPattern.of(s.ent(), "?w", J, G); // (s, ?w, ?j) — out-of-scope var
        };
    }

    /**
     * The set of ?j bindings this single pattern matches against the quad set (non-join positions
     * are wildcards; bound positions filter).
     */
    private static Set<String> matchJoinVar(Set<Triple> quads, Spec s) {
        Set<String> out = new HashSet<>();
        for (Triple t : quads) {
            switch (s.tmpl()) {
                case 'A' -> {
                    if (t.p().equals(s.pred()) && t.o().equals(s.ent())) out.add(t.s());
                }
                case 'B' -> {
                    if (t.s().equals(s.ent()) && t.p().equals(s.pred())) out.add(t.o());
                }
                case 'C' -> {
                    if (t.p().equals(s.pred())) out.add(t.s());
                }
                default -> {
                    if (t.s().equals(s.ent())) out.add(t.o());
                }
            }
        }
        return out;
    }
}
