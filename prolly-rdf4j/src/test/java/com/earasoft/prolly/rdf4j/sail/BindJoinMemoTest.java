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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code prolly-rdf4j/plans/join-approaches-benchmark.md} — correctness lock-in for the
 * bind-join inner-re-probe memo ({@link MemoizingTripleSource}). The memo is an optimization: it
 * must not change answers. This pins the do-not-break contract from the plan: <b>memo-on results ==
 * memo-off results</b> for the representative {@code ?c rdfs:subClassOf ?s . ?s rdfs:label ?l}
 * join, and that the memo <b>actually engages</b> (the recurring superclass {@code ?s} produces
 * hits — otherwise the test would pass trivially while measuring nothing).
 */
class BindJoinMemoTest {

    @Test
    void memoOnEqualsMemoOffAndActuallyHits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), registry);
        Repository repo = new SailRepository(sail);
        repo.init();
        try {
            // A subclass hierarchy that FANS IN: six classes share two superclasses → ?s recurs, so
            // the
            // inner `?s rdfs:label ?l` re-probe repeats (the memoizable shape).
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = conn.getValueFactory();
                IRI sup1 = vf.createIRI("urn:super:1");
                IRI sup2 = vf.createIRI("urn:super:2");
                conn.begin();
                conn.add(sup1, RDFS.LABEL, vf.createLiteral("Super One"));
                conn.add(sup2, RDFS.LABEL, vf.createLiteral("Super Two"));
                for (int i = 0; i < 6; i++) {
                    conn.add(
                            vf.createIRI("urn:c:" + i),
                            RDFS.SUBCLASSOF,
                            (i % 2 == 0) ? sup1 : sup2);
                }
                conn.commit();
            }

            String q =
                    "SELECT ?c ?l WHERE { ?c <"
                            + RDFS.SUBCLASSOF
                            + "> ?s . ?s <"
                            + RDFS.LABEL
                            + "> ?l }";

            sail.setBindJoinMemoEnabled(false);
            Set<String> off = runJoin(repo, q);

            sail.setBindJoinMemoEnabled(true);
            Set<String> on = runJoin(repo, q);

            assertEquals(off, on, "memo must not change the join's answers");
            assertEquals(6, off.size(), "six subclasses, each resolves to its superclass label");
            double hits = registry.get("prolly.bindjoinmemo.hits").counter().count();
            assertTrue(
                    hits > 0, "the recurring superclass must produce memo hits (got " + hits + ")");
        } finally {
            repo.shutDown();
        }
    }

    private static Set<String> runJoin(Repository repo, String q) {
        Set<String> rows = new HashSet<>();
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                rows.add(b.getValue("c").stringValue() + " -> " + b.getValue("l").stringValue());
            }
        }
        return rows;
    }
}
