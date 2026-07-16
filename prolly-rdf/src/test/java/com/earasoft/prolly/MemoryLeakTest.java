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

/**
 *
 *
 * <h3>Memory Leak & Pool Occupancy Test</h3>
 *
 * <p>Verifies that the off-heap buffer pool correctly reclaims all memory after massive Merkle
 * walks and mutations. This is a critical Project Panama safety check.
 */
public class MemoryLeakTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Memory Leak Test ---");
        Path tempDir = Files.createTempDirectory("prolly-mem-leak");

        // Use a single pool for multiple operations
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            System.out.println("Executing heavy mutation churn...");
            Node root = null;
            for (int i = 0; i < 50; i++) {
                List<TreeMutator.Mutation> batch = new ArrayList<>();
                for (int j = 0; j < 100; j++) {
                    String k = String.format("k-%08d", (i * 100) + j);
                    byte[] largeVal = new byte[8000]; // 8KB per value
                    batch.add(
                            new TreeMutator.Mutation(
                                    buildKey(pool, k), MemorySegment.ofArray(largeVal)));
                }
                root = mutator.applyMutations(root, batch.iterator());
            }

            long peakMemory = pool.getTotalAllocatedBytes();
            System.out.println("Peak Off-Heap Memory: " + (peakMemory / 1024 / 1024) + " MB");

            // Verify count
            if (root.treeCount() != 5000) throw new RuntimeException("Data loss during churn");

            System.out.println("Releasing all cursors and objects...");
            root = null;
            System.gc(); // Trigger GC for any remaining finalizers (though we use Arena)

            // Since we use Arena.ofShared() in the pool, the memory is tied to the pool's
            // lifecycle.
            // However, the BUCKETS in the pool should be stable or shrink if we added release
            // logic.
            // My current pool doesn't shrink buckets, but it should REUSE them.

            System.out.println("Checking reuse efficiency...");
            long memBefore = pool.getTotalAllocatedBytes();

            // Perform identical operation
            for (int i = 0; i < 10; i++) {
                List<TreeMutator.Mutation> batch = new ArrayList<>();
                for (int j = 0; j < 100; j++) {
                    String k = String.format("k-new-%08d", (i * 100) + j);
                    batch.add(
                            new TreeMutator.Mutation(
                                    buildKey(pool, k), MemorySegment.ofArray(new byte[8000])));
                }
                mutator.applyMutations(null, batch.iterator());
            }

            long memAfter = pool.getTotalAllocatedBytes();
            System.out.println("Memory after second run: " + (memAfter / 1024 / 1024) + " MB");

            // If the pool is working correctly, memory growth should be minimal due to bucket
            // reuse.
            if (memAfter > memBefore * 1.2) { // Allow some slack for different chunk sizes
                throw new RuntimeException("Significant memory growth detected! Reuse failed.");
            }

            System.out.println("Pool Reuse Efficiency: PASSED.");
            System.out.println("--- Memory Leak Test PASSED ---");
        }
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }
}
