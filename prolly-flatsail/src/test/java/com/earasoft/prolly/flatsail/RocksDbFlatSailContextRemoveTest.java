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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Context-remove contract for {@link RocksDbFlatSail} — the unversioned Sail's analogue of {@code
 * ProllySailRemoveAllContextsTest}. RDF4J's {@code removeStatements(s, p, o)} with an <b>empty</b>
 * contexts array removes the triple from <b>every</b> graph; a single {@code {null}} removes only
 * from the default graph.
 *
 * <p>This Sail has no MemoryStore differential oracle, and the abstract {@code RDFStoreTest} (which
 * it does run) did not catch the same-shaped all-contexts-remove bug found in {@code
 * ProllySailConnection}. So this pins the case explicitly. By inspection {@code
 * RocksDbFlatSailConnection.removeStatementsInternal} already handles it correctly — a bound {@code
 * (s,p,o)} with empty contexts leaves {@code contextFilter == null} and falls to {@code
 * scanSpocAndDelete}, deleting every matching graph — but inspection is not measurement; these
 * tests are the measurement.
 */
class RocksDbFlatSailContextRemoveTest {

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

    private IRI iri(String s) {
        return vf.createIRI("urn:test:" + s);
    }

    @Test
    void all_contexts_remove_reaches_a_named_graph() {
        IRI s = iri("s");
        IRI p = iri("p");
        IRI o = iri("o");
        IRI g = iri("g1");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, o, g);
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.removeStatements(s, p, o); // empty contexts == ALL graphs
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(
                    0L,
                    conn.size(),
                    "removeStatements(s,p,o) with no contexts must remove the named-graph copy");
        }
    }

    @Test
    void all_contexts_remove_reaches_a_same_tx_named_graph_add() {
        IRI s = iri("s");
        IRI p = iri("p");
        IRI o = iri("o");
        IRI g = iri("g1");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, o, g);
            conn.removeStatements(s, p, o); // all graphs, same transaction
            assertEquals(
                    0L,
                    conn.size(),
                    "a same-tx all-contexts remove must reach the buffered named-graph add");
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(0L, conn.size(), "and the removal must survive commit");
        }
    }

    @Test
    void all_contexts_remove_clears_the_triple_from_every_graph_at_once() {
        IRI s = iri("s");
        IRI p = iri("p");
        IRI o = iri("o");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, o); // default graph
            conn.addStatement(s, p, o, iri("g1"));
            conn.addStatement(s, p, o, iri("g2"));
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.removeStatements(s, p, o); // all graphs
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(
                    0L,
                    conn.size(),
                    "one all-contexts remove must clear the triple from default + g1 + g2");
        }
    }

    @Test
    void default_only_remove_does_not_touch_a_named_graph() {
        IRI s = iri("s");
        IRI p = iri("p");
        IRI o = iri("o");
        IRI g = iri("g1");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(s, p, o, g);
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.removeStatements(s, p, o, (Resource) null); // default graph only
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(
                    1L,
                    conn.size(),
                    "a default-graph-only remove must leave the named-graph copy intact");
        }
    }
}
