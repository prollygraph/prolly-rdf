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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link Int64Key} — the directly-built 1-column Int64 Prolly key used by {@link
 * Dictionary} and {@link TermStats}.
 *
 * <p>The two load-bearing invariants: {@code toTupleSegment} must be byte-identical to what the
 * generic {@code TupleBuilder} produces (so the tree comparator and decoders see the same wire
 * format), and {@code COMPARATOR} must induce the exact same order as a 1-column Int64 {@code
 * TupleDescriptor.compare} (so the {@code MutableMap} edit buffer and the {@code flush()} tree
 * rebuild agree).
 */
class Int64KeyTest {

    /** The schema both Dictionary and TermStats key on. */
    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));

    private BufferPool pool() {
        return new HeapBufferPool();
    }

    @Test
    void tuple_segment_is_exactly_12_bytes() {
        // 8 data + 2 offset + 2 count.
        assertEquals(12, Int64Key.TUPLE_SIZE);
        assertEquals(12L, Int64Key.toTupleSegment(pool(), 42L).byteSize());
    }

    @Test
    void tuple_segment_byte_identical_to_generic_builder() {
        // Int64Key hand-writes the 12-byte tuple; pin it byte-for-byte against
        // the TupleBuilder path it replaced in Dictionary / TermStats.
        BufferPool p = pool();
        for (long v :
                new long[] {
                    0L,
                    1L,
                    -1L,
                    Long.MIN_VALUE,
                    Long.MAX_VALUE,
                    Long.MIN_VALUE + 1,
                    Long.MAX_VALUE - 1,
                    0x1234_5678_9ABC_DEF0L,
                    -42L,
                    42L
                }) {
            MemorySegment actual = Int64Key.toTupleSegment(p, v);
            TupleBuilder tb = new TupleBuilder(p, DESC);
            tb.putInt64(0, v);
            MemorySegment expected = tb.build().segment();
            assertEquals(
                    expected.byteSize(),
                    actual.byteSize(),
                    "tuple size must match the generic builder for value " + v);
            for (long i = 0; i < expected.byteSize(); i++) {
                assertEquals(
                        expected.get(Layouts.BYTE, i),
                        actual.get(Layouts.BYTE, i),
                        "byte " + i + " differs for value " + v);
            }
        }
    }

    @Test
    void comparator_matches_descriptor_ordering() {
        // CRITICAL: the MutableMap edit buffer sorts with Int64Key.COMPARATOR
        // but flush() rebuilds with DESC. Fuzz the two against each other,
        // weighted toward signed boundaries (extension TermIds set the top
        // bit, and the comparator is signed).
        BufferPool p = pool();
        SplittableRandom r = new SplittableRandom(0x1234_5678L);
        long[] boundary = {
            0L,
            1L,
            -1L,
            2L,
            -2L,
            Long.MIN_VALUE,
            Long.MAX_VALUE,
            Long.MIN_VALUE + 1,
            Long.MAX_VALUE - 1
        };
        for (int i = 0; i < 5000; i++) {
            long va = r.nextBoolean() ? boundary[r.nextInt(boundary.length)] : r.nextLong();
            long vb = r.nextBoolean() ? boundary[r.nextInt(boundary.length)] : r.nextLong();
            Tuple ta = new Tuple(Int64Key.toTupleSegment(p, va));
            Tuple tb = new Tuple(Int64Key.toTupleSegment(p, vb));
            assertEquals(
                    Integer.signum(DESC.compare(ta, tb)),
                    Integer.signum(Int64Key.COMPARATOR.compare(ta, tb)),
                    "comparator divergence for " + va + " vs " + vb);
        }
    }

    @Test
    void comparator_returns_zero_for_equal_keys() {
        BufferPool p = pool();
        Tuple a = new Tuple(Int64Key.toTupleSegment(p, 77L));
        Tuple b = new Tuple(Int64Key.toTupleSegment(p, 77L));
        assertEquals(0, Int64Key.COMPARATOR.compare(a, b));
    }

    @Test
    void comparator_orders_signed_negative_below_positive() {
        // Extension TermIds carry the sign bit — they must sort below natural
        // ones under the signed comparator.
        BufferPool p = pool();
        Tuple neg = new Tuple(Int64Key.toTupleSegment(p, Long.MIN_VALUE));
        Tuple pos = new Tuple(Int64Key.toTupleSegment(p, 1L));
        assertTrue(Int64Key.COMPARATOR.compare(neg, pos) < 0);
        assertTrue(Int64Key.COMPARATOR.compare(pos, neg) > 0);
    }

    @Test
    void tuple_segment_round_trips_as_one_column_tuple() {
        BufferPool p = pool();
        for (long v : new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 123_456_789L}) {
            Tuple t = new Tuple(Int64Key.toTupleSegment(p, v));
            assertEquals(1, t.count(), "single-column tuple");
            long read = t.getFieldSegment(0).get(Layouts.LE64_U, 0);
            assertEquals(v, read, "column value must round-trip for " + v);
        }
    }

    /**
     * CANONICALITY — the presence-index contract, pinned where it is relied on: the dictionary
     * enables {@code SpillableSortedBuffer}'s presence index, whose absent answers are sound only
     * if comparator-equal keys are byte-identical. For this fixed-width single-column layout,
     * equal values through independent builds must produce byte-identical tuples.
     */
    @Test
    void equal_values_build_byte_identical_tuples() {
        for (long v : new long[] {0L, 1L, -1L, 42L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            var a = Int64Key.toTupleSegment(pool(), v);
            var b = Int64Key.toTupleSegment(pool(), v);
            assertEquals(a.byteSize(), b.byteSize());
            for (long i = 0; i < a.byteSize(); i++) {
                assertEquals(
                        a.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i),
                        b.get(java.lang.foreign.ValueLayout.JAVA_BYTE, i),
                        "byte " + i + " must match for value " + v);
            }
        }
    }

    /**
     * The retained-key allocation is exact-size: {@code toTupleSegment} goes through {@code
     * borrowRetained}, so the heap pool's 1 KiB bucket floor no longer amplifies every staged
     * dictionary key ~85x (12 real bytes vs a 1024-byte backing array) for the transaction's
     * lifetime. The BACKING array is the proof — the segment slice always claimed 12.
     */
    @Test
    void retained_key_backing_array_is_exact_size() {
        var seg = Int64Key.toTupleSegment(pool(), 42L);
        byte[] backing =
                (byte[]) seg.heapBase().orElseThrow(() -> new AssertionError("heap-backed"));
        assertEquals(Int64Key.TUPLE_SIZE, backing.length, "no bucket floor on retained keys");
    }
}
