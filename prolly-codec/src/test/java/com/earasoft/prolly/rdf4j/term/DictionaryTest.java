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
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class DictionaryTest {

    private NodeStore store() {
        return new InMemoryNodeStore();
    }

    private BufferPool pool() {
        return new HeapBufferPool();
    }

    private Dictionary fresh() {
        return new Dictionary(store(), pool(), HashFunctions.defaultHash());
    }

    @Test
    void empty_decode_returns_empty() {
        Dictionary d = fresh();
        assertTrue(d.decode(TermId.of(0L)).isEmpty());
        assertTrue(d.decode(TermId.of(42L)).isEmpty());
    }

    @Test
    void encode_then_decode_round_trip_simple() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            MemorySegment term = TermCodec.encodeInteger(42L, a);
            TermId id = d.encode(term);
            MemorySegment back = d.decode(id).orElseThrow();
            assertEquals(0, Compare.compareUnsigned(term, back));
        }
    }

    @Test
    void encoding_same_term_twice_returns_same_id() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            MemorySegment t1 = TermCodec.encodeInteger(42L, a);
            MemorySegment t2 = TermCodec.encodeInteger(42L, a);
            TermId id1 = d.encode(t1);
            TermId id2 = d.encode(t2);
            assertEquals(id1, id2);
        }
    }

    @Test
    void distinct_terms_get_distinct_ids() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            TermId i1 = d.encode(TermCodec.encodeInteger(1L, a));
            TermId i2 = d.encode(TermCodec.encodeInteger(2L, a));
            assertNotEquals(i1, i2);
        }
    }

    @Test
    void natural_id_top_bit_is_clear() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            for (long v : new long[] {0L, 1L, 42L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                TermId id = d.encode(TermCodec.encodeInteger(v, a));
                assertFalse(
                        id.isExtension(),
                        "encoded TermId for " + v + " has extension flag set: " + id);
            }
        }
    }

    @Test
    void encode_many_distinct_terms_all_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            int N = 500;
            TermId[] ids = new TermId[N];
            for (int i = 0; i < N; i++) {
                ids[i] = d.encode(TermCodec.encodeXsdString("term-" + i, a));
            }
            // All distinct
            Set<TermId> unique = new HashSet<>();
            for (TermId id : ids) unique.add(id);
            assertEquals(N, unique.size(), "duplicate TermIds across distinct terms");
            // All decode to the right bytes
            for (int i = 0; i < N; i++) {
                MemorySegment expected = TermCodec.encodeXsdString("term-" + i, a);
                MemorySegment actual = d.decode(ids[i]).orElseThrow();
                assertEquals(
                        0, Compare.compareUnsigned(expected, actual), "decode mismatch at i=" + i);
            }
        }
    }

    @Test
    void mixed_term_kinds_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            MemorySegment[] terms = {
                TermCodec.encodeBoolean("true", a),
                TermCodec.encodeInteger(42L, a),
                TermCodec.encodeFloat64(3.14, a),
                TermCodec.encodeXsdString("hello", a),
                TermCodec.encodeLangString("Hello", "en", a),
                TermCodec.encodeAnyURI("http://example.com/foo", a),
                TermCodec.encodeFullIri("http://schema.org/Person", a),
                TermCodec.encodeShortPrefixIri(4, "string", a),
                TermCodec.encodeBNodeLabel("b1", a),
                TermCodec.encodeBNodeCanon(0, a),
                TermCodec.encodeUuid(
                        java.util.UUID.fromString("12345678-1234-5678-1234-567812345678"), a),
                TermCodec.encodeQuotedTriple(TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a),
                TermCodec.encodeCustomLiteral(TermId.of(99L), "xyz", a),
            };
            TermId[] ids = new TermId[terms.length];
            for (int i = 0; i < terms.length; i++) {
                ids[i] = d.encode(terms[i]);
            }
            // Distinct ids (extremely unlikely 2-of-13 collision under FNV)
            assertEquals(terms.length, new HashSet<>(java.util.Arrays.asList(ids)).size());
            // Round-trip each
            for (int i = 0; i < terms.length; i++) {
                MemorySegment back = d.decode(ids[i]).orElseThrow();
                assertEquals(
                        0,
                        Compare.compareUnsigned(terms[i], back),
                        "round-trip failed for term " + i);
            }
        }
    }

    @Test
    void decode_unknown_id_returns_empty() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            d.encode(TermCodec.encodeInteger(42L, a));
            // A TermId we never inserted
            assertTrue(d.decode(TermId.of(0xDEAD_BEEF_FEED_FACEL)).isEmpty());
        }
    }

    @Test
    void encode_does_not_mutate_input_segment() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            MemorySegment term = TermCodec.encodeInteger(42L, a);
            byte[] before = term.toArray(Layouts.BYTE);
            d.encode(term);
            byte[] after = term.toArray(Layouts.BYTE);
            assertArrayEquals(before, after);
        }
    }

    @Test
    void decoded_bytes_independent_of_caller_arena() {
        // Encode a term using a confined arena; close the arena; decode should
        // still work because the dictionary copies the bytes at insert time.
        Dictionary d = fresh();
        TermId id;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment term = TermCodec.encodeInteger(42L, a);
            id = d.encode(term);
        }
        // Arena closed — but dictionary holds its own copy.
        MemorySegment back = d.decode(id).orElseThrow();
        assertEquals("42", TermCodec.decodeLexical(TermCodec.payloadOf(back)));
    }

    // -------------------------------------------------------------
    // Commit cycle
    // -------------------------------------------------------------

    @Test
    void commit_returns_non_empty_static_map_after_inserts() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            d.encode(TermCodec.encodeInteger(1L, a));
            d.encode(TermCodec.encodeInteger(2L, a));
            StaticMap committed = d.commit();
            assertNotNull(committed);
            assertNotNull(committed.root()); // non-empty tree
        }
    }

    @Test
    void reads_work_across_commit() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            TermId id = d.encode(TermCodec.encodeXsdString("alpha", a));
            d.commit();
            MemorySegment back = d.decode(id).orElseThrow();
            assertEquals(0, Compare.compareUnsigned(TermCodec.encodeXsdString("alpha", a), back));
        }
    }

    @Test
    void reopen_at_committed_root_preserves_data() {
        NodeStore store = store();
        BufferPool pool = pool();
        HashFunction hf = HashFunctions.defaultHash();
        TermId id;
        StaticMap committed;
        try (Arena a = Arena.ofConfined()) {
            Dictionary d1 = new Dictionary(store, pool, hf);
            id = d1.encode(TermCodec.encodeXsdString("survive", a));
            committed = d1.commit();
        }
        // Re-open against the same store and committed root with a fresh Dictionary
        Dictionary d2 = new Dictionary(store, pool, hf, committed);
        MemorySegment back = d2.decode(id).orElseThrow();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment expected = TermCodec.encodeXsdString("survive", a);
            assertEquals(0, Compare.compareUnsigned(expected, back));
        }
    }

    @Test
    void empty_commit_is_safe_noop() {
        Dictionary d = fresh();
        StaticMap committed = d.commit();
        assertNotNull(committed);
        // No data ever inserted
        try (Arena a = Arena.ofConfined()) {
            TermId id = d.encode(TermCodec.encodeInteger(1L, a));
            assertNotNull(d.decode(id).orElse(null));
        }
    }

    @Test
    void multiple_commit_cycles() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            TermId id1 = d.encode(TermCodec.encodeInteger(1L, a));
            d.commit();
            TermId id2 = d.encode(TermCodec.encodeInteger(2L, a));
            d.commit();
            TermId id3 = d.encode(TermCodec.encodeInteger(3L, a));
            // All three should be readable
            assertTrue(d.decode(id1).isPresent());
            assertTrue(d.decode(id2).isPresent());
            assertTrue(d.decode(id3).isPresent());
        }
    }

    // -------------------------------------------------------------
    // Collision handling — uses a fake HashFunction
    // -------------------------------------------------------------

    private static class FixedHash implements HashFunction {
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

    @Test
    void same_bytes_under_fixed_hash_dedupes_at_salt_zero() {
        // Two encodings of the same value SHOULD dedupe at salt 0 (byte-equal).
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L));
            TermId id1 = d.encode(TermCodec.encodeInteger(42L, a));
            TermId id2 = d.encode(TermCodec.encodeInteger(42L, a));
            assertEquals(id1, id2);
            assertFalse(id1.isExtension(), "first insertion uses natural salt=0");
        }
    }

    @Test
    void distinct_bytes_under_fixed_hash_escalate_to_extension() {
        // FixedHash returns the same value for any input. Salt 0 collides for any
        // pair of byte-different terms; salt 1 also returns the same value but the
        // TermId is computed via ofExtensionSlot, so the address is in extension
        // space — distinct from the natural slot.
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L));
            TermId id1 = d.encode(TermCodec.encodeInteger(1L, a));
            TermId id2 = d.encode(TermCodec.encodeInteger(2L, a));
            assertFalse(id1.isExtension(), "first insertion is natural");
            assertTrue(id2.isExtension(), "second (collision) escalates to extension");
            assertNotEquals(id1, id2);
        }
    }

    @Test
    void escalated_term_re_encodes_to_same_extension_id() {
        // Re-encoding the same byte sequence must traverse the same salt chain
        // and return the same extension TermId.
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L));
            d.encode(TermCodec.encodeInteger(1L, a));
            TermId first = d.encode(TermCodec.encodeInteger(2L, a));
            TermId second = d.encode(TermCodec.encodeInteger(2L, a));
            assertEquals(first, second);
        }
    }

    @Test
    void chain_exhaustion_throws() {
        // maxSalt=2 + FixedHash(0): can fit at most 2 distinct terms (natural + 1 extension).
        // The third triggers CollisionChainExhausted.
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L), 2);
            d.encode(TermCodec.encodeInteger(1L, a));
            d.encode(TermCodec.encodeInteger(2L, a));
            assertThrows(
                    Dictionary.CollisionChainExhausted.class,
                    () -> d.encode(TermCodec.encodeInteger(3L, a)));
        }
    }

    @Test
    void escalated_entries_decode_correctly() {
        // FixedHash collapses all salts to the same slot 0, so only 2 distinct
        // entries (natural + 1 extension) fit. That's enough to verify that both
        // the natural and extension entries decode through the same lookup path.
        // (Engineering "3+ distinct extension slots" requires a salt-aware hash
        // function — fragile; the algorithm is uniform so 2 is sufficient.)
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L));
            TermId id1 = d.encode(TermCodec.encodeInteger(1L, a));
            TermId id2 = d.encode(TermCodec.encodeInteger(2L, a));
            assertFalse(id1.isExtension());
            assertTrue(id2.isExtension());
            assertEquals(
                    "1", TermCodec.decodeLexical(TermCodec.payloadOf(d.decode(id1).orElseThrow())));
            assertEquals(
                    "2", TermCodec.decodeLexical(TermCodec.payloadOf(d.decode(id2).orElseThrow())));
        }
    }

    @Test
    void collision_does_not_corrupt_natural_entry() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L));
            TermId natural = d.encode(TermCodec.encodeInteger(1L, a));
            d.encode(TermCodec.encodeInteger(2L, a)); // escalates
            // Natural entry still decodes to its original value
            MemorySegment back = d.decode(natural).orElseThrow();
            assertEquals("1", TermCodec.decodeLexical(TermCodec.payloadOf(back)));
        }
    }

    @Test
    void escalation_survives_commit_cycle() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = new Dictionary(store(), pool(), new FixedHash(0L));
            TermId nat = d.encode(TermCodec.encodeInteger(1L, a));
            TermId ext = d.encode(TermCodec.encodeInteger(2L, a));
            d.commit();
            assertEquals(
                    "1", TermCodec.decodeLexical(TermCodec.payloadOf(d.decode(nat).orElseThrow())));
            assertEquals(
                    "2", TermCodec.decodeLexical(TermCodec.payloadOf(d.decode(ext).orElseThrow())));
            // Re-encoding finds the same extension entry post-commit
            TermId rt = d.encode(TermCodec.encodeInteger(2L, a));
            assertEquals(ext, rt);
        }
    }

    @Test
    void no_collision_under_real_hash_function() {
        // With FNV-1a, encoding many distinct values should not trigger
        // escalation for any of them (collision rate ≈ 0 at this scale).
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            for (int i = 0; i < 10_000; i++) {
                TermId id = d.encode(TermCodec.encodeXsdString("term-" + i, a));
                assertFalse(
                        id.isExtension(),
                        "real hash should not escalate at scale 10k; got extension for term-" + i);
            }
        }
    }

    // -------------------------------------------------------------
    // Stress
    // -------------------------------------------------------------

    @Test
    void stress_5k_random_strings_round_trip() {
        SplittableRandom r = new SplittableRandom(0xCAFEBABEL);
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = fresh();
            String[] inputs = new String[5_000];
            TermId[] ids = new TermId[inputs.length];
            for (int i = 0; i < inputs.length; i++) {
                inputs[i] = "term-" + r.nextLong();
                ids[i] = d.encode(TermCodec.encodeXsdString(inputs[i], a));
            }
            // Random sample round-trip checks
            for (int probe = 0; probe < 100; probe++) {
                int i = r.nextInt(inputs.length);
                MemorySegment expected = TermCodec.encodeXsdString(inputs[i], a);
                MemorySegment actual = d.decode(ids[i]).orElseThrow();
                assertEquals(
                        0,
                        Compare.compareUnsigned(expected, actual),
                        "round-trip failed at i=" + i);
            }
        }
    }
}
