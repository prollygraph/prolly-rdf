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
package com.earasoft.prolly.flatsail;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Step 10 — the Phase 1 closing test: {@link RocksDbFlatSail} driven end to end through a {@link
 * SailRepository}, covering the add/query/remove round-trip, transactions, named graphs,
 * persistence across a reopen, and SPARQL.
 */
class RocksDbFlatSailTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final String EX = "http://example.org/";

    @TempDir Path dir;

    private SailRepository repo;
    private ValueFactory vf;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new RocksDbFlatSail(dir));
        repo.init();
        vf = repo.getValueFactory();
    }

    @AfterEach
    void tearDown() {
        if (repo != null) {
            repo.shutDown();
        }
    }

    private IRI iri(String localName) {
        return vf.createIRI(EX + localName);
    }

    private static List<BindingSet> select(RepositoryConnection conn, String sparql) {
        List<BindingSet> rows = new ArrayList<>();
        try (TupleQueryResult result =
                conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            while (result.hasNext()) {
                rows.add(result.next());
            }
        }
        return rows;
    }

    @Test
    void add_query_remove_roundtrip() {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(iri("alice"), iri("knows"), iri("bob"));
            conn.add(iri("alice"), iri("knows"), iri("carol"));
            conn.add(iri("bob"), iri("knows"), iri("carol"));
            assertEquals(3, conn.size());

            try (RepositoryResult<Statement> r =
                    conn.getStatements(iri("alice"), iri("knows"), null)) {
                assertEquals(2, count(r), "alice knows two people");
            }

            conn.remove(iri("alice"), iri("knows"), iri("bob"));
            assertEquals(2, conn.size());
        }
    }

    @Test
    void rollback_discards_writes_and_commit_keeps_them() {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.add(iri("s"), iri("p"), iri("o"));
            conn.rollback();
            assertEquals(0, conn.size(), "rolled-back add must not persist");

            conn.begin();
            conn.add(iri("s"), iri("p"), iri("o"));
            conn.commit();
            assertEquals(1, conn.size(), "committed add must persist");
        }
    }

    @Test
    void named_graphs_are_isolated() {
        IRI graph = iri("graph1");
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(iri("s"), iri("p"), iri("inDefault"));
            conn.add(iri("s"), iri("p"), iri("inGraph1"), graph);

            assertEquals(2, conn.size(), "no contexts -> whole store");
            assertEquals(1, conn.size(graph), "size(graph) counts only that graph");

            try (RepositoryResult<Statement> r = conn.getStatements(null, null, null, graph)) {
                List<Statement> inGraph = drain(r);
                assertEquals(1, inGraph.size());
                assertEquals(graph, inGraph.get(0).getContext());
            }
        }
    }

    @Test
    void data_survives_a_repository_reopen() {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(iri("alice"), iri("knows"), iri("bob"));
        }
        repo.shutDown();

        // Reopen a fresh repository over the same directory.
        repo = new SailRepository(new RocksDbFlatSail(dir));
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            assertEquals(1, conn.size(), "committed data must survive a reopen");
            try (RepositoryResult<Statement> r = conn.getStatements(iri("alice"), null, null)) {
                assertEquals(1, count(r));
            }
        }
    }

    @Test
    void sparql_select_returns_matching_bindings() {
        try (RepositoryConnection conn = repo.getConnection()) {
            // Plain string literals — SPARQL "Alice" is xsd:string, matching
            // vf.createLiteral(String) exactly (no datatype ambiguity).
            conn.add(iri("alice"), iri("name"), vf.createLiteral("Alice"));
            conn.add(iri("bob"), iri("name"), vf.createLiteral("Bob"));

            List<BindingSet> rows =
                    select(conn, "SELECT ?p WHERE { ?p <" + EX + "name> \"Alice\" }");
            assertEquals(1, rows.size());
            assertEquals(iri("alice"), rows.get(0).getValue("p"));
        }
    }

    @Test
    void sparql_ask() {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(iri("s"), iri("p"), iri("o"));
            assertTrue(
                    conn.prepareBooleanQuery(
                                    QueryLanguage.SPARQL,
                                    "ASK { <" + EX + "s> <" + EX + "p> <" + EX + "o> }")
                            .evaluate());
            assertFalse(
                    conn.prepareBooleanQuery(
                                    QueryLanguage.SPARQL,
                                    "ASK { <" + EX + "s> <" + EX + "p> <" + EX + "missing> }")
                            .evaluate());
        }
    }

    @Test
    void sparql_basic_graph_pattern_join() {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(iri("alice"), iri("knows"), iri("bob"));
            conn.add(iri("bob"), iri("name"), vf.createLiteral("Bob"));

            List<BindingSet> rows =
                    select(
                            conn,
                            "SELECT ?name WHERE {"
                                    + " <"
                                    + EX
                                    + "alice> <"
                                    + EX
                                    + "knows> ?friend ."
                                    + " ?friend <"
                                    + EX
                                    + "name> ?name . }");
            assertEquals(1, rows.size());
            assertEquals("Bob", rows.get(0).getValue("name").stringValue());
        }
    }

    @Test
    void sparql_graph_clause_reads_a_named_graph() {
        IRI graph = iri("g");
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.add(iri("s"), iri("p"), iri("o"), graph);

            List<BindingSet> rows =
                    select(
                            conn,
                            "SELECT ?o WHERE { GRAPH <"
                                    + EX
                                    + "g> { <"
                                    + EX
                                    + "s> <"
                                    + EX
                                    + "p> ?o } }");
            assertEquals(1, rows.size());
            assertEquals(iri("o"), rows.get(0).getValue("o"));
        }
    }

    private static int count(RepositoryResult<Statement> result) {
        int n = 0;
        while (result.hasNext()) {
            result.next();
            n++;
        }
        return n;
    }

    private static List<Statement> drain(RepositoryResult<Statement> result) {
        List<Statement> out = new ArrayList<>();
        while (result.hasNext()) {
            out.add(result.next());
        }
        return out;
    }
}
