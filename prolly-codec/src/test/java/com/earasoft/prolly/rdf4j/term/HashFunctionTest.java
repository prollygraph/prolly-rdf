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
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HashFunctionTest {

    private final HashFunction hf = HashFunctions.defaultHash();

    @Test
    void distinct_strings_distinct_hashes() {
        assertNotEquals(hf.hash("foo".getBytes()), hf.hash("bar".getBytes()));
    }

    @Test
    void same_input_is_deterministic_acrossManyCalls() {
        long ref = hf.hash("hello".getBytes());
        for (int i = 0; i < 1000; i++) {
            assertEquals(ref, hf.hash("hello".getBytes()));
        }
    }

    @Test
    void empty_input_doesNotThrow() {
        assertDoesNotThrow(() -> hf.hash(new byte[0]));
    }

    @Test
    void empty_input_isStable() {
        long first = hf.hash(new byte[0]);
        long second = hf.hash(new byte[0]);
        assertEquals(first, second);
    }

    @Test
    void singleByte_inputs_distinct() {
        Set<Long> seen = new HashSet<>();
        for (int b = 0; b < 256; b++) {
            long h = hf.hash(new byte[] {(byte) b});
            assertTrue(seen.add(h), "collision on single-byte input " + b);
        }
    }

    @Test
    void array_and_segment_agree_forSameBytes() {
        byte[] data = "the quick brown fox jumps over the lazy dog".getBytes();
        long viaArray = hf.hash(data);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment seg = a.allocate(data.length);
            for (int i = 0; i < data.length; i++) seg.set(Layouts.BYTE, i, data[i]);
            long viaSeg = hf.hash(seg);
            assertEquals(viaArray, viaSeg);
        }
    }

    @Test
    void slice_givesSameHash_asStandaloneArray() {
        byte[] data = "the quick brown fox".getBytes();
        long midSlice = hf.hash(data, 4, 5); // "quick"
        long fromQuick = hf.hash("quick".getBytes());
        assertEquals(midSlice, fromQuick);
    }

    @Test
    void differs_byOneByte() {
        // Single-bit input changes should yield different hashes (with overwhelming probability).
        long h1 = hf.hash("hello".getBytes());
        long h2 = hf.hash("hellp".getBytes());
        assertNotEquals(h1, h2);
    }

    @Test
    void name_isStable() {
        assertEquals(hf.name(), HashFunctions.defaultHash().name());
        assertNotNull(hf.name());
        assertFalse(hf.name().isEmpty());
    }

    @Test
    void unicode_inputs_distinct() {
        long enHash = hf.hash("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long jaHash = hf.hash("こんにちは".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long heHash = hf.hash("שלום".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNotEquals(enHash, jaHash);
        assertNotEquals(enHash, heHash);
        assertNotEquals(jaHash, heHash);
    }

    @Test
    void emoji_input_isStable() {
        byte[] emoji = "🎉".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(hf.hash(emoji), hf.hash(emoji));
    }

    @Test
    void long_input_doesNotOverflow() {
        byte[] longData = new byte[10_000];
        for (int i = 0; i < longData.length; i++) longData[i] = (byte) i;
        assertDoesNotThrow(() -> hf.hash(longData));
    }

    /** Sanity: no obvious clustering for random inputs. */
    @Test
    void distribution_smokeTest_randomInputs() {
        SplittableRandom r = new SplittableRandom(0xC0FFEEL);
        int[] buckets = new int[64];
        int N = 10_000;
        for (int i = 0; i < N; i++) {
            byte[] data = new byte[8 + r.nextInt(32)];
            for (int j = 0; j < data.length; j++) data[j] = (byte) r.nextInt(256);
            long h = hf.hash(data);
            buckets[(int) Long.remainderUnsigned(h, 64)]++;
        }
        int mean = N / 64; // ~156
        // Wide tolerance — primarily we want to assert nothing's grossly wrong.
        for (int i = 0; i < buckets.length; i++) {
            assertTrue(
                    buckets[i] > mean / 4 && buckets[i] < mean * 4,
                    "bucket " + i + " count=" + buckets[i] + " is grossly skewed");
        }
    }

    @Test
    void high_bit_can_be_set_or_clear_in_output() {
        // Hash output is full 64 bits — the high bit (reserved for TermID extension)
        // is set roughly half the time across many random inputs.
        SplittableRandom r = new SplittableRandom(42);
        int highBitSetCount = 0;
        int N = 1000;
        for (int i = 0; i < N; i++) {
            long h = hf.hash(("input-" + r.nextLong()).getBytes());
            if ((h & 0x8000_0000_0000_0000L) != 0) highBitSetCount++;
        }
        // Expect ~500 ± wide tolerance
        assertTrue(
                highBitSetCount > 300 && highBitSetCount < 700,
                "high bit set " + highBitSetCount + "/" + N);
    }
}
