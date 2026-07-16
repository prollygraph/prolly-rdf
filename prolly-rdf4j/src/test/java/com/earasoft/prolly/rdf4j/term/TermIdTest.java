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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class TermIdTest {

    // ---------------------------------------------------------------------
    // construction & getters
    // ---------------------------------------------------------------------

    @Test
    void of_storesRawValueExactly() {
        for (long raw :
                new long[] {
                    0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 42L, 0xCAFEBABE_DEADBEEFL
                }) {
            assertEquals(raw, TermId.of(raw).value());
        }
    }

    @Test
    void ofNatural_alwaysClearsExtensionFlag() {
        for (long h :
                new long[] {0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0x8000_0000_0000_0001L}) {
            TermId t = TermId.ofNatural(h);
            assertFalse(
                    t.isExtension(),
                    "ofNatural produced extension flag for input " + Long.toHexString(h));
            assertEquals(h & TermId.NATURAL_MASK, t.value());
        }
    }

    @Test
    void ofExtensionSlot_setsExtensionFlag() {
        for (long slot : new long[] {0L, 1L, 42L, TermId.NATURAL_MASK, 0x4000_0000_0000_0000L}) {
            TermId t = TermId.ofExtensionSlot(slot);
            assertTrue(t.isExtension(), "slot " + slot + " did not set extension flag");
            assertEquals(slot, t.naturalBits(), "slot bits not preserved");
        }
    }

    @Test
    void ofExtensionSlot_rejectsOversizedSlot() {
        assertThrows(IllegalArgumentException.class, () -> TermId.ofExtensionSlot(Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> TermId.ofExtensionSlot(-1L));
        assertThrows(
                IllegalArgumentException.class,
                () -> TermId.ofExtensionSlot(TermId.EXTENSION_FLAG | 1L));
    }

    // ---------------------------------------------------------------------
    // extension flag semantics
    // ---------------------------------------------------------------------

    @Test
    void isExtension_truthTable() {
        assertFalse(TermId.of(0L).isExtension());
        assertFalse(TermId.of(1L).isExtension());
        assertFalse(TermId.of(Long.MAX_VALUE).isExtension());
        assertTrue(TermId.of(Long.MIN_VALUE).isExtension());
        assertTrue(TermId.of(-1L).isExtension());
        assertTrue(TermId.of(TermId.EXTENSION_FLAG).isExtension());
        assertTrue(TermId.of(TermId.EXTENSION_FLAG | 42L).isExtension());
    }

    @Test
    void naturalBits_stripsExtensionFlag() {
        assertEquals(0L, TermId.of(Long.MIN_VALUE).naturalBits());
        assertEquals(Long.MAX_VALUE, TermId.of(-1L).naturalBits());
        assertEquals(42L, TermId.of(TermId.EXTENSION_FLAG | 42L).naturalBits());
        // Natural values pass through unchanged
        assertEquals(42L, TermId.of(42L).naturalBits());
    }

    @Test
    void naturalMask_isCorrectBitPattern() {
        // NATURAL_MASK should be 63 ones
        assertEquals(0x7FFF_FFFF_FFFF_FFFFL, TermId.NATURAL_MASK);
        assertEquals(63, Long.bitCount(TermId.NATURAL_MASK));
    }

    @Test
    void extensionFlag_isCorrectBitPattern() {
        // EXTENSION_FLAG should be exactly the top bit
        assertEquals(0x8000_0000_0000_0000L, TermId.EXTENSION_FLAG);
        assertEquals(1, Long.bitCount(TermId.EXTENSION_FLAG));
        assertEquals(63, Long.numberOfTrailingZeros(TermId.EXTENSION_FLAG));
    }

    @Test
    void extensionFlag_and_naturalMask_areDisjointAndComplete() {
        assertEquals(0L, TermId.EXTENSION_FLAG & TermId.NATURAL_MASK);
        assertEquals(-1L, TermId.EXTENSION_FLAG | TermId.NATURAL_MASK);
    }

    // ---------------------------------------------------------------------
    // ordering (unsigned compare)
    // ---------------------------------------------------------------------

    @Test
    void compareTo_natural_byValue() {
        assertTrue(TermId.of(0L).compareTo(TermId.of(1L)) < 0);
        assertTrue(TermId.of(1L).compareTo(TermId.of(0L)) > 0);
        assertEquals(0, TermId.of(42L).compareTo(TermId.of(42L)));
    }

    @Test
    void compareTo_unsigned_naturalLessThanExtension() {
        // All natural ids (top bit 0) < all extension ids (top bit 1) under unsigned compare.
        TermId maxNatural = TermId.of(Long.MAX_VALUE);
        TermId minExt = TermId.of(Long.MIN_VALUE);
        assertTrue(
                maxNatural.compareTo(minExt) < 0,
                "max natural (0x"
                        + maxNatural.toHex()
                        + ") should be less than min extension (0x"
                        + minExt.toHex()
                        + ")");
    }

    @Test
    void compareTo_unsigned_byteCorrect() {
        // Signed compare would give: -1L < 0L. Unsigned: -1L > 0L (it's 0xFFFFFFFFFFFFFFFF).
        assertTrue(TermId.of(0L).compareTo(TermId.of(-1L)) < 0);
    }

    @Test
    void compareTo_antisymmetric_andReflexive() {
        TermId[] samples = {
            TermId.of(0L),
            TermId.of(1L),
            TermId.of(Long.MAX_VALUE),
            TermId.of(Long.MIN_VALUE),
            TermId.of(-1L),
            TermId.ofExtensionSlot(0L),
            TermId.ofExtensionSlot(5L),
            TermId.ofNatural(0x1234567890ABCDEFL),
        };
        for (TermId a : samples) {
            for (TermId b : samples) {
                int ab = a.compareTo(b);
                int ba = b.compareTo(a);
                assertEquals(Integer.signum(ab), -Integer.signum(ba), a + " vs " + b);
            }
            assertEquals(0, a.compareTo(a));
        }
    }

    @Test
    void compareTo_transitive_overSort() {
        SplittableRandom r = new SplittableRandom(0xBAD_F00DL);
        List<TermId> list = new ArrayList<>();
        for (int i = 0; i < 500; i++) list.add(TermId.of(r.nextLong()));
        Collections.sort(list);
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).compareTo(list.get(i)) <= 0, "sort not monotonic at i=" + i);
        }
    }

    @Test
    void treeSet_partitionsNaturalsThenExtensions() {
        TreeSet<TermId> set = new TreeSet<>();
        set.add(TermId.ofExtensionSlot(0L));
        set.add(TermId.of(1L));
        set.add(TermId.of(0L));
        set.add(TermId.ofExtensionSlot(Long.MAX_VALUE >>> 1));
        set.add(TermId.of(Long.MAX_VALUE));

        TermId[] expected = {
            TermId.of(0L),
            TermId.of(1L),
            TermId.of(Long.MAX_VALUE),
            TermId.ofExtensionSlot(0L),
            TermId.ofExtensionSlot(Long.MAX_VALUE >>> 1),
        };
        assertArrayEquals(expected, set.toArray(new TermId[0]));
    }

    // ---------------------------------------------------------------------
    // equality / hash / record semantics
    // ---------------------------------------------------------------------

    @Test
    void equals_reflexive() {
        TermId t = TermId.of(0xDEADBEEFL);
        assertEquals(t, t);
    }

    @Test
    void equals_symmetric_and_byValue() {
        TermId a = TermId.of(42L);
        TermId b = TermId.of(42L);
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_distinguishesNaturalFromExtensionWithSamePayload() {
        // ofNatural(0) and ofExtensionSlot(0) are distinct ids (top bit differs).
        TermId n = TermId.ofNatural(0L);
        TermId e = TermId.ofExtensionSlot(0L);
        assertNotEquals(n, e);
        assertNotEquals(n.value(), e.value());
    }

    @Test
    void equals_null_andOtherType_areNotEqual() {
        TermId t = TermId.of(0L);
        assertNotEquals(t, null);
        assertNotEquals(t, "TermId(0)");
        assertNotEquals(t, 0L);
    }

    @Test
    void hashCode_isStableAcrossCalls() {
        TermId t = TermId.of(0xC0FFEEL);
        int h0 = t.hashCode();
        for (int i = 0; i < 100; i++) {
            assertEquals(h0, t.hashCode());
        }
    }

    // ---------------------------------------------------------------------
    // formatting
    // ---------------------------------------------------------------------

    @Test
    void toHex_alwaysSixteenDigits() {
        assertEquals("0000000000000000", TermId.of(0L).toHex());
        assertEquals("0000000000000001", TermId.of(1L).toHex());
        assertEquals("ffffffffffffffff", TermId.of(-1L).toHex());
        assertEquals("8000000000000000", TermId.of(Long.MIN_VALUE).toHex());
        assertEquals("7fffffffffffffff", TermId.of(Long.MAX_VALUE).toHex());
    }

    @Test
    void toString_marksExtension() {
        assertTrue(TermId.of(Long.MIN_VALUE).toString().contains("ext"));
        assertFalse(TermId.of(0L).toString().contains("ext"));
    }

    // ---------------------------------------------------------------------
    // round-trip integrity
    // ---------------------------------------------------------------------

    @Test
    void rawRoundTrip_preservesAllBitPatterns() {
        SplittableRandom r = new SplittableRandom(0xCAFEL);
        for (int i = 0; i < 10_000; i++) {
            long raw = r.nextLong();
            assertEquals(raw, TermId.of(raw).value());
        }
    }

    @Test
    void naturalThenExtensionWithSameSlot_areAdjacentSentinels() {
        // ofNatural(N) and ofExtensionSlot(N) differ only in the top bit.
        long slot = 0x1234_5678_9ABC_DEF0L;
        TermId nat = TermId.ofNatural(slot);
        TermId ext = TermId.ofExtensionSlot(slot);
        assertEquals(slot & TermId.NATURAL_MASK, nat.value());
        assertEquals(slot | TermId.EXTENSION_FLAG, ext.value());
        // Differ exactly in the top bit
        assertEquals(TermId.EXTENSION_FLAG, ext.value() ^ nat.value());
    }

    @Test
    void zero_constant_isInstanceOfZero() {
        assertEquals(0L, TermId.ZERO.value());
        assertSame(TermId.ZERO, TermId.ZERO);
        assertEquals(TermId.ZERO, TermId.of(0L));
    }

    // ---------------------------------------------------------------------
    // collection use
    // ---------------------------------------------------------------------

    @Test
    void usableAsHashMapKey() {
        java.util.HashMap<TermId, String> map = new java.util.HashMap<>();
        map.put(TermId.of(1L), "one");
        map.put(TermId.of(2L), "two");
        map.put(TermId.ofExtensionSlot(0L), "ext-0");
        assertEquals("one", map.get(TermId.of(1L)));
        assertEquals("two", map.get(TermId.of(2L)));
        assertEquals("ext-0", map.get(TermId.ofExtensionSlot(0L)));
        assertNull(map.get(TermId.of(3L)));
    }

    @Test
    void sortedDistinct_overRandomCorpus() {
        SplittableRandom r = new SplittableRandom(7L);
        TermId[] ids = new TermId[1024];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = TermId.of(r.nextLong());
        }
        TermId[] copy = ids.clone();
        Arrays.sort(copy);
        // Validate sort by pairwise compareTo
        for (int i = 1; i < copy.length; i++) {
            assertTrue(copy[i - 1].compareTo(copy[i]) <= 0);
        }
    }
}
