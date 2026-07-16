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
import java.util.HashMap;
import java.util.Map;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/** Step 8 coverage — namespace prefixes over the {@code ns} column family. */
class RocksDbFlatSailNamespaceTest {
    static {
        RocksDB.loadLibrary();
    }

    private RocksDbFlatSail sail;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        sail = new RocksDbFlatSail(dir);
        sail.init();
    }

    @AfterEach
    void tearDown() {
        if (sail != null) {
            sail.shutDown();
        }
    }

    private static Map<String, String> namespaces(SailConnection conn) {
        Map<String, String> out = new HashMap<>();
        try (CloseableIteration<? extends Namespace> it = conn.getNamespaces()) {
            while (it.hasNext()) {
                Namespace ns = it.next();
                out.put(ns.getPrefix(), ns.getName());
            }
        }
        return out;
    }

    @Test
    void set_then_get_a_namespace() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://example.org/");
            conn.commit();
            assertEquals("http://example.org/", conn.getNamespace("ex"));
        }
    }

    @Test
    void unknown_prefix_returns_null() {
        try (SailConnection conn = sail.getConnection()) {
            assertNull(conn.getNamespace("nope"));
        }
    }

    @Test
    void get_namespaces_lists_every_set_prefix() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://example.org/");
            conn.setNamespace("foaf", "http://xmlns.com/foaf/0.1/");
            conn.commit();
            Map<String, String> all = namespaces(conn);
            assertEquals(2, all.size());
            assertEquals("http://example.org/", all.get("ex"));
            assertEquals("http://xmlns.com/foaf/0.1/", all.get("foaf"));
        }
    }

    @Test
    void a_prefix_can_be_overwritten() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://old.example/");
            conn.commit();
            conn.begin();
            conn.setNamespace("ex", "http://new.example/");
            conn.commit();
            assertEquals("http://new.example/", conn.getNamespace("ex"));
        }
    }

    @Test
    void remove_namespace_deletes_one_prefix() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://example.org/");
            conn.setNamespace("foaf", "http://xmlns.com/foaf/0.1/");
            conn.commit();
            conn.begin();
            conn.removeNamespace("ex");
            conn.commit();
            assertNull(conn.getNamespace("ex"));
            assertEquals("http://xmlns.com/foaf/0.1/", conn.getNamespace("foaf"));
        }
    }

    @Test
    void clear_namespaces_removes_them_all() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://example.org/");
            conn.setNamespace("foaf", "http://xmlns.com/foaf/0.1/");
            conn.commit();
            conn.begin();
            conn.clearNamespaces();
            conn.commit();
            assertTrue(namespaces(conn).isEmpty());
        }
    }

    @Test
    void a_rolled_back_set_namespace_does_not_persist() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.setNamespace("ex", "http://example.org/");
            conn.rollback();
            assertNull(conn.getNamespace("ex"), "rollback must discard the namespace write");
        }
    }

    @Test
    void namespaces_survive_into_a_new_connection() {
        try (SailConnection writer = sail.getConnection()) {
            writer.begin();
            writer.setNamespace("ex", "http://example.org/");
            writer.commit();
        }
        try (SailConnection reader = sail.getConnection()) {
            assertEquals("http://example.org/", reader.getNamespace("ex"));
        }
    }
}
