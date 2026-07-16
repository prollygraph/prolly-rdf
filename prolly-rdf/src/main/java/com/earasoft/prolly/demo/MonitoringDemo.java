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
package com.earasoft.prolly.demo;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
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

public class MonitoringDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Prolly Tree Monitoring & Observability Demo ===");
        Path tempDir = Files.createTempDirectory("prolly-monitoring");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore rocksStore = new RocksNodeStore(tempDir.toString())) {

            MetricsNodeStore metricsStore = new MetricsNodeStore(rocksStore);
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(metricsStore, "monitored-repo", desc, pool);
            db.createBranch("main", "EMPTY");

            // 1. Perform a heavy write workload
            System.out.println("Simulating write workload...");
            MutableMap mm = new MutableMap(db.getBranch("main"), metricsStore, desc, pool);
            for (int i = 0; i < 5000; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%08d", i).getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray("data".getBytes()));
            }
            db.commit("main", mm.flush(), null, "admin", "Batch Load");

            // 2. Perform a read workload
            System.out.println("Simulating read workload...");
            StaticMap sm = db.getBranch("main");
            for (int i = 0; i < 5000; i += 10) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%08d", i).getBytes());
                sm.get(tb.build().segment());
            }

            // 3. Report Stats
            System.out.println("\nPRODUCTION METRICS REPORT:");
            System.out.println("--------------------------------------------------");
            System.out.println("NodeStore Read Count:  " + metricsStore.getReadCount());
            System.out.println(
                    "NodeStore Read Bytes:  " + formatBytes(metricsStore.getReadBytes()));
            System.out.println("NodeStore Write Count: " + metricsStore.getWriteCount());
            System.out.println(
                    "NodeStore Write Bytes: " + formatBytes(metricsStore.getWriteBytes()));
            System.out.println("--------------------------------------------------");
            System.out.println(
                    "BufferPool Total Allocated: " + formatBytes(pool.getTotalAllocatedBytes()));
            System.out.println("BufferPool Borrow Count:    " + pool.getBorrowedCount());
            System.out.println("BufferPool Release Count:   " + pool.getReleasedCount());
            System.out.println("BufferPool Bucket Count:    " + pool.getActiveBucketCount());
            System.out.println("--------------------------------------------------");
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %ciB", bytes / Math.pow(1024, exp), pre);
    }
}
