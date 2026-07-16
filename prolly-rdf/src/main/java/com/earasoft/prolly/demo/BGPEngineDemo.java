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
package com.earasoft.prolly.demo;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.semantic.GraphPatternEngine;
import com.earasoft.prolly.semantic.QuadPattern;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>BGP Engine Demo</h3>
 *
 * <p>Demonstrates high-level graph pattern matching using the GraphPatternEngine with IRI-native
 * types.
 */
public class BGPEngineDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Prolly Tree BGP Pattern Engine Demo (IRI-native) ===");
        Path tempDir = Files.createTempDirectory("prolly-bgp-iri");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor spocDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                                    new Type(Encoding.IRI, false),
                                            new Type(Encoding.String, false)));

            TupleDescriptor poscDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                                    new Type(Encoding.IRI, false),
                                            new Type(Encoding.String, false)));

            // 1. Data Setup
            MutableMap spocMap =
                    new MutableMap(new StaticMap(store, null, spocDesc), store, spocDesc, pool);
            putQ(spocMap, pool, "Alice", "follows", "Bob");
            putQ(spocMap, pool, "Alice", "follows", "Charlie");
            putQ(spocMap, pool, "Bob", "worksAt", "DoltHub");
            putQ(spocMap, pool, "Charlie", "worksAt", "Google");

            StaticMap spocIndex = spocMap.flush();

            MutableMap poscMap =
                    new MutableMap(new StaticMap(store, null, poscDesc), store, poscDesc, pool);
            MapIterator it = spocIndex.iter();
            while (it.next()) {
                Tuple q = new Tuple(it.key());
                putQ(poscMap, pool, q.getField(1), q.getField(2), q.getField(0), q.getField(3));
            }
            StaticMap poscIndex = poscMap.flush();

            // 2. Initialize Engine
            Map<String, StaticMap> indices = Map.of("SPOC", spocIndex, "POSC", poscIndex);
            GraphPatternEngine bgp = new GraphPatternEngine(store, pool, spocDesc, indices);

            // 3. Define Patterns
            List<QuadPattern> query =
                    List.of(
                            QuadPattern.of("Alice", "follows", "?x", "g1"),
                            QuadPattern.of("?x", "worksAt", "DoltHub", "g1"));

            System.out.println("Executing BGP: (Alice follows ?x) AND (?x worksAt DoltHub)");
            MapIterator results = bgp.execute(query, "?x");

            int count = 0;
            while (results.next()) {
                String val = new String(new Tuple(results.key()).getField(0));
                System.out.println("  Match Found: ?x = " + val);
                count++;
            }
            if (count == 0) System.out.println("  (No results)");

            System.out.println("--- BGP Engine Demo PASSED ---");
        }
    }

    private static void putQ(MutableMap mm, DirectBufferPool pool, String s, String p, String o) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        tb.putField(1, p.getBytes());
        tb.putField(2, o.getBytes());
        tb.putField(3, "g1".getBytes());
        mm.put(tb.build().segment(), java.lang.foreign.MemorySegment.NULL);
    }

    private static void putQ(
            MutableMap mm,
            DirectBufferPool pool,
            byte @Nullable [] f0,
            byte @Nullable [] f1,
            byte @Nullable [] f2,
            byte @Nullable [] f3) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, f0);
        tb.putField(1, f1);
        tb.putField(2, f2);
        tb.putField(3, f3);
        mm.put(tb.build().segment(), java.lang.foreign.MemorySegment.NULL);
    }
}
