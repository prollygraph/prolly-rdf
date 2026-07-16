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
package com.earasoft.prolly.rdf4j.value;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link ProllyIRI}. Three encoded forms (full, short-prefix,
 * long-prefix) — full IRIs are exercised without the prefix table; the short-prefix path goes
 * through PrefixTable lookup, which is its own failure mode worth pinning.
 */
class ProllyIRITest {

    private static final PrefixTable EMPTY_PREFIXES = makeEmptyTable();

    private static PrefixTable makeEmptyTable() {
        NodeStore store = new InMemoryNodeStore();
        DirectBufferPool pool = new DirectBufferPool();
        return new PrefixTable(store, pool);
    }

    // ---- full IRI ----

    @Test
    void full_iri_decodes_to_string() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://example.org/x", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertEquals("http://example.org/x", iri.stringValue());
        }
    }

    @Test
    void stringValue_caches_result() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://example.org/x", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            String first = iri.stringValue();
            String second = iri.stringValue();
            assertSame(first, second, "cached string must be the same instance on repeated calls");
        }
    }

    @Test
    void namespace_local_split_at_last_slash() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://example.org/path/x", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertEquals("http://example.org/path/", iri.getNamespace());
            assertEquals("x", iri.getLocalName());
        }
    }

    @Test
    void namespace_local_split_at_hash() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://example.org/types#Person", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertEquals("http://example.org/types#", iri.getNamespace());
            assertEquals("Person", iri.getLocalName());
        }
    }

    @Test
    void namespace_local_prefers_hash_when_after_slash() {
        // Both '/' and '#' present — the LATER one wins per the implementation.
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://x/a#b", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertEquals("http://x/a#", iri.getNamespace());
            assertEquals("b", iri.getLocalName());
        }
    }

    @Test
    void namespace_local_with_no_separator() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("urn:isbn:0451450523", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertEquals("", iri.getNamespace(), "no '/' or '#' → namespace is empty");
            assertEquals(
                    "urn:isbn:0451450523",
                    iri.getLocalName(),
                    "no separator → whole string is the local name");
        }
    }

    @Test
    void namespace_local_caches_split_result() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://x/y", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertSame(iri.getNamespace(), iri.getNamespace());
            assertSame(iri.getLocalName(), iri.getLocalName());
        }
    }

    // ---- toString ----

    @Test
    void toString_returns_full_iri_unwrapped() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeFullIri("http://example.org/x", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            assertEquals(
                    "http://example.org/x",
                    iri.toString(),
                    "ProllyIRI.toString does NOT wrap in angle brackets (unlike semantic.Iri)");
        }
    }

    // ---- equality ----

    @Test
    void equal_to_self() {
        try (Arena a = Arena.ofConfined()) {
            ProllyIRI iri = new ProllyIRI(TermCodec.encodeFullIri("http://x/a", a), EMPTY_PREFIXES);
            assertEquals(iri, iri);
        }
    }

    @Test
    void equal_to_other_ProllyIRI_with_same_string() {
        try (Arena a = Arena.ofConfined()) {
            ProllyIRI i1 = new ProllyIRI(TermCodec.encodeFullIri("http://x/y", a), EMPTY_PREFIXES);
            ProllyIRI i2 = new ProllyIRI(TermCodec.encodeFullIri("http://x/y", a), EMPTY_PREFIXES);
            assertEquals(i1, i2);
            assertEquals(i1.hashCode(), i2.hashCode());
        }
    }

    @Test
    void not_equal_to_other_iri_with_different_string() {
        try (Arena a = Arena.ofConfined()) {
            ProllyIRI i1 = new ProllyIRI(TermCodec.encodeFullIri("http://x/a", a), EMPTY_PREFIXES);
            ProllyIRI i2 = new ProllyIRI(TermCodec.encodeFullIri("http://x/b", a), EMPTY_PREFIXES);
            assertNotEquals(i1, i2);
        }
    }

    @Test
    void equal_to_rdf4j_SimpleIRI_with_same_string() {
        // RDF-semantic equality across IRI implementations.
        try (Arena a = Arena.ofConfined()) {
            ProllyIRI iri =
                    new ProllyIRI(
                            TermCodec.encodeFullIri("http://example.org/x", a), EMPTY_PREFIXES);
            IRI simple = SimpleValueFactory.getInstance().createIRI("http://example.org/x");
            assertEquals(iri, simple);
            assertEquals(iri.hashCode(), simple.hashCode());
        }
    }

    @Test
    void not_equal_to_non_iri_object() {
        try (Arena a = Arena.ofConfined()) {
            ProllyIRI iri = new ProllyIRI(TermCodec.encodeFullIri("http://x", a), EMPTY_PREFIXES);
            assertNotEquals(iri, "http://x");
            assertNotEquals(iri, null);
        }
    }

    // ---- short-prefix IRI ----

    @Test
    void short_prefix_iri_resolves_through_prefix_table() {
        // Register a prefix, encode an IRI using it, decode → matches concatenation.
        NodeStore store = new InMemoryNodeStore();
        DirectBufferPool pool = new DirectBufferPool();
        PrefixTable table = new PrefixTable(store, pool);
        int id = table.register("http://example.org/");

        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeShortPrefixIri(id, "Person", a);
            ProllyIRI iri = new ProllyIRI(enc, table);
            assertEquals("http://example.org/Person", iri.stringValue());
        }
    }

    @Test
    void short_prefix_iri_with_unknown_id_throws() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeShortPrefixIri(99999, "x", a);
            ProllyIRI iri = new ProllyIRI(enc, EMPTY_PREFIXES);
            IllegalStateException e = assertThrows(IllegalStateException.class, iri::stringValue);
            assertTrue(
                    e.getMessage().contains("unknown prefix-id"),
                    "error must point at the missing prefix lookup");
        }
    }

    // ---- long-prefix IRI (two prefix-table segments) ----

    @Test
    void long_prefix_iri_resolves_through_two_prefix_lookups() {
        NodeStore store = new InMemoryNodeStore();
        DirectBufferPool pool = new DirectBufferPool();
        PrefixTable table = new PrefixTable(store, pool);
        int id1 = table.register("http://example.org/");
        int id2 = table.register("deep/ns#");

        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeLongPrefixIri(id1, id2, "Person", a);
            ProllyIRI iri = new ProllyIRI(enc, table);
            assertEquals(
                    "http://example.org/deep/ns#Person",
                    iri.stringValue(),
                    "long-prefix IRI resolves to ns1 + ns2 + localPart");
        }
    }

    @Test
    void long_prefix_iri_with_unknown_id1_throws() {
        NodeStore store = new InMemoryNodeStore();
        DirectBufferPool pool = new DirectBufferPool();
        PrefixTable table = new PrefixTable(store, pool);
        int id2 = table.register("ns#");

        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeLongPrefixIri(88888, id2, "x", a);
            ProllyIRI iri = new ProllyIRI(enc, table);
            IllegalStateException e = assertThrows(IllegalStateException.class, iri::stringValue);
            assertTrue(
                    e.getMessage().contains("id1"),
                    "error must point at the missing first prefix segment");
        }
    }

    @Test
    void long_prefix_iri_with_unknown_id2_throws() {
        NodeStore store = new InMemoryNodeStore();
        DirectBufferPool pool = new DirectBufferPool();
        PrefixTable table = new PrefixTable(store, pool);
        int id1 = table.register("http://example.org/");

        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeLongPrefixIri(id1, 88888, "x", a);
            ProllyIRI iri = new ProllyIRI(enc, table);
            IllegalStateException e = assertThrows(IllegalStateException.class, iri::stringValue);
            assertTrue(
                    e.getMessage().contains("id2"),
                    "error must point at the missing second prefix segment");
        }
    }

    // ---- error path ----

    @Test
    void wrong_tag_byte_throws_on_decode() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment evil = a.allocate(1);
            evil.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0x00);
            ProllyIRI iri = new ProllyIRI(evil, EMPTY_PREFIXES);
            assertThrows(
                    IllegalStateException.class,
                    iri::stringValue,
                    "non-IRI tag must throw IllegalStateException");
        }
    }

    @Test
    void is_sealed_subtype_of_ProllyValue() {
        try (Arena a = Arena.ofConfined()) {
            ProllyIRI iri = new ProllyIRI(TermCodec.encodeFullIri("http://x", a), EMPTY_PREFIXES);
            assertInstanceOf(ProllyValue.class, iri);
            assertInstanceOf(IRI.class, iri);
        }
    }
}
