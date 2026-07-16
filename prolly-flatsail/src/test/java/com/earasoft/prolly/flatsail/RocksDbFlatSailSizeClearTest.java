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
import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/** Step 9 coverage — {@code size}, {@code clear} and {@code getContextIDs}. */
class RocksDbFlatSailSizeClearTest {
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

    @Test
    void size_counts_committed_statements() {
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(0L, conn.size(), "a fresh store is empty");
            conn.begin();
            conn.addStatement(
                    vf.createIRI("urn:s1"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"));
            conn.addStatement(
                    vf.createIRI("urn:s2"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"));
            conn.commit();
            assertEquals(2L, conn.size());
        }
    }

    @Test
    void size_of_a_named_graph_counts_only_that_graph() {
        IRI graph = vf.createIRI("urn:g");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(
                    vf.createIRI("urn:s1"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"));
            conn.addStatement(
                    vf.createIRI("urn:s2"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"), graph);
            conn.commit();
            assertEquals(2L, conn.size(), "no contexts -> the whole store");
            assertEquals(1L, conn.size(graph), "size(graph) counts only that graph");
        }
    }

    @Test
    void clear_removes_every_statement() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(
                    vf.createIRI("urn:s1"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"));
            conn.addStatement(
                    vf.createIRI("urn:s2"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"));
            conn.commit();
            conn.begin();
            conn.clear();
            conn.commit();
            assertEquals(0L, conn.size());
        }
    }

    @Test
    void clear_of_one_graph_leaves_other_graphs_intact() {
        IRI graph = vf.createIRI("urn:g");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(
                    vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:inDefault"));
            conn.addStatement(
                    vf.createIRI("urn:s"),
                    vf.createIRI("urn:p"),
                    vf.createIRI("urn:inGraph"),
                    graph);
            conn.commit();
            conn.begin();
            conn.clear(graph);
            conn.commit();
            assertEquals(1L, conn.size(), "only the named graph was cleared");
            assertEquals(0L, conn.size(graph));
        }
    }

    @Test
    void get_context_ids_lists_named_graphs_but_not_the_default_graph() {
        IRI g1 = vf.createIRI("urn:g1");
        IRI g2 = vf.createIRI("urn:g2");
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(
                    vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:oDefault"));
            conn.addStatement(
                    vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"), g1);
            conn.addStatement(
                    vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"), g2);
            conn.commit();

            Set<Resource> contexts = new HashSet<>();
            try (CloseableIteration<? extends Resource> it = conn.getContextIDs()) {
                while (it.hasNext()) {
                    contexts.add(it.next());
                }
            }
            assertEquals(
                    Set.of(g1, g2),
                    contexts,
                    "named graphs are listed; the default graph is not a context");
        }
    }

    @Test
    void get_context_ids_is_empty_when_only_the_default_graph_is_used() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
            try (CloseableIteration<? extends Resource> it = conn.getContextIDs()) {
                assertFalse(it.hasNext(), "default-graph-only store has no context IDs");
            }
        }
    }

    @Test
    void clear_all_then_readd_repopulates_the_store() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"));
            conn.commit();
            conn.begin();
            conn.clear(); // range-delete every index
            conn.commit();
            assertEquals(0L, conn.size());
            // The range-delete tombstones must not block fresh inserts.
            conn.begin();
            conn.addStatement(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"));
            conn.commit();
            assertEquals(1L, conn.size(), "the store must be writable again after a clear");
        }
    }

    @Test
    void a_rolled_back_clear_keeps_the_data() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
            conn.begin();
            conn.clear();
            conn.rollback();
            assertEquals(1L, conn.size(), "a rolled-back clear must delete nothing");
        }
    }
}
