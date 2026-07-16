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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Deterministic coverage for the remaining WCOJ surface the property tests skip: {@link
 * ProjectingIterator#seek}, {@link GraphPatternEngine}'s heuristic {@code executeMulti(patterns)}
 * (which also drives {@link SelectivityVariableOrder}), and the {@code createIteratorForPattern}
 * "unsupported pattern" contract (a pattern with both subject and predicate variable, which no
 * index can prefix).
 */
class WcojEngineEdgeTest {

    private static final String G = "g";

    private static TupleDescriptor spocDesc() {
        return new TupleDescriptor(
                List.of(
                        new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                        new Type(Encoding.IRI, false), new Type(Encoding.String, false)));
    }

    private record T(String s, String p, String o) {}

    private static final List<T> CORPUS =
            List.of(
                    new T("A", "follows", "Bob"),
                    new T("A", "follows", "Carol"),
                    new T("A", "follows", "Dan"),
                    new T("A", "follows", "Eve"),
                    new T("B", "follows", "Carol"));

    private static byte[] b(String v) {
        return v.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] x) {
        return new String(x, StandardCharsets.UTF_8);
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

    private static GraphPatternEngine engine(DirectBufferPool pool, TupleDescriptor desc) {
        NodeStore store = new InMemoryNodeStore();
        MutableMap spocMap = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
        for (T t : CORPUS) put(spocMap, pool, b(t.s()), b(t.p()), b(t.o()), b(G));
        StaticMap spoc = spocMap.flush();
        MutableMap poscMap = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
        MapIterator it = spoc.iter();
        while (it.next()) {
            Tuple q = new Tuple(it.key());
            put(poscMap, pool, q.getField(1), q.getField(2), q.getField(0), q.getField(3));
        }
        return new GraphPatternEngine(
                store, pool, desc, Map.of("SPOC", spoc, "POSC", poscMap.flush()));
    }

    @Test
    void projectingIterator_seek_positionsAtOrAfterTarget() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            TupleDescriptor desc = spocDesc();
            NodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
            for (T t : CORPUS) put(mm, pool, b(t.s()), b(t.p()), b(t.o()), b(G));
            StaticMap spoc = mm.flush();

            ProjectingIterator pi =
                    new ProjectingIterator(spoc, desc, pool, List.of("A", "follows"), 2);
            // seek key is a single-field tuple carrying the projected o-value.
            TupleBuilder kb = new TupleBuilder(pool);
            kb.putField(0, b("Carol"));
            pi.seek(kb.build().segment());

            java.util.List<String> after = new java.util.ArrayList<>();
            while (pi.next()) after.add(str(new Tuple(pi.key()).getField(0)));
            assertFalse(after.contains("Bob"), "seek to Carol skips the earlier value Bob");
            assertTrue(
                    after.contains("Carol") || after.contains("Dan"),
                    "seek lands at or after the target, then iterates forward");
            assertTrue(
                    after.stream().allMatch(o -> o.compareTo("Carol") >= 0),
                    "every value after seek(Carol) is >= Carol");
        }
    }

    @Test
    void executeMulti_withHeuristic_choosesOrderAndJoins() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            TupleDescriptor desc = spocDesc();
            GraphPatternEngine eng = engine(pool, desc);
            // No explicit order -> SelectivityVariableOrder picks one. A 2-hop path.
            List<QuadPattern> patterns = List.of(QuadPattern.of("?x", "follows", "?y", G));
            List<Map<String, byte[]>> rows = eng.executeMulti(patterns);
            Set<String> ys = rows.stream().map(r -> str(r.get("?y"))).collect(Collectors.toSet());
            assertEquals(
                    Set.of("Bob", "Carol", "Dan", "Eve"),
                    ys,
                    "the heuristic-ordered single pattern binds every object of a follows edge");
        }
    }

    @Test
    void execute_patternWithVariableSubjectAndPredicate_isUnsupported() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            TupleDescriptor desc = spocDesc();
            GraphPatternEngine eng = engine(pool, desc);
            // (?x ?p Bob): neither subject nor predicate is a usable index prefix.
            List<QuadPattern> patterns = List.of(QuadPattern.of("?x", "?p", "Bob", G));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> eng.execute(patterns, "?x"),
                    "a pattern with both subject and predicate variable has no index prefix");
        }
    }
}
