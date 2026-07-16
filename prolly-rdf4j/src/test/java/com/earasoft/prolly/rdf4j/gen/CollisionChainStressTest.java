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
package com.earasoft.prolly.rdf4j.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunction;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashSet;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Step 9 of {@code prolly-rdf4j-test-strategy.md} — <b>dictionary collision-chain
 * stress</b> (S-3). Forces the salted collision chain to walk its full depth and exhaust, by
 * combining (a) a degenerate {@link HashFunction} that returns a term's <i>first four bytes</i>
 * with (b) same-length, shared-prefix terms. At salt 0 every term hashes to the <i>same</i> natural
 * slot (identical first 4 bytes) — a forced collision; for salt&gt;0 the dictionary prepends 4 salt
 * bytes, so the same hash yields a <i>distinct</i> extension slot per salt. The result is a clean
 * walk: the N-th distinct term lands at salt N-1, and term {@code maxSalt+1} exhausts the chain.
 *
 * <p>Pins: every term still round-trips under deep collisions; distinct terms keep distinct {@link
 * TermId}s; {@code encode} is idempotent (determinism — same bytes → same salt traversal → same
 * TermId); and {@code CollisionChainExhausted} fires exactly at the {@code maxSalt} boundary.
 */
class CollisionChainStressTest {

    /** Degenerate hash: a term's first 4 bytes, big-endian (0 if shorter). */
    private static final HashFunction FIRST4 =
            new HashFunction() {
                @Override
                public long hash(MemorySegment m) {
                    long n = Math.min(4, m.byteSize());
                    long h = 0;
                    for (long i = 0; i < n; i++)
                        h = (h << 8) | (m.get(ValueLayout.JAVA_BYTE, i) & 0xFFL);
                    return h;
                }

                @Override
                public String name() {
                    return "test-first4-degenerate";
                }
            };

    private static NodeStore store() {
        return new InMemoryNodeStore();
    }

    private static BufferPool pool() {
        return new HeapBufferPool();
    }

    /**
     * Same length (zero-padded) + shared prefix ⇒ identical first 4 bytes ⇒ all collide at salt 0
     * under {@link #FIRST4}.
     */
    private static MemorySegment term(Arena a, int i) {
        return TermCodec.encodeXsdString(String.format("collide-prefix-%04d", i), a);
    }

    @Property(tries = 30)
    void everyTermRoundTripsAndStaysDistinctUnderDeepCollisions(
            @ForAll @IntRange(min = 1, max = 60) int n) {
        NodeStore s = store();
        BufferPool p = pool();
        Dictionary d =
                new Dictionary(
                        s,
                        p,
                        FIRST4,
                        Dictionary.MAX_SALT,
                        com.earasoft.prolly.rdf4j.term.EncoderMetrics.noop());
        try (Arena a = Arena.ofConfined()) {
            Set<TermId> ids = new HashSet<>();
            TermId[] byIndex = new TermId[n];
            for (int i = 0; i < n; i++) {
                byIndex[i] =
                        d.encode(term(a, i)); // walks salt 0..i-1 (all collide), lands at salt i
                ids.add(byIndex[i]);
            }
            assertEquals(
                    n, ids.size(), "distinct terms must keep distinct TermIds despite collisions");
            for (int i = 0; i < n; i++) {
                assertEquals(
                        byIndex[i],
                        d.findTermId(term(a, i)).orElseThrow(),
                        "term " + i + " must be found at the same slot it was inserted");
                MemorySegment back = d.decode(byIndex[i]).orElseThrow();
                assertEquals(
                        -1,
                        back.mismatch(term(a, i)), // -1 == segments equal
                        "decode(encode(term " + i + ")) must be the same bytes");
            }
        }
    }

    @Test
    void encodeIsIdempotentAcrossTheChain() {
        NodeStore s = store();
        BufferPool p = pool();
        Dictionary d =
                new Dictionary(
                        s,
                        p,
                        FIRST4,
                        Dictionary.MAX_SALT,
                        com.earasoft.prolly.rdf4j.term.EncoderMetrics.noop());
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < 20; i++) d.encode(term(a, i)); // fill salts 0..19
            // Re-encoding each term hits the same slot — same TermId (determinism).
            for (int i = 0; i < 20; i++) {
                assertEquals(
                        d.encode(term(a, i)),
                        d.encode(term(a, i)),
                        "re-encoding term " + i + " must yield the same TermId");
            }
        }
    }

    @Test
    void collisionChainExhaustsAtMaxSaltBoundary() {
        NodeStore s = store();
        BufferPool p = pool();
        Dictionary d =
                new Dictionary(
                        s,
                        p,
                        FIRST4,
                        Dictionary.MAX_SALT,
                        com.earasoft.prolly.rdf4j.term.EncoderMetrics.noop());
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < Dictionary.MAX_SALT; i++) d.encode(term(a, i)); // fills salts 0..63
            Dictionary.CollisionChainExhausted ex =
                    assertThrows(
                            Dictionary.CollisionChainExhausted.class,
                            () -> d.encode(term(a, Dictionary.MAX_SALT)),
                            "the (MAX_SALT+1)-th colliding term must exhaust the chain");
            assertEquals(
                    Dictionary.MAX_SALT, ex.triedSalts, "exhaustion reports trying every salt");
            assertTrue(ex.getMessage().contains("collision chain exhausted"));
        }
    }
}
