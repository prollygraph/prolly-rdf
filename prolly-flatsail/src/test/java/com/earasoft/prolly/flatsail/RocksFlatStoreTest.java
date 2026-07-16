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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/** Coverage for {@link RocksFlatStore} — the flat Sail's 7-column-family RocksDB lifecycle. */
class RocksFlatStoreTest {
    static {
        RocksDB.loadLibrary();
    }

    @Test
    void there_are_seven_named_column_families() {
        assertEquals(7, RocksFlatStore.COLUMN_FAMILIES.size());
        assertTrue(
                RocksFlatStore.COLUMN_FAMILIES.containsAll(
                        java.util.List.of(
                                "dict-fwd", "dict-rev", "spoc", "posc", "ospc", "cspo", "ns")));
    }

    @Test
    void open_exposes_all_seven_column_families(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            for (String cf : RocksFlatStore.COLUMN_FAMILIES) {
                assertNotNull(store.columnFamily(cf), "missing column family: " + cf);
            }
            // The typed accessors and the index() map resolve to real handles.
            assertNotNull(store.dictForward());
            assertNotNull(store.dictReverse());
            assertNotNull(store.namespaces());
            for (QuadOrder order : QuadOrder.values()) {
                assertNotNull(store.index(order), "no index CF for " + order);
            }
        }
    }

    @Test
    void index_accessor_maps_each_order_to_its_own_column_family(@TempDir Path dir)
            throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            assertSame(store.columnFamily("spoc"), store.index(QuadOrder.SPOC));
            assertSame(store.columnFamily("posc"), store.index(QuadOrder.POSC));
            assertSame(store.columnFamily("ospc"), store.index(QuadOrder.OSPC));
            assertSame(store.columnFamily("cspo"), store.index(QuadOrder.CSPO));
            // The four index CFs are distinct handles.
            assertNotSame(store.index(QuadOrder.SPOC), store.index(QuadOrder.POSC));
        }
    }

    @Test
    void column_families_are_isolated(@TempDir Path dir) throws Exception {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        byte[] val = "v".getBytes(StandardCharsets.UTF_8);
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            store.db().put(store.index(QuadOrder.SPOC), key, val);
            assertArrayEquals(val, store.db().get(store.index(QuadOrder.SPOC), key));
            assertNull(
                    store.db().get(store.index(QuadOrder.POSC), key),
                    "a key written to spoc must not be visible in posc");
            assertNull(
                    store.db().get(store.dictForward(), key),
                    "an index key must not leak into the dictionary CF");
        }
    }

    @Test
    void data_survives_close_and_reopen(@TempDir Path dir) throws Exception {
        byte[] key = "urn:x".getBytes(StandardCharsets.UTF_8);
        byte[] val = "1".getBytes(StandardCharsets.UTF_8);
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            store.db().put(store.dictForward(), key, val);
        }
        // Reopen the same directory — the seven CFs must be rediscovered via
        // listColumnFamilies and the value must still be there.
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            assertArrayEquals(
                    val,
                    store.db().get(store.dictForward(), key),
                    "dictionary value must survive a close/reopen cycle");
        }
    }

    @Test
    void unknown_column_family_is_rejected(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            assertThrows(IllegalArgumentException.class, () -> store.columnFamily("no-such-cf"));
        }
    }

    @Test
    void close_is_idempotent(@TempDir Path dir) throws Exception {
        RocksFlatStore store = RocksFlatStore.open(dir.toString());
        store.close();
        assertDoesNotThrow(store::close, "a second close must be a no-op");
    }
}
