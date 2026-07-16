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

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test that the Sail can auto-restore from disk when given a {@link RootMetaTreeStore}.
 * Compare to {@code DiskPersistenceTest.SailLayer} which manually saved + restored roots — this
 * test does it via the Sail's own commit/init flow with zero test plumbing.
 */
class AutoRestoreE2ETest {

    private static long drain(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        } finally {
            it.close();
        }
        return n;
    }

    @Test
    void sail_auto_restores_a_single_committed_statement(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        // First lifetime: write, commit, close.
        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            RootMetaTreeStore mts = RootMetaTreeStore.beside(storeDir);
            ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/alice"),
                            vf.createIRI("http://example/knows"),
                            vf.createIRI("http://example/bob"));
                    conn.commit();
                }
                store.flushDurable();
            } finally {
                sail.shutDown();
            }
        }

        // Second lifetime: just open and query. No manual root restore.
        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            RootMetaTreeStore mts = RootMetaTreeStore.beside(storeDir);
            ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts);
            sail.init();
            try {
                assertNotNull(
                        sail.dictRoot(),
                        "Sail.init should have auto-restored the dict root from the metatree");
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(1L, conn.size());
                    Set<Statement> all = new HashSet<>();
                    try (var it = conn.getStatements(null, null, null, false)) {
                        while (it.hasNext()) all.add(it.next());
                    }
                    Statement st = all.iterator().next();
                    assertEquals("http://example/alice", st.getSubject().stringValue());
                    assertEquals("http://example/bob", st.getObject().stringValue());
                }
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void sail_auto_restores_many_statements(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        int N = 500;
        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    for (int i = 0; i < N; i++) {
                        conn.addStatement(
                                vf.createIRI("http://example/s" + i),
                                vf.createIRI("http://example/p"),
                                vf.createLiteral(i));
                    }
                    conn.commit();
                }
                store.flushDurable();
            } finally {
                sail.shutDown();
            }
        }

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(N, conn.size());
                long matched =
                        drain(
                                conn.getStatements(
                                        sail.getValueFactory().createIRI("http://example/s250"),
                                        null,
                                        null,
                                        false));
                assertEquals(1L, matched);
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void sail_auto_restores_then_appends(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        // Session 1
        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s1"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o1"));
                    conn.commit();
                }
                store.flushDurable();
            } finally {
                sail.shutDown();
            }
        }

        // Session 2 — append two more
        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s2"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o2"));
                    conn.addStatement(
                            vf.createIRI("http://example/s3"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o3"));
                    conn.commit();
                }
                store.flushDurable();
            } finally {
                sail.shutDown();
            }
        }

        // Session 3 — verify all 3 visible
        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(3L, conn.size());
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void sail_with_no_metatree_works_as_before(@TempDir Path dir) throws Exception {
        // Constructed without RootMetaTreeStore — pre-iter-29 behavior; volatile-only state.
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            ProllySail sail = new ProllySail(store, new HeapBufferPool());
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                    assertEquals(1L, conn.size());
                }
                // No metatree file exists.
                assertFalse(
                        Files.exists(dir.resolve(RootMetaTreeStore.FILENAME)),
                        "no RootMetaTreeStore configured → no sidecar file written");
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void metatree_pointer_file_exists_after_commit(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                }
            } finally {
                sail.shutDown();
            }
        }
        Path pointer = storeDir.resolve(RootMetaTreeStore.FILENAME);
        assertTrue(Files.exists(pointer), "sidecar 'meta-head' file should exist after commit");
        // It contains a valid hex hash
        String content = Files.readString(pointer).trim();
        assertEquals(40, content.length(), "SHA-1 hash = 20 bytes = 40 hex chars");
    }
}
