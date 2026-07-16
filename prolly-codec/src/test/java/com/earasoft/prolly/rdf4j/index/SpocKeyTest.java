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
package com.earasoft.prolly.rdf4j.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Tuple;
import com.earasoft.prolly.rdf4j.term.Layouts;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

/**
 * I-5 codec fidelity for {@link SpocKey}, the four-{@link TermId} index key shared by the SPOC /
 * POSC / OSPC / CSPO orderings (plans/core-engine-test-strategy.md Step 11 — prolly-codec isolation
 * suite).
 *
 * <p>Pins three things:
 *
 * <ol>
 *   <li><b>Round-trip</b>: {@code toTupleSegment → fromTuple} is identity, and the wire layout is
 *       exactly 42 bytes.
 *   <li><b>Sort order for natural ids</b>: the tree comparator ({@code SpocKey.DESCRIPTOR.compare})
 *       orders keys column-major, and for the common case (all ids natural, top bit clear) that
 *       order matches {@link TermId#compareTo}.
 *   <li><b>The signed/unsigned ordering mismatch</b> (a real, documented finding — see {@code
 *       newcomer-docs/foundations/the-termid-ordering-trap.md}): the column comparator is
 *       <em>signed</em> Int64, but {@code TermId} defines itself as <em>unsigned</em>. For
 *       extension ids (top bit set) the two orders are OPPOSITE. This test pins the actual index
 *       behavior so a future "fix" to either side is a conscious, reviewed change — not a silent
 *       tree-shape (and thus root-hash) drift.
 * </ol>
 */
class SpocKeyTest {

    private SpocKey roundTrip(SpocKey k) {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            MemorySegment seg = k.toTupleSegment(pool);
            return SpocKey.fromTuple(new Tuple(seg));
        }
    }

    /** Compare two keys the way the prolly tree does (signed Int64 per column). */
    private int indexCompare(SpocKey x, SpocKey y) {
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple tx = new Tuple(x.toTupleSegment(pool));
            Tuple ty = new Tuple(y.toTupleSegment(pool));
            return Integer.signum(SpocKey.DESCRIPTOR.compare(tx, ty));
        }
    }

    // Deterministic coverage for equals / round-trip / toString. The @Property variants
    // below exercise the same surface, but pitest does not reliably credit jqwik @Property
    // executions, so those mutants survive; fixed, distinct-per-column values pin them.

    @Test
    void roundTrip_recovers_each_column_individually() {
        // Distinct non-zero columns: dropping ANY of the four data writes in
        // toTupleSegment makes that column read back wrong, so each set() is pinned.
        SpocKey back =
                roundTrip(new SpocKey(TermId.of(11), TermId.of(22), TermId.of(33), TermId.of(44)));
        assertEquals(TermId.of(11), back.col0());
        assertEquals(TermId.of(22), back.col1());
        assertEquals(TermId.of(33), back.col2());
        assertEquals(TermId.of(44), back.col3());
    }

    @Test
    void equals_is_by_value_per_column() {
        SpocKey base = new SpocKey(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4));
        assertEquals(base, base); // reflexive
        assertEquals(base, new SpocKey(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4)));
        assertNotEquals(
                base, new SpocKey(TermId.of(9), TermId.of(2), TermId.of(3), TermId.of(4))); // col0
        assertNotEquals(
                base, new SpocKey(TermId.of(1), TermId.of(9), TermId.of(3), TermId.of(4))); // col1
        assertNotEquals(
                base, new SpocKey(TermId.of(1), TermId.of(2), TermId.of(9), TermId.of(4))); // col2
        assertNotEquals(
                base, new SpocKey(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(9))); // col3
        assertNotEquals(base, null);
        assertNotEquals(base, "not a SpocKey");
        // equals/hashCode contract: equal keys share a hash (does not pin the exact value —
        // the hashCode arithmetic mutants are equivalent, any deterministic hash satisfies this).
        assertEquals(
                base.hashCode(),
                new SpocKey(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4)).hashCode());
    }

    @Test
    void toString_is_non_empty_and_lists_each_column() {
        String s = new SpocKey(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4)).toString();
        assertFalse(s.isEmpty());
        assertTrue(s.contains(TermId.of(3).toHex()), "toString should include each column's value");
    }

    @Test
    void offset_table_yields_correct_per_column_field_ranges() {
        // fromTuple reads columns at FIXED offsets and ignores the offset table, so the
        // round-trip test above can't see the offset-table writes. The generic Tuple path
        // (getFieldSegment, used by the tree comparator) reads them — pin each here so the
        // four offset-table writes in toTupleSegment are covered by a deterministic test.
        long[] cols = {0x11L, 0x22L, 0x33L, 0x44L};
        try (HeapBufferPool pool = new HeapBufferPool()) {
            Tuple t =
                    new Tuple(
                            new SpocKey(
                                            TermId.of(cols[0]), TermId.of(cols[1]),
                                            TermId.of(cols[2]), TermId.of(cols[3]))
                                    .toTupleSegment(pool));
            for (int i = 0; i < 4; i++) {
                MemorySegment field = t.getFieldSegment(i);
                assertEquals(8L, field.byteSize(), "column " + i + " field width");
                assertEquals(cols[i], field.get(Layouts.LE64_U, 0), "column " + i + " value");
            }
        }
    }

    // ---- round-trip + layout ----

    @Property(tries = 1000)
    void toTupleSegmentRoundTrips(@ForAll long a, @ForAll long b, @ForAll long c, @ForAll long d) {
        SpocKey k = new SpocKey(TermId.of(a), TermId.of(b), TermId.of(c), TermId.of(d));
        assertEquals(
                k, roundTrip(k), "fromTuple(toTupleSegment(k)) must recover k for all 4 columns");
    }

    @Property(tries = 500)
    void wireLayoutIsExactly42Bytes(
            @ForAll long a, @ForAll long b, @ForAll long c, @ForAll long d) {
        SpocKey k = new SpocKey(TermId.of(a), TermId.of(b), TermId.of(c), TermId.of(d));
        try (HeapBufferPool pool = new HeapBufferPool()) {
            assertEquals(
                    42L,
                    k.toTupleSegment(pool).byteSize(),
                    "4×8 data + 4×2 offsets + 2 count = 42 bytes — drift orphans persisted indexes");
        }
    }

    @Property(tries = 1000)
    void equalityAndHashAreByValue(@ForAll long a, @ForAll long b, @ForAll long c, @ForAll long d) {
        SpocKey x = new SpocKey(TermId.of(a), TermId.of(b), TermId.of(c), TermId.of(d));
        SpocKey y = new SpocKey(TermId.of(a), TermId.of(b), TermId.of(c), TermId.of(d));
        assertEquals(x, y);
        assertEquals(x.hashCode(), y.hashCode());
    }

    // ---- sort order for the common case: all ids natural (top bit clear) ----

    @Property(tries = 2000)
    void naturalIdsSortColumnMajorMatchingTermIdOrder(
            @ForAll long a0, @ForAll long b0, @ForAll long a1, @ForAll long b1) {
        // Natural ids only: top bit clear → signed value == unsigned value, so the
        // signed column comparator agrees with TermId.compareTo here.
        TermId a = TermId.ofNatural(a0), b = TermId.ofNatural(b0);
        TermId c = TermId.ofNatural(a1), d = TermId.ofNatural(b1);
        // Compare two 2-distinct-column keys: (a,b,Z,Z) vs (c,d,Z,Z).
        TermId Z = TermId.ZERO;
        Assume.that(!a.equals(c) || !b.equals(d));
        SpocKey x = new SpocKey(a, b, Z, Z);
        SpocKey y = new SpocKey(c, d, Z, Z);

        // Oracle: lexicographic by (col0, col1) using TermId.compareTo.
        int oracle =
                a.compareTo(c) != 0
                        ? Integer.signum(a.compareTo(c))
                        : Integer.signum(b.compareTo(d));
        assertEquals(
                oracle,
                indexCompare(x, y),
                "for natural ids the index column order must match TermId.compareTo");
    }

    // ---- THE FINDING: signed index comparator vs unsigned TermId.compareTo ----

    @Test
    void extensionIdsExposeTheSignedVsUnsignedOrderingMismatch() {
        TermId natural = TermId.ofNatural(1L); // 0x0000…0001, signed-positive
        TermId extension = TermId.ofExtensionSlot(1L); // 0x8000…0001, signed-NEGATIVE

        // TermId's own contract (unsigned): natural sorts BEFORE extension.
        assertTrue(
                natural.compareTo(extension) < 0,
                "TermId.compareTo (unsigned): natural before extension");

        // The SPOC index column comparator (signed Int64): extension's top bit
        // makes it a negative long, so it sorts BEFORE the natural id — the
        // OPPOSITE of TermId.compareTo.
        TermId Z = TermId.ZERO;
        SpocKey naturalKey = new SpocKey(natural, Z, Z, Z);
        SpocKey extensionKey = new SpocKey(extension, Z, Z, Z);
        assertTrue(
                indexCompare(extensionKey, naturalKey) < 0,
                "SPOC index (signed Int64): extension sorts BEFORE natural — opposite of TermId.compareTo");

        // Pin the disagreement explicitly so neither side drifts silently:
        // TermId.compareTo says natural < extension; the index says natural > extension.
        int termOrder = Integer.signum(natural.compareTo(extension)); // -1 (natural first)
        int idxOrder = indexCompare(naturalKey, extensionKey); // +1 (natural last)
        assertEquals(-1, termOrder, "TermId.compareTo: natural < extension");
        assertEquals(1, idxOrder, "SPOC index: natural > extension");
        assertTrue(
                termOrder != idxOrder,
                "TermId order and index order DISAGREE for natural-vs-extension (documented mismatch)");
    }
}
