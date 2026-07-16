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
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Lifecycle coverage for the {@link RocksDbFlatSail} shell (Step 5) — init / connect / shut down,
 * and the owned-vs-external store contract. The data-path tests arrive with {@code
 * RocksDbFlatSailTest} in Step 10.
 */
class RocksDbFlatSailLifecycleTest {
    static {
        RocksDB.loadLibrary();
    }

    @Test
    void owned_store_sail_completes_a_full_lifecycle(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            assertTrue(sail.isWritable(), "the flat Sail is writable");
            assertNotNull(sail.getValueFactory());
            try (SailConnection conn = sail.getConnection()) {
                assertInstanceOf(RocksDbFlatSailConnection.class, conn);
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void shut_down_releases_the_owned_store(@TempDir Path dir) throws Exception {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        sail.shutDown();
        // If shutDown closed the owned RocksDB, its LOCK file is released and
        // the same directory reopens cleanly; a leaked open would throw here.
        try (RocksFlatStore reopened = RocksFlatStore.open(dir.toString())) {
            assertNotNull(reopened.db());
        }
    }

    @Test
    void external_store_is_not_closed_by_shut_down(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            RocksDbFlatSail sail = new RocksDbFlatSail(store);
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertNotNull(conn);
            }
            sail.shutDown();
            // The Sail does not own a caller-supplied store — it stays usable.
            assertDoesNotThrow(() -> store.db().get(store.dictForward(), new byte[] {0x01}));
        }
    }

    @Test
    void value_factory_is_the_shared_simple_value_factory(@TempDir Path dir) {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir);
        sail.init();
        try {
            assertSame(SimpleValueFactory.getInstance(), sail.getValueFactory());
        } finally {
            sail.shutDown();
        }
    }
}
