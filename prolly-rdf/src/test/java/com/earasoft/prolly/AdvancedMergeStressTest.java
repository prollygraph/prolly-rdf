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
import java.util.*;
import java.util.concurrent.*;

/**
 *
 *
 * <h3>Advanced Merge Stress Test</h3>
 *
 * <p>Simulates high-concurrency branching and recursive merging back to main.
 */
public class AdvancedMergeStressTest {
    private static final int NUM_BRANCHES = 8;
    private static final int COMMITS_PER_BRANCH = 20;

    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Advanced Merge Stress Test (Concurrent) ---");
        Path tempDir = Files.createTempDirectory("prolly-merge-stress-concurrent");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "stress-repo", desc, pool);
            db.createBranch("main", "EMPTY");

            // 1. Initial State
            MutableMap mm = new MutableMap(db.getBranch("main"), store, desc, pool);
            for (int i = 0; i < 1000; i++) put(mm, pool, String.format("%08d", i), "base");
            db.commit("main", mm.flush(), null, "admin", "init");
            byte[] ancestor = db.getHeadHash("main").get();

            // 2. Parallel Branching
            System.out.println("Spawning " + NUM_BRANCHES + " threads for parallel commit DAGs...");
            ExecutorService executor = Executors.newFixedThreadPool(NUM_BRANCHES);
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < NUM_BRANCHES; i++) {
                final int branchId = i;
                String branchName = "dev-" + branchId;
                db.createBranch(branchName, "main");

                futures.add(
                        executor.submit(
                                () -> {
                                    try {
                                        byte[] localParent = ancestor;
                                        for (int j = 0; j < COMMITS_PER_BRANCH; j++) {
                                            MutableMap localMM =
                                                    new MutableMap(
                                                            db.getBranch(branchName),
                                                            store,
                                                            desc,
                                                            pool);
                                            for (int k = 0; k < 10; k++) {
                                                int keyId =
                                                        100000 + (branchId * 10000) + (j * 10) + k;
                                                put(
                                                        localMM,
                                                        pool,
                                                        String.format("%08d", keyId),
                                                        "val-" + branchId);
                                            }
                                            boolean ok =
                                                    db.commit(
                                                            branchName,
                                                            localMM.flush(),
                                                            localParent,
                                                            "author-" + branchId,
                                                            "commit-" + j);
                                            if (!ok)
                                                throw new RuntimeException(
                                                        "Concurrent commit failed on "
                                                                + branchName);
                                            localParent = db.getHeadHash(branchName).get();
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }));
            }

            for (var f : futures) f.get();
            executor.shutdown();
            System.out.println("Divergent histories complete.");

            // 3. Serial Merging
            System.out.println("Merging all branches into 'main'...");
            for (int i = 0; i < NUM_BRANCHES; i++) {
                String branchName = "dev-" + i;
                System.out.print("  Merging " + branchName + "... ");
                MergeEngine.MergeResult res =
                        db.merge("main", branchName, "merger", "merge " + branchName);
                if (!res.conflicts().isEmpty())
                    throw new RuntimeException("Unexpected conflict in branch " + i);
                System.out.println("Done.");
            }

            // 4. Final Integrity Check
            StaticMap finalMap = db.getBranch("main");
            long expectedCount = 1000 + (NUM_BRANCHES * COMMITS_PER_BRANCH * 10);
            System.out.println(
                    "Final Tree Count: "
                            + finalMap.root().treeCount()
                            + " (Expected: "
                            + expectedCount
                            + ")");
            if (finalMap.root().treeCount() != expectedCount)
                throw new RuntimeException("Final count mismatch");

            System.out.println("--- Advanced Merge Stress Test (Concurrent) PASSED ---");
        }
    }

    private static void put(MutableMap m, DirectBufferPool pool, String k, String v) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        m.put(tb.build().segment(), MemorySegment.ofArray(v.getBytes()));
    }
}
