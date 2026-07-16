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
import org.junit.jupiter.api.Test;

/**
 * Edge-case coverage for {@link ProvenanceIndex} APIs that the existing happy-path tests don't
 * directly exercise — {@code contains}, {@code pendingCount}, {@code committedRoot}, {@code
 * parentEquals}, and the {@code GENESIS_PARENT} sentinel.
 */
class ProvenanceIndexEdgeTest {

    private static ProvenanceIndex freshIndex() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        return new ProvenanceIndex(store, pool);
    }

    private static SpocKey key(long s, long p, long o, long c) {
        return new SpocKey(TermId.of(s), TermId.of(p), TermId.of(o), TermId.of(c));
    }

    // ---- GENESIS_PARENT ----

    @Test
    void genesis_parent_is_empty_byte_array() {
        assertNotNull(ProvenanceIndex.GENESIS_PARENT);
        assertEquals(
                0,
                ProvenanceIndex.GENESIS_PARENT.length,
                "GENESIS_PARENT is empty bytes — the sentinel for 'no prior commit'");
    }

    // ---- contains ----

    @Test
    void contains_false_on_empty_index() {
        ProvenanceIndex idx = freshIndex();
        assertFalse(idx.contains(key(1, 2, 3, 4)));
    }

    @Test
    void contains_true_after_recordFirstSeen() {
        ProvenanceIndex idx = freshIndex();
        SpocKey k = key(1, 2, 3, 4);
        idx.recordFirstSeen(k, new byte[] {0x42});
        assertTrue(idx.contains(k), "contains() must see uncommitted (pending) entries");
    }

    @Test
    void contains_survives_commit() {
        ProvenanceIndex idx = freshIndex();
        SpocKey k = key(1, 2, 3, 4);
        idx.recordFirstSeen(k, new byte[] {0x42});
        idx.commit();
        assertTrue(idx.contains(k));
    }

    @Test
    void contains_distinguishes_unseen_keys() {
        ProvenanceIndex idx = freshIndex();
        idx.recordFirstSeen(key(1, 2, 3, 4), new byte[] {0x42});
        assertFalse(idx.contains(key(9, 9, 9, 9)));
    }

    // ---- pendingCount ----

    @Test
    void pendingCount_zero_initially() {
        assertEquals(0, freshIndex().pendingCount());
    }

    @Test
    void pendingCount_increments_per_distinct_key() {
        ProvenanceIndex idx = freshIndex();
        idx.recordFirstSeen(key(1, 1, 1, 1), new byte[] {0x01});
        idx.recordFirstSeen(key(2, 2, 2, 2), new byte[] {0x02});
        idx.recordFirstSeen(key(3, 3, 3, 3), new byte[] {0x03});
        assertEquals(3, idx.pendingCount());
    }

    @Test
    void pendingCount_idempotent_for_same_key() {
        // first-seen-wins: repeated recordFirstSeen for same key is a no-op.
        ProvenanceIndex idx = freshIndex();
        SpocKey k = key(1, 2, 3, 4);
        idx.recordFirstSeen(k, new byte[] {0x01});
        idx.recordFirstSeen(k, new byte[] {0x02});
        idx.recordFirstSeen(k, new byte[] {0x03});
        assertEquals(
                1,
                idx.pendingCount(),
                "first-seen-wins: redundant records must not grow the pending buffer");
    }

    @Test
    void pendingCount_zero_after_commit() {
        ProvenanceIndex idx = freshIndex();
        idx.recordFirstSeen(key(1, 1, 1, 1), new byte[] {0x01});
        idx.commit();
        assertEquals(0, idx.pendingCount(), "commit must drain the pending buffer");
    }

    @Test
    void pendingCount_zero_after_discard() {
        ProvenanceIndex idx = freshIndex();
        idx.recordFirstSeen(key(1, 1, 1, 1), new byte[] {0x01});
        idx.recordFirstSeen(key(2, 2, 2, 2), new byte[] {0x02});
        idx.discard();
        assertEquals(0, idx.pendingCount(), "discard must drop pending entries");
    }

    // ---- committedRoot ----

    @Test
    void committedRoot_initially_returns_something_inspectable() {
        ProvenanceIndex idx = freshIndex();
        StaticMap root = idx.committedRoot();
        // Initial root is well-defined (StaticMap with null Node), not null.
        assertNotNull(root);
    }

    @Test
    void committedRoot_advances_after_commit() {
        ProvenanceIndex idx = freshIndex();
        idx.recordFirstSeen(key(1, 2, 3, 4), new byte[] {0x42});
        StaticMap before = idx.committedRoot();
        idx.commit();
        StaticMap after = idx.committedRoot();
        assertNotSame(before, after, "commit must produce a new StaticMap reference");
    }

    @Test
    void committedRoot_stable_across_pending_writes() {
        ProvenanceIndex idx = freshIndex();
        StaticMap initial = idx.committedRoot();
        idx.recordFirstSeen(key(1, 2, 3, 4), new byte[] {0x42});
        // Pending write must NOT change committedRoot — only commit does.
        assertSame(
                initial,
                idx.committedRoot(),
                "committedRoot reflects only committed state, not pending writes");
    }

    // ---- parentEquals ----

    @Test
    void parentEquals_byte_content() {
        assertTrue(ProvenanceIndex.parentEquals(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
    }

    @Test
    void parentEquals_different_bytes() {
        assertFalse(ProvenanceIndex.parentEquals(new byte[] {1, 2, 3}, new byte[] {4, 5, 6}));
    }

    @Test
    void parentEquals_different_lengths() {
        assertFalse(ProvenanceIndex.parentEquals(new byte[] {1, 2}, new byte[] {1, 2, 3}));
    }

    @Test
    void parentEquals_empty_byte_arrays() {
        assertTrue(
                ProvenanceIndex.parentEquals(new byte[0], new byte[0]),
                "GENESIS_PARENT vs GENESIS_PARENT must compare equal");
    }

    @Test
    void parentEquals_handles_null() {
        // Arrays.equals(null, null) returns true; Arrays.equals(arr, null) returns false.
        assertTrue(ProvenanceIndex.parentEquals(null, null));
        assertFalse(ProvenanceIndex.parentEquals(new byte[] {1}, null));
        assertFalse(ProvenanceIndex.parentEquals(null, new byte[] {1}));
    }

    @Test
    void parentEquals_genesis_with_genesis() {
        assertTrue(
                ProvenanceIndex.parentEquals(
                        ProvenanceIndex.GENESIS_PARENT, ProvenanceIndex.GENESIS_PARENT));
        assertTrue(
                ProvenanceIndex.parentEquals(ProvenanceIndex.GENESIS_PARENT, new byte[0]),
                "GENESIS_PARENT compares equal to any zero-length array");
    }

    // ---- Entry record ----

    @Test
    void entry_carries_parent_and_repoId() {
        ProvenanceIndex.Entry e = new ProvenanceIndex.Entry(new byte[] {1, 2}, new byte[] {3, 4});
        assertArrayEquals(new byte[] {1, 2}, e.parent());
        assertArrayEquals(new byte[] {3, 4}, e.repoId());
    }

    @Test
    void entry_with_null_repoId_allowed() {
        ProvenanceIndex.Entry e = new ProvenanceIndex.Entry(new byte[] {1}, null);
        assertNull(e.repoId(), "null repoId is the v1-record-format sentinel for 'unrooted'");
    }
}
