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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.rdf4j.term.Layouts;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Four-{@link TermId} index key. The natural form for the SPOC, POSC, OSPC, and CSPO indexes — a
 * 4-column {@link Tuple} with one Int64 column per RDF quad position (in the index's column order).
 *
 * <p>Encoding: each Int64 column is written via {@link TupleBuilder#putInt64}. With {@code
 * binaryParity=false} (our default), each column is little-endian native; the tree comparator uses
 * {@code TypeCodec.compare} which dispatches to {@code Long.compare(readInt64, readInt64)} — i.e.
 * <b>signed Long compare per column</b>.
 *
 * <p>Consequence: tree iteration order over a SPOC index is by signed Long on each column. For
 * TermIds (where extension ids have their top bit set), this means extension TermIds sort
 * <em>before</em> natural ones (signed-negative &lt; signed-positive). Not a correctness issue for
 * point lookups but worth documenting for range queries.
 *
 * <h2>Wire layout</h2>
 *
 * {@code [s LE int64][p LE int64][o LE int64][c LE int64][4 × uint16 offsets][uint16 count=4]}
 *
 * <p>Total: {@code 4×8 + 4×2 + 2 = 42 bytes} per key. The offsets are redundant for fixed-width
 * columns; see ARCHITECTURE §8 Tuple-overhead caveat.
 *
 * @implNote <b>Collaborators:</b> {@link Tuple}/{@link TupleBuilder} (build and read the
 *     four-column key) and {@code TypeCodec} (the per-column comparison). <b>Dependents:</b> the
 *     four permutation indexes (SPOC, POSC, OSPC, CSPO) and {@code QuadIndex}, which all key on
 *     this layout.
 */
public final class SpocKey {

    /** The TupleDescriptor for a 4-column TermId index. Shared across all four orderings. */
    public static final TupleDescriptor DESCRIPTOR =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.Int64, false),
                            new Type(Encoding.Int64, false),
                            new Type(Encoding.Int64, false),
                            new Type(Encoding.Int64, false)));

    private final TermId a;
    private final TermId b;
    private final TermId c;
    private final TermId d;

    public SpocKey(TermId a, TermId b, TermId c, TermId d) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.d = d;
    }

    public TermId col0() {
        return a;
    }

    public TermId col1() {
        return b;
    }

    public TermId col2() {
        return c;
    }

    public TermId col3() {
        return d;
    }

    /**
     * Wire size of a 4-column Int64 tuple: 4×8 data + 4×2 offsets + 2 count. Public so a caller can
     * {@code pool.borrow(TUPLE_SIZE)} a recyclable block, build into it via {@link #writeInto}, and
     * {@code release} it after a transient lookup (ADR-0062 D-4).
     */
    public static final int TUPLE_SIZE = 42;

    /**
     * Write this key's 4-column tuple into the first {@link #TUPLE_SIZE} bytes of {@code dst} and
     * return that exact-size slice. The slice must be exactly {@code TUPLE_SIZE} because {@link
     * Tuple} reads its column count from the segment's <em>end</em>; a larger segment would misread
     * the footer.
     *
     * <p>Splitting the build from the borrow lets a caller <b>own the borrowed block for
     * recycling</b> (ADR-0062 D-4): borrow a block, {@code writeInto} it, use the returned slice as
     * a lookup key, then {@code release} the <em>block</em> (not the slice — {@code release}
     * buckets by {@code byteSize}). The retained-key sites use {@link #toTupleSegment} instead.
     * {@code dst} must be ≥ {@code TUPLE_SIZE}.
     */
    public MemorySegment writeInto(MemorySegment dst) {
        MemorySegment seg = dst.asSlice(0, TUPLE_SIZE);
        // Column data — four little-endian Int64s, packed (binaryParity=false).
        seg.set(Layouts.LE64_U, 0, a.value());
        seg.set(Layouts.LE64_U, 8, b.value());
        seg.set(Layouts.LE64_U, 16, c.value());
        seg.set(Layouts.LE64_U, 24, d.value());
        // Offset table — cumulative end position of each column.
        seg.set(Layouts.LE16_U, 32, (short) 8);
        seg.set(Layouts.LE16_U, 34, (short) 16);
        seg.set(Layouts.LE16_U, 36, (short) 24);
        seg.set(Layouts.LE16_U, 38, (short) 32);
        // Count footer.
        seg.set(Layouts.LE16_U, 40, (short) 4);
        return seg;
    }

    /**
     * Build the Tuple-shaped {@link MemorySegment} for insertion / lookup into a freshly-borrowed
     * pool segment. The non-recycling convenience used by retained-key sites (insert / delete,
     * where the segment becomes a {@code MutableMap} key); transient lookups borrow + {@link
     * #writeInto} + release the block themselves (ADR-0062 D-4). Byte layout identical to {@code
     * TupleBuilder.build()} for these four columns, but without its five per-key borrows.
     */
    public MemorySegment toTupleSegment(BufferPool pool) {
        return writeInto(pool.borrow(TUPLE_SIZE));
    }

    /**
     * Decode a 4-column SPOC tuple back to a {@link SpocKey}.
     *
     * <p>Reads the four Int64 columns directly at their fixed offsets (0/8/16/24) — the inverse of
     * {@link #toTupleSegment}. The generic {@code Tuple.getFieldSegment} path slices a {@code
     * MemorySegment} per column; on the scan hot path (one {@code fromTuple} per row) that is a
     * slice allocation per column per row. A SPOC tuple is always four fixed-width Int64 columns,
     * so the fixed-offset read is exact.
     */
    public static SpocKey fromTuple(Tuple tuple) {
        if (tuple.count() != 4) {
            throw new IllegalArgumentException("expected 4-column tuple, got " + tuple.count());
        }
        MemorySegment seg = tuple.segment();
        return new SpocKey(
                TermId.of(seg.get(Layouts.LE64_U, 0)),
                TermId.of(seg.get(Layouts.LE64_U, 8)),
                TermId.of(seg.get(Layouts.LE64_U, 16)),
                TermId.of(seg.get(Layouts.LE64_U, 24)));
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof SpocKey k)) return false;
        return a.equals(k.a) && b.equals(k.b) && c.equals(k.c) && d.equals(k.d);
    }

    @Override
    public int hashCode() {
        int h = a.hashCode();
        h = 31 * h + b.hashCode();
        h = 31 * h + c.hashCode();
        h = 31 * h + d.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "(" + a.toHex() + ", " + b.toHex() + ", " + c.toHex() + ", " + d.toHex() + ")";
    }
}
