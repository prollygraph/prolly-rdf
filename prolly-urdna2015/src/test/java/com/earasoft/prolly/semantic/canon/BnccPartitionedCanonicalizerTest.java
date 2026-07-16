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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link BnccPartitionedCanonicalizer} composes correctly with an inner canonicalizer and
 * preserves the canonicalizer contract properties.
 *
 * <p>Six tests:
 *
 * <ol>
 *   <li>No-blanks input → identity pass-through.
 *   <li>Single BNCC (all blanks connected) → identical to inner canonicalizer's output.
 *   <li>Multi-BNCC graph → produces unique global canonical labels.
 *   <li>Rename-stability across BNCCs — relabel input blanks; same canonical quad set.
 *   <li>Determinism — same input twice yields same output.
 *   <li>Idempotence — canonicalize(canonicalize(x)) == canonicalize(x).
 * </ol>
 */
class BnccPartitionedCanonicalizerTest {

    private static final Pattern C14N = Pattern.compile("^_:c14n\\d+$");

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    @Test
    void noBlanks_passesThroughUnchanged() {
        List<QuadPattern> graph = List.of(q("ex:alice", "ex:knows", "ex:bob"));
        List<QuadPattern> output = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        assertSame(graph, output);
    }

    /**
     * Single BNCC: behaviour should be identical to the inner canonicalizer's direct output (no
     * partitioning rewrite needed).
     */
    @Test
    void singleBncc_delegatesToInner() {
        List<QuadPattern> graph = List.of(q("_:a", "ex:knows", "_:b"), q("_:b", "ex:age", "30"));
        List<QuadPattern> bnccOutput = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        List<QuadPattern> innerOutput = CascadeCanonicalizer.INSTANCE.canonicalize(graph);
        assertEquals(innerOutput, bnccOutput);
    }

    /**
     * Multi-BNCC graph: each BNCC's local _:c14n0/_:c14n1/... get re-offset to globally unique
     * labels. No two BNCCs share a canonical name in the output.
     */
    @Test
    void multiBncc_producesGloballyUniqueLabels() {
        List<QuadPattern> graph =
                List.of(
                        // BNCC 1: _:a alone
                        q("_:a", "ex:age", "30"),
                        // BNCC 2: _:b alone (different age, distinguishable)
                        q("_:b", "ex:age", "25"),
                        // BNCC 3: cycle _:c1 ↔ _:c2
                        q("_:c1", "ex:knows", "_:c2"),
                        q("_:c2", "ex:knows", "_:c1"));

        List<QuadPattern> canon = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        assertEquals(graph.size(), canon.size());

        // Collect all canonical labels in the output.
        Set<String> globalLabels = new HashSet<>();
        for (QuadPattern p : canon) {
            if (C14N.matcher(p.s().value()).matches()) globalLabels.add(p.s().value());
            if (C14N.matcher(p.o().value()).matches()) globalLabels.add(p.o().value());
        }
        // 4 distinct blank nodes → 4 distinct global canonical labels.
        assertEquals(4, globalLabels.size());

        // Labels are contiguous starting from 0.
        for (int i = 0; i < 4; i++) {
            assertTrue(
                    globalLabels.contains("_:c14n" + i),
                    "expected _:c14n" + i + " in output: " + globalLabels);
        }
    }

    /**
     * Rename-stability: rewrite every input blank to a fresh label; the canonical output quad set
     * must be byte-equal.
     */
    @Test
    void renameStability_acrossBnccs() {
        List<QuadPattern> graphA =
                List.of(
                        q("_:a", "ex:age", "30"),
                        q("_:b", "ex:age", "25"),
                        q("_:c1", "ex:knows", "_:c2"),
                        q("_:c2", "ex:knows", "_:c1"));
        // Same graph, different input labels.
        List<QuadPattern> graphB =
                List.of(
                        q("_:zoo", "ex:age", "30"),
                        q("_:apple", "ex:age", "25"),
                        q("_:xy", "ex:knows", "_:yz"),
                        q("_:yz", "ex:knows", "_:xy"));

        Set<QuadPattern> cA =
                new HashSet<>(BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graphA));
        Set<QuadPattern> cB =
                new HashSet<>(BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graphB));
        assertEquals(cA, cB, "renamed multi-BNCC graph must produce same canonical quad set");
    }

    @Test
    void determinism_sameInputProducesSameOutput() {
        List<QuadPattern> graph = List.of(q("_:a", "ex:age", "30"), q("_:b", "ex:age", "25"));
        List<QuadPattern> first = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        List<QuadPattern> second = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        assertEquals(first, second);
    }

    @Test
    void idempotence_canonicalizeOfCanonical_isNoOp() {
        List<QuadPattern> graph = List.of(q("_:a", "ex:age", "30"), q("_:b", "ex:age", "25"));
        List<QuadPattern> once = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        List<QuadPattern> twice = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(once);
        assertEquals(once, twice);
    }

    /**
     * Documented divergence: for multi-BNCC graphs, BNCC-partitioned output is NOT byte-equal to
     * monolithic URDNA2015 output. The canonical-name assignment order differs. Users opt in
     * knowingly via this canonicalizer; the contract is preserved (deterministic + rename-stable)
     * but the byte-form differs.
     */
    @Test
    void multiBncc_outputDiffersFromMonolithicURDNA2015_byDesign() {
        List<QuadPattern> graph =
                List.of(
                        q("_:a", "ex:age", "30"),
                        q("_:b", "ex:age", "25"),
                        q("_:c", "ex:age", "40"));
        List<QuadPattern> partitioned = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        List<QuadPattern> monolithic = UrdnaCanonicalizer.INSTANCE.canonicalize(graph);

        // Both have 3 quads with c14n0/c14n1/c14n2 labels somewhere.
        assertEquals(3, partitioned.size());
        assertEquals(3, monolithic.size());

        // But the specific assignment of labels to literals can differ.
        // We don't assert equality; we assert label coverage as a sanity check.
        Map<String, String> partitionedByLit = labelByObject(partitioned);
        Map<String, String> monolithicByLit = labelByObject(monolithic);
        assertEquals(3, partitionedByLit.size());
        assertEquals(3, monolithicByLit.size());
        // (Either canonicalizer's mapping is a valid canonicalization;
        // they don't need to agree.)
    }

    /**
     * Use a non-default inner canonicalizer: works with first-degree for cheap workloads where
     * BNCCs are small and never cyclic.
     */
    @Test
    void customInnerCanonicalizer_isUsable() {
        BnccPartitionedCanonicalizer bp =
                new BnccPartitionedCanonicalizer(SimpleFirstDegreeCanonicalizer.INSTANCE);
        List<QuadPattern> graph = List.of(q("_:a", "ex:age", "30"), q("_:b", "ex:age", "25"));
        // First-degree resolves these independently; partitioner combines.
        List<QuadPattern> output = bp.canonicalize(graph);
        assertEquals(2, output.size());
    }

    private static Map<String, String> labelByObject(List<QuadPattern> canon) {
        Map<String, String> m = new HashMap<>();
        for (QuadPattern p : canon) {
            if (C14N.matcher(p.s().value()).matches()) {
                m.put(p.o().value(), p.s().value());
            }
        }
        return m;
    }
}
