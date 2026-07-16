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
 * Verifies the SimpleFirstDegreeCanonicalizer's contract:
 *
 * <ol>
 *   <li>Blank-node-rename case → equal canonical forms (the bug documented in
 *       BlankNodeRenameCanonicalizerTest is fixed for inputs whose blank nodes have unique
 *       first-degree hashes).
 *   <li>Blank-node-free input → identity pass-through.
 *   <li>Cyclic blank-node graph → throws NonCanonicalizableException (fail-closed; first-degree
 *       hashes collide).
 *   <li>Symmetric distinct blank nodes → throws (fail-closed).
 *   <li>Determinism → same input twice yields same output.
 *   <li>Idempotence → canonicalising an already-canonicalised graph is a no-op modulo label
 *       assignment stability.
 *   <li>Two distinguishable blank nodes → unique canonical labels in hash-sorted order.
 * </ol>
 */
class SimpleFirstDegreeCanonicalizerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    /**
     * The headline bug fix: graphs differing only in blank-node label canonicalize to equal forms.
     */
    @Test
    void blankNodeRename_canonicalizesToEqualForms() {
        List<QuadPattern> graphA =
                List.of(q("_:x", "ex:knows", "ex:bob"), q("_:x", "ex:age", "30"));
        List<QuadPattern> graphB =
                List.of(q("_:y", "ex:knows", "ex:bob"), q("_:y", "ex:age", "30"));

        // Sanity: as raw quads, NOT equal — the bug from iter 1.
        assertNotEquals(graphA, graphB);

        // After canonicalization: equal.
        List<QuadPattern> cA = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graphA);
        List<QuadPattern> cB = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graphB);
        assertEquals(cA, cB);

        // The blank-node label is canonicalized.
        assertEquals("_:c14n0", cA.get(0).s().value());
    }

    /** Pass-through for blank-node-free input — same list reference returned. */
    @Test
    void noBlankNodes_isIdentityPassThrough() {
        List<QuadPattern> input =
                List.of(q("ex:alice", "ex:knows", "ex:bob"), q("ex:bob", "ex:age", "30"));
        List<QuadPattern> output = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(input);
        assertSame(input, output);
    }

    /**
     * Cyclic blank-node graph: {@code _:b1 → _:b2}, {@code _:b2 → _:b1}. Both nodes have the same
     * first-degree neighbourhood once other-blank substitution kicks in. Collision → throw.
     */
    @Test
    void cyclicBlankNodes_failClosed() {
        List<QuadPattern> graph =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        NonCanonicalizableException ex =
                assertThrows(
                        NonCanonicalizableException.class,
                        () -> SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graph));
        assertTrue(
                ex.getMessage().contains("first-degree hash collision"),
                "expected collision diagnostic, got: " + ex.getMessage());
    }

    /**
     * Two distinct blank nodes that happen to have the same first-degree shape. URDNA2015 might or
     * might not distinguish them; first-degree-only conservatively throws.
     */
    @Test
    void symmetricBlankNodes_failClosed() {
        List<QuadPattern> graph =
                List.of(
                        q("_:b1", "ex:knows", "ex:bob"),
                        q("_:b1", "ex:age", "30"),
                        q("_:b2", "ex:knows", "ex:bob"),
                        q("_:b2", "ex:age", "30"));
        assertThrows(
                NonCanonicalizableException.class,
                () -> SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graph));
    }

    /** Same input twice → same output. */
    @Test
    void determinism_sameInputProducesSameOutput() {
        List<QuadPattern> input = List.of(q("_:x", "ex:p", "ex:o1"), q("_:x", "ex:q", "ex:o2"));
        List<QuadPattern> first = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(input);
        List<QuadPattern> second = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(input);
        assertEquals(first, second);
    }

    /** Canonicalising an already-canonicalised graph is a no-op. */
    @Test
    void idempotence_canonicalizeOfCanonical_isNoOp() {
        List<QuadPattern> input = List.of(q("_:x", "ex:p", "ex:o"));
        List<QuadPattern> once = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(input);
        List<QuadPattern> twice = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(once);
        assertEquals(once, twice);
        assertEquals("_:c14n0", once.get(0).s().value());
        assertEquals("_:c14n0", twice.get(0).s().value());
    }

    /**
     * Two blank nodes with distinguishable first-degree neighbourhoods get distinct canonical
     * labels, in hash-sorted order.
     */
    @Test
    void distinguishableBlankNodes_getUniqueLabels() {
        List<QuadPattern> graph =
                List.of(q("_:bob", "ex:knows", "ex:alice"), q("_:carol", "ex:knows", "ex:dave"));
        List<QuadPattern> canon = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graph);

        // Both inputs are mapped to canonical labels; the labels are distinct.
        String s0 = canon.get(0).s().value();
        String s1 = canon.get(1).s().value();
        assertTrue(s0.startsWith("_:c14n"), "expected canonical label, got: " + s0);
        assertTrue(s1.startsWith("_:c14n"), "expected canonical label, got: " + s1);
        assertNotEquals(s0, s1);
    }

    /**
     * Input ordering does not affect output. Canonicalization is a function of the unordered set of
     * triples, not their list order.
     */
    @Test
    void inputOrdering_doesNotAffectCanonicalLabels() {
        List<QuadPattern> graphA = List.of(q("_:x", "ex:p1", "ex:o1"), q("_:x", "ex:p2", "ex:o2"));
        List<QuadPattern> graphB = List.of(q("_:x", "ex:p2", "ex:o2"), q("_:x", "ex:p1", "ex:o1"));

        // Different list order, same RDF set.
        assertNotEquals(graphA, graphB);

        // Canonicalization assigns the same canonical label to _:x in both,
        // because the first-degree hash is order-insensitive (sorted internally).
        List<QuadPattern> cA = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graphA);
        List<QuadPattern> cB = SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graphB);
        assertEquals(cA.get(0).s().value(), cB.get(0).s().value());
    }
}
