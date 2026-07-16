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
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Quad Store & Knowledge Graph Demo</h3>
 *
 * <p>Demonstrates the Prolly Tree's capability as a versioned Quad Store (SPOC). Subject,
 * Predicate, and Object are modeled as IRI data types.
 */
public class QuadStoreDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Prolly Tree Quad Store & Graph Join Demo (IRI-native) ===");
        Path tempDir = Files.createTempDirectory("prolly-quad-iri");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            // Define Quad Schema using IRI encoding for S, P, and O
            TupleDescriptor spocDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.IRI, false), // S
                                    new Type(Encoding.IRI, false), // P
                                    new Type(Encoding.IRI, false), // O
                                    new Type(Encoding.String, false) // C
                                    ));

            TupleDescriptor poscDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.IRI, false), // P
                                    new Type(Encoding.IRI, false), // O
                                    new Type(Encoding.IRI, false), // S
                                    new Type(Encoding.String, false) // C
                                    ));

            Database db = new Database(store, "iri-graph-repo", spocDesc, pool);
            db.createBranch("main", "EMPTY");

            System.out.println("Loading IRI quads...");
            MutableMap spocIndex = new MutableMap(db.getBranch("main"), store, spocDesc, pool);

            // IRI quads:
            // <http://example.org/Alice> <http://example.org/follows> <http://example.org/Bob>
            // <http://example.org/Alice> <http://example.org/follows> <http://example.org/Charlie>
            // <http://example.org/Bob>   <http://example.org/worksAt> <http://example.org/DoltHub>

            String ns = "http://example.org/";
            putQuad(spocIndex, pool, ns + "Alice", ns + "follows", ns + "Bob", "g1");
            putQuad(spocIndex, pool, ns + "Alice", ns + "follows", ns + "Charlie", "g1");
            putQuad(spocIndex, pool, ns + "Bob", ns + "worksAt", ns + "DoltHub", "g1");
            putQuad(spocIndex, pool, ns + "Charlie", ns + "worksAt", ns + "Google", "g1");
            putQuad(spocIndex, pool, ns + "Dave", ns + "follows", ns + "Bob", "g1");

            StaticMap finalSpoc = spocIndex.flush();
            db.commit("main", finalSpoc, null, "admin", "Initial IRI load");

            System.out.println("Building Secondary POSC Index...");
            MutableMap poscMap =
                    new MutableMap(new StaticMap(store, null, poscDesc), store, poscDesc, pool);
            MapIterator it = finalSpoc.iter();
            while (it.next()) {
                Tuple q = new Tuple(it.key());
                putQuad(poscMap, pool, q.getField(1), q.getField(2), q.getField(0), q.getField(3));
            }
            StaticMap finalPosc = poscMap.flush();

            System.out.println(
                    "\nQUERY: Find ?x where (<Alice> follows ?x) AND (?x worksAt <DoltHub>)");

            // 1. Iterator for (<Alice>, <follows>, ?x)
            MapIterator aliceFollows =
                    new VariableIterator(
                            "AliceFollows",
                            finalSpoc,
                            spocDesc,
                            pool,
                            List.of(ns + "Alice", ns + "follows"),
                            2);

            // 2. Iterator for (?x, <worksAt>, <DoltHub>) -> in POSC index: (<worksAt>, <DoltHub>,
            // ?x)
            MapIterator doltHubEmployees =
                    new VariableIterator(
                            "DoltHubEmps",
                            finalPosc,
                            poscDesc,
                            pool,
                            List.of(ns + "worksAt", ns + "DoltHub"),
                            2);

            // 3. Intersection Join
            TupleDescriptor joinDesc = new TupleDescriptor(List.of(new Type(Encoding.IRI, false)));
            LeapfrogJoin join = new LeapfrogJoin(List.of(aliceFollows, doltHubEmployees), joinDesc);

            System.out.println("RESULTS:");
            int count = 0;
            while (join.next()) {
                String result = new String(new Tuple(join.key()).getField(0));
                System.out.println("  MATCH: ?x = <" + result + ">");
                count++;
            }

            System.out.println("\n--- Quad Store IRI Demo PASSED ---");
        }
    }

    private static void putQuad(
            MutableMap map, DirectBufferPool pool, String s, String p, String o, String c) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        tb.putField(1, p.getBytes());
        tb.putField(2, o.getBytes());
        tb.putField(3, c.getBytes());
        map.put(tb.build().segment(), MemorySegment.NULL);
    }

    private static void putQuad(
            MutableMap map,
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
        map.put(tb.build().segment(), MemorySegment.NULL);
    }

    public static class VariableIterator implements MapIterator {
        private final StaticMap map;
        private final TupleDescriptor desc;
        private final DirectBufferPool pool;
        private final List<String> prefix;
        private final int projectIdx;
        private MapIterator inner;
        // @Nullable: set only by next(); key() asserts the read-after-positioned-next() contract.
        private @Nullable MemorySegment currentJoinVar;
        private boolean done = false;

        public VariableIterator(
                String name,
                StaticMap map,
                TupleDescriptor desc,
                DirectBufferPool pool,
                List<String> prefix,
                int projectIdx) {
            this.map = map;
            this.desc = desc;
            this.pool = pool;
            this.prefix = prefix;
            this.projectIdx = projectIdx;
            TupleBuilder tb = new TupleBuilder(pool, desc);
            for (int i = 0; i < prefix.size(); i++) tb.putField(i, prefix.get(i).getBytes());
            this.inner = map.iterRange(tb.build().segment());
        }

        @Override
        public boolean next() {
            if (done) return false;
            while (inner.next()) {
                Tuple t = new Tuple(inner.key());
                if (!matchesPrefix(t)) {
                    done = true;
                    return false;
                }
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, t.getField(projectIdx));
                currentJoinVar = tb.build().segment();
                return true;
            }
            done = true;
            return false;
        }

        @Override
        public void seek(MemorySegment joinVarKey) {
            if (done) return;
            TupleBuilder tb = new TupleBuilder(pool, desc);
            for (int i = 0; i < prefix.size(); i++) tb.putField(i, prefix.get(i).getBytes());
            tb.putField(projectIdx, new Tuple(joinVarKey).getField(0));
            inner.seek(tb.build().segment());
        }

        private boolean matchesPrefix(Tuple t) {
            for (int i = 0; i < prefix.size(); i++) {
                byte[] f = t.getField(i);
                if (f == null || !Arrays.equals(f, prefix.get(i).getBytes())) return false;
            }
            return true;
        }

        @Override
        public boolean prev() {
            return false;
        }

        @Override
        public MemorySegment key() {
            return Objects.requireNonNull(currentJoinVar, "key() read before a successful next()");
        }

        @Override
        public MemorySegment value() {
            return MemorySegment.NULL;
        }
    }
}
