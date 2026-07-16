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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies the BNCC partitioner correctly groups blank nodes into connected components and
 * correctly partitions quads into the all-named bucket vs per-BNCC buckets.
 *
 * <p>Eight tests covering the cases from {@code BnccPartitioner}'s Javadoc plus the harder
 * invariants:
 *
 * <ol>
 *   <li>Empty graph
 *   <li>All-named graph
 *   <li>Single BNCC (one blank, multiple quads)
 *   <li>Two disjoint BNCCs sharing a named IRI
 *   <li>Two-blank quad unifies its blank endpoints
 *   <li>Cyclic blank pair share a BNCC
 *   <li>Symmetric distinct blanks are in SEPARATE BNCCs
 *   <li>Every input quad lands in exactly one output bucket
 * </ol>
 */
class BnccPartitionerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    @Test
    void emptyGraph_partitionsToEmpty() {
        var result = BnccPartitioner.partition(List.of());
        assertEquals(0, result.bnccCount());
        assertEquals(List.of(), result.allNamedQuads());
        assertEquals(List.of(), result.perBnccQuads());
        assertEquals(0, result.bnodeToBnccId().size());
    }

    @Test
    void allNamedGraph_yieldsZeroBnccsAllAllNamed() {
        List<QuadPattern> graph =
                List.of(q("ex:alice", "ex:knows", "ex:bob"), q("ex:bob", "ex:age", "30"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(0, result.bnccCount());
        assertEquals(2, result.allNamedQuads().size());
        assertTrue(result.perBnccQuads().isEmpty());
    }

    @Test
    void singleBlank_multipleQuads_yieldsOneBncc() {
        List<QuadPattern> graph = List.of(q("_:a", "ex:knows", "ex:bob"), q("_:a", "ex:age", "30"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(1, result.bnccCount());
        assertEquals(0, result.allNamedQuads().size());
        assertEquals(2, result.perBnccQuads().get(0).size());
        assertEquals(Integer.valueOf(0), result.bnodeToBnccId().get("_:a"));
    }

    /**
     * Key invariant: {@code _:b1} and {@code _:b2} both refer to {@code ex:bob}, but they are NOT
     * connected via a blank-blank edge — so they belong to different BNCCs.
     */
    @Test
    void symmetricDistinctBlanks_belongToSeparateBnccs() {
        List<QuadPattern> graph =
                List.of(
                        q("_:b1", "ex:knows", "ex:bob"),
                        q("_:b1", "ex:age", "30"),
                        q("_:b2", "ex:knows", "ex:bob"),
                        q("_:b2", "ex:age", "30"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(
                2,
                result.bnccCount(),
                "expected two BNCCs (no blank-blank edge between _:b1 and _:b2)");

        Integer b1 = result.bnodeToBnccId().get("_:b1");
        Integer b2 = result.bnodeToBnccId().get("_:b2");
        assertNotEquals(b1, b2);

        // Each BNCC has 2 quads.
        assertEquals(2, result.perBnccQuads().get(b1).size());
        assertEquals(2, result.perBnccQuads().get(b2).size());
        assertEquals(0, result.allNamedQuads().size());
    }

    /** Two-blank quad: the endpoints get unioned into one BNCC. */
    @Test
    void twoBlankQuad_unifiesBlanks() {
        List<QuadPattern> graph = List.of(q("_:a", "ex:knows", "_:b"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(1, result.bnccCount());
        assertEquals(result.bnodeToBnccId().get("_:a"), result.bnodeToBnccId().get("_:b"));
    }

    /** Cyclic pair shares a BNCC. */
    @Test
    void cyclicPair_sharesBncc() {
        List<QuadPattern> graph =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(1, result.bnccCount());
        assertEquals(result.bnodeToBnccId().get("_:b1"), result.bnodeToBnccId().get("_:b2"));
        assertEquals(2, result.perBnccQuads().get(0).size());
    }

    /** Longer chain: all blanks in one BNCC, transitive closure works. */
    @Test
    void longChain_transitiveUnion() {
        List<QuadPattern> graph =
                List.of(
                        q("_:a", "ex:next", "_:b"),
                        q("_:b", "ex:next", "_:c"),
                        q("_:c", "ex:next", "_:d"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(1, result.bnccCount());
        Integer id = result.bnodeToBnccId().get("_:a");
        assertEquals(id, result.bnodeToBnccId().get("_:b"));
        assertEquals(id, result.bnodeToBnccId().get("_:c"));
        assertEquals(id, result.bnodeToBnccId().get("_:d"));
    }

    /**
     * Each input quad ends up in exactly one output bucket: allNamedQuads or exactly one
     * perBnccQuads list.
     */
    @Test
    void everyQuad_landsInExactlyOneBucket() {
        List<QuadPattern> graph =
                List.of(
                        q("ex:alice", "ex:knows", "ex:bob"), // all-named
                        q("_:a", "ex:knows", "ex:bob"), // BNCC of _:a
                        q("_:a", "ex:age", "30"), // BNCC of _:a
                        q("_:b1", "ex:knows", "_:b2"), // BNCC of _:b1/_:b2
                        q("_:b2", "ex:age", "25"), // BNCC of _:b1/_:b2
                        q("_:c", "ex:visited", "ex:paris") // BNCC of _:c
                        );
        var result = BnccPartitioner.partition(graph);
        assertEquals(3, result.bnccCount());

        int total = result.allNamedQuads().size();
        for (List<QuadPattern> sub : result.perBnccQuads()) total += sub.size();
        assertEquals(graph.size(), total, "every input quad must appear in exactly one bucket");

        // Cross-check via a set: no duplication.
        Set<QuadPattern> all = new HashSet<>(result.allNamedQuads());
        for (List<QuadPattern> sub : result.perBnccQuads()) all.addAll(sub);
        assertEquals(graph.size(), all.size(), "no quad may appear in two buckets");
    }

    /** Mixed BNCC sizes: small + large in same input. */
    @Test
    void mixedBnccSizes_assignedCorrectly() {
        List<QuadPattern> graph =
                List.of(
                        // BNCC 1: cycle (_:a, _:b, _:c)
                        q("_:a", "ex:p", "_:b"),
                        q("_:b", "ex:p", "_:c"),
                        q("_:c", "ex:p", "_:a"),
                        // BNCC 2: lone blank _:lonely
                        q("_:lonely", "ex:p", "ex:end"),
                        // No-blank quad
                        q("ex:foo", "ex:bar", "ex:baz"));
        var result = BnccPartitioner.partition(graph);
        assertEquals(2, result.bnccCount());
        assertEquals(1, result.allNamedQuads().size());

        Integer cycleId = result.bnodeToBnccId().get("_:a");
        assertEquals(cycleId, result.bnodeToBnccId().get("_:b"));
        assertEquals(cycleId, result.bnodeToBnccId().get("_:c"));
        Integer lonelyId = result.bnodeToBnccId().get("_:lonely");
        assertNotEquals(cycleId, lonelyId);

        // BNCC sizes: cycle has 3 quads, lonely has 1.
        assertEquals(3, result.perBnccQuads().get(cycleId).size());
        assertEquals(1, result.perBnccQuads().get(lonelyId).size());
    }
}
