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
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SparqlNamespacesTest {

    private SparqlNamespaces fresh() {
        return new SparqlNamespaces(new InMemoryNodeStore(), new HeapBufferPool());
    }

    @Test
    void empty_get_returns_empty_optional() {
        assertEquals(Optional.empty(), fresh().get("foaf"));
    }

    @Test
    void set_then_get() {
        SparqlNamespaces ns = fresh();
        ns.set("ex", "http://example.com/");
        assertEquals(Optional.of("http://example.com/"), ns.get("ex"));
    }

    @Test
    void multiple_prefixes() {
        SparqlNamespaces ns = fresh();
        ns.set("foaf", "http://xmlns.com/foaf/0.1/");
        ns.set("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#");
        ns.set("ex", "http://example.com/");
        assertEquals("http://xmlns.com/foaf/0.1/", ns.get("foaf").orElseThrow());
        assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#", ns.get("rdf").orElseThrow());
        assertEquals("http://example.com/", ns.get("ex").orElseThrow());
    }

    @Test
    void overwrite_same_prefix() {
        SparqlNamespaces ns = fresh();
        ns.set("ex", "http://example.com/v1/");
        ns.set("ex", "http://example.com/v2/");
        assertEquals("http://example.com/v2/", ns.get("ex").orElseThrow());
    }

    @Test
    void remove_then_get_empty() {
        SparqlNamespaces ns = fresh();
        ns.set("ex", "http://example.com/");
        ns.remove("ex");
        assertEquals(Optional.empty(), ns.get("ex"));
    }

    @Test
    void unicode_prefix_and_namespace() {
        SparqlNamespaces ns = fresh();
        ns.set("日本", "https://例え.jp/オントロジー/");
        assertEquals("https://例え.jp/オントロジー/", ns.get("日本").orElseThrow());
    }

    @Test
    void persistent_across_commit_cycles() {
        SparqlNamespaces ns = fresh();
        ns.set("ex", "http://example.com/");
        ns.commit();
        ns.set("foaf", "http://xmlns.com/foaf/0.1/");
        ns.commit();
        assertEquals("http://example.com/", ns.get("ex").orElseThrow());
        assertEquals("http://xmlns.com/foaf/0.1/", ns.get("foaf").orElseThrow());
    }

    @Test
    void reopen_at_committed_root_preserves_entries() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        SparqlNamespaces ns1 = new SparqlNamespaces(store, pool);
        ns1.set("ex", "http://example.com/");
        ns1.set("foaf", "http://xmlns.com/foaf/0.1/");
        StaticMap committed = ns1.commit();

        SparqlNamespaces ns2 = new SparqlNamespaces(store, pool, committed);
        assertEquals("http://example.com/", ns2.get("ex").orElseThrow());
        assertEquals("http://xmlns.com/foaf/0.1/", ns2.get("foaf").orElseThrow());
    }

    @Test
    void snapshot_after_commit_returns_all_entries() {
        SparqlNamespaces ns = fresh();
        ns.set("a", "http://a.example/");
        ns.set("b", "http://b.example/");
        ns.set("c", "http://c.example/");
        ns.commit();
        var snap = ns.snapshot();
        assertEquals(3, snap.size());
        assertEquals("http://a.example/", snap.get("a"));
        assertEquals("http://b.example/", snap.get("b"));
        assertEquals("http://c.example/", snap.get("c"));
    }

    @Test
    void clear_then_commit_empties_map() {
        SparqlNamespaces ns = fresh();
        ns.set("ex", "http://example.com/");
        ns.set("foaf", "http://xmlns.com/foaf/0.1/");
        ns.commit();
        ns.clear();
        ns.commit();
        assertEquals(0, ns.snapshot().size());
    }

    @Test
    void empty_prefix_round_trip() {
        SparqlNamespaces ns = fresh();
        ns.set("", "http://default.example/");
        assertEquals("http://default.example/", ns.get("").orElseThrow());
    }

    @Test
    void empty_namespace_round_trip() {
        SparqlNamespaces ns = fresh();
        ns.set("empty", "");
        assertEquals("", ns.get("empty").orElseThrow());
    }
}
