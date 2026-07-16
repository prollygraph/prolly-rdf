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
package com.earasoft.prolly.rdf4j.bench;

import com.dolthub.prolly.Tuple;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Isolating control-armed microbench settling the {@code core-read-in-place-segments} lever (1): is
 * the descent's FFM cost the per-key <b>slice</b> (`getKeySegment` → `asSlice`, fixable by reading
 * the stable parent segment at an offset) or the per-`get` <b>ceremony</b> (only avoidable by
 * byte[]-direct, which grounding showed is infeasible for the zero-copy `MemorySegment` design)?
 *
 * <p>Three arms read the same field-0 (an Int64) from 64 single-column tuples in one heap segment,
 * the only variable being the read mechanism:
 *
 * <ul>
 *   <li><b>a_freshSlice</b> — `new Tuple(msg.asSlice(off, len)).getField(0)` + read: the current
 *       per-key pattern (slice view + Tuple wrapper + getField byte[]) — what lever (1) removes.
 *   <li><b>b_stableMsg</b> — `msg.get(LE_I64, off)`: lever (1) — read in place from the stable
 *       parent, no slice, no wrapper, no copy (still an FFM `get`, so still pays the per-get
 *       ceremony).
 *   <li><b>c_byteArray</b> — little-endian bit-ops on the backing `byte[]`: the byte[]-direct
 *       <em>ceiling</em> (no FFM at all) — infeasible to wire cleanly (the data is segments), but
 *       it bounds the prize.
 * </ul>
 *
 * <p>A→B = lever (1)'s win (drop the slice/wrapper/copy); B→C = whether the FFM ceremony is even
 * real. Run: {@code JmhRunner -f3 -rf json -rff seg.json SegmentReadModeBench}, then {@code
 * bench_significance.py --jmh seg.json --a a_freshSlice --b b_stableMsg}. Cycled offsets (not
 * loop-invariant) so the JIT can't hoist the `asSlice`; warm cache so the variable is the
 * mechanism.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 4, time = 2)
public class SegmentReadModeBench {

    private static final ValueLayout.OfLong LE_I64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final int K =
            64; // distinct keys cycled (defeats asSlice hoisting; stays in cache)
    private static final int TLEN =
            12; // single-Int64 tuple: 8 value + uint16 offset[0]=8 + uint16 count=1

    private byte[] backing;
    private MemorySegment msg;

    @Setup
    public void setup() {
        backing = new byte[K * TLEN];
        msg = MemorySegment.ofArray(backing);
        for (int i = 0; i < K; i++) {
            int o = i * TLEN;
            msg.set(LE_I64, o, 0x0102030405060708L + i); // field 0 (the value), bytes [o, o+8)
            msg.set(LE_U16, o + 8, (short) 8); // offset table: field-0 end = 8
            msg.set(LE_U16, o + 10, (short) 1); // count = 1
        }
    }

    /** Current pattern: per-key slice view + Tuple wrapper + getField byte[] copy + read. */
    @Benchmark
    public long a_freshSlice() {
        long s = 0;
        for (int i = 0; i < K; i++) {
            byte[] f = new Tuple(msg.asSlice((long) i * TLEN, TLEN)).getField(0);
            s += readLe(f, 0);
        }
        return s;
    }

    /**
     * Lever (1): read field 0 in place from the stable parent segment at its offset — no
     * slice/wrapper.
     */
    @Benchmark
    public long b_stableMsg() {
        long s = 0;
        for (int i = 0; i < K; i++) {
            s += msg.get(LE_I64, (long) i * TLEN); // field 0 starts at the tuple's byte 0
        }
        return s;
    }

    /**
     * Ceiling: bit-ops on the backing byte[] — zero FFM. Infeasible to wire (data is segments);
     * bounds the prize.
     */
    @Benchmark
    public long c_byteArray() {
        long s = 0;
        for (int i = 0; i < K; i++) {
            s += readLe(backing, i * TLEN);
        }
        return s;
    }

    private static long readLe(byte[] b, int o) {
        return (b[o] & 0xFFL)
                | (b[o + 1] & 0xFFL) << 8
                | (b[o + 2] & 0xFFL) << 16
                | (b[o + 3] & 0xFFL) << 24
                | (b[o + 4] & 0xFFL) << 32
                | (b[o + 5] & 0xFFL) << 40
                | (b[o + 6] & 0xFFL) << 48
                | (b[o + 7] & 0xFFL) << 56;
    }
}
