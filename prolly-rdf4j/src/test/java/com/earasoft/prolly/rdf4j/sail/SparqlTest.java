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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.BooleanQuery;
import org.eclipse.rdf4j.query.GraphQuery;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SPARQL end-to-end tests. Covers SELECT, ASK, CONSTRUCT (iter 30), UPDATE (iter 31), and a small
 * conformance subset (iter 32).
 *
 * <p>Each test boots a fresh {@link ProllySail} wrapped in a {@link SailRepository}, seeds it with
 * statements, then runs SPARQL via {@link RepositoryConnection}. The Sail's {@code
 * evaluateInternal} routes triple-pattern scans back through {@code getStatements} → planner →
 * indexes.
 */
class SparqlTest {

    private Repository repo;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new ProllySail());
        repo.init();
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    /** Convenience: ingest a few canonical statements. */
    private void seed() {
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = repo.getValueFactory();
            IRI alice = vf.createIRI("http://example/alice");
            IRI bob = vf.createIRI("http://example/bob");
            IRI carol = vf.createIRI("http://example/carol");
            IRI knows = vf.createIRI("http://example/knows");
            IRI age = vf.createIRI("http://example/age");
            IRI name = vf.createIRI("http://example/name");
            IRI person = vf.createIRI("http://example/Person");
            conn.begin();
            conn.add(alice, RDF.TYPE, person);
            conn.add(bob, RDF.TYPE, person);
            conn.add(carol, RDF.TYPE, person);
            conn.add(alice, name, vf.createLiteral("Alice"));
            conn.add(bob, name, vf.createLiteral("Bob"));
            conn.add(alice, knows, bob);
            conn.add(bob, knows, carol);
            conn.add(alice, age, vf.createLiteral(30));
            conn.add(bob, age, vf.createLiteral(25));
            conn.commit();
        }
    }

    // ==================================================================
    // Iter 30 — SELECT, ASK, CONSTRUCT
    // ==================================================================
    @Nested
    class SelectAndFriends {

        @Test
        void select_all_triples() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL, "SELECT ?s ?p ?o WHERE { ?s ?p ?o }");
                List<BindingSet> rows = new ArrayList<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) rows.add(r.next());
                }
                assertEquals(9, rows.size(), "all 9 seed statements visible");
            }
        }

        @Test
        void select_filter_by_type() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "PREFIX rdf: <"
                                        + RDF.NAMESPACE
                                        + ">"
                                        + "SELECT ?s WHERE { ?s rdf:type ex:Person }");
                Set<String> subjects = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) subjects.add(r.next().getValue("s").stringValue());
                }
                assertEquals(
                        Set.of(
                                "http://example/alice",
                                "http://example/bob",
                                "http://example/carol"),
                        subjects);
            }
        }

        @Test
        void select_join_two_patterns() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                // Find names of people Alice knows
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT ?friendName WHERE { "
                                        + "  ex:alice ex:knows ?friend . "
                                        + "  ?friend ex:name ?friendName "
                                        + "}");
                Set<String> names = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) names.add(r.next().getValue("friendName").stringValue());
                }
                assertEquals(Set.of("Bob"), names);
            }
        }

        @Test
        void select_filter_numeric_compare() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>"
                                        + "SELECT ?s WHERE { ?s ex:age ?n . FILTER(?n > 28) }");
                Set<String> matches = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) matches.add(r.next().getValue("s").stringValue());
                }
                assertEquals(Set.of("http://example/alice"), matches);
            }
        }

        @Test
        void ask_returns_true_when_pattern_matches() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                BooleanQuery q =
                        conn.prepareBooleanQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "ASK { ex:alice ex:knows ex:bob }");
                assertTrue(q.evaluate());
            }
        }

        @Test
        void ask_returns_false_when_no_match() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                BooleanQuery q =
                        conn.prepareBooleanQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "ASK { ex:alice ex:knows ex:nobody }");
                assertFalse(q.evaluate());
            }
        }

        @Test
        void construct_returns_graph_of_triples() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                GraphQuery q =
                        conn.prepareGraphQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "CONSTRUCT { ?s ex:isFriendOf ?o } WHERE { ?s ex:knows ?o }");
                Set<String> objs = new HashSet<>();
                try (var r = q.evaluate()) {
                    while (r.hasNext()) {
                        Statement s = r.next();
                        assertEquals("http://example/isFriendOf", s.getPredicate().stringValue());
                        objs.add(s.getObject().stringValue());
                    }
                }
                assertEquals(Set.of("http://example/bob", "http://example/carol"), objs);
            }
        }

        @Test
        void select_optional() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                // carol has no name; OPTIONAL should still return her row
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "PREFIX rdf: <"
                                        + RDF.NAMESPACE
                                        + ">"
                                        + "SELECT ?s ?n WHERE { ?s rdf:type ex:Person . OPTIONAL { ?s ex:name ?n } }");
                int total = 0;
                int withName = 0;
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) {
                        total++;
                        if (r.next().hasBinding("n")) withName++;
                    }
                }
                assertEquals(3, total);
                assertEquals(2, withName);
            }
        }
    }

    // ==================================================================
    // Iter 31 — UPDATE (INSERT DATA, DELETE DATA, DELETE+INSERT WHERE)
    // ==================================================================
    @Nested
    class Updates {

        @Test
        void insert_data() {
            try (RepositoryConnection conn = repo.getConnection()) {
                Update u =
                        conn.prepareUpdate(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>" + "INSERT DATA { ex:s ex:p ex:o }");
                u.execute();
                assertEquals(1L, conn.size());
            }
        }

        @Test
        void delete_data() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                long before = conn.size();
                Update u =
                        conn.prepareUpdate(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "DELETE DATA { ex:alice ex:knows ex:bob }");
                u.execute();
                assertEquals(before - 1, conn.size());
            }
        }

        @Test
        void delete_where_pattern() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                // Delete all `?s ex:knows ?o` rows
                Update u =
                        conn.prepareUpdate(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>" + "DELETE WHERE { ?s ex:knows ?o }");
                u.execute();
                // Two such rows existed (alice knows bob, bob knows carol)
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT (COUNT(*) AS ?c) WHERE { ?s ex:knows ?o }");
                try (TupleQueryResult r = q.evaluate()) {
                    BindingSet row = r.next();
                    assertEquals("0", row.getValue("c").stringValue());
                }
            }
        }

        @Test
        void modify_replace_via_delete_insert() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                // Rename Alice → Alicia
                Update u =
                        conn.prepareUpdate(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "DELETE { ex:alice ex:name ?old } "
                                        + "INSERT { ex:alice ex:name \"Alicia\" } "
                                        + "WHERE { ex:alice ex:name ?old }");
                u.execute();

                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT ?n WHERE { ex:alice ex:name ?n }");
                Set<String> names = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) names.add(r.next().getValue("n").stringValue());
                }
                assertEquals(Set.of("Alicia"), names);
            }
        }

        @Test
        void clear_via_update() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                Update u = conn.prepareUpdate(QueryLanguage.SPARQL, "CLEAR DEFAULT");
                u.execute();
                assertEquals(0L, conn.size());
            }
        }

        @Test
        void copy_via_construct_then_insert_round_trips() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                // First snapshot how many ex:knows there are
                TupleQuery beforeQ =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT (COUNT(*) AS ?c) WHERE { ?s ex:knows ?o }");
                long before;
                try (TupleQueryResult r = beforeQ.evaluate()) {
                    before = Long.parseLong(r.next().getValue("c").stringValue());
                }
                // INSERT … WHERE: for each ex:knows triple, add ex:friendOf
                conn.prepareUpdate(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "INSERT { ?s ex:friendOf ?o } WHERE { ?s ex:knows ?o }")
                        .execute();
                // Now there should be `before` extra triples with predicate ex:friendOf
                TupleQuery afterQ =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT (COUNT(*) AS ?c) WHERE { ?s ex:friendOf ?o }");
                try (TupleQueryResult r = afterQ.evaluate()) {
                    long after = Long.parseLong(r.next().getValue("c").stringValue());
                    assertEquals(before, after);
                }
            }
        }
    }

    // ==================================================================
    // Iter 32 — Conformance subset
    // ==================================================================
    @Nested
    class ConformanceSubset {

        @Test
        void distinct() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT DISTINCT ?p WHERE { ?s ?p ?o }");
                Set<String> preds = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) preds.add(r.next().getValue("p").stringValue());
                }
                // type, name, knows, age = 4 distinct predicates
                assertEquals(4, preds.size());
            }
        }

        @Test
        void limit_and_offset() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 3 OFFSET 2");
                int n = 0;
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) {
                        r.next();
                        n++;
                    }
                }
                assertEquals(3, n);
            }
        }

        @Test
        void order_by_then_limit_one() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT ?s WHERE { ?s ex:age ?n } ORDER BY DESC(?n) LIMIT 1");
                try (TupleQueryResult r = q.evaluate()) {
                    assertTrue(r.hasNext());
                    BindingSet row = r.next();
                    // Alice has age 30 (highest), Bob 25
                    assertEquals("http://example/alice", row.getValue("s").stringValue());
                }
            }
        }

        @Test
        void numeric_order_by_and_filter_are_value_based_not_lexical() {
            // REGRESSION for ADR-0043 term-faithful (lexical) integer storage (Step 4a/4b): SPARQL
            // ORDER BY / FILTER compute the NUMERIC value of a literal above the Sail (RDF4J's
            // ValueComparator / QueryEvaluationUtil parse the label by datatype), so they stay
            // numeric
            // even though the index byte-order is now lexical. Values chosen so the two orders
            // DIFFER:
            // numeric 2 < 10 < 100, but lexical "10" < "100" < "2". xsd:int (createLiteral(int)) is
            // the
            // tag whose encoding 4b just flipped to lexical — this pins that the flip is invisible
            // here.
            ValueFactory vf = repo.getValueFactory();
            IRI metric = vf.createIRI("http://example/metric");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(vf.createIRI("http://example/a"), metric, vf.createLiteral(2));
                conn.add(vf.createIRI("http://example/b"), metric, vf.createLiteral(10));
                conn.add(vf.createIRI("http://example/c"), metric, vf.createLiteral(100));
                conn.commit();
            }
            try (RepositoryConnection conn = repo.getConnection()) {
                // ORDER BY ?n ASC must be NUMERIC [2, 10, 100], not lexical [10, 100, 2].
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/> SELECT ?n WHERE { ?s ex:metric ?n } ORDER BY ?n");
                List<Integer> ordered = new ArrayList<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) ordered.add(((Literal) r.next().getValue("n")).intValue());
                }
                assertEquals(
                        List.of(2, 10, 100),
                        ordered,
                        "ORDER BY ?n must sort by NUMERIC value, not lexical");

                // FILTER(?n > 9) must be NUMERIC {10, 100}; a lexical compare would yield {}
                // ("10"/"100" < "9").
                TupleQuery qf =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/> SELECT ?n WHERE { ?s ex:metric ?n . FILTER(?n > 9) }");
                Set<Integer> filtered = new HashSet<>();
                try (TupleQueryResult r = qf.evaluate()) {
                    while (r.hasNext()) filtered.add(((Literal) r.next().getValue("n")).intValue());
                }
                assertEquals(
                        Set.of(10, 100),
                        filtered,
                        "FILTER(?n > 9) must compare NUMERIC value, not lexical");
            }
        }

        @Test
        void union() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT ?s WHERE { { ?s ex:age ?n } UNION { ?s ex:knows ?o } }");
                Set<String> subjects = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) subjects.add(r.next().getValue("s").stringValue());
                }
                // Alice (age + knows), Bob (age + knows). Carol has neither.
                assertEquals(Set.of("http://example/alice", "http://example/bob"), subjects);
            }
        }

        @Test
        void minus() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "PREFIX rdf: <"
                                        + RDF.NAMESPACE
                                        + ">"
                                        + "SELECT ?s WHERE { ?s rdf:type ex:Person . MINUS { ?s ex:age ?n } }");
                Set<String> noAge = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) noAge.add(r.next().getValue("s").stringValue());
                }
                assertEquals(Set.of("http://example/carol"), noAge);
            }
        }

        @Test
        void aggregate_count() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX rdf: <"
                                        + RDF.NAMESPACE
                                        + ">"
                                        + "PREFIX ex: <http://example/>"
                                        + "SELECT (COUNT(?s) AS ?n) WHERE { ?s rdf:type ex:Person }");
                try (TupleQueryResult r = q.evaluate()) {
                    assertTrue(r.hasNext());
                    assertEquals("3", r.next().getValue("n").stringValue());
                }
            }
        }

        @Test
        void aggregate_avg_sum() {
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT (AVG(?n) AS ?avg) (SUM(?n) AS ?sum) WHERE { ?s ex:age ?n }");
                try (TupleQueryResult r = q.evaluate()) {
                    BindingSet row = r.next();
                    // ages: 30, 25 → sum 55, avg 27.5
                    assertEquals("55", row.getValue("sum").stringValue());
                    // avg may render as "27.5" or "27.500000000000000000000000" — assert prefix
                    assertTrue(
                            row.getValue("avg").stringValue().startsWith("27.5"),
                            "got: " + row.getValue("avg").stringValue());
                }
            }
        }

        @Test
        void group_by() {
            // Add a few "city" triples for grouping
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = repo.getValueFactory();
                IRI city = vf.createIRI("http://example/city");
                conn.add(vf.createIRI("http://example/alice"), city, vf.createLiteral("SF"));
                conn.add(vf.createIRI("http://example/bob"), city, vf.createLiteral("SF"));
                conn.add(vf.createIRI("http://example/carol"), city, vf.createLiteral("NYC"));
            }
            try (RepositoryConnection conn = repo.getConnection()) {
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX ex: <http://example/>"
                                        + "SELECT ?city (COUNT(?s) AS ?n) "
                                        + "WHERE { ?s ex:city ?city } GROUP BY ?city");
                int rows = 0;
                Set<String> seen = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) {
                        BindingSet row = r.next();
                        seen.add(
                                row.getValue("city").stringValue()
                                        + ":"
                                        + row.getValue("n").stringValue());
                        rows++;
                    }
                }
                assertEquals(2, rows);
                assertEquals(Set.of("SF:2", "NYC:1"), seen);
            }
        }

        @Test
        void rdfs_subclassof_pattern_without_inferencer() {
            // Without inferencer, RDFS isn't materialized — only asserted triples match.
            seed();
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = repo.getValueFactory();
                IRI agent = vf.createIRI("http://example/Agent");
                conn.add(vf.createIRI("http://example/Person"), RDFS.SUBCLASSOF, agent);
                TupleQuery q =
                        conn.prepareTupleQuery(
                                QueryLanguage.SPARQL,
                                "PREFIX rdfs: <"
                                        + RDFS.NAMESPACE
                                        + ">"
                                        + "PREFIX ex: <http://example/>"
                                        + "SELECT ?p WHERE { ?p rdfs:subClassOf ex:Agent }");
                Set<String> subjects = new HashSet<>();
                try (TupleQueryResult r = q.evaluate()) {
                    while (r.hasNext()) subjects.add(r.next().getValue("p").stringValue());
                }
                assertEquals(Set.of("http://example/Person"), subjects);
            }
        }
    }
}
