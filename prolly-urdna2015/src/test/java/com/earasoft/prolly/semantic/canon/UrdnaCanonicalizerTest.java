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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the phase-1/2/3/5 portion of W3C URDNA2015 lands correctly, and the phase-4 stub throws
 * cleanly with a useful diagnostic.
 *
 * <p>Coverage matches the test gate from {@code prolly-urdna2015/FUTURE_WORK.md} sub-iter 6b.
 */
class UrdnaCanonicalizerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    /**
     * Blank-node rename: two graphs differing only in parser-minted label resolve to byte-identical
     * canonical output.
     */
    @Test
    void blankNodeRename_canonicalizesToEqualForms() {
        List<QuadPattern> graphA =
                List.of(q("_:x", "ex:knows", "ex:bob"), q("_:x", "ex:age", "30"));
        List<QuadPattern> graphB =
                List.of(q("_:y", "ex:knows", "ex:bob"), q("_:y", "ex:age", "30"));

        assertNotEquals(graphA, graphB);

        List<QuadPattern> cA = UrdnaCanonicalizer.INSTANCE.canonicalize(graphA);
        List<QuadPattern> cB = UrdnaCanonicalizer.INSTANCE.canonicalize(graphB);
        assertEquals(cA, cB);
        assertEquals("_:c14n0", cA.get(0).s().value());
    }

    /** No blank nodes → identity pass-through (same list reference). */
    @Test
    void noBlankNodes_isIdentityPassThrough() {
        List<QuadPattern> input =
                List.of(q("ex:alice", "ex:knows", "ex:bob"), q("ex:bob", "ex:age", "30"));
        assertSame(input, UrdnaCanonicalizer.INSTANCE.canonicalize(input));
    }

    /** Multiple distinguishable blank nodes get unique canonical labels in h₁-sorted order. */
    @Test
    void multipleDistinguishableBlanks_getUniqueLabels() {
        List<QuadPattern> graph =
                List.of(
                        q("_:bob", "ex:age", "30"),
                        q("_:carol", "ex:age", "25"),
                        q("_:dave", "ex:age", "40"));
        List<QuadPattern> canon = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);
        long uniqueCanon =
                canon.stream()
                        .map(p -> p.s().value())
                        .filter(s -> s.startsWith("_:c14n"))
                        .distinct()
                        .count();
        assertEquals(3, uniqueCanon);
    }

    // ---- phase 4 (single-level, iter 6c): cyclic + symmetric resolve ----

    /**
     * Cyclic pair: defeated phase 3 (both blanks have identical h₁) — now resolves via phase 4
     * single-level. Each blank node gets a distinct canonical name; the cyclic edges are preserved.
     */
    @Test
    void cyclicPair_resolvesViaNDegree() {
        List<QuadPattern> graph =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        List<QuadPattern> canon = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);

        // Both blank nodes get canonical names; output has 2 quads.
        assertEquals(2, canon.size());
        long uniqueBlanks =
                canon.stream()
                        .flatMap(p -> java.util.stream.Stream.of(p.s().value(), p.o().value()))
                        .filter(s -> s.startsWith("_:c14n"))
                        .distinct()
                        .count();
        assertEquals(2, uniqueBlanks, "expected two distinct canonical blank-node labels");

        // The cyclic structure is preserved: each canonical node points at the other.
        // Both edges use ex:knows; both endpoints are canonical blanks.
        for (QuadPattern p : canon) {
            assertEquals("ex:knows", p.p().value());
            assertTrue(p.s().value().startsWith("_:c14n"));
            assertTrue(p.o().value().startsWith("_:c14n"));
            assertNotEquals(p.s().value(), p.o().value(), "edge endpoints must differ");
        }
    }

    /**
     * Symmetric distinct blank nodes: phase 3 couldn't disambiguate; phase 4 single-level assigns
     * them distinct canonical names in processing order. Output has 4 quads with two canonical
     * identities.
     */
    @Test
    void symmetricDistinctBlanks_resolveViaNDegree() {
        List<QuadPattern> graph =
                List.of(
                        q("_:b1", "ex:knows", "ex:bob"),
                        q("_:b1", "ex:age", "30"),
                        q("_:b2", "ex:knows", "ex:bob"),
                        q("_:b2", "ex:age", "30"));
        List<QuadPattern> canon = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);

        assertEquals(4, canon.size());
        long uniqueBlanks =
                canon.stream()
                        .map(p -> p.s().value())
                        .filter(s -> s.startsWith("_:c14n"))
                        .distinct()
                        .count();
        assertEquals(2, uniqueBlanks, "expected two distinct canonical blank-node labels");
    }

    /**
     * Renamed cyclic pair: same RDF graph, different parser-minted labels. Phase 4 must produce
     * byte-identical canonical output regardless of which input label was minted for which side.
     */
    @Test
    void renamedCyclicPair_canonicalizesToEqualForms() {
        List<QuadPattern> graphA =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        List<QuadPattern> graphB =
                List.of(q("_:zoo", "ex:knows", "_:apple"), q("_:apple", "ex:knows", "_:zoo"));
        List<QuadPattern> cA = UrdnaCanonicalizer.INSTANCE.canonicalize(graphA);
        List<QuadPattern> cB = UrdnaCanonicalizer.INSTANCE.canonicalize(graphB);

        // Same RDF graph; same canonical output.
        assertEquals(
                new java.util.HashSet<>(cA),
                new java.util.HashSet<>(cB),
                "renamed cyclic pair must produce the same canonical quad set");
    }

    // ---- iter 6d: recursion through un-issued relateds ------------------

    /**
     * Three-node cycle: {@code _:a → _:b → _:c → _:a}. Every blank node has identical first-degree
     * shape. Single-level N-degree can resolve this (each iteration discovers one new neighbour and
     * assigns it a temp name), but the recursion path is what stabilises the labelling under
     * renames.
     */
    @Test
    void threeNodeCycle_resolvesWithRecursion() {
        List<QuadPattern> graph =
                List.of(q("_:a", "ex:p", "_:b"), q("_:b", "ex:p", "_:c"), q("_:c", "ex:p", "_:a"));
        List<QuadPattern> canon = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);

        assertEquals(3, canon.size());
        long uniqueBlanks =
                canon.stream()
                        .flatMap(p -> java.util.stream.Stream.of(p.s().value(), p.o().value()))
                        .filter(s -> s.startsWith("_:c14n"))
                        .distinct()
                        .count();
        assertEquals(3, uniqueBlanks, "expected three distinct canonical blank-node labels");

        // Every edge connects two distinct canonical blanks via ex:p.
        for (QuadPattern p : canon) {
            assertEquals("ex:p", p.p().value());
            assertTrue(p.s().value().startsWith("_:c14n"));
            assertTrue(p.o().value().startsWith("_:c14n"));
            assertNotEquals(p.s().value(), p.o().value());
        }
    }

    /**
     * Renamed three-node cycle: same RDF graph with different parser labels. Recursion-enabled
     * algorithm produces byte-identical canonical output set.
     */
    @Test
    void renamedThreeNodeCycle_canonicalizesToEqualForms() {
        List<QuadPattern> graphA =
                List.of(q("_:a", "ex:p", "_:b"), q("_:b", "ex:p", "_:c"), q("_:c", "ex:p", "_:a"));
        List<QuadPattern> graphB =
                List.of(
                        q("_:zoo", "ex:p", "_:apple"),
                        q("_:apple", "ex:p", "_:mango"),
                        q("_:mango", "ex:p", "_:zoo"));
        List<QuadPattern> cA = UrdnaCanonicalizer.INSTANCE.canonicalize(graphA);
        List<QuadPattern> cB = UrdnaCanonicalizer.INSTANCE.canonicalize(graphB);

        assertEquals(
                new java.util.HashSet<>(cA),
                new java.util.HashSet<>(cB),
                "renamed 3-node cycle must produce the same canonical quad set");
    }

    /**
     * Two related-blank groups inside the same hashNDegreeQuads call: exercises the recursionList
     * across multiple permutation groups.
     */
    @Test
    void multipleRelatedBlanks_recursionStable() {
        List<QuadPattern> graph =
                List.of(
                        q("_:a", "ex:p", "_:b"),
                        q("_:a", "ex:q", "_:c"),
                        q("_:b", "ex:r", "ex:end_b"),
                        q("_:c", "ex:r", "ex:end_c"));
        List<QuadPattern> canon = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);
        assertEquals(4, canon.size());
    }

    // ---- canonicalizer contract continuity ------------------------------

    @Test
    void determinism_sameInputProducesSameOutput() {
        List<QuadPattern> input = List.of(q("_:x", "ex:p", "ex:o1"), q("_:x", "ex:q", "ex:o2"));
        List<QuadPattern> first = UrdnaCanonicalizer.INSTANCE.canonicalize(input);
        List<QuadPattern> second = UrdnaCanonicalizer.INSTANCE.canonicalize(input);
        assertEquals(first, second);
    }

    @Test
    void idempotence_canonicalizeOfCanonical_isNoOp() {
        List<QuadPattern> input = List.of(q("_:x", "ex:p", "ex:o"));
        List<QuadPattern> once = UrdnaCanonicalizer.INSTANCE.canonicalize(input);
        List<QuadPattern> twice = UrdnaCanonicalizer.INSTANCE.canonicalize(once);
        assertEquals(once, twice);
        assertEquals("_:c14n0", once.get(0).s().value());
    }

    /**
     * Input list ordering does not affect canonical-label assignment. (Iter 6b's phase 1+3 are
     * order-insensitive on the input.)
     */
    @Test
    void inputOrdering_doesNotAffectCanonicalLabels() {
        List<QuadPattern> graphA = List.of(q("_:x", "ex:p1", "ex:o1"), q("_:x", "ex:p2", "ex:o2"));
        List<QuadPattern> graphB = List.of(q("_:x", "ex:p2", "ex:o2"), q("_:x", "ex:p1", "ex:o1"));
        assertNotEquals(graphA, graphB);
        assertEquals(
                UrdnaCanonicalizer.INSTANCE.canonicalize(graphA).get(0).s().value(),
                UrdnaCanonicalizer.INSTANCE.canonicalize(graphB).get(0).s().value());
    }

    /**
     * For graphs without first-degree collisions, UrdnaCanonicalizer and
     * SimpleFirstDegreeCanonicalizer produce equivalent results (modulo label assignment ordering,
     * both deterministic).
     *
     * <p>Iter 6b regression-locks this property: as we add phase 4 in iter 6c, the non-colliding
     * cases must continue to match the simpler canonicalizer to preserve cascading behaviour.
     */
    @Test
    void agreesWithSimpleFirstDegreeOnNonCollidingCases() {
        List<QuadPattern> graph = List.of(q("_:bob", "ex:age", "30"), q("_:carol", "ex:age", "25"));
        List<QuadPattern> urdna = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);
        List<QuadPattern> simple = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graph);
        assertEquals(
                simple, urdna, "phase 1+5 should match SimpleFirstDegreeCanonicalizer's output");
    }
}
