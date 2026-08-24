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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.Tuple;
import java.lang.foreign.MemorySegment;
import java.util.Comparator;

/**
 * Helpers for the 1-column {@code Int64} Prolly key shared by {@code Dictionary} (TermId →
 * encoded-term bytes) and {@code TermStats} (TermId → frequency) — both in {@code prolly-rdf4j},
 * which depends on this module.
 *
 * <p>Both tables key on a single {@code Int64} column. The generic {@code TupleBuilder} / {@code
 * TupleDescriptor.compare} path borrows a {@code BufferPool} segment per column and {@code
 * asSlice}s a {@code MemorySegment} per field comparison — pure waste for a key whose wire layout
 * is a 12-byte compile-time constant. These helpers build and compare it directly. (Mirrors {@code
 * SpocKey.toTupleSegment} / {@code SpocIndex.FAST_KEY_COMPARATOR} for the 4-column index key.)
 */
public final class Int64Key {
    private Int64Key() {}

    /** Wire size of a 1-column Int64 tuple: 8 data + 2 offset + 2 count. */
    public static final int TUPLE_SIZE = 12;

    /**
     * Write the 1-column Int64 tuple into the first {@link #TUPLE_SIZE} bytes of {@code dst} and
     * return that exact-size slice (a {@code Tuple} reads its count from the segment's end).
     * Splitting the build from the borrow lets a caller own the borrowed block for recycling
     * (ADR-0062 D-4): borrow a block, {@code writeInto} it, use the slice for a lookup, then {@code
     * release} the block. {@code dst} must be ≥ {@code TUPLE_SIZE}.
     */
    public static MemorySegment writeInto(MemorySegment dst, long value) {
        MemorySegment seg = dst.asSlice(0, TUPLE_SIZE);
        seg.set(Layouts.LE64_U, 0, value);
        seg.set(Layouts.LE16_U, 8, (short) 8); // column-0 end offset
        seg.set(Layouts.LE16_U, 10, (short) 1); // column count
        return seg;
    }

    /**
     * Build the 1-column Int64 tuple into a freshly-borrowed pool segment. Byte-identical to {@code
     * new TupleBuilder(pool, schema).putInt64(0, value).build().segment()} for a 1-column Int64
     * descriptor. The non-recycling convenience for retained-key sites; transient lookups borrow +
     * {@link #writeInto} + release the block themselves.
     */
    public static MemorySegment toTupleSegment(BufferPool pool, long value) {
        // borrowRetained: held as a staged key until flush, never recycled —
        // exact-size allocation, not the pool's bucket rounding (see
        // SpocKey.toTupleSegment for the measured why).
        return writeInto(pool.borrowRetained(TUPLE_SIZE), value);
    }

    /**
     * Non-allocating key comparator, order-identical to a 1-column Int64 {@code
     * TupleDescriptor.compare}: one signed {@code Long.compare} on the column read directly at
     * offset 0, with no per-field {@code MemorySegment} slicing. Safe only for tuples built by
     * {@link #toTupleSegment} — i.e. exactly the keys {@code Dictionary} and {@code TermStats}
     * insert.
     */
    public static final Comparator<Tuple> COMPARATOR =
            (a, b) ->
                    Long.compare(
                            a.segment().get(Layouts.LE64_U, 0), b.segment().get(Layouts.LE64_U, 0));
}
