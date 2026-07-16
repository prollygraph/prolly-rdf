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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Step 7 of {@code prolly-rdf/plans/triejoin-streaming-results.md} — the <b>Sail end-to-end
 * half</b> of the {@code LIMIT}/early-close short-circuit (D-6). The engine work-bound (seek work ≪
 * full) is pinned by {@code TriejoinLimitShortCircuitTest}; this pins the wiring through the lazy
 * {@link ProllyEvaluationStrategy} consumer:
 *
 * <ul>
 *   <li><b>{@code LIMIT k} correctness</b> — a {@code LIMIT 5} cyclic-triangle query, flag-ON,
 *       returns <em>exactly</em> 5 rows, each a member of the full (unlimited) result. RDF4J's
 *       {@code LimitIteration} stops pulling at 5 → the lazy consumer advances the cursor ~5 times.
 *   <li><b>Early consumer-close is clean</b> — pulling a couple rows then closing the {@code
 *       TupleQueryResult} <em>without</em> draining drives {@code handleClose()} while the cursor
 *       is mid-descent ({@code cursor.close()} then {@code pool.close()} → {@code arena.close()}
 *       frees the still-borrowed buffers wholesale). This is the only path that exercises
 *       early-{@code handleClose}; every Step 6 test fully drains.
 * </ul>
 */
class SailTriejoinLimitTest {

    private static final String EDGE = "urn:e";
    private static final String TRIANGLE =
            "SELECT ?x ?y ?z WHERE { ?x <"
                    + EDGE
                    + "> ?y . ?y <"
                    + EDGE
                    + "> ?z . ?z <"
                    + EDGE
                    + "> ?x }";
    private static final int N = 6; // complete digraph: 30 edges, 6·5·4 = 120 triangle solutions

    @Test
    void limit5ReturnsExactly5AndIsASubsetOfTheFullResult() {
        SailRepository repo = completeDigraphRepo(true);
        try (RepositoryConnection conn = repo.getConnection()) {
            Set<List<String>> full = collect(conn, TRIANGLE);
            assertEquals((long) N * (N - 1) * (N - 2), full.size(), "full triangle count");

            List<List<String>> limited = collectList(conn, TRIANGLE + " LIMIT 5");
            assertEquals(5, limited.size(), "LIMIT 5 must return exactly 5 rows");
            for (List<String> row : limited) {
                assertTrue(full.contains(row), "each LIMIT row must be a real solution: " + row);
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void earlyConsumerCloseIsClean() {
        SailRepository repo = completeDigraphRepo(true);
        try (RepositoryConnection conn = repo.getConnection()) {
            int pulled = 0;
            // Pull two rows, then let try-with-resources close the result WITHOUT draining —
            // handleClose() runs mid-descent. A leak/assert in pool.close() would surface here.
            try (TupleQueryResult r =
                    conn.prepareTupleQuery(QueryLanguage.SPARQL, TRIANGLE).evaluate()) {
                while (pulled < 2 && r.hasNext()) {
                    r.next();
                    pulled++;
                }
            }
            assertEquals(2, pulled, "graph has ≫2 triangles, so two rows must be pullable");

            // The connection is still usable after an early close (no corrupted pool/cursor state):
            // a fresh full query succeeds.
            assertEquals(
                    (long) N * (N - 1) * (N - 2),
                    collect(conn, TRIANGLE).size(),
                    "a full query after an early close must still work");
        } finally {
            repo.shutDown();
        }
    }

    private static SailRepository completeDigraphRepo(boolean triejoinEnabled) {
        ProllySail sail = new ProllySail(); // in-memory
        sail.setTriejoinEnabled(triejoinEnabled);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(EDGE);
            conn.begin();
            for (int i = 0; i < N; i++) {
                for (int j = 0; j < N; j++) {
                    if (i == j) continue;
                    conn.add(vf.createIRI("urn:n:V" + i), e, vf.createIRI("urn:n:V" + j));
                }
            }
            conn.commit();
        }
        return repo;
    }

    private static Set<List<String>> collect(RepositoryConnection conn, String sparql) {
        return new HashSet<>(collectList(conn, sparql));
    }

    private static List<List<String>> collectList(RepositoryConnection conn, String sparql) {
        List<List<String>> out = new ArrayList<>();
        try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                out.add(
                        List.of(
                                b.getValue("x").stringValue(),
                                b.getValue("y").stringValue(),
                                b.getValue("z").stringValue()));
            }
        }
        return out;
    }
}
