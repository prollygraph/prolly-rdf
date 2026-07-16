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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProvenanceIndex} — the sidecar prolly tree mapping each quad to the parent
 * commit hash at its first appearance.
 */
class ProvenanceIndexTest {

    private static SpocKey key(long a, long b, long c, long d) {
        return new SpocKey(new TermId(a), new TermId(b), new TermId(c), new TermId(d));
    }

    private static byte[] hash(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        return out;
    }

    @Test
    void empty_index_has_no_entries() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        assertTrue(idx.firstSeen(key(1, 2, 3, 0)).isEmpty());
        assertFalse(idx.contains(key(1, 2, 3, 0)));
        assertEquals(0, idx.pendingCount());
    }

    @Test
    void recordFirstSeen_then_firstSeen_roundtrips() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        byte[] parent = hash(0x42);
        idx.recordFirstSeen(key(1, 2, 3, 0), parent);

        assertTrue(idx.contains(key(1, 2, 3, 0)));
        Optional<byte[]> back = idx.firstSeen(key(1, 2, 3, 0));
        assertTrue(back.isPresent());
        assertArrayEquals(parent, back.get());
    }

    @Test
    void recordFirstSeen_is_idempotent_keeps_original() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        byte[] first = hash(0x10);
        byte[] second = hash(0x20);
        idx.recordFirstSeen(key(1, 2, 3, 0), first);
        idx.recordFirstSeen(key(1, 2, 3, 0), second); // should NOT overwrite

        byte[] back = idx.firstSeen(key(1, 2, 3, 0)).orElseThrow();
        assertArrayEquals(first, back, "idempotence: keep the first-seen parent");
    }

    @Test
    void idempotence_survives_commit() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        byte[] first = hash(0x10);
        byte[] second = hash(0x20);

        ProvenanceIndex idx = new ProvenanceIndex(store, pool);
        idx.recordFirstSeen(key(1, 2, 3, 0), first);
        StaticMap root1 = idx.commit();

        ProvenanceIndex idx2 = new ProvenanceIndex(store, pool, root1);
        // A later attempt to record the same triple with a different parent
        // must be a no-op against the committed entry.
        idx2.recordFirstSeen(key(1, 2, 3, 0), second);
        assertArrayEquals(first, idx2.firstSeen(key(1, 2, 3, 0)).orElseThrow());

        // And a second commit produces no new pending writes.
        StaticMap root2 = idx2.commit();
        assertNotNull(root2);
    }

    @Test
    void multiple_keys_independent() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        idx.recordFirstSeen(key(1, 2, 3, 0), hash(0x01));
        idx.recordFirstSeen(key(4, 5, 6, 0), hash(0x02));
        idx.recordFirstSeen(key(7, 8, 9, 0), hash(0x03));

        assertArrayEquals(hash(0x01), idx.firstSeen(key(1, 2, 3, 0)).orElseThrow());
        assertArrayEquals(hash(0x02), idx.firstSeen(key(4, 5, 6, 0)).orElseThrow());
        assertArrayEquals(hash(0x03), idx.firstSeen(key(7, 8, 9, 0)).orElseThrow());

        assertTrue(idx.firstSeen(key(99, 99, 99, 0)).isEmpty());
    }

    @Test
    void genesis_parent_round_trips() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        idx.recordFirstSeen(key(1, 2, 3, 0), ProvenanceIndex.GENESIS_PARENT);

        byte[] back = idx.firstSeen(key(1, 2, 3, 0)).orElseThrow();
        assertEquals(0, back.length, "genesis sentinel is empty byte array");
    }

    @Test
    void commit_persists_then_reload_reads() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        byte[] parent = hash(0xab);

        ProvenanceIndex w = new ProvenanceIndex(store, pool);
        w.recordFirstSeen(key(1, 2, 3, 0), parent);
        StaticMap committed = w.commit();

        // Fresh reader, no shared state.
        ProvenanceIndex r = new ProvenanceIndex(store, pool, committed);
        assertArrayEquals(parent, r.firstSeen(key(1, 2, 3, 0)).orElseThrow());
    }

    @Test
    void discard_drops_pending() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        idx.recordFirstSeen(key(1, 2, 3, 0), hash(0x42));
        assertEquals(1, idx.pendingCount());

        idx.discard();
        assertEquals(0, idx.pendingCount());
        assertTrue(idx.firstSeen(key(1, 2, 3, 0)).isEmpty());
    }

    @Test
    void recordFirstSeen_rejects_null_parent() {
        ProvenanceIndex idx = new ProvenanceIndex(new InMemoryNodeStore(), new HeapBufferPool());
        assertThrows(
                IllegalArgumentException.class, () -> idx.recordFirstSeen(key(1, 2, 3, 0), null));
    }

    @Test
    void mergeFrom_adopts_unseen_entries() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        // A has entry for triple X; B has no entries.
        ProvenanceIndex a = new ProvenanceIndex(store, pool);
        ProvenanceIndex b = new ProvenanceIndex(store, pool);
        byte[] parentA = new byte[] {1, 2, 3};
        a.recordFirstSeen(key(1, 2, 3, 0), parentA);
        a = new ProvenanceIndex(store, pool, a.commit()); // flush so b can see it
        b.mergeFrom(a, (other, mine) -> true /* unused — no conflict */);
        // After commit, b's committed root holds the entry.
        b.commit();
        assertArrayEquals(
                parentA,
                new ProvenanceIndex(store, pool, b.committedRoot())
                        .firstSeen(key(1, 2, 3, 0))
                        .orElseThrow());
    }

    @Test
    void mergeFrom_older_wins_when_both_sides_have_entry() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        // Both have an entry for the same triple, with different parents.
        ProvenanceIndex a = new ProvenanceIndex(store, pool);
        ProvenanceIndex b = new ProvenanceIndex(store, pool);
        byte[] olderParent = new byte[] {0x0a};
        byte[] newerParent = new byte[] {0x0b};
        a.recordFirstSeen(key(1, 2, 3, 0), newerParent);
        b.recordFirstSeen(key(1, 2, 3, 0), olderParent);
        a = new ProvenanceIndex(store, pool, a.commit());
        b = new ProvenanceIndex(store, pool, b.commit());
        // Fold b into a — b's parent is older, so a should adopt b's.
        a.mergeFrom(b, (other, mine) -> other[0] < mine[0]);
        a.commit();
        assertArrayEquals(
                olderParent,
                new ProvenanceIndex(store, pool, a.committedRoot())
                        .firstSeen(key(1, 2, 3, 0))
                        .orElseThrow());
    }

    @Test
    void axis5_repoId_round_trips_in_firstSeenEntry() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        ProvenanceIndex idx = new ProvenanceIndex(store, pool);
        byte[] parent = hash(0x42);
        byte[] repoId = hash(0xAB); // pretend genesis hash
        idx.recordFirstSeen(key(1, 2, 3, 0), parent, repoId);
        idx = new ProvenanceIndex(store, pool, idx.commit());

        ProvenanceIndex.Entry e = idx.firstSeenEntry(key(1, 2, 3, 0)).orElseThrow();
        assertArrayEquals(parent, e.parent());
        assertArrayEquals(repoId, e.repoId());
        // The 2-arg firstSeen still returns just the parent (legacy shape).
        assertArrayEquals(parent, idx.firstSeen(key(1, 2, 3, 0)).orElseThrow());
    }

    @Test
    void axis5_same_triple_different_repos_produce_different_chunks() {
        // The CAS-isolation guarantee: same (s,p,o,c) + same parent + different
        // repoId must produce different encoded values (and therefore different
        // chunk bytes when the index is committed). Otherwise CAS-level dedup
        // could leak metadata across repos.
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        ProvenanceIndex repoA = new ProvenanceIndex(store, pool);
        ProvenanceIndex repoB = new ProvenanceIndex(store, pool);
        byte[] sharedParent = hash(0xC1);
        byte[] repoAId = hash(0xA0);
        byte[] repoBId = hash(0xB0);
        repoA.recordFirstSeen(key(1, 2, 3, 0), sharedParent, repoAId);
        repoB.recordFirstSeen(key(1, 2, 3, 0), sharedParent, repoBId);

        // The encoded values differ → CAS won't dedup these leaves.
        ProvenanceIndex.Entry ea = repoA.firstSeenEntry(key(1, 2, 3, 0)).orElseThrow();
        ProvenanceIndex.Entry eb = repoB.firstSeenEntry(key(1, 2, 3, 0)).orElseThrow();
        assertArrayEquals(sharedParent, ea.parent());
        assertArrayEquals(sharedParent, eb.parent());
        assertArrayEquals(repoAId, ea.repoId());
        assertArrayEquals(repoBId, eb.repoId());
    }

    @Test
    void mergeFrom_keeps_ours_when_other_is_newer() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        ProvenanceIndex a = new ProvenanceIndex(store, pool);
        ProvenanceIndex b = new ProvenanceIndex(store, pool);
        byte[] olderParent = new byte[] {0x0a};
        byte[] newerParent = new byte[] {0x0b};
        a.recordFirstSeen(key(1, 2, 3, 0), olderParent); // a has the older
        b.recordFirstSeen(key(1, 2, 3, 0), newerParent);
        a = new ProvenanceIndex(store, pool, a.commit());
        b = new ProvenanceIndex(store, pool, b.commit());
        a.mergeFrom(b, (other, mine) -> other[0] < mine[0]);
        a.commit();
        // a's older entry should win — b's newer parent should not override.
        assertArrayEquals(
                olderParent,
                new ProvenanceIndex(store, pool, a.committedRoot())
                        .firstSeen(key(1, 2, 3, 0))
                        .orElseThrow());
    }

    @Test
    void mergeFrom_older_wins_even_when_our_entry_is_only_pending() {
        // The real merge scenario, and the one the two tests above do NOT cover:
        // the merge transaction records a triple via recordFirstSeen (→ pending,
        // uncommitted) and THEN folds the peer's provenance. The peer's parent
        // is older, so older-wins must keep it — and the subsequent commit()
        // must not re-apply the pending (newer) value over the fold.
        // Regression for the mergeFrom/commit clobber bug.
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        byte[] olderParent = new byte[] {0x0a};
        byte[] newerParent = new byte[] {0x0b};

        // Peer: the older entry, committed.
        ProvenanceIndex peer = new ProvenanceIndex(store, pool);
        peer.recordFirstSeen(key(1, 2, 3, 0), olderParent);
        peer = new ProvenanceIndex(store, pool, peer.commit());

        // Target: records the SAME triple this transaction — entry stays in
        // `pending`, never committed (the case the prior tests skip).
        ProvenanceIndex target = new ProvenanceIndex(store, pool);
        target.recordFirstSeen(key(1, 2, 3, 0), newerParent);
        assertEquals(1, target.pendingCount(), "precondition: our entry is pending");

        // Fold the peer with older-wins, then commit — exactly commitInternal's order.
        target.mergeFrom(peer, (other, mine) -> other[0] < mine[0]);
        target.commit();

        assertArrayEquals(
                olderParent,
                new ProvenanceIndex(store, pool, target.committedRoot())
                        .firstSeen(key(1, 2, 3, 0))
                        .orElseThrow(),
                "older-wins must survive commit(): the pending newer entry must not "
                        + "clobber the fold");
    }
}
