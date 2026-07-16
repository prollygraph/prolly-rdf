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

public class MainTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Full Parity E2E Test ---");
        Path tempDir = Files.createTempDirectory("prolly-parity");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "test-repo", desc, pool);
            TupleBuilder tb = new TupleBuilder(pool);

            db.createBranch("main", "EMPTY");
            byte[] parent = db.getHeadHash("main").orElse(null);
            StaticMap sm = db.getBranch("main");
            MutableMap mm = new MutableMap(sm, store, desc, pool);

            int count = 2000;
            for (int i = 0; i < count; i++) {
                tb.putField(0, String.format("key-%05d", i).getBytes());
                Tuple key = tb.build();
                tb.putField(0, String.format("val-%05d", i).getBytes());
                Tuple val = tb.build();
                mm.put(key.segment(), val.segment());
            }
            db.commit("main", mm.flush(), parent, "author", "initial");
            System.out.println("Done.");

            sm = db.getBranch("main");
            System.out.println(
                    "Root Level: " + sm.root().level() + ", Items: " + sm.root().treeCount());

            System.out.print("Testing point lookups... ");
            for (int i = 0; i < count; i += 100) {
                tb.putField(0, String.format("key-%05d", i).getBytes());
                var res = sm.get(tb.build().segment());
                if (res.isEmpty()) throw new RuntimeException("Missing key " + i);
            }
            System.out.println("Passed.");

            System.out.print("Testing prefix scan (key-001)... ");
            tb.putField(0, "key-001".getBytes());
            MapIterator it =
                    sm.iterPrefix(
                            tb.build().segment(), MemorySegment.ofArray("key-001".getBytes()));
            int found = 0;
            while (it.next()) found++;
            if (found != 100) throw new RuntimeException("Prefix scan failed: " + found);
            System.out.println("Passed.");

            System.out.println("--- Full Parity E2E Test PASSED ---");
        }
    }
}
