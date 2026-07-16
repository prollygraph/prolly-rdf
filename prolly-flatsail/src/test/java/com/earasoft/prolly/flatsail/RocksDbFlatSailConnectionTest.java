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

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;

/**
 * Step 6 coverage for {@link RocksDbFlatSailConnection} — the write path: {@code WriteBatch}
 * transactions and {@code addStatement}/{@code removeStatement} across the four permutation
 * indexes. Verified at the RocksDB level since {@code getStatements} (Step 7) is not implemented
 * yet.
 */
class RocksDbFlatSailConnectionTest {
    static {
        RocksDB.loadLibrary();
    }

    private static int countKeys(RocksFlatStore store, QuadOrder order) {
        int n = 0;
        try (RocksIterator it = store.db().newIterator(store.index(order))) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                n++;
            }
        }
        return n;
    }

    private static List<SpocKey> spocKeys(RocksFlatStore store) {
        List<SpocKey> keys = new ArrayList<>();
        try (RocksIterator it = store.db().newIterator(store.index(QuadOrder.SPOC))) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                keys.add(FlatKeyCodec.decode(it.key()));
            }
        }
        return keys;
    }

    @Test
    void add_then_commit_writes_one_key_into_each_of_the_four_indexes(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
                conn.commit();
            }
            for (QuadOrder order : QuadOrder.values()) {
                assertEquals(
                        1, countKeys(sail.store(), order), order + " must hold exactly one key");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void an_added_statement_decodes_back_to_the_original_triple(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = vf.createIRI("urn:alice");
            IRI p = vf.createIRI("urn:knows");
            IRI o = vf.createIRI("urn:bob");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, o);
                conn.commit();
            }
            List<SpocKey> keys = spocKeys(sail.store());
            assertEquals(1, keys.size());
            SpocKey quad = keys.get(0);
            FlatDictionary dict = sail.dictionary();
            assertEquals(Optional.of(s), dict.lookup(quad.col0()));
            assertEquals(Optional.of(p), dict.lookup(quad.col1()));
            assertEquals(Optional.of(o), dict.lookup(quad.col2()));
            assertEquals(TermId.ZERO, quad.col3(), "no context -> default-graph sentinel");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void a_named_context_is_stored_in_the_context_column(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI graph = vf.createIRI("urn:graph1");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"), graph);
                conn.commit();
            }
            SpocKey quad = spocKeys(sail.store()).get(0);
            assertNotEquals(TermId.ZERO, quad.col3());
            assertEquals(Optional.of(graph), sail.dictionary().lookup(quad.col3()));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void rollback_writes_nothing(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
                conn.rollback();
            }
            assertEquals(
                    0,
                    countKeys(sail.store(), QuadOrder.SPOC),
                    "a rolled-back transaction must leave the store untouched");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void fully_bound_remove_deletes_the_quad_from_every_index(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = vf.createIRI("urn:s");
            IRI p = vf.createIRI("urn:p");
            IRI o = vf.createIRI("urn:o");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, o);
                conn.commit();
                conn.begin();
                conn.removeStatements(s, p, o, (Resource) null); // explicit default-graph context
                conn.commit();
            }
            for (QuadOrder order : QuadOrder.values()) {
                assertEquals(0, countKeys(sail.store(), order), order + " must be empty");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void wildcard_remove_scans_and_deletes_matching_quads(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = vf.createIRI("urn:s");
            IRI other = vf.createIRI("urn:other");
            IRI p = vf.createIRI("urn:p");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, vf.createIRI("urn:o1"));
                conn.addStatement(s, p, vf.createIRI("urn:o2"));
                conn.addStatement(other, p, vf.createIRI("urn:o3"));
                conn.commit();
                conn.begin();
                conn.removeStatements(s, null, null); // all of s's statements, any predicate/object
                conn.commit();
            }
            List<SpocKey> remaining = spocKeys(sail.store());
            assertEquals(1, remaining.size(), "only urn:other's statement should survive");
            assertEquals(Optional.of(other), sail.dictionary().lookup(remaining.get(0).col0()));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void two_statements_sharing_a_predicate_keep_index_counts_consistent(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = vf.createIRI("urn:sharedPredicate");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(vf.createIRI("urn:s1"), p, vf.createIRI("urn:o1"));
                conn.addStatement(vf.createIRI("urn:s2"), p, vf.createIRI("urn:o2"));
                conn.commit();
            }
            for (QuadOrder order : QuadOrder.values()) {
                assertEquals(2, countKeys(sail.store(), order), order + " must hold both quads");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void writes_accumulate_across_separate_transactions(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:s1"), vf.createIRI("urn:p"), vf.createIRI("urn:o1"));
                conn.commit();
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:s2"), vf.createIRI("urn:p"), vf.createIRI("urn:o2"));
                conn.commit();
            }
            assertEquals(2, countKeys(sail.store(), QuadOrder.SPOC));
        } finally {
            sail.shutDown();
        }
    }
}
