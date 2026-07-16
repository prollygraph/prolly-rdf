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

/**
 * 64-bit dictionary identifier for an RDF term.
 *
 * <p>The top bit ({@link #EXTENSION_FLAG}) is reserved as the <b>collision-extension flag</b>:
 *
 * <ul>
 *   <li>If clear: the lower 63 bits are the natural {@link HashFunction} digest of the encoded term
 *       (truncated to 63 bits).
 *   <li>If set: the lower 63 bits are an extension-table slot — a counter allocated when a
 *       natural-hash slot was already taken by a byte-different term.
 * </ul>
 *
 * <p>This type's own ordering ({@link #compareTo}) is unsigned 64-bit, so natural ids sort before
 * extension ids (the extension flag is the top bit, the most significant in an unsigned compare).
 *
 * <p><b>Caveat — the SPOC index does NOT use this order.</b> {@code SpocKey} stores TermIds in
 * {@code Encoding.Int64} columns, whose tree comparator is {@code Long.compare} (<i>signed</i>). An
 * extension id has its top bit set, so it is a negative long and sorts <i>before</i> natural ids in
 * the index — the opposite of {@link #compareTo}. The two agree for natural-only ids (top bit clear
 * ⇒ signed == unsigned). This is a documented mismatch, pinned by {@code SpocKeyTest}; see
 * newcomer-docs/foundations/the-termid-ordering-trap.md.
 *
 * <p>{@code TermId} is a value-style record: cheap to construct, immutable, with structural
 * equality. For tight loops where boxing matters, use the raw {@code long} via {@link #value()} and
 * the static helpers.
 *
 * @param value raw 64-bit id, top bit = extension flag
 */
public record TermId(long value) implements Comparable<TermId> {

    /** Top bit set means this id refers to an extension-table slot. */
    public static final long EXTENSION_FLAG = 0x8000_0000_0000_0000L;

    /** Mask for the lower 63 bits — the natural-hash or extension-slot portion. */
    public static final long NATURAL_MASK = 0x7FFF_FFFF_FFFF_FFFFL;

    /**
     * Pre-allocated zero-valued id; some callers use it as a sentinel for the default graph. Phase
     * 2 confirms whether that pattern survives.
     */
    public static final TermId ZERO = new TermId(0L);

    /** Factory: wrap a raw long. */
    public static TermId of(long raw) {
        return new TermId(raw);
    }

    /**
     * Build a natural-form TermId from a 64-bit hash. The top bit is masked away — the resulting id
     * is always non-extension.
     */
    public static TermId ofNatural(long hash) {
        return new TermId(hash & NATURAL_MASK);
    }

    /**
     * Build an extension-form TermId from a slot index. The top bit is set; the slot occupies the
     * lower 63 bits.
     *
     * @throws IllegalArgumentException if {@code slot} has its top bit set
     */
    public static TermId ofExtensionSlot(long slot) {
        if ((slot & EXTENSION_FLAG) != 0) {
            throw new IllegalArgumentException(
                    "extension slot must fit in 63 bits, got 0x" + Long.toHexString(slot));
        }
        return new TermId(slot | EXTENSION_FLAG);
    }

    /**
     * @return true iff this id references the extension table.
     */
    public boolean isExtension() {
        return (value & EXTENSION_FLAG) != 0;
    }

    /**
     * @return lower 63 bits — either the natural hash or the extension slot.
     */
    public long naturalBits() {
        return value & NATURAL_MASK;
    }

    /**
     * Unsigned 64-bit comparison. Natural ids (top bit 0) come before extension ids (top bit 1).
     * Within either band, ordering is by the 63-bit payload.
     */
    @Override
    public int compareTo(TermId other) {
        return Long.compareUnsigned(this.value, other.value);
    }

    /** Hex form for logging / debugging. Always 16 hex digits, zero-padded. */
    public String toHex() {
        return String.format("%016x", value);
    }

    @Override
    public String toString() {
        return "TermId(0x" + toHex() + (isExtension() ? ", ext)" : ")");
    }
}
