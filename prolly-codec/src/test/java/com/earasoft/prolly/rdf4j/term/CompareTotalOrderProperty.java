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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Total-order laws for {@link Compare#compareUnsigned} — the comparator every index scan, run
 * merge, and dictionary probe sits on (hardening round 1: the seam had a single example test; a
 * comparator that violates antisymmetry or transitivity corrupts sorted structures SILENTLY, which
 * is exactly what a property must forbid). Generators bias toward shared prefixes and
 * prefix-of-the-other shapes, the branchy part of the mismatch-based implementation.
 */
class CompareTotalOrderProperty {

    @Provide
    Arbitrary<byte[]> keyBytes() {
        // Short arrays with few distinct values maximize prefix collisions and
        // exact-prefix pairs — the mismatch()/size-tiebreak branches.
        return Arbitraries.bytes().between((byte) 0, (byte) 3).array(byte[].class).ofMaxSize(6);
    }

    private static int sign(int x) {
        return Integer.compare(x, 0);
    }

    private static int cmp(byte[] a, byte[] b) {
        return Compare.compareUnsigned(MemorySegment.ofArray(a), MemorySegment.ofArray(b));
    }

    @Property(tries = 2000)
    void antisymmetric(@ForAll("keyBytes") byte[] a, @ForAll("keyBytes") byte[] b) {
        assertEquals(sign(cmp(a, b)), -sign(cmp(b, a)));
    }

    @Property(tries = 2000)
    void zeroExactlyOnByteEquality(@ForAll("keyBytes") byte[] a, @ForAll("keyBytes") byte[] b) {
        assertEquals(Arrays.equals(a, b), cmp(a, b) == 0);
    }

    @Property(tries = 2000)
    void transitive(
            @ForAll("keyBytes") byte[] a,
            @ForAll("keyBytes") byte[] b,
            @ForAll("keyBytes") byte[] c) {
        if (cmp(a, b) <= 0 && cmp(b, c) <= 0) {
            assertTrue(cmp(a, c) <= 0, "a<=b<=c must imply a<=c");
        }
    }

    @Property(tries = 1000)
    void agreesWithUnsignedLexicographicReference(
            @ForAll("keyBytes") byte[] a, @ForAll("keyBytes") byte[] b) {
        assertEquals(sign(Arrays.compareUnsigned(a, b)), sign(cmp(a, b)));
    }
}
