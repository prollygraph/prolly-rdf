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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
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
import java.util.List;

public class GCTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Garbage Collection Test ---");
        Path tempDir = Files.createTempDirectory("prolly-gc");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "gc-repo", desc, pool);
            TupleBuilder tb = new TupleBuilder(pool);
            db.createBranch("main", "EMPTY");
            StaticMap sm1 = db.getBranch("main");
            MutableMap mm = new MutableMap(sm1, store, desc, pool);
            tb.putField(0, "item1".getBytes());
            mm.put(tb.build().segment(), MemorySegment.ofArray("val1".getBytes()));
            db.commit("main", mm.flush(), null, "author", "v1");
            byte[] head1 = db.getHeadHash("main").get();
            mm = new MutableMap(db.getBranch("main"), store, desc, pool);
            tb.putField(0, "item2".getBytes());
            mm.put(tb.build().segment(), MemorySegment.ofArray("val2".getBytes()));
            db.commit("main", mm.flush(), head1, "author", "v2");
            GarbageCollector gc = new GarbageCollector(db, store);
            gc.collect();
            // Invariant: GC must never delete LIVE data. Both commits are
            // reachable (main -> v2 -> v1), so a correct collect() leaves both
            // keys readable and the head resolvable; an over-collecting GC
            // drops chunks and these reads fail. The THROW is what fails the
            // DynamicTest -- MainMethodTests treats a clean return as PASS, so
            // before this, "gc.collect() didn't crash" was the only bar and the
            // test could never actually fail.
            tb.putField(0, "item1".getBytes());
            boolean item1Live = db.getBranch("main").get(tb.build().segment()).isPresent();
            tb.putField(0, "item2".getBytes());
            boolean item2Live = db.getBranch("main").get(tb.build().segment()).isPresent();
            if (!item1Live || !item2Live) {
                throw new AssertionError(
                        "GC deleted live data: item1Live=" + item1Live + " item2Live=" + item2Live);
            }
            if (db.getHeadHash("main").isEmpty()) {
                throw new AssertionError("GC left branch head unresolvable");
            }
            System.out.println("--- GCTest PASSED (live data intact after GC) ---");
        }
    }
}
