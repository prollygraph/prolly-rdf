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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the parallel BnccPartitionedCanonicalizer produces exactly the same output as the
 * sequential variant — determinism holds regardless of the thread schedule the commonPool happens
 * to use.
 */
class ParallelBnccCanonicalizerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    /** Parallel output must equal sequential output across many BNCCs. */
    @Test
    void parallel_equalsSequential_onManyBnccs() {
        List<QuadPattern> graph = manyBnccsGraph(50);

        var sequential = BnccPartitionedCanonicalizer.INSTANCE;
        var parallel = BnccPartitionedCanonicalizer.PARALLEL_INSTANCE;

        assertTrue(sequential.isParallel() == false);
        assertTrue(parallel.isParallel() == true);

        List<QuadPattern> seqOut = sequential.canonicalize(graph);
        List<QuadPattern> parOut = parallel.canonicalize(graph);

        assertEquals(seqOut, parOut, "parallel output must match sequential output byte-for-byte");
    }

    /** Parallel mode is also rename-stable. */
    @Test
    void parallel_isRenameStable() {
        List<QuadPattern> graphA = manyBnccsGraph(20);

        // Build a rename of graphA by appending an arbitrary suffix to every blank.
        List<QuadPattern> graphB = new ArrayList<>(graphA.size());
        for (QuadPattern p : graphA) {
            graphB.add(q(rename(p.s().value()), p.p().value(), rename(p.o().value())));
        }

        var parallel = BnccPartitionedCanonicalizer.PARALLEL_INSTANCE;
        Set<QuadPattern> cA = new HashSet<>(parallel.canonicalize(graphA));
        Set<QuadPattern> cB = new HashSet<>(parallel.canonicalize(graphB));

        assertEquals(cA, cB);
    }

    /** Parallel + idempotent: canonicalize(canonicalize(x)) == canonicalize(x). */
    @Test
    void parallel_isIdempotent() {
        List<QuadPattern> graph = manyBnccsGraph(30);
        var parallel = BnccPartitionedCanonicalizer.PARALLEL_INSTANCE;
        List<QuadPattern> once = parallel.canonicalize(graph);
        List<QuadPattern> twice = parallel.canonicalize(once);
        assertEquals(once, twice);
    }

    /** Determinism: same input twice yields same output even in parallel. */
    @Test
    void parallel_isDeterministic() {
        List<QuadPattern> graph = manyBnccsGraph(40);
        var parallel = BnccPartitionedCanonicalizer.PARALLEL_INSTANCE;
        List<QuadPattern> first = parallel.canonicalize(graph);
        List<QuadPattern> second = parallel.canonicalize(graph);
        List<QuadPattern> third = parallel.canonicalize(graph);
        assertEquals(first, second);
        assertEquals(second, third);
    }

    /**
     * Factory ergonomic: the parallel() factory produces an instance whose isParallel() == true and
     * whose inner is the supplied one.
     */
    @Test
    void parallelFactory_setsExpectedFields() {
        var p = BnccPartitionedCanonicalizer.parallel(UrdnaCanonicalizer.INSTANCE);
        assertTrue(p.isParallel());
        assertEquals(UrdnaCanonicalizer.INSTANCE, p.inner());
    }

    /**
     * Single-BNCC case: parallel mode short-circuits to direct delegation, just like sequential.
     */
    @Test
    void singleBncc_parallelDelegates() {
        List<QuadPattern> graph = List.of(q("_:a", "ex:knows", "_:b"), q("_:b", "ex:age", "30"));
        List<QuadPattern> seqOut = BnccPartitionedCanonicalizer.INSTANCE.canonicalize(graph);
        List<QuadPattern> parOut =
                BnccPartitionedCanonicalizer.PARALLEL_INSTANCE.canonicalize(graph);
        assertEquals(seqOut, parOut);
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Construct a graph with {@code n} BNCCs, each a single-blank "_:bN ex:age <iri>" quad.
     * Distinct ages ensure each BNCC has a unique first-degree hash, so they don't merge.
     */
    private static List<QuadPattern> manyBnccsGraph(int n) {
        List<QuadPattern> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(q("_:b" + i, "ex:age", "ex:age" + i));
        }
        return out;
    }

    private static String rename(String value) {
        if (value.startsWith("_:")) return "_:zoo" + value.substring(2);
        return value;
    }
}
