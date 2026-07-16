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

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Golden-vector coverage for {@code Fnv1a64}, reached through the public {@link
 * HashFunctions#FNV1A_64} handle.
 *
 * <p>The hash function is <em>part of the on-disk format</em> — every term ID in a persisted
 * dictionary derives from it, and the manifest records its {@code name()}. {@code
 * HashFunctionsTest} pins the empty and zero-byte inputs and internal consistency, but never checks
 * the implementation against the <em>published FNV-1a-64 reference vectors</em>. Without that, an
 * implementation that is merely self-consistent (e.g. wrong prime, wrong offset basis, signed-byte
 * bug) would pass — and silently orphan every dictionary written by a correct build.
 *
 * <p>Vectors are the canonical FNV-1a 64-bit test values from the FNV reference (Fowler/Noll/Vo,
 * isthe.com/chongo/tech/comp/fnv).
 */
class Fnv1a64GoldenVectorTest {

    private static final HashFunction FNV = HashFunctions.FNV1A_64;

    private static long hash(String s) {
        return FNV.hash(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void empty_input_is_the_offset_basis() {
        assertEquals(
                0xcbf29ce484222325L,
                hash(""),
                "FNV-1a of the empty input is the unmodified 64-bit offset basis");
    }

    @Test
    void canonical_single_character_vectors() {
        // Published FNV-1a-64 reference values.
        assertEquals(0xaf63dc4c8601ec8cL, hash("a"), "FNV-1a-64(\"a\")");
        assertEquals(0xaf63df4c8601f1a5L, hash("b"), "FNV-1a-64(\"b\")");
        assertEquals(0xaf63de4c8601eff2L, hash("c"), "FNV-1a-64(\"c\")");
        assertEquals(0xaf63d94c8601e773L, hash("d"), "FNV-1a-64(\"d\")");
    }

    @Test
    void canonical_multi_byte_vectors() {
        assertEquals(0x85944171f73967e8L, hash("foobar"), "FNV-1a-64(\"foobar\")");
        assertEquals(0xdcb27518fed9d577L, hash("foo"), "FNV-1a-64(\"foo\")");
    }

    @Test
    void name_is_the_format_tied_identifier() {
        assertEquals(
                "fnv1a-64",
                FNV.name(),
                "name() is recorded in the manifest — drift breaks reader verification");
    }

    /**
     * Independent oracle: a from-scratch FNV-1a-64 written straight from the spec. Cross-checking
     * the production hash against it over many inputs catches any future drift (a mistuned prime, a
     * signed-byte regression) without relying on a hand-copied magic number.
     */
    @Test
    void matches_independent_spec_implementation_over_many_inputs() {
        Random rng = new Random(0xF7E1A64L);
        for (int trial = 0; trial < 5000; trial++) {
            byte[] data = new byte[rng.nextInt(64)];
            rng.nextBytes(data);
            assertEquals(
                    referenceFnv1a64(data),
                    FNV.hash(MemorySegment.ofArray(data)),
                    "production FNV-1a-64 must match the spec implementation byte-for-byte");
        }
    }

    @Test
    void high_byte_values_use_unsigned_byte_semantics() {
        // A signed-byte bug (missing & 0xff) would diverge for bytes >= 0x80.
        byte[] data = {(byte) 0x80, (byte) 0xff, (byte) 0xc3, 0x00, 0x7f};
        assertEquals(
                referenceFnv1a64(data),
                FNV.hash(MemorySegment.ofArray(data)),
                "bytes with the high bit set must be folded in as unsigned");
    }

    /** FNV-1a-64 exactly as specified by the FNV reference. */
    private static long referenceFnv1a64(byte[] data) {
        long h = 0xcbf29ce484222325L; // 64-bit offset basis
        for (byte b : data) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L; // 64-bit FNV prime
        }
        return h;
    }
}
