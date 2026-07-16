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

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Documents the canonicalizer SPI contract and the gap that the planned
 * SimpleFirstDegreeCanonicalizer / UrdnaCanonicalizer implementations need to close.
 *
 * <p>Three categories of test:
 *
 * <ol>
 *   <li>{@link NoopCanonicalizer} pass-through is identity for blank-node-free input — confirms the
 *       SPI plumbing.
 *   <li>{@link NoopCanonicalizer} fails closed on blank-node input — confirms the fail-closed
 *       contract from {@link RdfCanonicalizer}'s Javadoc, contract item 4.
 *   <li>Two structurally-equivalent graphs differing only in blank-node labels are NOT byte-equal
 *       as raw quads — this is the bug the URDNA2015-shaped canonicalizer is meant to fix. The test
 *       currently asserts the bug exists. When a real canonicalizer lands, a parallel test will
 *       assert the bug is fixed; this test stays as a regression marker that the noop cannot solve
 *       the problem on its own.
 * </ol>
 *
 * <p>Iteration tracker: this is iter 1 of the URDNA2015 work scoped in {@code a private strategy
 * note} and {@code prolly-audit/design/HASHING_CANONICALIZATION.md}.
 */
class BlankNodeRenameCanonicalizerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    /** SPI plumbing: noop returns its input unchanged when no blank nodes. */
    @Test
    void noopCanonicalizer_passesThroughBlankNodeFreeInput() {
        List<QuadPattern> input =
                List.of(q("ex:alice", "ex:knows", "ex:bob"), q("ex:bob", "ex:age", "30"));

        List<QuadPattern> output = NoopCanonicalizer.INSTANCE.canonicalize(input);

        // Identity pass-through: same list reference for the noop case.
        assertSame(input, output);
        assertEquals(2, output.size());
        assertEquals("ex:alice", output.get(0).s().value());
    }

    /**
     * Fail-closed contract: a noop canonicalizer applied to blank-node input would silently produce
     * a wrong result downstream (substrate sees structurally-equivalent graphs as different
     * commits). The noop refuses instead.
     */
    @Test
    void noopCanonicalizer_failsClosedOnBlankNodeInput() {
        List<QuadPattern> input = List.of(q("_:b1", "ex:knows", "ex:bob"));

        assertThrows(
                NonCanonicalizableException.class,
                () -> NoopCanonicalizer.INSTANCE.canonicalize(input));
    }

    /**
     * Documents the bug we need to fix in iter 2.
     *
     * <p>Two graphs are structurally equivalent: each says "some anonymous resource knows ex:bob
     * and is aged 30." Their RDF meaning is identical — yet because the parser minted different
     * blank-node labels ({@code _:x} vs {@code _:y}), the raw quad lists are not byte-equal.
     *
     * <p>This is exactly the case that breaks naive merge for blank-node-bearing data (whitepaper
     * §3.1, §5.1 of {@code RDF_MERGE_SEMANTICS.md}). A SimpleFirstDegreeCanonicalizer applied to
     * both inputs would rewrite {@code _:x} and {@code _:y} to the same canonical label (e.g.
     * {@code _:c14n0}), at which point the lists become byte-equal and the substrate's three-way
     * merge sees them as the same triple.
     *
     * <p>This test asserts the current state — that the lists are <em>not</em> equal — to make the
     * bug visible and to fail loudly if someone accidentally normalises the parser's blank-node
     * minting (which would mask the real fix).
     */
    @Test
    void documentedBug_renamedBlankNodesAreNotByteEqualWithoutCanonicalizer() {
        List<QuadPattern> graphA =
                List.of(q("_:x", "ex:knows", "ex:bob"), q("_:x", "ex:age", "30"));
        List<QuadPattern> graphB =
                List.of(q("_:y", "ex:knows", "ex:bob"), q("_:y", "ex:age", "30"));

        // Same RDF meaning; different parser-minted labels.
        // Without a real canonicalizer, the substrate sees them as
        // different graphs and a three-way merge double-inserts.
        assertNotEquals(graphA, graphB);

        // Sanity: each list, internally, is consistent with itself.
        assertEquals(graphA, graphA);
        assertEquals(graphB, graphB);

        // The ONLY difference is the blank-node label string.
        // When SimpleFirstDegreeCanonicalizer (iter 2) lands, a
        // sibling test will canonicalize both and assert equality.
        assertEquals("_:x", graphA.get(0).s().value());
        assertEquals("_:y", graphB.get(0).s().value());
    }
}
