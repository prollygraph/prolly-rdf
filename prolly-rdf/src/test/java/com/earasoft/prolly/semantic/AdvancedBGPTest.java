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

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 *
 *
 * <h3>Advanced BGP Logic Test</h3>
 *
 * <p>Verifies complex graph patterns with {@link Iri} native types.
 */
public class AdvancedBGPTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Advanced BGP Logic Test (IRI-native) ---");
        Path tempDir = Files.createTempDirectory("prolly-bgp-adv-iri");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                                    new Type(Encoding.IRI, false),
                                            new Type(Encoding.String, false)));

            // Setup Data
            MutableMap spocMap =
                    new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
            put(spocMap, pool, "A", "follows", "B");
            put(spocMap, pool, "B", "follows", "C");
            put(spocMap, pool, "A", "follows", "C");
            StaticMap spoc = spocMap.flush();

            MutableMap poscMap =
                    new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
            MapIterator it = spoc.iter();
            while (it.next()) {
                Tuple q = new Tuple(it.key());
                put(poscMap, pool, q.getField(1), q.getField(2), q.getField(0), q.getField(3));
            }
            StaticMap posc = poscMap.flush();

            GraphPatternEngine bgp =
                    new GraphPatternEngine(store, pool, desc, Map.of("SPOC", spoc, "POSC", posc));

            // 1. Triangle Join
            System.out.print("Testing Triangle Join (A -> ?x -> C)... ");
            List<QuadPattern> triangle =
                    List.of(
                            QuadPattern.of("A", "follows", "?x", "g1"),
                            QuadPattern.of("?x", "follows", "C", "g1"));
            MapIterator res1 = bgp.execute(triangle, "?x");
            if (!res1.next()) throw new RuntimeException("Match B not found");
            if (!new String(new Tuple(res1.key()).getField(0)).equals("B"))
                throw new RuntimeException("Wrong match");
            System.out.println("Passed.");

            // 2. Disjoint Join
            System.out.print("Testing Disjoint Join Intersection... ");
            List<QuadPattern> disjoint =
                    List.of(
                            QuadPattern.of("A", "follows", "?x", "g1"),
                            QuadPattern.of("D", "follows", "?x", "g1"));
            MapIterator res2 = bgp.execute(disjoint, "?x");
            if (res2.next()) throw new RuntimeException("Found unexpected result");
            System.out.println("Passed.");
        }
        System.out.println("--- Advanced BGP Test PASSED ---");
    }

    private static void put(MutableMap m, DirectBufferPool pool, String s, String p, String o) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        tb.putField(1, p.getBytes());
        tb.putField(2, o.getBytes());
        tb.putField(3, "g1".getBytes());
        m.put(tb.build().segment(), java.lang.foreign.MemorySegment.NULL);
    }

    private static void put(
            MutableMap m, DirectBufferPool pool, byte[] f0, byte[] f1, byte[] f2, byte[] f3) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, f0);
        tb.putField(1, f1);
        tb.putField(2, f2);
        tb.putField(3, f3);
        m.put(tb.build().segment(), java.lang.foreign.MemorySegment.NULL);
    }
}
