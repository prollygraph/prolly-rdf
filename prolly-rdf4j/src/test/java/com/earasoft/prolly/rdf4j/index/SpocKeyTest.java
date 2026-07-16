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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Tuple;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SpocKeyTest {

    private BufferPool pool() {
        return new HeapBufferPool();
    }

    @Test
    void descriptor_supports_four_field_tuple() {
        // No public accessor for column count on TupleDescriptor; verify indirectly
        // by building a 4-column tuple and reading its count.
        com.dolthub.prolly.TupleBuilder tb =
                new com.dolthub.prolly.TupleBuilder(pool(), SpocKey.DESCRIPTOR);
        tb.putInt64(0, 1L);
        tb.putInt64(1, 2L);
        tb.putInt64(2, 3L);
        tb.putInt64(3, 4L);
        Tuple t = tb.build();
        assertEquals(4, t.count());
    }

    @Test
    void round_trip_simple() {
        SpocKey k = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        MemorySegment seg = k.toTupleSegment(pool());
        Tuple t = new Tuple(seg);
        SpocKey rt = SpocKey.fromTuple(t);
        assertEquals(k, rt);
    }

    @Test
    void boundary_term_ids_round_trip() {
        SpocKey k =
                new SpocKey(
                        TermId.of(Long.MIN_VALUE),
                        TermId.of(0L),
                        TermId.of(Long.MAX_VALUE),
                        TermId.of(-1L));
        MemorySegment seg = k.toTupleSegment(pool());
        SpocKey rt = SpocKey.fromTuple(new Tuple(seg));
        assertEquals(k, rt);
        assertEquals(Long.MIN_VALUE, rt.col0().value());
        assertEquals(0L, rt.col1().value());
        assertEquals(Long.MAX_VALUE, rt.col2().value());
        assertEquals(-1L, rt.col3().value());
    }

    @Test
    void extension_and_natural_ids_round_trip() {
        SpocKey k =
                new SpocKey(
                        TermId.ofNatural(0x1234_5678_9ABC_DEF0L),
                        TermId.ofExtensionSlot(42L),
                        TermId.ofNatural(0L),
                        TermId.ofExtensionSlot(Long.MAX_VALUE >>> 1));
        SpocKey rt = SpocKey.fromTuple(new Tuple(k.toTupleSegment(pool())));
        assertEquals(k, rt);
        assertFalse(rt.col0().isExtension());
        assertTrue(rt.col1().isExtension());
        assertFalse(rt.col2().isExtension());
        assertTrue(rt.col3().isExtension());
    }

    @Test
    void column_accessors_return_correct_values() {
        SpocKey k = new SpocKey(TermId.of(10L), TermId.of(20L), TermId.of(30L), TermId.of(40L));
        assertEquals(TermId.of(10L), k.col0());
        assertEquals(TermId.of(20L), k.col1());
        assertEquals(TermId.of(30L), k.col2());
        assertEquals(TermId.of(40L), k.col3());
    }

    @Test
    void equals_and_hashCode_consistent() {
        SpocKey a = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        SpocKey b = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        SpocKey c = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(5L));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }

    @Test
    void distinct_keys_have_distinct_segments() {
        BufferPool p = pool();
        SpocKey k1 = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        SpocKey k2 = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(5L));
        MemorySegment s1 = k1.toTupleSegment(p);
        MemorySegment s2 = k2.toTupleSegment(p);
        // Segments differ in the last column
        assertNotEquals(s1.byteSize(), 0L);
        boolean anyByteDiff = false;
        for (long i = 0; i < Math.min(s1.byteSize(), s2.byteSize()); i++) {
            if (s1.get(com.earasoft.prolly.rdf4j.term.Layouts.BYTE, i)
                    != s2.get(com.earasoft.prolly.rdf4j.term.Layouts.BYTE, i)) {
                anyByteDiff = true;
                break;
            }
        }
        assertTrue(anyByteDiff);
    }

    @Test
    void rejects_wrong_column_count() {
        // A 1-column tuple cannot be parsed as a SpocKey
        com.dolthub.prolly.TupleBuilder tb =
                new com.dolthub.prolly.TupleBuilder(pool(), SpocKey.DESCRIPTOR);
        tb.putInt64(0, 42L);
        Tuple t = tb.build();
        assertEquals(1, t.count()); // only one field populated
        assertThrows(IllegalArgumentException.class, () -> SpocKey.fromTuple(t));
    }

    @Test
    void fuzz_round_trip() {
        SplittableRandom r = new SplittableRandom(0xC0FFEEL);
        BufferPool p = pool();
        for (int i = 0; i < 1000; i++) {
            SpocKey k =
                    new SpocKey(
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()));
            SpocKey rt = SpocKey.fromTuple(new Tuple(k.toTupleSegment(p)));
            assertEquals(k, rt);
        }
    }

    @Test
    void uniqueness_across_random_corpus() {
        SplittableRandom r = new SplittableRandom(7L);
        BufferPool p = pool();
        Set<SpocKey> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            SpocKey k =
                    new SpocKey(
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()));
            assertTrue(keys.add(k), "duplicate at i=" + i);
            // Also verify round-trip via tuple
            assertEquals(k, SpocKey.fromTuple(new Tuple(k.toTupleSegment(p))));
        }
    }

    @Test
    void all_zeros_round_trip() {
        SpocKey k = new SpocKey(TermId.ZERO, TermId.ZERO, TermId.ZERO, TermId.ZERO);
        SpocKey rt = SpocKey.fromTuple(new Tuple(k.toTupleSegment(pool())));
        assertEquals(k, rt);
    }

    @Test
    void toString_renders_hex() {
        SpocKey k = new SpocKey(TermId.of(0L), TermId.of(1L), TermId.of(0xFF_FFL), TermId.of(-1L));
        String s = k.toString();
        assertTrue(s.contains("0000000000000000"));
        assertTrue(s.contains("0000000000000001"));
        assertTrue(s.contains("000000000000ffff"));
        assertTrue(s.contains("ffffffffffffffff"));
    }

    @Test
    void toTupleSegment_is_exactly_42_bytes() {
        // 4×8 column data + 4×2 offset table + 2 count footer.
        SpocKey k = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        assertEquals(42L, k.toTupleSegment(pool()).byteSize());
    }

    @Test
    void toTupleSegment_byte_identical_to_generic_builder() {
        // toTupleSegment hand-writes the 42-byte wire layout directly into one
        // pooled segment instead of going through TupleBuilder. This pins it
        // byte-for-byte against the generic builder it replaced — any drift in
        // the offset table or count footer would corrupt the index
        // comparator's view of the key.
        BufferPool p = pool();
        long[][] cases = {
            {1L, 2L, 3L, 4L},
            {0L, 0L, 0L, 0L},
            {Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE},
            {0x1234_5678_9ABC_DEF0L, 0x0FED_CBA9_8765_4321L, 1L, -42L},
        };
        for (long[] c : cases) {
            SpocKey k =
                    new SpocKey(TermId.of(c[0]), TermId.of(c[1]), TermId.of(c[2]), TermId.of(c[3]));
            MemorySegment actual = k.toTupleSegment(p);

            com.dolthub.prolly.TupleBuilder tb =
                    new com.dolthub.prolly.TupleBuilder(p, SpocKey.DESCRIPTOR);
            tb.putInt64(0, c[0]);
            tb.putInt64(1, c[1]);
            tb.putInt64(2, c[2]);
            tb.putInt64(3, c[3]);
            MemorySegment expected = tb.build().segment();

            assertEquals(
                    expected.byteSize(),
                    actual.byteSize(),
                    "tuple size must match the generic builder");
            for (long i = 0; i < expected.byteSize(); i++) {
                assertEquals(
                        expected.get(com.earasoft.prolly.rdf4j.term.Layouts.BYTE, i),
                        actual.get(com.earasoft.prolly.rdf4j.term.Layouts.BYTE, i),
                        "byte " + i + " differs for " + java.util.Arrays.toString(c));
            }
        }
    }

    @Test
    void direct_built_segment_orders_under_descriptor_comparator() {
        // The whole point of the index is that DESCRIPTOR.compare sees the
        // directly-built segment the same way it would a TupleBuilder one.
        BufferPool p = pool();
        Tuple lo =
                new Tuple(
                        new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L))
                                .toTupleSegment(p));
        Tuple hi =
                new Tuple(
                        new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(5L))
                                .toTupleSegment(p));
        Tuple eq =
                new Tuple(
                        new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L))
                                .toTupleSegment(p));
        assertTrue(SpocKey.DESCRIPTOR.compare(lo, hi) < 0);
        assertTrue(SpocKey.DESCRIPTOR.compare(hi, lo) > 0);
        assertEquals(0, SpocKey.DESCRIPTOR.compare(lo, eq));
    }

    @Test
    void fromTuple_decodes_a_generic_builder_tuple() {
        // fromTuple reads columns at hardcoded offsets 0/8/16/24. Prove that
        // matches what the generic TupleBuilder lays out — not just our own
        // toTupleSegment — so a tuple built either way decodes identically.
        // If TupleBuilder ever changed its column packing, fromTuple's fixed
        // offsets would silently misdecode; this is the guard.
        BufferPool p = pool();
        SplittableRandom r = new SplittableRandom(0xF00DL);
        for (int i = 0; i < 1000; i++) {
            long a = r.nextLong(), b = r.nextLong(), c = r.nextLong(), d = r.nextLong();
            com.dolthub.prolly.TupleBuilder tb =
                    new com.dolthub.prolly.TupleBuilder(p, SpocKey.DESCRIPTOR);
            tb.putInt64(0, a);
            tb.putInt64(1, b);
            tb.putInt64(2, c);
            tb.putInt64(3, d);
            SpocKey k = SpocKey.fromTuple(tb.build());
            assertEquals(a, k.col0().value());
            assertEquals(b, k.col1().value());
            assertEquals(c, k.col2().value());
            assertEquals(d, k.col3().value());
        }
    }

    @Test
    void fromTuple_decodes_boundary_values_from_generic_builder() {
        // The signed-boundary longs through the generic builder → fromTuple.
        BufferPool p = pool();
        long[] vals = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};
        for (long a : vals) {
            com.dolthub.prolly.TupleBuilder tb =
                    new com.dolthub.prolly.TupleBuilder(p, SpocKey.DESCRIPTOR);
            tb.putInt64(0, a);
            tb.putInt64(1, 0L);
            tb.putInt64(2, a);
            tb.putInt64(3, -a);
            SpocKey k = SpocKey.fromTuple(tb.build());
            assertEquals(a, k.col0().value());
            assertEquals(0L, k.col1().value());
            assertEquals(a, k.col2().value());
            assertEquals(-a, k.col3().value());
        }
    }
}
