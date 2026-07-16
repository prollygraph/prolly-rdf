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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.ActionChainArbitrary;
import net.jqwik.api.state.Transformer;

/**
 * Phase 3 Step 6 of {@code plans/model-based-testing-rollout.md} — <b>stateful model-based</b>
 * property for {@link Dictionary}, the content-addressed {@code TermId ↔ encoded-term} map that
 * every index reads through. Where {@code DictionaryTest} pins individual behaviours by example (a
 * round-trip here, an escalation there), this drives a long random interleaving of {@code encode /
 * decode / findTermId / commit} over one dictionary and checks the dictionary's <i>contract</i>
 * against a reference {@code term → id} map <b>after every op</b>.
 *
 * <p><b>Why a model, not just round-trip examples:</b> the dictionary's bugs are
 * <i>order-dependent</i> — a collision escalation that corrupts an earlier natural entry, a {@code
 * commit()} re-base that loses a buffered extension entry, a {@code findTermId} that drifts from
 * {@code encode} after several salts. A flat example can only show one such sequence; the {@link
 * ActionChain} explores the interleaving and shrinks a failure to the shortest one. The model
 * cannot predict the hash-derived {@code TermId} value, so it asserts the <i>invariants</i> that
 * must hold whatever the hash returns:
 *
 * <ul>
 *   <li><b>idempotent encode</b> — re-encoding a term returns its first id (dedupe);
 *   <li><b>injectivity</b> — two byte-different terms never share an id (the salted-rehash must
 *       escalate);
 *   <li><b>round-trip</b> — {@code decode(encode(t))} returns {@code t}'s bytes;
 *   <li><b>encode/find agreement</b> — {@code findTermId(t)} is {@code Optional.of(id)} once
 *       encoded, and <b>empty for any term not yet encoded</b> (a never-inserted term must not be
 *       found, even when it salt-chains through occupied slots);
 *   <li><b>commit survival</b> — after {@code commit()} every term still decodes, is still found,
 *       and still dedupes to its id.
 * </ul>
 *
 * <p><b>The regime — a collision-forcing hash (read first):</b> under the real FNV hash the
 * extension address space is never reached at this scale ({@code
 * DictionaryTest.no_collision_under_real_hash_function} encodes 10k terms with zero escalation), so
 * a property run with the production hash would leave the entire salted-rehash path — the
 * dictionary's most error-prone code — <b>untested</b>. This property deliberately installs a
 * {@link BucketHash} that masks the real hash down to {@code 4} buckets, so an 8-term alphabet
 * <i>must</i> collide and the extension/dedupe/decode-of-extension paths fire constantly. The
 * alphabet stays far under {@code MAX_SALT} (64), so {@code CollisionChainExhausted} is out of
 * scope here (it has its own example test). This is the "put the variable in the regime where it
 * can act" discipline: a clean run under a no-collision hash would be a false negative for the
 * rehash logic.
 */
class DictionaryModelProperty {

    /**
     * A long-lived arena for the fixed term alphabet (immutable bytes shared across all chains).
     */
    private static final Arena ARENA = Arena.ofShared();

    /** 8 distinct encoded terms — a mix of kinds so the bytes differ in length and content. */
    private static final MemorySegment[] TERMS = buildAlphabet();

    private static MemorySegment[] buildAlphabet() {
        return new MemorySegment[] {
            TermCodec.encodeInteger(0L, ARENA),
            TermCodec.encodeInteger(1L, ARENA),
            TermCodec.encodeInteger(2L, ARENA),
            TermCodec.encodeInteger(3L, ARENA),
            TermCodec.encodeXsdString("a", ARENA),
            TermCodec.encodeXsdString("bb", ARENA),
            TermCodec.encodeXsdString("ccc", ARENA),
            TermCodec.encodeXsdString("dddd", ARENA),
        };
    }

    @Property(tries = 400)
    void dictionaryMatchesModelAcrossActionChains(@ForAll("chains") ActionChain<Model> chain) {
        chain.run();
    }

    /**
     * Guards the property's <i>regime</i>: encoding the full 8-term alphabet under {@link
     * BucketHash}(4) must actually push at least one term into the extension address space. Without
     * this, a future change to the alphabet or hash could quietly make every term land naturally,
     * turning the property into a clean-looking <b>false negative</b> for the salted-rehash path.
     * (Distrust the clean result — verify the variable acts.)
     */
    @org.junit.jupiter.api.Test
    void bucket_hash_forces_escalation_so_the_property_exercises_the_extension_path() {
        Dictionary d =
                new Dictionary(new InMemoryNodeStore(), new HeapBufferPool(), new BucketHash(4));
        int extensions = 0;
        for (MemorySegment term : TERMS) if (d.encode(term).isExtension()) extensions++;
        assertTrue(
                extensions > 0,
                "BucketHash(4) over "
                        + TERMS.length
                        + " distinct terms must escalate at least one to extension "
                        + "space; got "
                        + extensions
                        + " — the rehash path would be untested otherwise");
    }

    @Provide
    ActionChainArbitrary<Model> chains() {
        return ActionChain.startWith(Model::new)
                // weighted toward encode (the mutation) — find/decode probe; commit re-bases the
                // buffer
                .withAction(encode())
                .withAction(encode())
                .withAction(encode())
                .withAction(findTermId())
                .withAction(findTermId())
                .withAction(decode())
                .withAction(commit())
                .withMaxTransformations(150);
    }

    private Action.Independent<Model> encode() {
        return () -> term().map(i -> Transformer.mutate("encode t" + i, m -> m.encode(i)));
    }

    private Action.Independent<Model> findTermId() {
        return () -> term().map(i -> Transformer.mutate("find t" + i, m -> m.findTermId(i)));
    }

    private Action.Independent<Model> decode() {
        return () -> term().map(i -> Transformer.mutate("decode t" + i, m -> m.decode(i)));
    }

    private Action.Independent<Model> commit() {
        return () -> Arbitraries.just(Transformer.mutate("commit", Model::commitAndReverify));
    }

    private static net.jqwik.api.Arbitrary<Integer> term() {
        return Arbitraries.integers().between(0, TERMS.length - 1);
    }

    /**
     * The {@link Dictionary} under test paired with the reference {@code term-index ↔ id}
     * bijection.
     */
    static final class Model {
        final Dictionary dict =
                new Dictionary(new InMemoryNodeStore(), new HeapBufferPool(), new BucketHash(4));
        final Map<Integer, TermId> idByTerm = new HashMap<>(); // term index → its assigned id
        final Map<TermId, Integer> termById = new HashMap<>(); // reverse, to police injectivity

        void encode(int i) {
            TermId id = dict.encode(TERMS[i]);
            if (idByTerm.containsKey(i)) {
                assertEquals(
                        idByTerm.get(i),
                        id,
                        "re-encoding term " + i + " must dedupe to its first id");
            } else {
                Integer collidedWith = termById.get(id);
                assertTrue(
                        collidedWith == null,
                        "term "
                                + i
                                + " got id "
                                + id
                                + " already held by term "
                                + collidedWith
                                + " (injectivity broken)");
                idByTerm.put(i, id);
                termById.put(id, i);
            }
            assertRoundTrip(i, id, "immediately after encode");
        }

        void findTermId(int i) {
            Optional<TermId> got = dict.findTermId(TERMS[i]);
            if (idByTerm.containsKey(i)) {
                assertEquals(
                        Optional.of(idByTerm.get(i)),
                        got,
                        "findTermId must return the encode id for term " + i);
            } else {
                assertTrue(
                        got.isEmpty(),
                        "findTermId must not find the un-encoded term " + i + " (got " + got + ")");
            }
        }

        void decode(int i) {
            if (!idByTerm.containsKey(i))
                return; // no id assigned yet → nothing to decode for this term
            assertRoundTrip(i, idByTerm.get(i), "on decode");
        }

        /**
         * Flush the buffer to a fresh committed root; every encoded term must survive the re-base
         * unchanged.
         */
        void commitAndReverify() {
            dict.commit();
            for (Map.Entry<Integer, TermId> e : idByTerm.entrySet()) {
                int i = e.getKey();
                TermId id = e.getValue();
                assertRoundTrip(i, id, "after commit");
                assertEquals(
                        Optional.of(id),
                        dict.findTermId(TERMS[i]),
                        "post-commit findTermId term " + i);
                assertEquals(
                        id,
                        dict.encode(TERMS[i]),
                        "post-commit re-encode must still dedupe term " + i);
            }
        }

        private void assertRoundTrip(int i, TermId id, String when) {
            MemorySegment back =
                    dict.decode(id)
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "decode("
                                                            + id
                                                            + ") empty for term "
                                                            + i
                                                            + " "
                                                            + when));
            assertEquals(
                    0,
                    Compare.compareUnsigned(TERMS[i], back),
                    "round-trip term " + i + " " + when);
        }
    }

    /**
     * Masks a real hash down to {@code buckets} (a power of two) so distinct terms collide
     * constantly, forcing the salted-rehash / extension-space path that a production hash never
     * reaches at this scale. Same bytes still hash to the same bucket (dedupe at salt 0 works); the
     * salt-prefixed rehash stays in the same small space, so escalation walks a short, reproducible
     * chain.
     */
    private static final class BucketHash implements HashFunction {
        private final HashFunction base = HashFunctions.defaultHash();
        private final long mask;

        BucketHash(int buckets) {
            if ((buckets & (buckets - 1)) != 0)
                throw new IllegalArgumentException("buckets must be a power of two");
            this.mask = buckets - 1L;
        }

        @Override
        public long hash(MemorySegment data) {
            return base.hash(data) & mask;
        }

        @Override
        public String name() {
            return "bucket-" + (mask + 1);
        }
    }
}
