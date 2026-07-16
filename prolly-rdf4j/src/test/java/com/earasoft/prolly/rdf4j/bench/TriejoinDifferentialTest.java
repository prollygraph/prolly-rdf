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
package com.earasoft.prolly.rdf4j.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.bench.TriejoinVsRdf4jBenchmark.Edge;
import com.earasoft.prolly.semantic.GraphPatternEngine;
import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;

/**
 * Phase 5 (Step 19, standalone form) of {@code multi-variable-leapfrog-triejoin.md} — the
 * <b>differential SPARQL correctness harness</b>, run <i>without</i> wiring the triejoin into the
 * live Sail (Step 17 is blocked on the TermId index-encoding gap; the chosen path validates
 * correctness standalone).
 *
 * <p>For a sweep of graph families × query shapes, the native leapfrog triejoin's answer must equal
 * RDF4J's (the oracle, over a {@code MemoryStore}) <b>both as a set and as a multiset</b> —
 * sorted-list equality catches a dedup or cardinality regression that set-equality alone would
 * miss. Because {@code executeMulti} returns <i>full</i> bindings (no projection), each solution is
 * distinct, so the meaningful cardinality guarantee here is "neither drops nor duplicates any full
 * binding vs RDF4J"; projection-level multiplicity is a property of the algebra RDF4J layers on
 * top, exercised only once the triejoin is wired in (deferred).
 */
class TriejoinDifferentialTest {

    private static final List<String> VARS = List.of("x", "y", "z");
    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void triejoinMatchesRdf4jAsSetAndMultisetAcrossFamiliesAndShapes() {
        record Case(String name, Set<Edge> edges) {}
        List<Case> graphs =
                List.of(
                        new Case("dense-20", TriejoinVsRdf4jBenchmark.denseCore(20)),
                        new Case("dense-60", TriejoinVsRdf4jBenchmark.denseCore(60)),
                        new Case("star-6", TriejoinVsRdf4jBenchmark.star(6)),
                        new Case("star-12", TriejoinVsRdf4jBenchmark.star(12)),
                        new Case("sparse-40-s1", TriejoinVsRdf4jBenchmark.sparseRandom(40, 1L)),
                        new Case("sparse-40-s2", TriejoinVsRdf4jBenchmark.sparseRandom(40, 2L)),
                        new Case("sparse-80-s3", TriejoinVsRdf4jBenchmark.sparseRandom(80, 3L)));

        long totalRows = 0;
        for (Case g : graphs) {
            try (DirectBufferPool pool = new DirectBufferPool()) {
                GraphPatternEngine engine =
                        TriejoinVsRdf4jBenchmark.buildNativeEngine(g.edges(), pool);
                Repository oracle = TriejoinVsRdf4jBenchmark.buildMemory(g.edges());
                try {
                    for (String shape : List.of("triangle", "path2", "star")) {
                        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor(shape);
                        String sparql = TriejoinVsRdf4jBenchmark.sparqlFor(shape);

                        List<List<String>> got =
                                TriejoinVsRdf4jBenchmark.rowsNative(engine, patterns, ORDER, VARS);
                        List<List<String>> exp =
                                TriejoinVsRdf4jBenchmark.rowsSparql(oracle, sparql, VARS);
                        String where = g.name() + "/" + shape;

                        // Multiset equality (sorted list) — also implies set equality.
                        assertEquals(
                                sorted(exp),
                                sorted(got),
                                where + ": triejoin must equal RDF4J as a multiset");
                        // Explicit set equality, per the plan's "set AND multiset".
                        assertEquals(
                                Set.copyOf(exp),
                                Set.copyOf(got),
                                where + ": triejoin must equal RDF4J as a set");
                        // No spurious internal duplicates in the native result (full bindings are
                        // distinct).
                        assertEquals(
                                got.size(),
                                Set.copyOf(got).size(),
                                where + ": native rows must be distinct");

                        totalRows += got.size();
                    }
                } finally {
                    oracle.shutDown();
                }
            }
        }
        assertTrue(
                totalRows > 0,
                "the sweep must exercise non-empty result sets (dense core has triangles)");
    }

    private static List<List<String>> sorted(List<List<String>> rows) {
        List<List<String>> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparing(r -> String.join("", r)));
        return copy;
    }
}
