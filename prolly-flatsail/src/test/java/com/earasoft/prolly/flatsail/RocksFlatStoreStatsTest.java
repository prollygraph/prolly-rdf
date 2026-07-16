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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The flatsail store-health statistics opt-in (rocksdb-perf-instrumentation Steps 6–7): the {@code
 * prolly.rocksdb.statistics} property lights up the full {@code Statistics} recorder in {@code
 * rocksDbFullStats()}, the default keeps it OFF (the behavior-spec contract — always-on ticker
 * counting has overhead), and the property-table sections render either way.
 */
class RocksFlatStoreStatsTest {

    @Test
    void statisticsOptIn_rendersTheRecorder_defaultStaysOff(@TempDir Path dir) throws Exception {
        // Default: OFF — no recorder section, but the property tables render.
        try (RocksFlatStore store = RocksFlatStore.open(dir.resolve("off").toString())) {
            store.db()
                    .put(
                            store.dictForward(),
                            "k".getBytes(StandardCharsets.UTF_8),
                            "v".getBytes(StandardCharsets.UTF_8));
            String dump = store.rocksDbFullStats();
            assertTrue(dump.contains("=== rocksdb.stats ==="), dump.substring(0, 200));
            assertFalse(
                    dump.contains("rocksdb Statistics"),
                    "recorder must be OFF by default (the behavior-spec contract)");
        }

        // Opt-in: the recorder section appears with real ticker lines.
        System.setProperty("prolly.rocksdb.statistics", "true");
        try (RocksFlatStore store = RocksFlatStore.open(dir.resolve("on").toString())) {
            store.db()
                    .put(
                            store.dictForward(),
                            "k".getBytes(StandardCharsets.UTF_8),
                            "v".getBytes(StandardCharsets.UTF_8));
            store.db().get(store.dictForward(), "k".getBytes(StandardCharsets.UTF_8));
            String dump = store.rocksDbFullStats();
            assertTrue(dump.contains("rocksdb Statistics"), "opt-in recorder section present");
            assertTrue(dump.contains("rocksdb.block.cache"), "ticker lines present");
        } finally {
            System.clearProperty("prolly.rocksdb.statistics");
        }
    }
}
