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

public class VCTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree VC Utilities Test (Blame & Bisect) ---");
        Path tempDir = Files.createTempDirectory("prolly-vc");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "vc-repo", desc, pool);
            db.createBranch("main", "EMPTY");
            VCUtils vc = new VCUtils(db, store, desc);
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, "key1".getBytes());
            MemorySegment key1 = tb.build().segment();

            // 1. Create 5 commits
            byte[] firstCommit = null;
            byte[] thirdCommit = null;
            for (int i = 1; i <= 5; i++) {
                byte[] parent = db.getHeadHash("main").orElse(null);
                StaticMap base = db.getBranch("main");
                MutableMap mm = new MutableMap(base, store, desc, pool);
                mm.put(key1, MemorySegment.ofArray(("val-" + i).getBytes()));
                db.commit("main", mm.flush(), parent, "author-" + i, "msg-" + i);
                byte[] current = db.getHeadHash("main").get();
                if (i == 1) firstCommit = current;
                if (i == 3) thirdCommit = current;
            }

            // 2. Test Blame
            System.out.print("Testing Blame on key1... ");
            Commit blame = vc.blame("main", key1);
            if (!blame.getMessage().equals("msg-5"))
                throw new RuntimeException("Blame mismatch: " + blame.getMessage());
            System.out.println("Passed.");

            // 3. Test Bisect
            System.out.print("Testing Bisect (finding commit #3)... ");
            Commit bisect =
                    vc.bisect(
                            firstCommit,
                            db.getHeadHash("main").get(),
                            c -> {
                                // "Bad" if value is val-3 or higher
                                StaticMap sm =
                                        new StaticMap(
                                                store,
                                                store.read(c.getRootValueHash())
                                                        .map(Node::fromBytes)
                                                        .orElse(null),
                                                desc);
                                String val =
                                        new String(
                                                sm.get(key1)
                                                        .get()
                                                        .toArray(
                                                                java.lang.foreign.ValueLayout
                                                                        .JAVA_BYTE));
                                int num = Integer.parseInt(val.split("-")[1]);
                                return num >= 3;
                            });
            if (!bisect.getMessage().equals("msg-3"))
                throw new RuntimeException("Bisect mismatch: " + bisect.getMessage());
            System.out.println("Passed.");

            System.out.println("--- VC Utilities Test PASSED ---");
        }
    }
}
