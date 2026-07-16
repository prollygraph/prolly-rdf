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
package com.earasoft.prolly.semantic.canon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@link SecondDegreeCanonicalizer} closes more cases than {@link
 * SimpleFirstDegreeCanonicalizer} without overreaching into the full-URDNA2015 territory.
 *
 * <ol>
 *   <li>All cases iter 2 handled correctly still work.
 *   <li>The new case iter 4 closes: same first-degree shape but different blank-node neighbours.
 *   <li>The cases iter 4 still fails closed on: cyclic pair, deeply symmetric graphs.
 * </ol>
 */
class SecondDegreeCanonicalizerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    // ---- coverage continuity: iter 2 cases still pass ---------------------

    @Test
    void blankNodeRename_canonicalizesToEqualForms() {
        List<QuadPattern> graphA =
                List.of(q("_:x", "ex:knows", "ex:bob"), q("_:x", "ex:age", "30"));
        List<QuadPattern> graphB =
                List.of(q("_:y", "ex:knows", "ex:bob"), q("_:y", "ex:age", "30"));
        List<QuadPattern> cA = SecondDegreeCanonicalizer.INSTANCE.canonicalize(graphA);
        List<QuadPattern> cB = SecondDegreeCanonicalizer.INSTANCE.canonicalize(graphB);
        assertEquals(cA, cB);
        assertEquals("_:c14n0", cA.get(0).s().value());
    }

    @Test
    void noBlankNodes_isIdentityPassThrough() {
        List<QuadPattern> input = List.of(q("ex:alice", "ex:knows", "ex:bob"));
        assertSame(input, SecondDegreeCanonicalizer.INSTANCE.canonicalize(input));
    }

    // ---- the case iter 4 newly closes -------------------------------------

    /**
     * Two blank nodes with structurally identical first-degree neighbourhoods but distinguishable
     * blank-node neighbours.
     *
     * <p>Both {@code _:p1} and {@code _:p2} say "I am a person who follows another person."
     * First-degree hashing alone cannot tell them apart — {@link SimpleFirstDegreeCanonicalizer}
     * would throw. But {@code _:p1}'s neighbour {@code _:friend1} knows {@code ex:alice} while
     * {@code _:p2}'s neighbour {@code _:friend2} knows {@code ex:bob} — the neighbours have
     * different first-degree hashes, so the second-degree disambiguates.
     */
    @Test
    void distinguishableByNeighbourFirstDegree_resolvesSuccessfully() {
        List<QuadPattern> graph =
                List.of(
                        // _:p1 follows _:friend1; _:friend1 knows ex:alice
                        q("_:p1", "ex:follows", "_:friend1"),
                        q("_:friend1", "ex:knows", "ex:alice"),
                        // _:p2 follows _:friend2; _:friend2 knows ex:bob
                        q("_:p2", "ex:follows", "_:friend2"),
                        q("_:friend2", "ex:knows", "ex:bob"));

        // First-degree alone collapses _:p1 with _:p2 (both shapes
        // are "follows _:_other") AND _:friend1 with _:friend2 (both
        // are "_:_other follows me; I know <ex:X>" — but the <ex:X>
        // distinguishes the friends, so first-degree DOES tell those
        // apart). _:p1/_:p2 collide on first degree though.
        assertThrows(
                NonCanonicalizableException.class,
                () -> SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graph));

        // Second-degree resolves: the friends' h₁ differ, so propagating
        // through neighbours distinguishes _:p1 from _:p2.
        List<QuadPattern> canon = SecondDegreeCanonicalizer.INSTANCE.canonicalize(graph);
        // All four blank nodes get unique canonical labels.
        long uniqueBlanks =
                canon.stream()
                        .flatMap(p -> java.util.stream.Stream.of(p.s().value(), p.o().value()))
                        .filter(RdfCanonicalizer::isBlankNode)
                        .distinct()
                        .count();
        assertEquals(4, uniqueBlanks);
    }

    // ---- the cases iter 4 still fails closed on ---------------------------

    /**
     * Pure cyclic pair: {@code _:b1 ↔ _:b2}. Both have identical first-degree shape ("knows
     * _:_other / known-by _:_other") AND identical second-degree (each neighbour's h₁ is also the
     * same). Still throws.
     */
    @Test
    void cyclicPair_stillFailsClosed() {
        List<QuadPattern> graph =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        NonCanonicalizableException ex =
                assertThrows(
                        NonCanonicalizableException.class,
                        () -> SecondDegreeCanonicalizer.INSTANCE.canonicalize(graph));
        assertTrue(ex.getMessage().contains("second-degree hash collision"));
    }

    /**
     * Truly symmetric graph: two blank nodes whose neighbourhoods are structurally identical at
     * every depth. Still throws — full URDNA2015 would also collapse them to the same canonical
     * label, which is correct RDF semantics, but second-degree alone can't prove the collapse safe.
     */
    @Test
    void symmetricDistinctBlankNodes_stillFailClosed() {
        List<QuadPattern> graph =
                List.of(
                        q("_:b1", "ex:knows", "ex:bob"),
                        q("_:b1", "ex:age", "30"),
                        q("_:b2", "ex:knows", "ex:bob"),
                        q("_:b2", "ex:age", "30"));
        assertThrows(
                NonCanonicalizableException.class,
                () -> SecondDegreeCanonicalizer.INSTANCE.canonicalize(graph));
    }

    // ---- contract continuity ---------------------------------------------

    @Test
    void determinism_sameInputProducesSameOutput() {
        List<QuadPattern> input =
                List.of(
                        q("_:p1", "ex:follows", "_:friend1"),
                        q("_:friend1", "ex:knows", "ex:alice"));
        List<QuadPattern> first = SecondDegreeCanonicalizer.INSTANCE.canonicalize(input);
        List<QuadPattern> second = SecondDegreeCanonicalizer.INSTANCE.canonicalize(input);
        assertEquals(first, second);
    }

    @Test
    void idempotence_canonicalizeOfCanonical_isNoOp() {
        List<QuadPattern> input =
                List.of(
                        q("_:p1", "ex:follows", "_:friend1"),
                        q("_:friend1", "ex:knows", "ex:alice"));
        List<QuadPattern> once = SecondDegreeCanonicalizer.INSTANCE.canonicalize(input);
        List<QuadPattern> twice = SecondDegreeCanonicalizer.INSTANCE.canonicalize(once);
        assertEquals(once, twice);
    }

    /**
     * Two structurally-equivalent graphs that the iter 2 first-degree canonicalizer could not
     * handle — graph isomorphism with distinguishable distant signals — produce equal canonical
     * forms under iter 4.
     */
    @Test
    void blankNodeRenameWithNeighbours_canonicalizesToEqualForms() {
        List<QuadPattern> graphA =
                List.of(q("_:a1", "ex:follows", "_:a2"), q("_:a2", "ex:knows", "ex:alice"));
        List<QuadPattern> graphB =
                List.of(q("_:x9", "ex:follows", "_:y7"), q("_:y7", "ex:knows", "ex:alice"));
        // Raw lists differ.
        assertNotEquals(graphA, graphB);
        // Canonical forms agree.
        assertEquals(
                SecondDegreeCanonicalizer.INSTANCE.canonicalize(graphA),
                SecondDegreeCanonicalizer.INSTANCE.canonicalize(graphB));
    }
}
