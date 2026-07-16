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
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Step 7 coverage for {@code getStatementsInternal} — the read path: index selection, range scan
 * and decode back to {@link Statement}s.
 */
class RocksDbFlatSailReadTest {
    static {
        RocksDB.loadLibrary();
    }

    private RocksDbFlatSail sail;
    private ValueFactory vf;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        sail = new RocksDbFlatSail(dir);
        sail.init();
        vf = sail.getValueFactory();
    }

    @AfterEach
    void tearDown() {
        if (sail != null) {
            sail.shutDown();
        }
    }

    private static List<Statement> collect(CloseableIteration<? extends Statement> it) {
        List<Statement> out = new ArrayList<>();
        try (it) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    @Test
    void unfiltered_scan_returns_every_statement() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(
                    vf.createIRI("urn:s1"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"));
            conn.addStatement(
                    vf.createIRI("urn:s2"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"));
            conn.addStatement(
                    vf.createIRI("urn:s3"), vf.createIRI("urn:q"), vf.createIRI("urn:o3"));
            conn.commit();
            assertEquals(3, collect(conn.getStatements(null, null, null, false)).size());
        }
    }

    @Test
    void scan_by_subject_returns_only_that_subjects_statements() {
        IRI s1 = vf.createIRI("urn:s1");
        IRI p = vf.createIRI("urn:p");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s1, p, vf.createIRI("urn:o1"));
            conn.addStatement(s1, p, vf.createIRI("urn:o2"));
            conn.addStatement(vf.createIRI("urn:other"), p, vf.createIRI("urn:o3"));
            conn.commit();
            List<Statement> got = collect(conn.getStatements(s1, null, null, false));
            assertEquals(2, got.size());
            assertTrue(got.stream().allMatch(st -> st.getSubject().equals(s1)));
        }
    }

    @Test
    void scan_by_predicate_and_by_object() {
        IRI p = vf.createIRI("urn:p");
        IRI q = vf.createIRI("urn:q");
        IRI obj = vf.createIRI("urn:target");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(vf.createIRI("urn:s1"), p, obj);
            conn.addStatement(vf.createIRI("urn:s2"), q, vf.createIRI("urn:other"));
            conn.commit();
            assertEquals(1, collect(conn.getStatements(null, p, null, false)).size());
            assertEquals(1, collect(conn.getStatements(null, null, obj, false)).size());
        }
    }

    @Test
    void exact_quad_lookup_returns_one_matching_statement() {
        IRI s = vf.createIRI("urn:s");
        IRI p = vf.createIRI("urn:p");
        IRI o = vf.createIRI("urn:o");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, o);
            conn.addStatement(s, p, vf.createIRI("urn:other"));
            conn.commit();
            List<Statement> got = collect(conn.getStatements(s, p, o, false));
            assertEquals(1, got.size());
            Statement st = got.get(0);
            assertEquals(s, st.getSubject());
            assertEquals(p, st.getPredicate());
            assertEquals(o, st.getObject());
            assertNull(st.getContext(), "no context -> default graph -> null context");
        }
    }

    @Test
    void a_pattern_term_unknown_to_the_dictionary_matches_nothing() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
            assertEquals(
                    0,
                    collect(conn.getStatements(vf.createIRI("urn:never-added"), null, null, false))
                            .size());
        }
    }

    @Test
    void named_graph_statements_are_isolated_by_context() {
        IRI graph = vf.createIRI("urn:graph1");
        IRI s = vf.createIRI("urn:s");
        IRI p = vf.createIRI("urn:p");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, vf.createIRI("urn:inDefault"));
            conn.addStatement(s, p, vf.createIRI("urn:inGraph1"), graph);
            conn.commit();

            // No contexts -> union of all graphs.
            assertEquals(2, collect(conn.getStatements(null, null, null, false)).size());
            // Restricted to the named graph.
            List<Statement> inGraph = collect(conn.getStatements(null, null, null, false, graph));
            assertEquals(1, inGraph.size());
            assertEquals(graph, inGraph.get(0).getContext());
            // Restricted to the default graph (a single null context).
            List<Statement> inDefault =
                    collect(conn.getStatements(null, null, null, false, (Resource) null));
            assertEquals(1, inDefault.size());
            assertNull(inDefault.get(0).getContext());
        }
    }

    @Test
    void typed_literal_objects_survive_the_read_path() {
        IRI s = vf.createIRI("urn:s");
        IRI p = vf.createIRI("urn:age");
        Value literal = vf.createLiteral(42);
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, literal);
            conn.commit();
            List<Statement> got = collect(conn.getStatements(s, p, null, false));
            assertEquals(1, got.size());
            assertEquals(literal, got.get(0).getObject());
        }
    }
}
