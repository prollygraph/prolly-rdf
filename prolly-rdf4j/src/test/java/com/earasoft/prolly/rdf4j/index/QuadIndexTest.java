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
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link QuadIndex} — the {@link QuadOrder}-parameterized 4-column index. {@code
 * QuadOrderTest} pins the permutation enum and {@code SpocIndexTest} pins the underlying tree, but
 * {@code QuadIndex} itself — the logical⇄physical translation, {@code leadingPrefixLength}, and
 * {@code scan} prefix derivation — had no dedicated test.
 *
 * <p>The {@code leadingPrefixLength} cases below double as the regression net for {@code
 * QuadIndex.physicalToLogical}: that private switch duplicates {@code QuadOrder}'s column
 * permutation, so it can silently drift out of sync. Every order × bind-pattern result here is
 * derived from each enum's documented physical column order.
 *
 * <p>Real {@link InMemoryNodeStore} + {@link HeapBufferPool} throughout — no mocks.
 */
class QuadIndexTest {

    private static final TermId S = TermId.of(11);
    private static final TermId P = TermId.of(22);
    private static final TermId O = TermId.of(33);
    private static final TermId C = TermId.of(44);

    private static QuadIndex fresh(QuadOrder order) {
        return new QuadIndex(order, new InMemoryNodeStore(), new HeapBufferPool());
    }

    private static int count(Iterator<SpocKey> it) {
        int n = 0;
        while (it.hasNext()) {
            it.next();
            n++;
        }
        return n;
    }

    // ---- insert / contains / delete round-trip, every order ----

    @Test
    void insert_contains_delete_round_trip_for_every_order() {
        for (QuadOrder order : QuadOrder.values()) {
            QuadIndex idx = fresh(order);
            assertFalse(idx.contains(S, P, O, C), order + ": empty index contains nothing");

            idx.insert(S, P, O, C);
            assertTrue(idx.contains(S, P, O, C), order + ": inserted quad must be found");

            idx.delete(S, P, O, C);
            assertFalse(idx.contains(S, P, O, C), order + ": deleted quad must be gone");
        }
    }

    @Test
    void contains_is_false_for_a_quad_that_differs_in_one_position() {
        for (QuadOrder order : QuadOrder.values()) {
            QuadIndex idx = fresh(order);
            idx.insert(S, P, O, C);
            assertFalse(
                    idx.contains(TermId.of(99), P, O, C),
                    order + ": a different subject must not match");
            assertFalse(
                    idx.contains(S, P, O, TermId.of(99)),
                    order + ": a different context must not match");
        }
    }

    @Test
    void order_accessor_returns_the_construction_order() {
        for (QuadOrder order : QuadOrder.values()) {
            assertEquals(order, fresh(order).order());
        }
    }

    // ---- leadingPrefixLength: pins physicalToLogical for each order ----

    @Test
    void leading_prefix_length_spoc() {
        // SPOC physical columns = (s, p, o, c).
        QuadIndex idx = fresh(QuadOrder.SPOC);
        assertEquals(0, idx.leadingPrefixLength(null, null, null, null));
        assertEquals(1, idx.leadingPrefixLength(S, null, null, null));
        assertEquals(2, idx.leadingPrefixLength(S, P, null, null));
        assertEquals(
                1,
                idx.leadingPrefixLength(S, null, O, C),
                "a gap at physical col 1 stops the prefix even though o,c are bound");
        assertEquals(
                3,
                idx.leadingPrefixLength(S, P, O, C),
                "all four bound → capped at 3 (iterPrefix supports at most 3 columns)");
    }

    @Test
    void leading_prefix_length_posc() {
        // POSC physical columns = (p, o, s, c).
        QuadIndex idx = fresh(QuadOrder.POSC);
        assertEquals(
                0,
                idx.leadingPrefixLength(S, null, O, C),
                "predicate unbound → physical col 0 unbound → prefix 0");
        assertEquals(1, idx.leadingPrefixLength(null, P, null, null));
        assertEquals(2, idx.leadingPrefixLength(null, P, O, null));
        assertEquals(3, idx.leadingPrefixLength(S, P, O, C));
    }

    @Test
    void leading_prefix_length_ospc() {
        // OSPC physical columns = (o, s, p, c).
        QuadIndex idx = fresh(QuadOrder.OSPC);
        assertEquals(
                0,
                idx.leadingPrefixLength(S, P, null, C),
                "object unbound → physical col 0 unbound → prefix 0");
        assertEquals(1, idx.leadingPrefixLength(null, null, O, null));
        assertEquals(2, idx.leadingPrefixLength(S, null, O, null));
        assertEquals(3, idx.leadingPrefixLength(S, P, O, C));
    }

    @Test
    void leading_prefix_length_cspo() {
        // CSPO physical columns = (c, s, p, o).
        QuadIndex idx = fresh(QuadOrder.CSPO);
        assertEquals(
                0,
                idx.leadingPrefixLength(S, P, O, null),
                "context unbound → physical col 0 unbound → prefix 0");
        assertEquals(1, idx.leadingPrefixLength(null, null, null, C));
        assertEquals(2, idx.leadingPrefixLength(S, null, null, C));
        assertEquals(3, idx.leadingPrefixLength(S, P, O, C));
    }

    // ---- scan (reads committed state — see SpocIndex doc) ----

    @Test
    void scan_sees_only_committed_state_not_the_pending_buffer() {
        // SpocIndex documents that iter/iterPrefix read the committed tree;
        // buffered writes are visible to contains() but not to scan().
        QuadIndex idx = fresh(QuadOrder.SPOC);
        idx.insert(S, P, O, C);
        assertTrue(idx.contains(S, P, O, C), "contains() sees the pending buffer");
        assertEquals(
                0,
                count(idx.scan(null, null, null, null)),
                "scan() must NOT see uncommitted buffered inserts");

        idx.commit();
        assertEquals(
                1,
                count(idx.scan(null, null, null, null)),
                "after commit() the row becomes visible to scan()");
    }

    @Test
    void scan_with_no_bindings_is_a_full_iteration() {
        QuadIndex idx = fresh(QuadOrder.SPOC);
        idx.insert(S, P, O, C);
        idx.insert(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4));
        idx.insert(TermId.of(5), TermId.of(6), TermId.of(7), TermId.of(8));
        idx.commit();
        assertEquals(
                3,
                count(idx.scan(null, null, null, null)),
                "an all-null scan must visit every row");
    }

    @Test
    void scan_with_a_leading_prefix_returns_only_matching_rows() {
        // SPOC: bind subject → only rows with that subject come back.
        QuadIndex idx = fresh(QuadOrder.SPOC);
        idx.insert(S, P, O, C);
        idx.insert(S, TermId.of(2), TermId.of(3), TermId.of(4)); // same subject
        idx.insert(TermId.of(99), P, O, C); // different subject
        idx.commit();

        Set<SpocKey> got = new HashSet<>();
        Iterator<SpocKey> it = idx.scan(S, null, null, null);
        while (it.hasNext()) got.add(it.next());

        assertEquals(2, got.size(), "scan bound on subject S returns exactly its two rows");
        assertTrue(got.contains(QuadOrder.SPOC.keyOf(S, P, O, C)));
        assertTrue(got.contains(QuadOrder.SPOC.keyOf(S, TermId.of(2), TermId.of(3), TermId.of(4))));
    }

    @Test
    void scan_with_a_full_quad_finds_the_inserted_row() {
        for (QuadOrder order : QuadOrder.values()) {
            QuadIndex idx = fresh(order);
            idx.insert(S, P, O, C);
            idx.commit();
            Iterator<SpocKey> it = idx.scan(S, P, O, C);
            boolean found = false;
            while (it.hasNext()) {
                if (it.next().equals(order.keyOf(S, P, O, C))) found = true;
            }
            assertTrue(found, order + ": a fully-bound scan must surface the inserted quad");
        }
    }

    // ---- commit / reopen ----

    @Test
    void commit_then_reopen_preserves_the_index_contents() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();

        QuadIndex first = new QuadIndex(QuadOrder.POSC, store, pool);
        first.insert(S, P, O, C);
        first.insert(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4));
        StaticMap committed = first.commit();

        QuadIndex reopened = new QuadIndex(QuadOrder.POSC, store, pool, committed);
        assertTrue(
                reopened.contains(S, P, O, C),
                "a committed quad must survive reopen from the committed StaticMap");
        assertTrue(reopened.contains(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4)));
        assertFalse(reopened.contains(TermId.of(9), TermId.of(9), TermId.of(9), TermId.of(9)));
    }
}
