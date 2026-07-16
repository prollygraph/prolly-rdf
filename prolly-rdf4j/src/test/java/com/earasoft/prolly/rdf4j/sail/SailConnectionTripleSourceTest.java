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
import java.util.List;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link SailConnectionTripleSource} — the {@code TripleSource} adapter that lets
 * RDF4J's SPARQL evaluator drive scans through a {@link ProllySailConnection}. It is exercised
 * transitively by the SPARQL suite, but had no direct test pinning its two contracts: scan
 * delegation and {@code getValueFactory}.
 *
 * <p>Driven against a real {@link ProllySail} — no mocks.
 */
class SailConnectionTripleSourceTest {

    private static IRI iri(ValueFactory vf, String s) {
        return vf.createIRI("http://example.org/" + s);
    }

    private static int count(CloseableIteration<? extends Statement> it) {
        int n = 0;
        try (it) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    /** Build a sail with three statements committed: (a,p,x) (a,p,y) (b,p,x). */
    private static ProllySail sailWithData() {
        ProllySail sail = new ProllySail();
        sail.init();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
            conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "y"));
            conn.addStatement(iri(vf, "b"), iri(vf, "p"), iri(vf, "x"));
            conn.commit();
        }
        return sail;
    }

    @Test
    void getValueFactory_returns_the_supplied_factory() {
        ProllySail sail = new ProllySail();
        sail.init();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            SailConnectionTripleSource ts =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            assertSame(
                    vf,
                    ts.getValueFactory(),
                    "getValueFactory must return the exact factory passed at construction");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void getStatements_delegates_a_fully_bound_pattern() {
        ProllySail sail = sailWithData();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            SailConnectionTripleSource ts =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            assertEquals(
                    1,
                    count(ts.getStatements(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"))),
                    "a fully-bound pattern must return its single matching statement");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void getStatements_delegates_a_partially_bound_pattern() {
        ProllySail sail = sailWithData();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            SailConnectionTripleSource ts =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            // subject a appears in two statements.
            assertEquals(
                    2,
                    count(ts.getStatements(iri(vf, "a"), null, null)),
                    "binding only the subject must return both of its statements");
            // object x appears in two statements.
            assertEquals(2, count(ts.getStatements(null, null, iri(vf, "x"))));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void getStatements_with_all_wildcards_returns_every_statement() {
        ProllySail sail = sailWithData();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            SailConnectionTripleSource ts =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            assertEquals(
                    3,
                    count(ts.getStatements(null, null, null)),
                    "an all-wildcard scan must surface every committed statement");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void getStatements_no_match_returns_empty() {
        ProllySail sail = sailWithData();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            SailConnectionTripleSource ts =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            assertEquals(
                    0,
                    count(ts.getStatements(iri(vf, "missing"), null, null)),
                    "a pattern that matches nothing must yield an empty iteration");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void scanned_statement_carries_the_expected_terms() {
        ProllySail sail = sailWithData();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            SailConnectionTripleSource ts =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            List<Statement> got = new ArrayList<>();
            try (var it = ts.getStatements(iri(vf, "b"), iri(vf, "p"), iri(vf, "x"))) {
                while (it.hasNext()) got.add(it.next());
            }
            assertEquals(1, got.size());
            Statement s = got.get(0);
            assertEquals(iri(vf, "b"), s.getSubject());
            assertEquals(iri(vf, "p"), s.getPredicate());
            assertEquals(iri(vf, "x"), s.getObject());
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void includeInferred_flag_is_accepted_in_both_states() {
        // No reasoning happens, so the flag is opaque here — pin that the
        // adapter is constructible and functional with the flag either way.
        ProllySail sail = sailWithData();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            var withInferred =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, true);
            var withoutInferred =
                    new SailConnectionTripleSource((ProllySailConnection) conn, vf, false);
            assertEquals(3, count(withInferred.getStatements(null, null, null)));
            assertEquals(3, count(withoutInferred.getStatements(null, null, null)));
        } finally {
            sail.shutDown();
        }
    }
}
