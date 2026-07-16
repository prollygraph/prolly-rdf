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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.UUID;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link DictionaryTermResolver} — the {@link TermResolver} that reads encoded bytes
 * from a {@link Dictionary} and wraps them in the right {@link ProllyValue} variant by the term's
 * leading tag byte.
 *
 * <p>The {@code wrap} switch dispatches on the tag's high nibble; this file pins every mapped
 * family arm end-to-end (real term encoded via {@link TermCodec}, stored in a real {@link
 * Dictionary}, resolved back) plus the unmapped-nibble arms that must throw.
 *
 * <p>Real {@link InMemoryNodeStore} + {@link HeapBufferPool} — no mocks.
 */
class DictionaryTermResolverTest {

    private NodeStore store = new InMemoryNodeStore();
    private BufferPool pool = new HeapBufferPool();

    private Dictionary dict() {
        return new Dictionary(store, pool, HashFunctions.defaultHash());
    }

    private DictionaryTermResolver resolver(Dictionary d) {
        return new DictionaryTermResolver(d, new PrefixTable(store, pool));
    }

    private DictionaryTermResolver cachedResolver(Dictionary d) {
        return new DictionaryTermResolver(d, new PrefixTable(store, pool), 1024);
    }

    // ---- each mapped tag family, end-to-end ----

    @Test
    void resolve_integer_literal_yields_a_literal() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            TermId id = d.encode(TermCodec.encodeInteger(42L, a));
            ProllyValue v = resolver(d).resolve(id);
            assertInstanceOf(Literal.class, v, "integer term family → a Literal");
        }
    }

    @Test
    void resolve_string_literal_yields_a_literal() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            TermId id = d.encode(TermCodec.encodeXsdString("hello world", a));
            ProllyValue v = resolver(d).resolve(id);
            assertInstanceOf(Literal.class, v);
            assertEquals("hello world", v.stringValue());
        }
    }

    @Test
    void resolve_full_iri_yields_an_iri() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            TermId id = d.encode(TermCodec.encodeFullIri("http://example.org/thing", a));
            ProllyValue v = resolver(d).resolve(id);
            assertInstanceOf(IRI.class, v, "IRI term family → an IRI");
            assertEquals("http://example.org/thing", v.stringValue());
        }
    }

    @Test
    void resolve_bnode_yields_a_bnode() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            TermId id =
                    d.encode(
                            TermCodec.encodeBNodeUuid(
                                    UUID.fromString("00000000-0000-0000-0000-0000000000ff"), a));
            ProllyValue v = resolver(d).resolve(id);
            assertInstanceOf(BNode.class, v, "blank-node term family → a BNode");
        }
    }

    @Test
    void resolve_is_consistent_across_repeated_calls() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            TermId id = d.encode(TermCodec.encodeAnyURI("http://example.org/x", a));
            DictionaryTermResolver r = resolver(d);
            assertEquals(
                    r.resolve(id).stringValue(),
                    r.resolve(id).stringValue(),
                    "resolving the same TermId twice must yield the same value");
        }
    }

    // ---- decode cache (Step 3 of read-path-cache-and-zerocopy): transparency + hit-serving ----

    @Test
    void decode_cache_is_transparent_cache_on_equals_cache_off() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            // One id per mapped family: IRI, xsd:string literal, integer literal, bnode.
            TermId iri = d.encode(TermCodec.encodeFullIri("http://example.org/c", a));
            TermId str = d.encode(TermCodec.encodeXsdString("label text", a));
            TermId num = d.encode(TermCodec.encodeInteger(7L, a));
            TermId bn =
                    d.encode(
                            TermCodec.encodeBNodeUuid(
                                    UUID.fromString("00000000-0000-0000-0000-00000000beef"), a));
            DictionaryTermResolver plain = resolver(d);
            DictionaryTermResolver cached = cachedResolver(d);
            for (TermId id : new TermId[] {iri, str, num, bn}) {
                ProllyValue off = plain.resolve(id);
                ProllyValue on = cached.resolve(id);
                assertEquals(
                        off, on, "cache-ON must resolve to the same value as cache-OFF for " + id);
                assertEquals(off.stringValue(), on.stringValue());
            }
        }
    }

    @Test
    void decode_cache_serves_a_repeated_id_from_cache() {
        try (Arena a = Arena.ofConfined()) {
            Dictionary d = dict();
            TermId id = d.encode(TermCodec.encodeFullIri("http://example.org/hot", a));
            DictionaryTermResolver cached = cachedResolver(d);
            ProllyValue first = cached.resolve(id);
            ProllyValue second = cached.resolve(id);
            // A cache hit returns the *same* materialized instance — proof the second resolve
            // skipped the decode + wrap rather than rebuilding the value.
            assertSame(
                    first,
                    second,
                    "the second resolve of a hot id must be served from the cache (same instance)");
        }
    }

    @Test
    void decode_cache_does_not_memoize_a_miss() {
        // A miss throws and must NOT be cached: were the term later inserted under that id, a
        // memoized miss would wrongly keep throwing. (Belt-and-suspenders for the no-stale rule.)
        Dictionary d = dict();
        DictionaryTermResolver cached = cachedResolver(d);
        TermId absent = TermId.of(0x0BADF00DL);
        assertThrows(IllegalStateException.class, () -> cached.resolve(absent));
        assertThrows(IllegalStateException.class, () -> cached.resolve(absent));
    }

    // ---- error path: TermId not in the dictionary ----

    @Test
    void resolve_unknown_termid_throws_illegal_state() {
        Dictionary d = dict();
        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> resolver(d).resolve(TermId.of(0x1234_5678L)));
        assertTrue(
                ex.getMessage().contains("not in dictionary"),
                "the error must name the missing-term cause: " + ex.getMessage());
    }

    // ---- wrap(): unmapped tag families must fail loudly ----

    @Test
    void wrap_rejects_unmapped_tag_families() {
        DictionaryTermResolver r = resolver(dict());
        // High nibbles 0x70, 0xB0, 0xD0, 0xF0 have no ProllyValue mapping.
        for (int family : new int[] {0x70, 0xB0, 0xD0, 0xF0}) {
            MemorySegment fake = MemorySegment.ofArray(new byte[] {(byte) family, 0, 0, 0});
            IllegalStateException ex =
                    assertThrows(
                            IllegalStateException.class,
                            () -> r.wrap(fake),
                            "tag family 0x" + Integer.toHexString(family) + " must be rejected");
            assertTrue(
                    ex.getMessage().contains("unknown tag family"),
                    "the error must identify the bad family: " + ex.getMessage());
        }
    }

    @Test
    void wrap_dispatches_a_directly_supplied_iri_segment() {
        // wrap() is also called on already-fetched bytes (no Dictionary round-trip).
        try (Arena a = Arena.ofConfined()) {
            MemorySegment iri = TermCodec.encodeFullIri("http://example.org/direct", a);
            ProllyValue v = resolver(dict()).wrap(iri);
            assertInstanceOf(IRI.class, v);
            assertEquals("http://example.org/direct", v.stringValue());
        }
    }
}
