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
package com.earasoft.prolly.pool;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;

/**
 * PoolStressTest targets DirectBufferPool with high-frequency concurrent allocation and
 * deallocation across different bucket sizes.
 */
public class PoolStressTest {
    private static final int NUM_THREADS = 8;
    private static final int OPS_PER_THREAD = 10000;

    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Buffer Pool Stress Test ---");
        try (DirectBufferPool pool = new DirectBufferPool()) {
            testConcurrentChurn(pool);
            testBucketIsolation(pool);
        }
        System.out.println("--- Buffer Pool Stress Test PASSED ---");
    }

    private static void testConcurrentChurn(DirectBufferPool pool) throws Exception {
        System.out.print("Simulating High Churn (" + (NUM_THREADS * OPS_PER_THREAD) + " ops)... ");
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < NUM_THREADS; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                Random rnd = new Random();
                                for (int j = 0; j < OPS_PER_THREAD; j++) {
                                    int size = rnd.nextInt(1, 20000);
                                    MemorySegment seg = pool.borrow(size);
                                    if (seg.byteSize() < size)
                                        throw new RuntimeException("Undersized segment");
                                    // Yield occasionally
                                    if (j % 10 == 0) Thread.yield();
                                    pool.release(seg);
                                }
                            }));
        }

        for (var f : futures) f.get();
        executor.shutdown();
        System.out.println("Passed.");
    }

    private static void testBucketIsolation(DirectBufferPool pool) {
        System.out.print("Verifying Bucket Integrity... ");
        MemorySegment seg1K = pool.borrow(512);
        MemorySegment seg4K = pool.borrow(4096);

        if (seg1K.byteSize() != 1024) throw new RuntimeException("Expected 1024 bucket");
        if (seg4K.byteSize() != 4096) throw new RuntimeException("Expected 4096 bucket");

        pool.release(seg1K);
        pool.release(seg4K);

        // Re-borrow should ideally reuse from buckets
        MemorySegment seg1K_again = pool.borrow(800);
        if (seg1K_again.byteSize() != 1024) throw new RuntimeException("Reuse failed");

        System.out.println("Passed.");
    }
}
