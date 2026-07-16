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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrencyTest {
    private static final int NUM_THREADS = 4;
    private static final int COMMITS_PER_THREAD = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Concurrency Stress Test ---");
        Path tempDir = Files.createTempDirectory("prolly-concurrency");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "concurrent-repo", desc, pool);
            db.createBranch("main", "EMPTY");
            ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
            AtomicInteger totalConflicts = new AtomicInteger(0);
            java.util.concurrent.atomic.AtomicReference<Throwable> threadError =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(NUM_THREADS);
            for (int i = 0; i < NUM_THREADS; i++) {
                final int threadId = i;
                executor.submit(
                        () -> {
                            try {
                                TupleBuilder tb = new TupleBuilder(pool);
                                for (int j = 0; j < COMMITS_PER_THREAD; j++) {
                                    boolean committed = false;
                                    while (!committed) {
                                        byte[] parent = db.getHeadHash("main").orElse(null);
                                        StaticMap base = db.getBranch("main");
                                        MutableMap mm = new MutableMap(base, store, desc, pool);
                                        tb.putField(
                                                0,
                                                String.format("t%d-commit%d", threadId, j)
                                                        .getBytes());
                                        mm.put(
                                                tb.build().segment(),
                                                MemorySegment.ofArray("data".getBytes()));
                                        if (db.commit("main", mm.flush(), parent, "author", "msg"))
                                            committed = true;
                                        else totalConflicts.incrementAndGet();
                                    }
                                }
                            } catch (Throwable e) {
                                threadError.compareAndSet(null, e);
                            } finally {
                                latch.countDown();
                            }
                        });
            }
            latch.await();
            executor.shutdown();
            long finalCount = db.getBranch("main").root().treeCount();
            long expected = (long) NUM_THREADS * COMMITS_PER_THREAD;
            System.out.println("Final Tree Count: " + finalCount);
            System.out.println("Total Conflicts Resolved: " + totalConflicts.get());
            // R-2: concurrent commits serialize through the manifest CAS with NO
            // lost updates. Each thread writes COMMITS_PER_THREAD distinct keys,
            // so all NUM_THREADS*COMMITS_PER_THREAD must survive -- a dropped
            // update (CAS race accepting a stale parent) leaves the tree short.
            // The THROW is what fails the DynamicTest; previously the loop only
            // printed PASSED and could never fail.
            if (threadError.get() != null) {
                throw new AssertionError("a writer thread failed", threadError.get());
            }
            if (finalCount != expected) {
                throw new AssertionError(
                        "lost update under concurrent OCC: expected "
                                + expected
                                + " keys, tree has "
                                + finalCount);
            }
            System.out.println("--- Concurrency Test PASSED (no lost updates) ---");
        }
    }
}
