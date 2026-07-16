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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash-safety / durability tests for {@link ProllySail}.
 *
 * <p>{@code AutoRestoreE2ETest} and {@code DiskPersistenceTest} verify the <em>clean</em>
 * close/reopen path — every session ends with {@code commit()} + {@code shutDown()}. This file
 * covers the <em>unclean</em> paths a real process crash produces:
 *
 * <ul>
 *   <li>a transaction abandoned before {@code commit()} — writes must not survive (commit atomicity
 *       / crash-before-commit);
 *   <li>an explicit {@code rollback()} — writes must not survive;
 *   <li>the {@code root-head} restore pointer lost or truncated — the Sail must degrade to an empty
 *       store rather than crashing on init.
 * </ul>
 *
 * <p>A true {@code kill -9} can't be staged in-process (RocksDB's directory lock must be released
 * before reopen), so "crash" is simulated by dropping a transaction without committing, and by
 * mutating the sidecar pointer file the way a partial/lost fsync would.
 */
class SailCrashSafetyTest {

    /** A disk-backed, auto-restoring Sail rooted at {@code storeDir}. */
    private static ProllySail sailOn(RocksNodeStore store, Path storeDir) {
        return new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(storeDir));
    }

    private static void addQuad(SailConnection conn, ValueFactory vf, String s) {
        conn.addStatement(vf.createIRI("urn:" + s), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
    }

    // ---- commit atomicity ----------------------------------------------

    @Test
    void uncommitted_writes_are_not_visible_after_reopen(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "committed");
                    conn.commit();
                }
                // A second transaction that is never committed — stands in
                // for a process crash after the writes but before commit.
                // Closing the connection with an open transaction discards it.
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "orphan");
                    // no commit() — fall out of try-with-resources
                }
            } finally {
                sail.shutDown();
            }
        }

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        1L,
                        conn.size(),
                        "only the committed statement survives — the uncommitted write is gone");
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void explicitly_rolled_back_writes_do_not_persist(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "discardme");
                    conn.rollback();
                }
            } finally {
                sail.shutDown();
            }
        }

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(0L, conn.size(), "a rolled-back transaction leaves no trace on disk");
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void the_last_commit_is_the_restore_point_not_an_abandoned_followup(@TempDir Path dir)
            throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "a");
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "b");
                    conn.commit();
                }
                // A third transaction crashes before commit.
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "c");
                }
            } finally {
                sail.shutDown();
            }
        }

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        2L,
                        conn.size(),
                        "restore lands on the last committed state (a + b), not the abandoned c");
            } finally {
                sail.shutDown();
            }
        }
    }

    // ---- restore-pointer corruption ------------------------------------

    @Test
    void a_missing_restore_pointer_degrades_to_an_empty_store(@TempDir Path dir) throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "s");
                    conn.commit();
                }
            } finally {
                sail.shutDown();
            }
        }

        // Simulate a lost sidecar — the data chunks are still in RocksDB but
        // the pointer that names the committed root never made it to disk.
        Path pointer = storeDir.resolve(RootMetaTreeStore.FILENAME);
        assertTrue(Files.exists(pointer), "sanity: the commit wrote a restore pointer");
        Files.delete(pointer);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            assertDoesNotThrow(sail::init, "a missing restore pointer must not crash Sail init");
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        0L,
                        conn.size(),
                        "with no restore pointer the Sail starts empty rather than half-restored");
            } finally {
                sail.shutDown();
            }
        }
    }

    @Test
    void a_truncated_restore_pointer_degrades_to_an_empty_store(@TempDir Path dir)
            throws Exception {
        Path storeDir = dir.resolve("store");
        Files.createDirectory(storeDir);

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    addQuad(conn, vf, "s");
                    conn.commit();
                }
            } finally {
                sail.shutDown();
            }
        }

        // A partial fsync can leave the pointer file present but empty.
        Path pointer = storeDir.resolve(RootMetaTreeStore.FILENAME);
        Files.writeString(pointer, "");

        try (RocksNodeStore store = new RocksNodeStore(storeDir.toString())) {
            ProllySail sail = sailOn(store, storeDir);
            assertDoesNotThrow(sail::init, "an empty restore pointer must not crash Sail init");
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        0L, conn.size(), "an empty restore pointer is treated as 'no commit yet'");
            } finally {
                sail.shutDown();
            }
        }
    }
}
