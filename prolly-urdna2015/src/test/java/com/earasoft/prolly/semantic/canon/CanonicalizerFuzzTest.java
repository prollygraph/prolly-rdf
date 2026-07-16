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
import static org.junit.jupiter.api.Assertions.fail;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Property-based fuzz tests for the canonicalizer suite.
 *
 * <p>Asserts four properties hold across many randomly-generated graphs:
 *
 * <ol>
 *   <li>Determinism — same input twice yields equal output.
 *   <li>Idempotence — canonicalize(canonicalize(x)) == canonicalize(x).
 *   <li>Rename-stability — relabel blank nodes; canonical output set is byte-equal to the original.
 *   <li>URDNA2015 totality — never throws on a well-formed graph (if it does, that's a bug in the
 *       algorithm or our impl).
 * </ol>
 *
 * <p>Generator covers four shape regimes (sparse-blank, dense-blank, cyclic-heavy, chain-heavy)
 * with seeded RNG for reproducibility. 250 graphs per regime → 1,000 graphs per property → 4,000
 * canonicalizer invocations exercised.
 */
class CanonicalizerFuzzTest {

    private static final long SEED = 0xCAFE_BEEF_2026_F022L;
    private static final int GRAPHS_PER_REGIME = 250;

    // ---- Determinism ------------------------------------------------------

    @Test
    void urdna_isDeterministic_acrossAllRegimes() {
        forEachShape(
                (label, graph, rng) -> {
                    try {
                        List<QuadPattern> first = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);
                        List<QuadPattern> second = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);
                        assertEquals(
                                first,
                                second,
                                "URDNA2015 determinism violated on " + label + " graph: " + graph);
                    } catch (NonCanonicalizableException e) {
                        fail(
                                "URDNA2015 should never throw on well-formed input; got "
                                        + e.getMessage()
                                        + " on "
                                        + label
                                        + " graph: "
                                        + graph);
                    }
                });
    }

    @Test
    void cascade_isDeterministic_acrossAllRegimes() {
        forEachShape(
                (label, graph, rng) -> {
                    try {
                        List<QuadPattern> first = CascadeCanonicalizer.INSTANCE.canonicalize(graph);
                        List<QuadPattern> second =
                                CascadeCanonicalizer.INSTANCE.canonicalize(graph);
                        assertEquals(
                                first,
                                second,
                                "cascade determinism violated on " + label + " graph: " + graph);
                    } catch (NonCanonicalizableException e) {
                        fail(
                                "cascade should never throw with URDNA2015 at level 2; got: "
                                        + e.getMessage());
                    }
                });
    }

    // ---- Idempotence ------------------------------------------------------

    @Test
    void urdna_isIdempotent_acrossAllRegimes() {
        forEachShape(
                (label, graph, rng) -> {
                    try {
                        List<QuadPattern> once = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);
                        List<QuadPattern> twice = UrdnaCanonicalizer.INSTANCE.canonicalize(once);
                        assertEquals(
                                once,
                                twice,
                                "URDNA2015 idempotence violated on " + label + " graph: " + graph);
                    } catch (NonCanonicalizableException e) {
                        fail("URDNA2015 idempotence pass should not throw");
                    }
                });
    }

    // ---- Rename-stability -------------------------------------------------

    @Test
    void urdna_isRenameStable_acrossAllRegimes() {
        forEachShape(
                (label, graph, rng) -> {
                    List<QuadPattern> renamed = renameBlankNodes(graph, rng);

                    try {
                        Set<QuadPattern> original =
                                new HashSet<>(UrdnaCanonicalizer.INSTANCE.canonicalize(graph));
                        Set<QuadPattern> renamedCanon =
                                new HashSet<>(UrdnaCanonicalizer.INSTANCE.canonicalize(renamed));

                        assertEquals(
                                original,
                                renamedCanon,
                                "URDNA2015 rename-stability violated on "
                                        + label
                                        + ". original="
                                        + graph
                                        + " renamed="
                                        + renamed);
                    } catch (NonCanonicalizableException e) {
                        fail("URDNA2015 should never throw on well-formed input");
                    }
                });
    }

    // ---- Test harness -----------------------------------------------------

    @FunctionalInterface
    private interface ShapeAssertion {
        void apply(String label, List<QuadPattern> graph, Random rng);
    }

    private static void forEachShape(ShapeAssertion check) {
        long baseSeed = SEED;
        runRegime("sparse", baseSeed, CanonicalizerFuzzTest::sparseGraph, check);
        runRegime("dense", baseSeed ^ 0x1L, CanonicalizerFuzzTest::denseGraph, check);
        runRegime("cyclic", baseSeed ^ 0x2L, CanonicalizerFuzzTest::cyclicGraph, check);
        runRegime("chain", baseSeed ^ 0x3L, CanonicalizerFuzzTest::chainGraph, check);
    }

    @FunctionalInterface
    private interface GraphGenerator {
        List<QuadPattern> generate(Random rng);
    }

    private static void runRegime(
            String label, long seed, GraphGenerator gen, ShapeAssertion check) {
        Random rng = new Random(seed);
        for (int i = 0; i < GRAPHS_PER_REGIME; i++) {
            List<QuadPattern> graph = gen.generate(rng);
            check.apply(label + "[" + i + ", seed=" + seed + "]", graph, rng);
        }
    }

    // ---- Graph generators (4 regimes) -------------------------------------

    /** All-named or rarely-blank: stress-test phase 0 / 1 short-circuits. */
    private static List<QuadPattern> sparseGraph(Random rng) {
        int n = 1 + rng.nextInt(15); // 1..15 quads
        int blanks = rng.nextInt(3); // 0..2 blank nodes
        return generateBag(rng, n, blanks, 6);
    }

    /** Many blanks: heavy phase 4 traffic likely. */
    private static List<QuadPattern> denseGraph(Random rng) {
        int n = 4 + rng.nextInt(20); // 4..23 quads
        int blanks = 2 + rng.nextInt(8); // 2..9 blank nodes
        return generateBag(rng, n, blanks, 3);
    }

    /** Cycles: blank-blank edges that create rings. */
    private static List<QuadPattern> cyclicGraph(Random rng) {
        int ringSize = 2 + rng.nextInt(5); // 2..6 nodes in cycle
        List<QuadPattern> quads = new ArrayList<>(ringSize);
        for (int i = 0; i < ringSize; i++) {
            String s = "_:b" + i;
            String o = "_:b" + ((i + 1) % ringSize);
            quads.add(QuadPattern.of(s, predicate(rng), o, "g"));
        }
        // optional pendants
        int pendants = rng.nextInt(4);
        for (int i = 0; i < pendants; i++) {
            String s = "_:b" + rng.nextInt(ringSize);
            quads.add(QuadPattern.of(s, predicate(rng), "ex:end" + rng.nextInt(3), "g"));
        }
        return quads;
    }

    /** Chains: a-b-c-...-fixed terminus, varying depth. */
    private static List<QuadPattern> chainGraph(Random rng) {
        int depth = 2 + rng.nextInt(6); // 2..7 deep
        List<QuadPattern> quads = new ArrayList<>(depth + 1);
        for (int i = 0; i < depth - 1; i++) {
            quads.add(QuadPattern.of("_:b" + i, predicate(rng), "_:b" + (i + 1), "g"));
        }
        // terminus to a named IRI
        quads.add(
                QuadPattern.of(
                        "_:b" + (depth - 1), predicate(rng), "ex:end" + rng.nextInt(3), "g"));
        return quads;
    }

    private static List<QuadPattern> generateBag(Random rng, int n, int blanks, int iris) {
        List<QuadPattern> quads = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String s = subject(rng, blanks, iris);
            String p = predicate(rng);
            String o = object(rng, blanks, iris);
            quads.add(QuadPattern.of(s, p, o, "g"));
        }
        return quads;
    }

    private static String subject(Random rng, int blanks, int iris) {
        if (blanks > 0 && rng.nextBoolean()) {
            return "_:b" + rng.nextInt(blanks);
        }
        return "ex:s" + rng.nextInt(iris);
    }

    private static String predicate(Random rng) {
        return "ex:p" + rng.nextInt(4);
    }

    private static String object(Random rng, int blanks, int iris) {
        int r = rng.nextInt(3);
        if (r == 0 && blanks > 0) return "_:b" + rng.nextInt(blanks);
        if (r == 1) return "ex:o" + rng.nextInt(iris);
        return "lit:" + rng.nextInt(5);
    }

    // ---- Blank-node rename helper ----------------------------------------

    /**
     * Renames every blank node in {@code graph} to a fresh random label. The set of distinct blank
     * nodes is preserved (1:1 mapping); the RDF graph is structurally identical.
     */
    private static List<QuadPattern> renameBlankNodes(List<QuadPattern> graph, Random rng) {
        Set<String> blanks = new HashSet<>();
        for (QuadPattern q : graph) {
            if (RdfCanonicalizer.isBlankNode(q.s().value())) blanks.add(q.s().value());
            if (RdfCanonicalizer.isBlankNode(q.o().value())) blanks.add(q.o().value());
        }
        if (blanks.isEmpty()) return graph;

        List<String> labels = new ArrayList<>(blanks);
        Collections.shuffle(labels, rng);
        Map<String, String> mapping = new HashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            // Pick fresh labels that don't collide with existing ones.
            mapping.put(labels.get(i), "_:renamed" + i + "_" + rng.nextInt(10_000));
        }

        List<QuadPattern> result = new ArrayList<>(graph.size());
        for (QuadPattern q : graph) {
            String s = mapping.getOrDefault(q.s().value(), q.s().value());
            String o = mapping.getOrDefault(q.o().value(), q.o().value());
            result.add(QuadPattern.of(s, q.p().value(), o, q.c()));
        }
        return result;
    }
}
