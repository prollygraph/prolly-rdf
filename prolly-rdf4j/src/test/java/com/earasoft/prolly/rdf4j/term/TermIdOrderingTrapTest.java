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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.TypeCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

/**
 * Characterization (NOT aspiration) of the <b>TermId ordering trap</b> — {@code
 * newcomer-docs/foundations/the-termid-ordering-trap.md}, plan Step 10 (S-3).
 *
 * <p>A {@link TermId}'s top bit is the {@code EXTENSION_FLAG}, so {@link TermId#compareTo} is
 * <b>unsigned</b> ({@code Long.compareUnsigned}) — an extension id (top bit set) sorts <i>above</i>
 * every natural id. But the four permutation indexes (SPOC/POSC/OSPC/CSPO) store each TermId in an
 * {@code Int64} column, and {@link TypeCodec#compare} for {@link Encoding#Int64} is {@code
 * Long.compare} — <b>signed</b> (top-bit-set reads as negative → sorts <i>below</i> every natural
 * id). The two comparators therefore <b>disagree for extension ids</b> and <b>agree for
 * natural-only ids</b>.
 *
 * <p>This pins the divergence so any future "fix" is <b>deliberate</b>: making the two agree would
 * re-shape on-disk index order — and the index order <i>is</i> the prolly tree's key order, which
 * decides chunk boundaries and the root (commit) hash. A silent "alignment" here is a format break.
 */
class TermIdOrderingTrapTest {

    /**
     * The real index-column comparator: {@code TypeCodec.compare(Int64, …)} == signed {@code
     * Long.compare}.
     */
    private static int indexColumnCompare(long a, long b) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment sa = arena.allocate(8);
            MemorySegment sb = arena.allocate(8);
            TypeCodec.encodeInt64(a, sa);
            TypeCodec.encodeInt64(b, sb);
            return Integer.signum(TypeCodec.compare(Encoding.Int64, sa, sb));
        }
    }

    @Test
    void natural_ids_sort_identically_under_both_comparators() {
        TermId lo = TermId.ofNatural(5L);
        TermId hi = TermId.ofNatural(9_000_000L);
        assertTrue(lo.compareTo(hi) < 0, "unsigned TermId order: lo < hi");
        assertTrue(
                indexColumnCompare(lo.value(), hi.value()) < 0,
                "signed Int64 column AGREES with compareTo while both ids keep the top bit clear");
    }

    @Test
    void extension_id_sorts_above_natural_by_termId_but_below_by_the_index_column() {
        TermId natural = TermId.ofNatural(5L);
        TermId extension = TermId.ofExtensionSlot(5L); // top bit set
        assertTrue(extension.isExtension());

        // TermId.compareTo is UNSIGNED — the extension id is the larger value.
        assertTrue(natural.compareTo(extension) < 0, "unsigned: natural < extension");

        // The Int64 index column is SIGNED — the extension id's top bit reads as negative.
        assertTrue(
                indexColumnCompare(natural.value(), extension.value()) > 0,
                "signed Int64 column: natural > extension — the TRAP, opposite of compareTo");

        // The pin: the two comparators give OPPOSITE answers for the same pair. If this ever
        // starts passing as "equal direction", someone aligned the comparators — which re-shapes
        // on-disk index order + the root hash. That must be a deliberate, format-versioned change.
        assertNotEquals(
                Integer.signum(natural.compareTo(extension)),
                indexColumnCompare(natural.value(), extension.value()),
                "documented divergence (the ordering trap) — keep it deliberate, not accidental");
    }
}
