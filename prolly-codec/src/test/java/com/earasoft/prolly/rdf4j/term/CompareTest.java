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
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class CompareTest {

    private static MemorySegment seg(Arena a, int... unsignedBytes) {
        MemorySegment s = a.allocate(unsignedBytes.length);
        for (int i = 0; i < unsignedBytes.length; i++) {
            s.set(Layouts.BYTE, i, (byte) unsignedBytes[i]);
        }
        return s;
    }

    @Test
    void equalSegments_returnZero() {
        try (Arena a = Arena.ofConfined()) {
            assertEquals(0, Compare.compareUnsigned(seg(a, 1, 2, 3), seg(a, 1, 2, 3)));
        }
    }

    @Test
    void bothEmpty_returnZero() {
        try (Arena a = Arena.ofConfined()) {
            assertEquals(0, Compare.compareUnsigned(seg(a), seg(a)));
        }
    }

    @Test
    void emptyVsNonEmpty_emptyIsLess() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a), seg(a, 1)) < 0);
            assertTrue(Compare.compareUnsigned(seg(a, 1), seg(a)) > 0);
        }
    }

    @Test
    void emptyVsEmptyOfLongerArena_stillZero() {
        // Sanity: empty segments compare equal regardless of underlying arena layout
        try (Arena a = Arena.ofConfined()) {
            assertEquals(0, Compare.compareUnsigned(seg(a), seg(a)));
        }
    }

    @Test
    void aStrictPrefixOfB_aIsLess() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 1, 2), seg(a, 1, 2, 3)) < 0);
        }
    }

    @Test
    void bStrictPrefixOfA_aIsGreater() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 1, 2, 3), seg(a, 1, 2)) > 0);
        }
    }

    @Test
    void differAtFirstByte() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 1, 2, 3), seg(a, 2, 2, 3)) < 0);
            assertTrue(Compare.compareUnsigned(seg(a, 2, 2, 3), seg(a, 1, 2, 3)) > 0);
        }
    }

    @Test
    void differAtLastByte() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 1, 2, 3), seg(a, 1, 2, 4)) < 0);
        }
    }

    @Test
    void differAtMiddleByte() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 1, 2, 3, 4), seg(a, 1, 2, 5, 4)) < 0);
        }
    }

    /** The bug-prone case: bytes whose signed comparison would flip the result. */
    @Test
    void unsignedOrdering_byteWithHighBit_isLarger() {
        try (Arena a = Arena.ofConfined()) {
            // 0x7F (signed: +127) vs 0x80 (signed: -128). Unsigned: 0x80 > 0x7F.
            // Arrays.compare on these bytes would give the wrong answer (signed).
            assertTrue(Compare.compareUnsigned(seg(a, 0x7F), seg(a, 0x80)) < 0);
            assertTrue(Compare.compareUnsigned(seg(a, 0x80), seg(a, 0x7F)) > 0);
        }
    }

    @Test
    void unsignedOrdering_FE_lessThan_FF() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 0xFE), seg(a, 0xFF)) < 0);
        }
    }

    @Test
    void unsignedOrdering_00_lessThan_FF() {
        try (Arena a = Arena.ofConfined()) {
            assertTrue(Compare.compareUnsigned(seg(a, 0x00), seg(a, 0xFF)) < 0);
        }
    }

    @Test
    void zeroOnlyVsNonzero() {
        try (Arena a = Arena.ofConfined()) {
            // 00 00 00 vs 00 00 01 → 00 00 00 is less
            assertTrue(Compare.compareUnsigned(seg(a, 0, 0, 0), seg(a, 0, 0, 1)) < 0);
        }
    }

    @Test
    void antisymmetric_property() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment[][] pairs = {
                {seg(a, 1, 2, 3), seg(a, 1, 2, 4)},
                {seg(a, 0xff), seg(a, 0x00)},
                {seg(a, 1, 2, 3), seg(a, 1, 2, 3, 4)},
                {seg(a, 0x7F, 0xff), seg(a, 0x80, 0x00)},
                {seg(a), seg(a, 0)},
            };
            for (var pair : pairs) {
                int ab = Compare.compareUnsigned(pair[0], pair[1]);
                int ba = Compare.compareUnsigned(pair[1], pair[0]);
                assertEquals(
                        Integer.signum(ab),
                        -Integer.signum(ba),
                        "antisymmetry failed: " + ab + " vs " + ba);
            }
        }
    }

    @Test
    void transitivity_property() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment x = seg(a, 1, 0);
            MemorySegment y = seg(a, 1, 1);
            MemorySegment z = seg(a, 1, 2);
            assertTrue(Compare.compareUnsigned(x, y) < 0);
            assertTrue(Compare.compareUnsigned(y, z) < 0);
            assertTrue(Compare.compareUnsigned(x, z) < 0, "transitivity failed");
        }
    }

    @Test
    void transitivity_withMixedLengths() {
        try (Arena a = Arena.ofConfined()) {
            // x = "01" prefix-less-than y = "01 00" prefix-less-than z = "01 01"
            MemorySegment x = seg(a, 1);
            MemorySegment y = seg(a, 1, 0);
            MemorySegment z = seg(a, 1, 1);
            assertTrue(Compare.compareUnsigned(x, y) < 0);
            assertTrue(Compare.compareUnsigned(y, z) < 0);
            assertTrue(Compare.compareUnsigned(x, z) < 0);
        }
    }

    @Test
    void reflexive_property() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = seg(a, 5, 10, 15);
            assertEquals(0, Compare.compareUnsigned(s, s));
        }
    }

    @Test
    void largeSegments_differAtEnd() {
        try (Arena a = Arena.ofConfined()) {
            int[] bytesA = new int[1024];
            int[] bytesB = new int[1024];
            for (int i = 0; i < 1024; i++) {
                bytesA[i] = bytesB[i] = i & 0xFF;
            }
            // Set the last byte to a fixed value so the +1 increment doesn't wrap.
            bytesA[1023] = 0x42;
            bytesB[1023] = 0x43;
            assertTrue(Compare.compareUnsigned(seg(a, bytesA), seg(a, bytesB)) < 0);
        }
    }

    @Test
    void largeSegments_differAtStart_shortCircuits() {
        try (Arena a = Arena.ofConfined()) {
            int[] bytesA = new int[4096];
            int[] bytesB = new int[4096];
            for (int i = 0; i < 4096; i++) {
                bytesA[i] = bytesB[i] = 0xAB;
            }
            bytesA[0] = 0x01;
            bytesB[0] = 0x02;
            assertTrue(Compare.compareUnsigned(seg(a, bytesA), seg(a, bytesB)) < 0);
        }
    }

    @Test
    void slicesOfSameSegment_compareByContents() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment whole = a.allocate(8);
            for (int i = 0; i < 8; i++) whole.set(Layouts.BYTE, i, (byte) i);
            MemorySegment left = whole.asSlice(0, 4); // [0,1,2,3]
            MemorySegment right = whole.asSlice(4, 4); // [4,5,6,7]
            assertTrue(Compare.compareUnsigned(left, right) < 0);
        }
    }

    /**
     * Stress with random inputs: sort a list using our comparator, verify the sort is monotonic via
     * pairwise compare.
     */
    @Test
    void fuzzSort_isMonotonic() {
        SplittableRandom r = new SplittableRandom(0xCAFEBABEL);
        try (Arena a = Arena.ofConfined()) {
            int N = 200;
            MemorySegment[] segs = new MemorySegment[N];
            for (int i = 0; i < N; i++) {
                int len = 1 + r.nextInt(32);
                int[] bytes = new int[len];
                for (int j = 0; j < len; j++) bytes[j] = r.nextInt(256);
                segs[i] = seg(a, bytes);
            }
            java.util.Arrays.sort(segs, Compare::compareUnsigned);
            for (int i = 1; i < N; i++) {
                assertTrue(
                        Compare.compareUnsigned(segs[i - 1], segs[i]) <= 0,
                        "sort not monotonic at index " + i);
            }
        }
    }
}
