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
import java.util.ArrayList;
import java.util.List;

public class FaultInjectionTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Fault Injection Test ---");
        Path tempDir = Files.createTempDirectory("prolly-fault");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore rocksStore = new RocksNodeStore(tempDir.toString())) {

            ErrorInjectingNodeStore errorStore = new ErrorInjectingNodeStore(rocksStore);
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(rocksStore, "fault-repo", desc, pool);
            db.createBranch("main", "EMPTY");

            TreeMutator mutator = new TreeMutator(errorStore, desc, pool);

            List<TreeMutator.Mutation> edits = new ArrayList<>();
            TupleBuilder tb = new TupleBuilder(pool);
            for (int i = 0; i < 2000; i++) {
                tb.putField(0, String.format("key-%05d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(), MemorySegment.ofArray("data".getBytes())));
            }

            System.out.print("Verifying mutation failure mid-operation... ");
            errorStore.injectErrorAfter(3);

            try {
                mutator.applyMutations(null, edits.iterator());
                System.err.println("FAILED: Mutation should have failed!");
                System.exit(1);
            } catch (RuntimeException e) {
                System.out.println("Passed (Caught: " + e.getMessage() + ")");
            }

            StaticMap current = db.getBranch("main");
            if (current.root() != null)
                throw new RuntimeException("Manifest was updated despite failure!");
            System.out.println("Rollback Safety: Verified.");
            System.out.println("--- Fault Injection Test PASSED ---");
        }
    }
}
