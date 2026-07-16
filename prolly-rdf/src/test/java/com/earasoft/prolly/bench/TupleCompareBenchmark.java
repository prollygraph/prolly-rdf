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
import com.dolthub.prolly.ByteUtils;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Throughput of {@link TupleDescriptor#compare} — the hottest hot-path in cursor binary search and
 * mutation-stream merging. Three variants:
 *
 * <ul>
 *   <li>type-aware single-column string: per-byte compare via TypeCodec.
 *   <li>binary-parity single-column: same compare, fixed dispatch.
 *   <li>{@code ByteUtils.compareUnsigned} directly on raw segments (bypasses the descriptor
 *       entirely; the absolute-floor reference).
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class TupleCompareBenchmark {
    private static final int N = 1024;

    private DirectBufferPool pool;
    private TupleDescriptor descTypeAware;
    private TupleDescriptor descBinary;
    private MemorySegment[] keysA;
    private MemorySegment[] keysB;

    @Setup(Level.Trial)
    public void setup() {
        pool = new DirectBufferPool();
        descTypeAware = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        descBinary = new TupleDescriptor(List.of(new Type(Encoding.String, false)), true);
        keysA = new MemorySegment[N];
        keysB = new MemorySegment[N];
        Random rng = new Random(0xCAFEBABEL);
        for (int i = 0; i < N; i++) {
            byte[] aBytes = randomKey(rng, 32);
            byte[] bBytes = randomKey(rng, 32);
            keysA[i] = build(aBytes);
            keysB[i] = build(bBytes);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public int compareTypeAware() {
        int sum = 0;
        for (int i = 0; i < N; i++) {
            sum += descTypeAware.compare(new Tuple(keysA[i]), new Tuple(keysB[i]));
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public int compareBinaryParity() {
        int sum = 0;
        for (int i = 0; i < N; i++) {
            sum += descBinary.compare(new Tuple(keysA[i]), new Tuple(keysB[i]));
        }
        return sum;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public int compareUnsignedRaw() {
        int sum = 0;
        for (int i = 0; i < N; i++) {
            sum += ByteUtils.compareUnsigned(keysA[i], keysB[i]);
        }
        return sum;
    }

    private MemorySegment build(byte[] keyBytes) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, keyBytes);
        return tb.build().segment();
    }

    private static byte[] randomKey(Random rng, int len) {
        byte[] out = new byte[len];
        // Restrict to printable ASCII so collisions are realistic for string keys.
        for (int i = 0; i < len; i++) out[i] = (byte) (32 + rng.nextInt(95));
        return out;
    }
}
