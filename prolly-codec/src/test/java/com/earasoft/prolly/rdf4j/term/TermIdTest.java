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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

/**
 * I-5 codec fidelity for {@link TermId}, the 64-bit RDF-term dictionary id
 * (plans/core-engine-test-strategy.md Step 11 — prolly-codec isolation suite).
 *
 * <p>Pins the flag/payload algebra and the <b>unsigned</b> compare contract. The unsigned contract
 * is load-bearing and subtle: a {@code TermId} is a hash (or extension slot) whose top bit is a
 * <em>flag</em>, not a sign. Treating it as signed flips the natural/extension ordering — see
 * {@link com.earasoft.prolly.rdf4j.index.SpocKeyTest} for the index-level consequence (the SPOC
 * column comparator is signed, so it disagrees with this class — a documented mismatch, pinned
 * there).
 */
class TermIdTest {

    // ---- raw round-trip ----

    @Property(tries = 2000)
    void ofRawRoundTrips(@ForAll long raw) {
        assertEquals(raw, TermId.of(raw).value(), "of(raw).value() must be identity");
    }

    // ---- natural form: top bit always cleared ----

    @Property(tries = 2000)
    void ofNaturalAlwaysClearsTopBit(@ForAll long hash) {
        TermId id = TermId.ofNatural(hash);
        assertFalse(id.isExtension(), "natural id must never have the extension flag set");
        assertEquals(hash & TermId.NATURAL_MASK, id.value(), "natural id keeps the low 63 bits");
        assertEquals(hash & TermId.NATURAL_MASK, id.naturalBits());
    }

    // ---- extension form: top bit always set, slot in low 63 bits ----

    @Property(tries = 2000)
    void ofExtensionSlotSetsTopBitAndPreservesSlot(@ForAll long slot) {
        Assume.that((slot & TermId.EXTENSION_FLAG) == 0); // valid slot: top bit clear
        TermId id = TermId.ofExtensionSlot(slot);
        assertTrue(id.isExtension(), "extension id must have the flag set");
        assertEquals(slot, id.naturalBits(), "the low 63 bits recover the slot index");
    }

    @Property(tries = 2000)
    void ofExtensionSlotRejectsOversizedSlot(@ForAll long slot) {
        Assume.that((slot & TermId.EXTENSION_FLAG) != 0); // top bit set → invalid
        assertThrows(
                IllegalArgumentException.class,
                () -> TermId.ofExtensionSlot(slot),
                "a slot that does not fit in 63 bits must be rejected, not silently truncated");
    }

    // ---- compareTo is UNSIGNED (the contract that the class javadoc names) ----

    @Property(tries = 3000)
    void compareToIsUnsigned(@ForAll long x, @ForAll long y) {
        assertEquals(
                Integer.signum(Long.compareUnsigned(x, y)),
                Integer.signum(TermId.of(x).compareTo(TermId.of(y))),
                "TermId.compareTo must equal Long.compareUnsigned (top bit is a flag, not a sign)");
    }

    @Property(tries = 2000)
    void naturalAlwaysSortsBeforeExtension(@ForAll long natHash, @ForAll long extSlot) {
        Assume.that((extSlot & TermId.EXTENSION_FLAG) == 0);
        TermId nat = TermId.ofNatural(natHash);
        TermId ext = TermId.ofExtensionSlot(extSlot);
        assertTrue(
                nat.compareTo(ext) < 0,
                "every natural id sorts before every extension id under TermId.compareTo (unsigned)");
    }

    // ---- pinned constants + equality ----

    @Test
    void flagAndMaskAreComplementary() {
        assertEquals(0x8000_0000_0000_0000L, TermId.EXTENSION_FLAG);
        assertEquals(0x7FFF_FFFF_FFFF_FFFFL, TermId.NATURAL_MASK);
        assertEquals(
                -1L,
                TermId.EXTENSION_FLAG ^ TermId.NATURAL_MASK,
                "flag and mask must partition all 64 bits with no overlap");
        assertEquals(0L, TermId.ZERO.value());
        assertFalse(TermId.ZERO.isExtension());
    }

    @Property(tries = 1000)
    void recordEqualityIsByValue(@ForAll long raw) {
        assertEquals(TermId.of(raw), TermId.of(raw));
        assertEquals(TermId.of(raw).hashCode(), TermId.of(raw).hashCode());
    }
}
