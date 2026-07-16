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
package com.earasoft.prolly.rdf4j.term;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class PrefixTableTest {

    private PrefixTable fresh() {
        return new PrefixTable(new InMemoryNodeStore(), new HeapBufferPool());
    }

    @Test
    void bootstrap_entries_present_with_canonical_ids() {
        PrefixTable t = fresh();
        // Spot-check the 15 bootstrap entries from SPEC §0.5
        assertEquals(
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
                t.lookupNamespaceAsString(PrefixTable.ID_RDF).orElseThrow());
        assertEquals(
                "http://www.w3.org/2000/01/rdf-schema#",
                t.lookupNamespaceAsString(PrefixTable.ID_RDFS).orElseThrow());
        assertEquals(
                "http://www.w3.org/2002/07/owl#",
                t.lookupNamespaceAsString(PrefixTable.ID_OWL).orElseThrow());
        assertEquals(
                "http://www.w3.org/2001/XMLSchema#",
                t.lookupNamespaceAsString(PrefixTable.ID_XSD).orElseThrow());
        assertEquals(
                "https://schema.org/",
                t.lookupNamespaceAsString(PrefixTable.ID_SCHEMA).orElseThrow());
        assertEquals(
                "http://www.w3.org/2006/time#",
                t.lookupNamespaceAsString(PrefixTable.ID_TIME).orElseThrow());
    }

    @Test
    void all_15_bootstrap_entries_round_trip() {
        PrefixTable t = fresh();
        for (var e : PrefixTable.BOOTSTRAP) {
            int id = e.getKey();
            String ns = e.getValue();
            assertEquals(ns, t.lookupNamespaceAsString(id).orElseThrow());
            assertEquals(id, t.lookupId(ns).orElseThrow());
        }
    }

    @Test
    void bootstrap_ids_are_one_through_fifteen() {
        PrefixTable t = fresh();
        // Walk 1..15 inclusive — each must resolve
        for (int id = 1; id <= 15; id++) {
            assertTrue(t.lookupNamespace(id).isPresent(), "missing bootstrap id " + id);
        }
        // 16..1023 should be empty (reserved for future bootstraps)
        assertFalse(t.lookupNamespace(16).isPresent());
        assertFalse(t.lookupNamespace(100).isPresent());
        assertFalse(t.lookupNamespace(1023).isPresent());
    }

    @Test
    void unregistered_namespace_returns_empty_for_lookupId() {
        PrefixTable t = fresh();
        assertEquals(OptionalInt.empty(), t.lookupId("http://not-registered.example/"));
    }

    @Test
    void register_first_runtime_namespace_gets_id_1024() {
        PrefixTable t = fresh();
        int id = t.register("http://example.com/myns#");
        assertEquals(PrefixTable.RUNTIME_ID_START, id);
        assertEquals(1024, id);
    }

    @Test
    void register_multiple_namespaces_gets_sequential_ids() {
        PrefixTable t = fresh();
        int id1 = t.register("http://a.example/");
        int id2 = t.register("http://b.example/");
        int id3 = t.register("http://c.example/");
        assertEquals(1024, id1);
        assertEquals(1025, id2);
        assertEquals(1026, id3);
    }

    @Test
    void register_is_idempotent() {
        PrefixTable t = fresh();
        int first = t.register("http://example.com/");
        int second = t.register("http://example.com/");
        assertEquals(first, second);
        assertEquals(1024, first);
    }

    @Test
    void re_registering_bootstrap_returns_bootstrap_id() {
        PrefixTable t = fresh();
        int id = t.register("http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        assertEquals(PrefixTable.ID_RDF, id);
        // No runtime IDs consumed
        assertEquals(PrefixTable.RUNTIME_ID_START - 1, t.highestRuntimeId());
    }

    @Test
    void register_and_lookup_round_trip() {
        PrefixTable t = fresh();
        String ns = "http://example.com/myns#";
        int id = t.register(ns);
        assertEquals(ns, t.lookupNamespaceAsString(id).orElseThrow());
        assertEquals(id, t.lookupId(ns).orElseThrow());
    }

    @Test
    void unicode_namespace_round_trip() {
        PrefixTable t = fresh();
        String ns = "https://日本語.example/オントロジー#";
        int id = t.register(ns);
        assertEquals(ns, t.lookupNamespaceAsString(id).orElseThrow());
        assertEquals(id, t.lookupId(ns).orElseThrow());
    }

    @Test
    void empty_namespace_round_trip() {
        PrefixTable t = fresh();
        int id = t.register("");
        assertEquals("", t.lookupNamespaceAsString(id).orElseThrow());
        assertEquals(id, t.lookupId("").orElseThrow());
    }

    @Test
    void size_includes_bootstrap_plus_runtime() {
        PrefixTable t = fresh();
        assertEquals(15, t.size());
        t.register("http://a.example/");
        t.register("http://b.example/");
        assertEquals(17, t.size());
    }

    // -------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------

    @Test
    void commit_returns_static_map() {
        PrefixTable t = fresh();
        StaticMap committed = t.commit();
        assertNotNull(committed);
        assertNotNull(committed.root()); // bootstrap entries make it non-empty
    }

    @Test
    void empty_commit_after_no_changes_safe() {
        PrefixTable t = fresh();
        t.commit(); // commits bootstraps
        StaticMap second = t.commit(); // no changes
        assertNotNull(second);
    }

    @Test
    void reopen_at_committed_root_preserves_all_entries() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        PrefixTable t1 = new PrefixTable(store, pool);
        int customId = t1.register("http://my.example/v1#");
        StaticMap committed = t1.commit();

        // Re-open at the committed root
        PrefixTable t2 = new PrefixTable(store, pool, committed);
        // Bootstraps survive
        assertEquals(
                "http://www.w3.org/2001/XMLSchema#",
                t2.lookupNamespaceAsString(PrefixTable.ID_XSD).orElseThrow());
        // Custom entry survives
        assertEquals("http://my.example/v1#", t2.lookupNamespaceAsString(customId).orElseThrow());
        assertEquals(customId, t2.lookupId("http://my.example/v1#").orElseThrow());
    }

    @Test
    void reopen_continues_runtime_id_sequence() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        PrefixTable t1 = new PrefixTable(store, pool);
        t1.register("http://a.example/"); // gets 1024
        t1.register("http://b.example/"); // gets 1025
        StaticMap committed = t1.commit();

        PrefixTable t2 = new PrefixTable(store, pool, committed);
        // Next runtime id should continue at 1026
        int id = t2.register("http://c.example/");
        assertEquals(1026, id);
    }

    @Test
    void reopen_does_not_double_register_bootstraps() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        PrefixTable t1 = new PrefixTable(store, pool);
        StaticMap committed = t1.commit();

        PrefixTable t2 = new PrefixTable(store, pool, committed);
        // Still 15 bootstraps, no duplicates
        assertEquals(15, t2.size());
    }

    @Test
    void runtime_registrations_survive_multiple_commit_cycles() {
        PrefixTable t = fresh();
        int id1 = t.register("http://a.example/");
        t.commit();
        int id2 = t.register("http://b.example/");
        t.commit();
        int id3 = t.register("http://c.example/");
        assertEquals(1024, id1);
        assertEquals(1025, id2);
        assertEquals(1026, id3);
        assertEquals(id1, t.lookupId("http://a.example/").orElseThrow());
        assertEquals(id2, t.lookupId("http://b.example/").orElseThrow());
        assertEquals(id3, t.lookupId("http://c.example/").orElseThrow());
    }
}
