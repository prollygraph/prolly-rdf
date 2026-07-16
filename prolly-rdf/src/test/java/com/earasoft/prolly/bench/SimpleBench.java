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
package com.earasoft.prolly.bench;

import com.dolthub.prolly.*;
import com.dolthub.prolly.BuzHash;
import com.dolthub.prolly.ByteUtils;
import com.dolthub.prolly.TupleBuilder;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.Random;

/** A simple benchmarking harness for Prolly Tree primitives. */
public class SimpleBench {
    private static final int ITERATIONS = 10_000_000;
    private static final int DATA_SIZE = 1024 * 1024 * 100; // 100MB

    public static void main(String[] args) {
        System.out.println("--- Prolly Tree Java Port Benchmarks ---");

        benchBuzHash();
        benchComparison();
        benchTupleBuilding();
    }

    private static void benchBuzHash() {
        BuzHash bz = new BuzHash(67);
        byte[] data = new byte[DATA_SIZE];
        new Random(42).nextBytes(data);

        System.out.print(
                "Benchmarking BuzHash throughput (" + (DATA_SIZE / 1024 / 1024) + "MB)... ");
        long start = System.nanoTime();
        for (byte b : data) {
            bz.hashByte(b);
        }
        long end = System.nanoTime();

        double seconds = (end - start) / 1_000_000_000.0;
        double mbps = (DATA_SIZE / 1024.0 / 1024.0) / seconds;
        System.out.printf("%.2f MB/s\n", mbps);
    }

    private static void benchComparison() {
        byte[] a = new byte[32];
        byte[] b = new byte[32];
        new Random(42).nextBytes(a);
        new Random(43).nextBytes(b);

        MemorySegment msA = MemorySegment.ofArray(a);
        MemorySegment msB = MemorySegment.ofArray(b);

        System.out.print("Benchmarking unsigned comparisons (" + ITERATIONS + " ops)... ");
        long start = System.nanoTime();
        int sink = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sink += ByteUtils.compareUnsigned(msA, msB);
        }
        long end = System.nanoTime();

        double seconds = (end - start) / 1_000_000_000.0;
        double ops = ITERATIONS / seconds;
        System.out.printf("%.2f M ops/s (sink: %d)\n", ops / 1_000_000.0, sink);
    }

    private static void benchTupleBuilding() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            TupleBuilder builder = new TupleBuilder(pool);
            byte[] field1 = "subject-uri-123456789".getBytes();
            byte[] field2 = "predicate-uri-abcdef".getBytes();
            byte[] field3 = "object-literal-value".getBytes();

            int count = 100_000;
            System.out.print("Benchmarking Tuple building (" + count + " triples)... ");
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                builder.putField(0, field1);
                builder.putField(1, field2);
                builder.putField(2, field3);
                builder.build();
                // In a real bench we would release, but we're testing builder overhead
            }
            long end = System.nanoTime();

            double seconds = (end - start) / 1_000_000_000.0;
            double ops = count / seconds;
            System.out.printf("%.2f K tuples/s\n", ops / 1000.0);
        }
    }
}
