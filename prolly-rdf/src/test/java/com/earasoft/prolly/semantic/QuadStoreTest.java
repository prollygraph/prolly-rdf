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
import com.earasoft.prolly.demo.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * QuadStoreTest validates the multi-index orchestration and join logic required for knowledge graph
 * workloads.
 */
public class QuadStoreTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Quad Logic Unit Test ---");
        Path tempDir = Files.createTempDirectory("prolly-quad-test");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            testIndexOrchestration(store, pool);
            testComplexLFTJ(store, pool);
            testVersionedGraphJoin(store, pool);
            testEmptyJoinIntersection(store, pool);
        }
        System.out.println("--- Quad Logic Unit Test PASSED ---");
    }

    private static void testIndexOrchestration(RocksNodeStore store, DirectBufferPool pool) {
        System.out.print("Testing Index Orchestration (SPOC -> POSC)... ");

        TupleDescriptor spocDesc = createQuadDesc();
        TupleDescriptor poscDesc = createQuadDesc();
        IndexSchema poscSchema = new IndexSchema("POSC", poscDesc, new int[] {1, 2, 0, 3});

        Table table =
                new Table(
                        store,
                        pool,
                        new StaticMap(store, null, spocDesc),
                        spocDesc,
                        spocDesc,
                        Map.of(poscSchema, new StaticMap(store, null, poscDesc)));

        TupleBuilder tbPK =
                new TupleBuilder(
                        pool, new TupleDescriptor(List.of(new Type(Encoding.String, false))));
        tbPK.putField(0, "S1".getBytes());
        MemorySegment pk = tbPK.build().segment();

        TupleBuilder tbRow = new TupleBuilder(pool, spocDesc);
        tbRow.putField(0, "S1".getBytes());
        tbRow.putField(1, "P1".getBytes());
        tbRow.putField(2, "O1".getBytes());
        tbRow.putField(3, "C1".getBytes());
        MemorySegment row = tbRow.build().segment();

        table.put(pk, row);
        var state = table.flush();

        TupleBuilder tbP = new TupleBuilder(pool, poscDesc);
        tbP.putField(0, "P1".getBytes());
        tbP.putField(1, "O1".getBytes());
        tbP.putField(2, "S1".getBytes());
        tbP.putField(3, "C1".getBytes());
        MemorySegment expectedSecondaryKey = tbP.build().segment();

        if (state.secondaries().get(poscSchema).get(expectedSecondaryKey).isEmpty())
            throw new RuntimeException("Secondary index out of sync");

        table.delete(pk);
        var state2 = table.flush();
        if (state2.secondaries().get(poscSchema).get(expectedSecondaryKey).isPresent())
            throw new RuntimeException("Secondary index failed to delete");

        System.out.println("Passed.");
    }

    private static void testComplexLFTJ(RocksNodeStore store, DirectBufferPool pool) {
        System.out.print("Testing Complex Leapfrog Triejoin... ");
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);

        MapIterator it1 = createIntIterator(store, pool, desc, List.of(1L, 5L, 10L, 15L, 20L));
        MapIterator it2 = createIntIterator(store, pool, desc, List.of(5L, 10L, 25L));
        MapIterator it3 = createIntIterator(store, pool, desc, List.of(2L, 5L, 10L, 30L));

        LeapfrogJoin join = new LeapfrogJoin(List.of(it1, it2, it3), desc);
        List<Long> results = new ArrayList<>();
        while (join.next())
            results.add(TypeCodec.decodeInt64(new Tuple(join.key()).getFieldSegment(0)));

        if (!results.equals(List.of(5L, 10L)))
            throw new RuntimeException("Join failed: " + results);
        System.out.println("Passed.");
    }

    private static void testEmptyJoinIntersection(RocksNodeStore store, DirectBufferPool pool) {
        System.out.print("Testing Empty Join Intersection... ");
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);

        MapIterator it1 = createIntIterator(store, pool, desc, List.of(1L, 2L, 3L));
        MapIterator it2 = createIntIterator(store, pool, desc, List.of(4L, 5L, 6L));

        LeapfrogJoin join = new LeapfrogJoin(List.of(it1, it2), desc);
        if (join.next()) throw new RuntimeException("Join should be empty");
        System.out.println("Passed.");
    }

    private static void testVersionedGraphJoin(RocksNodeStore store, DirectBufferPool pool) {
        System.out.print("Testing Versioned Graph Join... ");
        TupleDescriptor desc = createQuadDesc();
        Database db = new Database(store, "v-repo", desc, pool);
        db.createBranch("main", "EMPTY");

        db.createBranch("shared", "EMPTY");
        MutableMap mm = new MutableMap(db.getBranch("shared"), store, desc, pool);
        putQ(mm, pool, "A", "follows", "S");
        db.commit("shared", mm.flush(), null, "a", "s1");

        db.createBranch("shared2", "EMPTY");
        mm = new MutableMap(db.getBranch("shared2"), store, desc, pool);
        putQ(mm, pool, "A", "follows", "S");
        db.commit("shared2", mm.flush(), null, "a", "s2");

        StaticMap graph1 = db.getBranch("shared");
        StaticMap graph2 = db.getBranch("shared2");

        MapIterator it1 =
                new QuadStoreDemo.VariableIterator(
                        "G1", graph1, desc, pool, List.of("A", "follows"), 2);
        MapIterator it2 =
                new QuadStoreDemo.VariableIterator(
                        "G2", graph2, desc, pool, List.of("A", "follows"), 2);

        TupleDescriptor joinDesc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        LeapfrogJoin join = new LeapfrogJoin(List.of(it1, it2), joinDesc);

        if (!join.next()) throw new RuntimeException("Shared friend 'S' not found");
        System.out.println("Passed.");
    }

    private static TupleDescriptor createQuadDesc() {
        return new TupleDescriptor(
                List.of(
                        new Type(Encoding.String, false),
                        new Type(Encoding.String, false),
                        new Type(Encoding.String, false),
                        new Type(Encoding.String, false)));
    }

    private static void putQ(MutableMap mm, DirectBufferPool pool, String s, String p, String o) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        tb.putField(1, p.getBytes());
        tb.putField(2, o.getBytes());
        tb.putField(3, "g1".getBytes());
        mm.put(tb.build().segment(), MemorySegment.NULL);
    }

    private static MapIterator createIntIterator(
            RocksNodeStore store, DirectBufferPool pool, TupleDescriptor desc, List<Long> vals) {
        MutableMap mm = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
        for (long v : vals) {
            MemorySegment valSeg = pool.borrow(8);
            TypeCodec.encodeInt64(v, valSeg);
            TupleBuilder tbReal = new TupleBuilder(pool, desc);
            tbReal.putField(0, valSeg);
            mm.put(tbReal.build().segment(), MemorySegment.NULL);
        }
        return mm.flush().iter();
    }
}
