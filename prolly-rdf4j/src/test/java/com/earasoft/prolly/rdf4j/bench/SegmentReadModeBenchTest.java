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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Tuple;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

/**
 * The <b>correctness companion</b> to {@link SegmentReadModeBench}. That microbench times three
 * ways of reading a field-0 Int64 — {@code a_freshSlice} ({@code asSlice} + {@code
 * Tuple.getField}), {@code b_stableMsg} ({@code msg.get}), {@code c_byteArray} (hand bit-ops) — to
 * settle the {@code core-read-in-place-segments} lever. Its A→B→C comparison is only meaningful if
 * the three arms <b>decode the same value</b>; the bench silently assumes that. A timing comparison
 * between two mechanisms that compute <i>different</i> results is meaningless
 * (measure-the-real-thing: the instrument must be valid), so this test makes the assumption an
 * asserted invariant.
 *
 * <ul>
 *   <li>{@link #benchArmsComputeIdenticalSums} runs the bench's <i>actual</i> arm methods over its
 *       {@code @Setup} state and asserts all three return the same sum — the instrument is valid.
 *   <li>{@link #readModesAgreeOnEdgeValues} reconstructs the three mechanisms over the 64-bit
 *       values the fixed {@code @Setup} data ({@code 0x0102030405060708L + i}) never reaches —
 *       {@code Long.MIN/MAX}, {@code -1}, and a single byte set in each lane — and asserts they
 *       still agree, so the bench's conclusion would hold across the whole value range, not just
 *       its seed.
 * </ul>
 */
class SegmentReadModeBenchTest {

    private static final ValueLayout.OfLong LE_I64 =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort LE_U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int TLEN =
            12; // mirrors SegmentReadModeBench: 8 value + u16 offset + u16 count

    /** The bench's three arms, over its own setup state, must compute the identical sum. */
    @Test
    void benchArmsComputeIdenticalSums() {
        SegmentReadModeBench bench = new SegmentReadModeBench();
        bench.setup();
        long viaFreshSlice = bench.a_freshSlice();
        long viaStableMsg = bench.b_stableMsg();
        long viaByteArray = bench.c_byteArray();
        assertEquals(
                viaFreshSlice,
                viaStableMsg,
                "asSlice+getField and msg.get must read the same field-0 values (the bench compares them)");
        assertEquals(
                viaStableMsg,
                viaByteArray,
                "msg.get and byte[] bit-ops must agree (else B->C measures unequal work)");
    }

    /** The same three mechanisms agree at the value extremes the fixed setup data never visits. */
    @Test
    void readModesAgreeOnEdgeValues() {
        long[] values = {
            0L,
            1L,
            -1L,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            0x0102030405060708L,
            0xFFL, // byte 0
            0xFF00L, // byte 1
            0xFF0000L, // byte 2
            0xFF000000L, // byte 3
            0xFF00000000L, // byte 4
            0xFF0000000000L, // byte 5
            0xFF000000000000L, // byte 6
            0xFF00000000000000L // byte 7 (sign byte)
        };
        for (long v : values) {
            byte[] buf = new byte[TLEN];
            MemorySegment seg = MemorySegment.ofArray(buf);
            seg.set(LE_I64, 0, v); // field 0 value
            seg.set(LE_U16, 8, (short) 8); // offset table: field-0 end = 8
            seg.set(LE_U16, 10, (short) 1); // count = 1

            long viaFreshSlice = readLe(new Tuple(seg.asSlice(0, TLEN)).getField(0), 0);
            long viaStableMsg = seg.get(LE_I64, 0);
            long viaByteArray = readLe(buf, 0);

            assertEquals(v, viaStableMsg, "msg.get must round-trip 0x" + Long.toHexString(v));
            assertEquals(
                    viaStableMsg,
                    viaFreshSlice,
                    "asSlice+getField must agree at 0x" + Long.toHexString(v));
            assertEquals(
                    viaStableMsg,
                    viaByteArray,
                    "byte[] bit-ops must agree at 0x" + Long.toHexString(v));
        }
    }

    /**
     * Little-endian long from 8 bytes — the same decode {@code SegmentReadModeBench.readLe} uses.
     */
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
