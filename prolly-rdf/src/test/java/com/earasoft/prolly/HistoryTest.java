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

public class HistoryTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Commit History Test ---");
        Path tempDir = Files.createTempDirectory("prolly-history");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "history-repo", desc, pool);
            db.createBranch("main", "EMPTY");

            for (int i = 1; i <= 3; i++) {
                byte[] parent = db.getHeadHash("main").orElse(null);
                StaticMap base = db.getBranch("main");
                MutableMap mm = new MutableMap(base, store, desc, pool);
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, ("item-" + i).getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray("data".getBytes()));
                db.commit("main", mm.flush(), parent, "author", "Commit #" + i);
            }

            System.out.print("Walking commit graph... ");
            Commit current = db.getHead("main");
            int count = 0;
            while (current != null) {
                count++;
                if (current.getParents().isEmpty()) break;
                byte[] pHash = current.getParents().get(0);
                current =
                        Commit.deserialize(
                                store.read(pHash)
                                        .get()
                                        .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
            }
            if (count != 3) throw new RuntimeException("History walk failed: " + count);
            System.out.println("Passed (found " + count + " commits).");

            System.out.println("--- History Test PASSED ---");
        }
    }
}
