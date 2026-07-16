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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.sync.SyncPack;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit coverage for {@link SyncLimits} (plan Step 23). */
class SyncLimitsTest {

    @Test
    void defaults_admit_a_normally_sized_pack() {
        SyncLimits limits = SyncLimits.defaults();
        SyncPack pack = new SyncPack(List.of(new byte[1024]), List.of());
        assertDoesNotThrow(() -> limits.validate(pack));
    }

    @Test
    void chunk_count_cap_is_enforced() {
        SyncLimits limits = new SyncLimits(3, 1L << 30);
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < 4; i++) chunks.add(new byte[1]);
        SyncPack pack = new SyncPack(chunks, List.of());

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> limits.validate(pack));
        assertTrue(ex.getMessage().contains("maxChunks"), ex.getMessage());
    }

    @Test
    void byte_cap_is_enforced_and_bails_early() {
        SyncLimits limits = new SyncLimits(1_000, 100); // 100 bytes total
        List<byte[]> chunks = new ArrayList<>();
        chunks.add(new byte[64]);
        chunks.add(new byte[64]); // running total 128 > 100 — must trip mid-scan
        chunks.add(new byte[1_000]); // never inspected — short-circuit semantics
        SyncPack pack = new SyncPack(chunks, List.of());

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> limits.validate(pack));
        assertTrue(ex.getMessage().contains("maxBytes"), ex.getMessage());
    }

    @Test
    void zero_or_negative_limits_are_rejected_at_construction() {
        assertThrows(IllegalArgumentException.class, () -> new SyncLimits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SyncLimits(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SyncLimits(-1, 1));
    }
}
