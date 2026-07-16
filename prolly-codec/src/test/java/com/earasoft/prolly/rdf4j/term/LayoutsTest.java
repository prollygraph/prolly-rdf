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
package com.earasoft.prolly.rdf4j.term;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class LayoutsTest {

    @Test
    void be64_readsBigEndianBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            // Write 8 bytes: 01 02 03 04 05 06 07 08
            for (int i = 0; i < 8; i++) {
                seg.set(Layouts.BYTE, i, (byte) (i + 1));
            }
            // BE interpretation: 0x0102030405060708
            assertEquals(0x0102030405060708L, seg.get(Layouts.BE64_U, 0));
        }
    }

    @Test
    void be64_le64_differForAsymmetricBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BYTE, 0, (byte) 0x01);
            for (int i = 1; i < 8; i++) seg.set(Layouts.BYTE, i, (byte) 0);
            // BE: bytes 01 00 00 00 00 00 00 00 → 0x0100000000000000L
            // LE: same bytes → 0x0000000000000001L = 1L
            assertEquals(0x0100000000000000L, seg.get(Layouts.BE64_U, 0));
            assertEquals(1L, seg.get(Layouts.LE64_U, 0));
        }
    }

    @Test
    void be64_atUnalignedOffset_doesNotThrow() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(16);
            for (int i = 0; i < 16; i++) seg.set(Layouts.BYTE, i, (byte) 0);
            // Offset 3 is NOT 8-byte aligned. Default JAVA_LONG would throw here.
            seg.set(Layouts.BYTE, 3, (byte) 0xff);
            assertDoesNotThrow(() -> seg.get(Layouts.BE64_U, 3));
            // 0xff is high byte of the 8-byte read starting at offset 3.
            assertEquals(0xff00000000000000L, seg.get(Layouts.BE64_U, 3));
        }
    }

    @Test
    void be32_correctValue() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(4);
            seg.set(Layouts.BYTE, 0, (byte) 0xde);
            seg.set(Layouts.BYTE, 1, (byte) 0xad);
            seg.set(Layouts.BYTE, 2, (byte) 0xbe);
            seg.set(Layouts.BYTE, 3, (byte) 0xef);
            assertEquals(0xdeadbeef, seg.get(Layouts.BE32_U, 0));
        }
    }

    @Test
    void be16_correctValue() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(2);
            seg.set(Layouts.BYTE, 0, (byte) 0x12);
            seg.set(Layouts.BYTE, 1, (byte) 0x34);
            assertEquals((short) 0x1234, seg.get(Layouts.BE16_U, 0));
        }
    }

    @Test
    void be_f64_roundTripPositive() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, 3.141592653589793);
            assertEquals(3.141592653589793, seg.get(Layouts.BE_F64_U, 0));
        }
    }

    @Test
    void be_f64_negative_topByteHasSignBit() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, -1.0);
            // IEEE-754 -1.0 = 0xBFF0000000000000 — top byte 0xBF (has sign bit set)
            assertEquals((byte) 0xBF, seg.get(Layouts.BYTE, 0));
        }
    }

    @Test
    void be_f64_zero_isAllZeroes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, 0.0);
            for (int i = 0; i < 8; i++) {
                assertEquals((byte) 0, seg.get(Layouts.BYTE, i));
            }
        }
    }

    @Test
    void be_f64_negativeZero_distinctFromPositiveZero() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, -0.0);
            // -0.0 IEEE-754 = 0x8000000000000000
            assertEquals((byte) 0x80, seg.get(Layouts.BYTE, 0));
        }
    }

    @Test
    void be_f64_NaN_topBitsMatchNaNPattern() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, Double.NaN);
            // NaN's top byte begins with 0x7F or 0xFF depending on encoding
            byte top = seg.get(Layouts.BYTE, 0);
            assertTrue(
                    top == (byte) 0x7F || top == (byte) 0xFF,
                    "top byte: " + Integer.toHexString(top & 0xFF));
            // Reading back should give NaN
            assertTrue(Double.isNaN(seg.get(Layouts.BE_F64_U, 0)));
        }
    }

    @Test
    void be_f64_positiveInfinity() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, Double.POSITIVE_INFINITY);
            // +∞ = 0x7FF0000000000000 — top byte 0x7F
            assertEquals((byte) 0x7F, seg.get(Layouts.BYTE, 0));
            assertEquals((byte) 0xF0, seg.get(Layouts.BYTE, 1));
        }
    }

    @Test
    void be_f64_negativeInfinity() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE_F64_U, 0, Double.NEGATIVE_INFINITY);
            // -∞ = 0xFFF0000000000000
            assertEquals((byte) 0xFF, seg.get(Layouts.BYTE, 0));
            assertEquals((byte) 0xF0, seg.get(Layouts.BYTE, 1));
        }
    }

    @Test
    void be_f32_roundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(4);
            seg.set(Layouts.BE_F32_U, 0, 1.5f);
            assertEquals(1.5f, seg.get(Layouts.BE_F32_U, 0));
        }
    }

    @Test
    void be64_extremeValues_roundTrip() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            for (long v : new long[] {Long.MIN_VALUE, Long.MAX_VALUE, 0L, -1L, 1L, 42L}) {
                seg.set(Layouts.BE64_U, 0, v);
                assertEquals(v, seg.get(Layouts.BE64_U, 0), "round-trip failed for " + v);
            }
        }
    }

    @Test
    void be64_minValue_topByteIs0x80() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(8);
            seg.set(Layouts.BE64_U, 0, Long.MIN_VALUE);
            assertEquals((byte) 0x80, seg.get(Layouts.BYTE, 0));
            for (int i = 1; i < 8; i++) {
                assertEquals((byte) 0, seg.get(Layouts.BYTE, i));
            }
        }
    }

    @Test
    void be64_writesAtMultipleOffsets_doNotCorrupt() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(24);
            seg.set(Layouts.BE64_U, 0, 0x0102030405060708L);
            seg.set(Layouts.BE64_U, 8, 0x1112131415161718L);
            seg.set(Layouts.BE64_U, 16, 0x2122232425262728L);
            assertEquals(0x0102030405060708L, seg.get(Layouts.BE64_U, 0));
            assertEquals(0x1112131415161718L, seg.get(Layouts.BE64_U, 8));
            assertEquals(0x2122232425262728L, seg.get(Layouts.BE64_U, 16));
        }
    }
}
