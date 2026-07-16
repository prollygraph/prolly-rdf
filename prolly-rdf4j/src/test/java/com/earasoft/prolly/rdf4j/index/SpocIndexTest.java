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
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SpocIndexTest {

    private SpocIndex fresh() {
        return new SpocIndex(new InMemoryNodeStore(), new HeapBufferPool());
    }

    private static SpocKey k(long s, long p, long o, long c) {
        return new SpocKey(TermId.of(s), TermId.of(p), TermId.of(o), TermId.of(c));
    }

    private static Set<SpocKey> drain(Iterator<SpocKey> it) {
        Set<SpocKey> out = new LinkedHashSet<>();
        it.forEachRemaining(out::add);
        return out;
    }

    @Test
    void empty_index_contains_returns_false() {
        SpocIndex idx = fresh();
        assertFalse(idx.contains(k(1, 2, 3, 4)));
    }

    @Test
    void insert_then_contains() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        assertTrue(idx.contains(k(1, 2, 3, 4)));
    }

    @Test
    void hasNext_stays_false_when_re_queried_after_exhaustion() {
        // The iterator's `if (exhausted) return false` re-entry guard is only hit by calling
        // hasNext() AGAIN
        // after it already returned false — normal drain loops never do, so a mutation audit
        // flagged it
        // uncovered. An exhausted iterator that re-reported hasNext()==true would be a real bug.
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.commit();
        Iterator<SpocKey> it = idx.iter();
        assertTrue(it.hasNext());
        it.next();
        assertFalse(it.hasNext(), "exhausted on the first re-query");
        assertFalse(
                it.hasNext(),
                "and still exhausted on a second re-query (the `if (exhausted)` guard)");
    }

    @Test
    void insert_does_not_affect_unrelated_keys() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        assertFalse(idx.contains(k(5, 2, 3, 4)));
        assertFalse(idx.contains(k(1, 9, 3, 4)));
        assertFalse(idx.contains(k(1, 2, 0, 4)));
        assertFalse(idx.contains(k(1, 2, 3, 0)));
    }

    @Test
    void duplicate_insert_idempotent() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.insert(k(1, 2, 3, 4));
        idx.insert(k(1, 2, 3, 4));
        assertTrue(idx.contains(k(1, 2, 3, 4)));
        idx.commit();
        // After commit, full scan returns exactly one row
        assertEquals(1, drain(idx.iter()).size());
    }

    @Test
    void delete_buffered_in_pending() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.delete(k(1, 2, 3, 4));
        assertFalse(idx.contains(k(1, 2, 3, 4)));
    }

    @Test
    void delete_after_commit_persists() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.commit();
        idx.delete(k(1, 2, 3, 4));
        idx.commit();
        assertFalse(idx.contains(k(1, 2, 3, 4)));
        assertEquals(0, drain(idx.iter()).size());
    }

    @Test
    void delete_of_absent_key_is_safe() {
        SpocIndex idx = fresh();
        assertDoesNotThrow(() -> idx.delete(k(42, 43, 44, 45)));
        idx.commit();
        assertEquals(0, drain(idx.iter()).size());
    }

    @Test
    void iter_returns_all_inserted_rows() {
        SpocIndex idx = fresh();
        Set<SpocKey> inserted = Set.of(k(1, 2, 3, 4), k(1, 2, 3, 5), k(2, 2, 3, 4), k(1, 9, 3, 4));
        inserted.forEach(idx::insert);
        idx.commit();
        assertEquals(inserted, drain(idx.iter()));
    }

    @Test
    void iter_after_no_commit_is_empty_for_buffered_writes() {
        // Documented behavior: iter() reads committed state only in this iter.
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        assertEquals(0, drain(idx.iter()).size());
        idx.commit();
        assertEquals(1, drain(idx.iter()).size());
    }

    @Test
    void prefix_scan_one_column() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.insert(k(1, 5, 6, 7));
        idx.insert(k(2, 2, 3, 4));
        idx.commit();
        Set<SpocKey> got = drain(idx.iterPrefix(TermId.of(1)));
        assertEquals(Set.of(k(1, 2, 3, 4), k(1, 5, 6, 7)), got);
    }

    @Test
    void prefix_scan_two_columns() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.insert(k(1, 2, 5, 6));
        idx.insert(k(1, 9, 3, 4));
        idx.commit();
        Set<SpocKey> got = drain(idx.iterPrefix(TermId.of(1), TermId.of(2)));
        assertEquals(Set.of(k(1, 2, 3, 4), k(1, 2, 5, 6)), got);
    }

    @Test
    void prefix_scan_three_columns() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.insert(k(1, 2, 3, 5)); // same s, p, o; different c
        idx.insert(k(1, 2, 9, 4)); // different o
        idx.commit();
        Set<SpocKey> got = drain(idx.iterPrefix(TermId.of(1), TermId.of(2), TermId.of(3)));
        assertEquals(Set.of(k(1, 2, 3, 4), k(1, 2, 3, 5)), got);
    }

    @Test
    void prefix_scan_no_matches_returns_empty() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        idx.commit();
        assertEquals(0, drain(idx.iterPrefix(TermId.of(99))).size());
    }

    @Test
    void prefix_scan_rejects_invalid_length() {
        SpocIndex idx = fresh();
        assertThrows(IllegalArgumentException.class, idx::iterPrefix); // 0 args
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        idx.iterPrefix(
                                TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4))); // 4 args
    }

    @Test
    void commit_returns_static_map_with_root() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 2, 3, 4));
        StaticMap committed = idx.commit();
        assertNotNull(committed);
        assertNotNull(committed.root());
    }

    @Test
    void empty_commit_safe_noop() {
        SpocIndex idx = fresh();
        StaticMap a = idx.commit();
        StaticMap b = idx.commit();
        assertNotNull(a);
        assertNotNull(b);
    }

    @Test
    void reopen_at_committed_root_preserves_data() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        SpocIndex idx1 = new SpocIndex(store, pool);
        idx1.insert(k(1, 2, 3, 4));
        idx1.insert(k(5, 6, 7, 8));
        StaticMap committed = idx1.commit();

        SpocIndex idx2 = new SpocIndex(store, pool, committed);
        assertTrue(idx2.contains(k(1, 2, 3, 4)));
        assertTrue(idx2.contains(k(5, 6, 7, 8)));
        assertFalse(idx2.contains(k(9, 9, 9, 9)));
        assertEquals(2, drain(idx2.iter()).size());
    }

    @Test
    void multi_commit_cycle_preserves_history() {
        SpocIndex idx = fresh();
        idx.insert(k(1, 1, 1, 1));
        idx.commit();
        idx.insert(k(2, 2, 2, 2));
        idx.commit();
        idx.insert(k(3, 3, 3, 3));
        idx.commit();
        Set<SpocKey> all = drain(idx.iter());
        assertEquals(3, all.size());
        assertTrue(all.contains(k(1, 1, 1, 1)));
        assertTrue(all.contains(k(2, 2, 2, 2)));
        assertTrue(all.contains(k(3, 3, 3, 3)));
    }

    @Test
    void boundary_term_id_values_round_trip_through_index() {
        SpocIndex idx = fresh();
        SpocKey min =
                new SpocKey(
                        TermId.of(Long.MIN_VALUE), TermId.of(0L),
                        TermId.of(Long.MAX_VALUE), TermId.of(-1L));
        idx.insert(min);
        idx.commit();
        assertTrue(idx.contains(min));
        assertEquals(min, drain(idx.iter()).iterator().next());
    }

    @Test
    void extension_termIds_work_in_index() {
        SpocIndex idx = fresh();
        SpocKey ext =
                new SpocKey(
                        TermId.ofExtensionSlot(5L),
                        TermId.ofNatural(0x1234L),
                        TermId.ofExtensionSlot(Long.MAX_VALUE >>> 1),
                        TermId.of(0L));
        idx.insert(ext);
        idx.commit();
        assertTrue(idx.contains(ext));
    }

    @Test
    void stress_1k_inserts_all_distinct() {
        SplittableRandom r = new SplittableRandom(0xCAFEL);
        SpocIndex idx = fresh();
        Set<SpocKey> inserted = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            SpocKey key =
                    new SpocKey(
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()),
                            TermId.of(r.nextLong()));
            inserted.add(key);
            idx.insert(key);
        }
        idx.commit();
        Set<SpocKey> read = drain(idx.iter());
        assertEquals(inserted, read);
    }

    @Test
    void prefix_scan_with_repeating_columns() {
        SpocIndex idx = fresh();
        // Many rows sharing the same (s, p) prefix
        for (int i = 0; i < 100; i++) {
            idx.insert(k(1, 1, i, 0));
        }
        idx.commit();
        Set<SpocKey> hit = drain(idx.iterPrefix(TermId.of(1), TermId.of(1)));
        assertEquals(100, hit.size());
    }

    @Test
    void prefix_scan_excludes_non_matching_neighbors() {
        SpocIndex idx = fresh();
        // Insert rows with prefix (1, 2, *), interleaved with (1, 3, *) and (2, 2, *)
        idx.insert(k(1, 2, 1, 1));
        idx.insert(k(1, 3, 1, 1)); // wrong column-1
        idx.insert(k(2, 2, 1, 1)); // wrong column-0
        idx.insert(k(1, 2, 2, 1));
        idx.commit();
        Set<SpocKey> hit = drain(idx.iterPrefix(TermId.of(1), TermId.of(2)));
        assertEquals(Set.of(k(1, 2, 1, 1), k(1, 2, 2, 1)), hit);
    }

    // ---- FAST_KEY_COMPARATOR: order-equivalence to the generic descriptor ----

    private static final long[] BOUNDARY_VALUES = {
        0L,
        1L,
        -1L,
        2L,
        -2L,
        Long.MIN_VALUE,
        Long.MAX_VALUE,
        Long.MIN_VALUE + 1,
        Long.MAX_VALUE - 1,
        0x8000_0000_0000_0000L,
        0x7FFF_FFFF_FFFF_FFFFL,
        42L,
        -42L,
    };

    private static long pick(SplittableRandom r) {
        // Half boundary value, half uniformly random — boundaries matter because
        // extension TermIds carry the sign bit, and the comparator is signed.
        return r.nextBoolean() ? BOUNDARY_VALUES[r.nextInt(BOUNDARY_VALUES.length)] : r.nextLong();
    }

    private static SpocKey randomKey(SplittableRandom r) {
        return new SpocKey(
                TermId.of(pick(r)), TermId.of(pick(r)),
                TermId.of(pick(r)), TermId.of(pick(r)));
    }

    @Test
    void fast_comparator_matches_descriptor_ordering() {
        // CRITICAL invariant: SpocIndex's edit buffer sorts with
        // FAST_KEY_COMPARATOR, but flush() rebuilds the tree with
        // SpocKey.DESCRIPTOR. If the two orders ever diverged the prolly tree
        // would be built wrong — silently. Fuzz both comparators against each
        // other over a wide value range including the signed boundaries.
        BufferPool pool = new HeapBufferPool();
        SplittableRandom r = new SplittableRandom(0xBADC0FFEEL);
        for (int i = 0; i < 5000; i++) {
            SpocKey ka = randomKey(r);
            SpocKey kb = randomKey(r);
            com.dolthub.prolly.Tuple ta = new com.dolthub.prolly.Tuple(ka.toTupleSegment(pool));
            com.dolthub.prolly.Tuple tb = new com.dolthub.prolly.Tuple(kb.toTupleSegment(pool));
            int fast = SpocIndex.FAST_KEY_COMPARATOR.compare(ta, tb);
            int generic = SpocKey.DESCRIPTOR.compare(ta, tb);
            assertEquals(
                    Integer.signum(generic),
                    Integer.signum(fast),
                    "comparator divergence: " + ka + " vs " + kb);
        }
    }

    @Test
    void fast_comparator_each_column_is_decisive() {
        // A difference confined to any single column must still order — checks
        // the comparator walks all four offsets, not just the leading one.
        BufferPool pool = new HeapBufferPool();
        for (int col = 0; col < 4; col++) {
            long[] lo = {5L, 5L, 5L, 5L};
            long[] hi = {5L, 5L, 5L, 5L};
            hi[col] = 6L;
            com.dolthub.prolly.Tuple tLo =
                    new com.dolthub.prolly.Tuple(
                            new SpocKey(
                                            TermId.of(lo[0]), TermId.of(lo[1]),
                                            TermId.of(lo[2]), TermId.of(lo[3]))
                                    .toTupleSegment(pool));
            com.dolthub.prolly.Tuple tHi =
                    new com.dolthub.prolly.Tuple(
                            new SpocKey(
                                            TermId.of(hi[0]), TermId.of(hi[1]),
                                            TermId.of(hi[2]), TermId.of(hi[3]))
                                    .toTupleSegment(pool));
            assertTrue(
                    SpocIndex.FAST_KEY_COMPARATOR.compare(tLo, tHi) < 0,
                    "column " + col + " difference must order lo < hi");
            assertTrue(
                    SpocIndex.FAST_KEY_COMPARATOR.compare(tHi, tLo) > 0,
                    "column " + col + " difference must order hi > lo");
        }
    }

    @Test
    void fast_comparator_returns_zero_for_equal_keys() {
        BufferPool pool = new HeapBufferPool();
        SpocKey k =
                new SpocKey(
                        TermId.of(7L), TermId.of(-3L),
                        TermId.of(0L), TermId.of(99L));
        com.dolthub.prolly.Tuple a = new com.dolthub.prolly.Tuple(k.toTupleSegment(pool));
        com.dolthub.prolly.Tuple b = new com.dolthub.prolly.Tuple(k.toTupleSegment(pool));
        assertEquals(0, SpocIndex.FAST_KEY_COMPARATOR.compare(a, b));
    }

    @Test
    void inserts_iterate_back_in_comparator_sorted_order() {
        // End-to-end: keys inserted in scrambled order through the
        // FAST_KEY_COMPARATOR-backed buffer must come back in DESCRIPTOR order
        // after a flush — proving the buffer order and the rebuilt tree agree.
        SpocIndex idx = fresh();
        SplittableRandom r = new SplittableRandom(99L);
        java.util.List<SpocKey> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) {
            SpocKey key = randomKey(r);
            if (keys.contains(key)) continue; // keep the corpus distinct
            keys.add(key);
            idx.insert(key);
        }
        idx.commit();
        java.util.List<SpocKey> expected = new java.util.ArrayList<>(keys);
        BufferPool pool = new HeapBufferPool();
        expected.sort(
                (x, y) ->
                        SpocKey.DESCRIPTOR.compare(
                                new com.dolthub.prolly.Tuple(x.toTupleSegment(pool)),
                                new com.dolthub.prolly.Tuple(y.toTupleSegment(pool))));
        java.util.List<SpocKey> actual = new java.util.ArrayList<>();
        idx.iter().forEachRemaining(actual::add);
        assertEquals(expected, actual);
    }
}
