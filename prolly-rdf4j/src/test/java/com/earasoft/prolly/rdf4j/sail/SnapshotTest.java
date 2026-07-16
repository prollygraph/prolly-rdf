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

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the {@link Snapshot} record. The constructor's defensive copy is what prevents a
 * caller from mutating the recorded snapshot after fork — silent mutation would break the
 * CAS-expected-parent invariant for Phase 4 multi-writer commits.
 *
 * <p>Pin the EnumMap defensive copy in particular: {@code Map.copyOf} rejects null values, but null
 * is the "empty tree" sentinel here, so the implementation must use EnumMap.
 */
class SnapshotTest {

    @Test
    void empty_snapshot_all_nulls() {
        Snapshot s = new Snapshot(null, Map.of(), null, null);
        assertNull(s.dictRoot());
        assertNull(s.namespacesRoot());
        assertNull(s.statsRoot());
        assertTrue(s.indexRoots().isEmpty());
    }

    @Test
    void index_roots_accept_null_static_map_values() {
        // Critical: null StaticMap = "empty tree, no commits yet". Map.copyOf
        // would reject these and break the contract.
        Map<QuadOrder, StaticMap> withNulls = new HashMap<>();
        withNulls.put(QuadOrder.SPOC, null);
        withNulls.put(QuadOrder.POSC, null);
        Snapshot s = new Snapshot(null, withNulls, null, null);
        assertNull(
                s.indexRoots().get(QuadOrder.SPOC),
                "null-as-empty-tree semantics must survive defensive copy");
        assertNull(s.indexRoots().get(QuadOrder.POSC));
    }

    @Test
    void index_roots_defensively_copied() {
        // Mutating the source map after construction must not affect the snapshot.
        Map<QuadOrder, StaticMap> mutable = new HashMap<>();
        mutable.put(QuadOrder.SPOC, null);
        Snapshot s = new Snapshot(null, mutable, null, null);
        mutable.put(QuadOrder.POSC, null);
        assertEquals(
                1,
                s.indexRoots().size(),
                "Snapshot must defensively copy indexRoots so post-fork mutation can't poison the CAS-expected parent");
    }

    @Test
    void index_roots_map_immutable_to_callers() {
        Snapshot s = new Snapshot(null, Map.of(), null, null);
        assertThrows(
                UnsupportedOperationException.class,
                () -> s.indexRoots().put(QuadOrder.SPOC, null));
    }

    @Test
    void index_roots_returns_enum_map_keyed_by_quad_order() {
        // The defensive copy is an EnumMap — pin that all QuadOrder keys
        // can be present without surprises.
        Map<QuadOrder, StaticMap> source = new HashMap<>();
        for (QuadOrder o : QuadOrder.values()) source.put(o, null);
        Snapshot s = new Snapshot(null, source, null, null);
        assertEquals(QuadOrder.values().length, s.indexRoots().size());
        for (QuadOrder o : QuadOrder.values()) {
            assertTrue(
                    s.indexRoots().containsKey(o),
                    "every QuadOrder must round-trip through the defensive copy: " + o);
        }
    }

    @Test
    void enumMap_source_works_too() {
        // EnumMap → EnumMap copy must not throw.
        EnumMap<QuadOrder, StaticMap> source = new EnumMap<>(QuadOrder.class);
        source.put(QuadOrder.SPOC, null);
        Snapshot s = new Snapshot(null, source, null, null);
        assertEquals(1, s.indexRoots().size());
    }

    @Test
    void record_equality_by_value() {
        Snapshot a = new Snapshot(null, Map.of(), null, null);
        Snapshot b = new Snapshot(null, Map.of(), null, null);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void distinct_index_roots_not_equal() {
        // Different keys present → different snapshots.
        Map<QuadOrder, StaticMap> a = new HashMap<>();
        a.put(QuadOrder.SPOC, null);
        Map<QuadOrder, StaticMap> b = new HashMap<>();
        b.put(QuadOrder.POSC, null);
        Snapshot sa = new Snapshot(null, a, null, null);
        Snapshot sb = new Snapshot(null, b, null, null);
        assertNotEquals(sa, sb);
    }

    @Test
    void all_null_field_snapshot_is_meaningful() {
        // A snapshot of an entirely fresh Sail — pre-genesis — is legitimate.
        Snapshot fresh = new Snapshot(null, Map.of(), null, null);
        assertNotNull(
                fresh,
                "all-null fields represent a fresh, never-committed Sail — must be constructible");
    }

    @Test
    void null_index_roots_map_throws() {
        // The constructor calls putAll which NPEs on a null source.
        assertThrows(NullPointerException.class, () -> new Snapshot(null, null, null, null));
    }
}
