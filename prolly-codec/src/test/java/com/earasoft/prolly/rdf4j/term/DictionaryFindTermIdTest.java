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
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link Dictionary#findTermId} — the read-only counterpart to {@code encode}. It
 * walks the same salt chain but never writes; it is the lookup primitive for snapshot-mode reads
 * (provenance, etc.). {@code DictionaryTest} exhaustively covers {@code encode}/{@code decode} but
 * never exercises {@code findTermId}.
 *
 * <p>The collision-chain cases use a degenerate constant {@link HashFunction} — the same
 * real-implementation fixture {@code DictionaryTest} uses to force escalation deterministically
 * (not a mock of the Dictionary).
 */
class DictionaryFindTermIdTest {

    /** Constant hash → every term/salt collapses to one address; forces escalation. */
    private static final class FixedHash implements HashFunction {
        private final long fixed;

        FixedHash(long v) {
            this.fixed = v;
        }

        @Override
        public long hash(MemorySegment data) {
            return fixed;
        }

        @Override
        public String name() {
            return "fixed-" + Long.toHexString(fixed);
        }
    }

    private final NodeStore store = new InMemoryNodeStore();
    private final BufferPool pool = new HeapBufferPool();

    private Dictionary fresh() {
        return new Dictionary(store, pool, HashFunctions.defaultHash());
    }

    // ---- consistency with encode ----

    @Test
    void findTermId_returns_the_same_id_that_encode_assigned() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            for (long n = 0; n < 200; n++) {
                MemorySegment term = TermCodec.encodeInteger(n, a);
                TermId encoded = d.encode(term);
                Optional<TermId> found = d.findTermId(term);
                assertTrue(found.isPresent(), "an encoded term must be findable");
                assertEquals(
                        encoded,
                        found.get(),
                        "findTermId must return exactly the id encode assigned");
            }
        }
    }

    @Test
    void findTermId_of_a_never_encoded_term_is_empty() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            d.encode(TermCodec.encodeInteger(1L, a));
            assertTrue(
                    d.findTermId(TermCodec.encodeInteger(999_999L, a)).isEmpty(),
                    "a term that was never encoded must not be found");
        }
    }

    // ---- read-only: no mutation ----

    @Test
    void findTermId_does_not_insert_the_term_it_looks_up() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            MemorySegment term = TermCodec.encodeXsdString("snapshot-probe", a);

            // Probe a missing term twice — must stay missing (no side effect).
            assertTrue(d.findTermId(term).isEmpty());
            assertTrue(
                    d.findTermId(term).isEmpty(),
                    "repeated findTermId of an absent term must keep returning empty");

            // Only encode actually inserts; afterwards findTermId resolves it.
            TermId id = d.encode(term);
            assertEquals(
                    Optional.of(id),
                    d.findTermId(term),
                    "after encode, findTermId resolves the term — proving the earlier "
                            + "probes never wrote it");
        }
    }

    // ---- salt-chain walk ----

    @Test
    void findTermId_resolves_a_collision_escalated_extension_term() {
        try (Arena a = Arena.ofConfined()) {
            // FixedHash collapses every salt to one slot: term A lands natural,
            // term B escalates to an extension slot.
            Dictionary d = new Dictionary(store, pool, new FixedHash(0L));
            MemorySegment termA = TermCodec.encodeInteger(1L, a);
            MemorySegment termB = TermCodec.encodeInteger(2L, a);
            TermId idA = d.encode(termA);
            TermId idB = d.encode(termB);
            assertFalse(idA.isExtension(), "first term takes the natural slot");
            assertTrue(idB.isExtension(), "second term escalates to an extension slot");

            assertEquals(
                    Optional.of(idA),
                    d.findTermId(termA),
                    "findTermId resolves the natural-slot term");
            assertEquals(
                    Optional.of(idB),
                    d.findTermId(termB),
                    "findTermId walks the salt chain to resolve the escalated term");
        }
    }

    @Test
    void findTermId_walks_a_fully_occupied_chain_to_empty_for_an_absent_term() {
        try (Arena a = Arena.ofConfined()) {
            // Under FixedHash the chain fills after two terms; a third, never
            // encoded, must still resolve to empty after walking past the
            // byte-different occupants of every salt.
            Dictionary d = new Dictionary(store, pool, new FixedHash(0L));
            d.encode(TermCodec.encodeInteger(1L, a));
            d.encode(TermCodec.encodeInteger(2L, a));

            MemorySegment absent = TermCodec.encodeInteger(3L, a);
            assertTrue(
                    d.findTermId(absent).isEmpty(),
                    "an absent term must be reported empty even when every salt slot "
                            + "is occupied by a byte-different term");
        }
    }

    // ---- across commit ----

    /**
     * Delegating store that counts reads — the only way to observe probe COUNT, not just answer.
     */
    private static final class CountingStore implements NodeStore {
        private final NodeStore delegate;
        long reads;

        CountingStore(NodeStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            reads++;
            return delegate.read(hash);
        }

        @Override
        public byte[] write(MemorySegment data) {
            return delegate.write(data);
        }

        @Override
        public byte[] write(byte[] data) {
            return delegate.write(data);
        }
    }

    /**
     * An absent term must stop at the FIRST EMPTY salt slot, not walk all {@code MAX_SALT} of them.
     *
     * <p>{@code encode} places a term at the first empty-or-matching slot, so if salt 0 is empty
     * the term was never inserted at any salt — and {@link Dictionary} exposes no delete, so no
     * chain can be broken by a removal. Continuing past an empty slot therefore cannot find
     * anything; it just costs 64x.
     *
     * <p>Asserted as a RATIO against a hit rather than an absolute read count, so the test does not
     * encode the tree's depth and keeps meaning as the dictionary grows. Measured on the real NCIt
     * dictionary before this was fixed, an all-miss probe cost 2,081.9 us against 365.4 us for the
     * salt-0-only equivalent — 5.7x — and 49x the block reads of a hit ({@code
     * quarkus-ontology-editor: docs/benchmarks/prolly-bloom-proof.md}).
     */
    @Test
    void findTermId_of_an_absent_term_stops_at_the_first_empty_slot() {
        try (Arena a = Arena.ofConfined()) {
            CountingStore counting = new CountingStore(new InMemoryNodeStore());
            Dictionary d1 = new Dictionary(counting, pool, HashFunctions.defaultHash());
            // Enough terms that the dictionary tree has real depth. A one-entry tree is a single
            // root node the StaticMap already holds, so NOTHING is ever read from the store and
            // the comparison below is vacuously true at 0 == 0 — which is exactly how the first
            // version of this test passed against the unfixed code.
            MemorySegment present = null;
            for (int i = 0; i < 5_000; i++) {
                MemorySegment term = TermCodec.encodeAnyURI("http://example.org/t/" + i, a);
                d1.encode(term);
                if (i == 2_500) {
                    present = term;
                }
            }
            StaticMap committed = d1.commit();

            Dictionary d2 = new Dictionary(counting, pool, HashFunctions.defaultHash(), committed);
            MemorySegment absent = TermCodec.encodeAnyURI("http://example.org/never-encoded", a);

            counting.reads = 0;
            assertTrue(d2.findTermId(present).isPresent(), "guard: the present term must be found");
            long hitReads = counting.reads;

            counting.reads = 0;
            assertTrue(d2.findTermId(absent).isEmpty(), "guard: the absent term must not be found");
            long missReads = counting.reads;

            assertTrue(
                    hitReads > 0,
                    "guard: a hit must read at least one node, or this test "
                            + "compares 0 against 0 and cannot fail");
            assertTrue(
                    missReads <= hitReads * 2,
                    "a miss must stop at the first empty slot, not walk every salt: "
                            + missReads
                            + " store reads for a miss against "
                            + hitReads
                            + " for a hit");
        }
    }

    @Test
    void findTermId_resolves_terms_on_a_reopened_committed_dictionary() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d1 = fresh();
            MemorySegment term = TermCodec.encodeAnyURI("http://example.org/snap", a);
            TermId id = d1.encode(term);
            StaticMap committed = d1.commit();

            Dictionary d2 = new Dictionary(store, pool, HashFunctions.defaultHash(), committed);
            assertEquals(
                    Optional.of(id),
                    d2.findTermId(term),
                    "findTermId on a dictionary reopened at a committed root must "
                            + "resolve terms committed before the reopen");
        }
    }
}
