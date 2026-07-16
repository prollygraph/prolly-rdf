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
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Step 8 of {@code prolly-rdf/plans/triejoin-streaming-results.md} — the <b>within-transaction
 * read-your-writes</b> contract for the lazy triejoin consumer. {@code
 * ProllySailReadYourWritesTest} pins it for the <em>scan</em> read path ({@code
 * getStatements}/{@code size}); this pins it for the <em>flag-ON SPARQL triejoin</em> path: {@code
 * ProllyEvaluationStrategy.evaluateTriejoin} reads the indexes via {@code
 * conn.triejoinIndexRoot(order)}, which calls {@code QuadIndex.commit()} to flush the
 * per-transaction buffer — so a cyclic query must see triples added <em>earlier in the same
 * transaction, before any commit</em>, exactly as the old materialized path did.
 *
 * <p>The graph is a pure directed 3-cycle {@code a→b→c→a}; the triangle query {@code ?x :e ?y . ?y
 * :e ?z . ?z :e ?x} has exactly its 3 rotations as solutions.
 */
class SailTriejoinReadYourWritesTest {

    private static final String EDGE = "urn:e";
    private static final String TRIANGLE =
            "SELECT ?x ?y ?z WHERE { ?x <"
                    + EDGE
                    + "> ?y . ?y <"
                    + EDGE
                    + "> ?z . ?z <"
                    + EDGE
                    + "> ?x }";

    @Test
    void uncommittedTriangleIsVisibleToAFlagOnCyclicQuery() {
        SailRepository repo = flagOnRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(EDGE);
            conn.begin();
            addEdge(conn, vf, e, "a", "b");
            addEdge(conn, vf, e, "b", "c");
            addEdge(conn, vf, e, "c", "a");

            // NO commit yet: the triejoin must read the flushed in-tx index.
            List<List<String>> rows = collect(conn, TRIANGLE);
            assertEquals(3, rows.size(), "the 3-cycle's 3 rotations must be visible within the tx");
            assertTrue(
                    rows.contains(List.of("urn:n:a", "urn:n:b", "urn:n:c")),
                    "the (a,b,c) rotation must be present: " + rows);
            conn.commit();
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void eachInTxQueryRereadsTheFlushedIndex() {
        // Proves the cursor reads CURRENT in-tx state per evaluation, not a stale snapshot: the
        // triangle appears only after the closing edge is added — within one uncommitted tx.
        SailRepository repo = flagOnRepo();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(EDGE);
            conn.begin();
            addEdge(conn, vf, e, "a", "b");
            addEdge(conn, vf, e, "b", "c");
            assertEquals(0, collect(conn, TRIANGLE).size(), "no triangle until the cycle closes");

            addEdge(conn, vf, e, "c", "a"); // close the cycle, same tx, no commit
            assertEquals(
                    3, collect(conn, TRIANGLE).size(), "the closing edge completes the triangle");
            conn.commit();
        } finally {
            repo.shutDown();
        }
    }

    private static SailRepository flagOnRepo() {
        ProllySail sail = new ProllySail(); // in-memory
        sail.setTriejoinEnabled(true);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        return repo;
    }

    private static void addEdge(
            RepositoryConnection conn, ValueFactory vf, IRI e, String s, String o) {
        conn.add(vf.createIRI("urn:n:" + s), e, vf.createIRI("urn:n:" + o));
    }

    private static List<List<String>> collect(RepositoryConnection conn, String sparql) {
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
