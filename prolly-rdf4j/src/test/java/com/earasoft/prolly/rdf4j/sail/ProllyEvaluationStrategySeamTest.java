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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Phase 0 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md} — pins the <b>inert seam</b>:
 * flipping {@link ProllySail#setTriejoinEnabled} routes evaluation through {@link
 * ProllyEvaluationStrategy} (vs the stock {@code DefaultEvaluationStrategy}), and — because that
 * strategy overrides nothing yet — a cyclic-triangle SPARQL query returns the <b>identical</b>
 * result set either way. This is the contract that lets later phases add real routing without
 * changing flag-OFF behaviour, and proves flag-ON is safe (no-op) before any optimizer exists.
 */
class ProllyEvaluationStrategySeamTest {

    private static final String TRIANGLE =
            "SELECT ?x ?y ?z WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x }";

    @Test
    void triejoinFlagIsInert_flagOnResultsEqualFlagOff() {
        Set<List<String>> off = runTriangle(false);
        Set<List<String>> on = runTriangle(true);
        assertFalse(off.isEmpty(), "sanity: the cyclic triangle query should return rows");
        assertEquals(
                off,
                on,
                "Phase 0: ProllyEvaluationStrategy must be inert — flag-ON results must equal flag-OFF");
    }

    private static Set<List<String>> runTriangle(boolean triejoinEnabled) {
        ProllySail sail = new ProllySail(); // in-memory
        sail.setTriejoinEnabled(triejoinEnabled);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            IRI e = conn.getValueFactory().createIRI("urn:e");
            conn.begin();
            // directed triangle a->b->c->a, plus a non-triangle edge a->c
            String[][] edges = {{"a", "b"}, {"b", "c"}, {"c", "a"}, {"a", "c"}};
            for (String[] edge : edges) {
                conn.add(
                        conn.getValueFactory().createIRI("urn:n:" + edge[0]),
                        e,
                        conn.getValueFactory().createIRI("urn:n:" + edge[1]));
            }
            conn.commit();
            Set<List<String>> out = new HashSet<>();
            try (TupleQueryResult r =
                    conn.prepareTupleQuery(QueryLanguage.SPARQL, TRIANGLE).evaluate()) {
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
        } finally {
            repo.shutDown();
        }
    }
}
