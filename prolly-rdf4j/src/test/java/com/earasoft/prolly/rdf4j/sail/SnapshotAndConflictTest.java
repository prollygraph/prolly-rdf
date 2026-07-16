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
import org.eclipse.rdf4j.sail.SailException;
import org.junit.jupiter.api.Test;

/**
 * Verifies the Phase 4 plumbing additions ({@link Snapshot}, {@link SailConflictException})
 * integrate cleanly without disturbing the rest of the codebase.
 */
class SnapshotAndConflictTest {

    @Test
    void snapshot_holds_null_roots_for_empty_sail() {
        Map<QuadOrder, StaticMap> empty = new EnumMap<>(QuadOrder.class);
        for (QuadOrder o : QuadOrder.values()) empty.put(o, null);
        Snapshot s = new Snapshot(null, empty, null, null);
        assertNull(s.dictRoot());
        assertNull(s.namespacesRoot());
        assertNull(s.statsRoot());
        for (QuadOrder o : QuadOrder.values()) {
            assertNull(s.indexRoots().get(o));
        }
    }

    @Test
    void snapshot_index_roots_map_is_defensively_copied() {
        Map<QuadOrder, StaticMap> input = new HashMap<>();
        for (QuadOrder o : QuadOrder.values()) input.put(o, null);
        Snapshot s = new Snapshot(null, input, null, null);
        // Mutating the caller's input must not affect the snapshot
        input.clear();
        for (QuadOrder o : QuadOrder.values()) {
            assertTrue(s.indexRoots().containsKey(o));
        }
    }

    @Test
    void snapshot_index_roots_view_is_immutable() {
        Map<QuadOrder, StaticMap> input = new EnumMap<>(QuadOrder.class);
        for (QuadOrder o : QuadOrder.values()) input.put(o, null);
        Snapshot s = new Snapshot(null, input, null, null);
        assertThrows(
                UnsupportedOperationException.class,
                () -> s.indexRoots().put(QuadOrder.SPOC, null));
    }

    @Test
    void snapshot_record_equality() {
        Map<QuadOrder, StaticMap> a = new EnumMap<>(QuadOrder.class);
        Map<QuadOrder, StaticMap> b = new EnumMap<>(QuadOrder.class);
        for (QuadOrder o : QuadOrder.values()) {
            a.put(o, null);
            b.put(o, null);
        }
        assertEquals(new Snapshot(null, a, null, null), new Snapshot(null, b, null, null));
    }

    @Test
    void sail_conflict_exception_is_a_sail_exception() {
        SailConflictException e = new SailConflictException("boom");
        assertInstanceOf(SailException.class, e);
        assertEquals("boom", e.getMessage());
    }

    @Test
    void sail_conflict_exception_chains_cause() {
        Throwable cause = new RuntimeException("under");
        SailConflictException e = new SailConflictException("over", cause);
        assertSame(cause, e.getCause());
    }

    @Test
    void sail_conflict_exception_catchable_as_sail_exception() {
        try {
            throw new SailConflictException("test");
        } catch (SailException expected) {
            assertEquals("test", expected.getMessage());
        }
    }
}
