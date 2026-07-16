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

public class MVCCTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree MVCC Manifest Test (Automated Rebase) ---");
        Path tempDir = Files.createTempDirectory("prolly-mvcc");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "test-repo", desc, pool);

            db.createBranch("main", "EMPTY");
            byte[] parentHash = db.getHeadHash("main").orElse(null);
            StaticMap sm1 = db.getBranch("main");

            TupleBuilder tbA = new TupleBuilder(pool);
            MutableMap mmA = new MutableMap(sm1, store, desc, pool);
            tbA.putField(0, "key1".getBytes());
            Tuple k1 = tbA.build();
            mmA.put(k1.segment(), MemorySegment.ofArray("valA".getBytes()));
            StaticMap smA = mmA.flush();

            // Client B starts from the same sm1
            MutableMap mmB = new MutableMap(sm1, store, desc, pool);
            TupleBuilder tbB = new TupleBuilder(pool);
            tbB.putField(0, "key2".getBytes());
            Tuple k2 = tbB.build();
            mmB.put(k2.segment(), MemorySegment.ofArray("valB".getBytes()));

            // Client A commits successfully
            db.commit("main", smA, parentHash, "author", "commitA");

            // Client B attempt (without clearing its edits)
            // We use a temporary flush here or implement a way to check commit without destruction
            TreeMutator mutator = new TreeMutator(store, desc, pool);
            var mutationIter =
                    List.of(
                                    new TreeMutator.Mutation(
                                            k2.segment(), MemorySegment.ofArray("valB".getBytes())))
                            .iterator();
            StaticMap smB_attempt =
                    new StaticMap(store, mutator.applyMutations(sm1.root(), mutationIter), desc);

            boolean okB = db.commit("main", smB_attempt, parentHash, "author", "commitB");
            if (okB) throw new RuntimeException("Conflict not detected");

            // Automated Rebase
            StaticMap smLatest = db.getBranch("main");
            mmB = db.rebase(mmB, smLatest);
            db.commit(
                    "main", mmB.flush(), db.getHeadHash("main").get(), "author", "commitB-rebased");

            StaticMap finalMap = db.getBranch("main");
            System.out.println("Final Tree Count: " + finalMap.root().treeCount());

            if (finalMap.root().treeCount() != 2)
                throw new RuntimeException(
                        "Merge failed: expected 2, got " + finalMap.root().treeCount());
            System.out.println("--- MVCC Test PASSED ---");
        }
    }
}
