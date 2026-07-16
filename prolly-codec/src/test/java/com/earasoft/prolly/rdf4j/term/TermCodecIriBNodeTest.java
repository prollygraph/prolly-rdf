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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TermCodecIriBNodeTest {

    // ==================================================================
    // IRI — short-prefix form (0x80)
    // ==================================================================
    @Nested
    class ShortPrefixIri {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeShortPrefixIri(1, "Person", a);
                // tag(1) + prefixId(4) + "Person"(6) = 11
                assertEquals(11, s.byteSize());
                assertEquals(TermCodec.TAG_IRI_SHORT_PREFIX, TermCodec.tagOf(s));
            }
        }

        @Test
        void round_trip_typical() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.ShortPrefixIri rt =
                        TermCodec.decodeShortPrefixIri(
                                TermCodec.payloadOf(TermCodec.encodeShortPrefixIri(8, "Thing", a)));
                assertEquals(8, rt.prefixId());
                assertEquals("Thing", rt.localPart());
            }
        }

        @Test
        void empty_local_part_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.ShortPrefixIri rt =
                        TermCodec.decodeShortPrefixIri(
                                TermCodec.payloadOf(TermCodec.encodeShortPrefixIri(42, "", a)));
                assertEquals(42, rt.prefixId());
                assertEquals("", rt.localPart());
            }
        }

        @Test
        void unicode_local_part_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                String local = "Personen";
                TermCodec.ShortPrefixIri rt =
                        TermCodec.decodeShortPrefixIri(
                                TermCodec.payloadOf(TermCodec.encodeShortPrefixIri(1, local, a)));
                assertEquals(local, rt.localPart());
            }
        }

        @Test
        void prefix_id_boundary_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                for (int id : new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x4000}) {
                    TermCodec.ShortPrefixIri rt =
                            TermCodec.decodeShortPrefixIri(
                                    TermCodec.payloadOf(
                                            TermCodec.encodeShortPrefixIri(id, "x", a)));
                    assertEquals(id, rt.prefixId());
                }
            }
        }

        @Test
        void lex_order_by_prefix_id_first() {
            try (Arena a = Arena.ofConfined()) {
                // prefix-id 1 < prefix-id 2, regardless of local part
                MemorySegment p1Big = TermCodec.encodeShortPrefixIri(1, "zzz", a);
                MemorySegment p2Small = TermCodec.encodeShortPrefixIri(2, "aaa", a);
                assertTrue(
                        Compare.compareUnsigned(p1Big, p2Small) < 0,
                        "prefix-id is primary sort key");
            }
        }

        @Test
        void lex_order_same_prefix_by_local() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment a1 = TermCodec.encodeShortPrefixIri(1, "Apple", a);
                MemorySegment a2 = TermCodec.encodeShortPrefixIri(1, "Banana", a);
                assertTrue(Compare.compareUnsigned(a1, a2) < 0);
            }
        }
    }

    // ==================================================================
    // IRI — long-prefix form (0x81)
    // ==================================================================
    @Nested
    class LongPrefixIri {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeLongPrefixIri(1, 2, "Foo", a);
                // tag(1) + 4+4 + "Foo"(3) = 12
                assertEquals(12, s.byteSize());
                assertEquals(TermCodec.TAG_IRI_LONG_PREFIX, TermCodec.tagOf(s));
            }
        }

        @Test
        void round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.LongPrefixIri rt =
                        TermCodec.decodeLongPrefixIri(
                                TermCodec.payloadOf(
                                        TermCodec.encodeLongPrefixIri(5, 100, "Item-42", a)));
                assertEquals(5, rt.prefixId1());
                assertEquals(100, rt.prefixId2());
                assertEquals("Item-42", rt.localPart());
            }
        }

        @Test
        void lex_order_by_first_prefix_then_second_then_local() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment a1 = TermCodec.encodeLongPrefixIri(1, 1, "z", a);
                MemorySegment a2 = TermCodec.encodeLongPrefixIri(1, 2, "a", a);
                MemorySegment a3 = TermCodec.encodeLongPrefixIri(2, 1, "a", a);
                assertTrue(Compare.compareUnsigned(a1, a2) < 0);
                assertTrue(Compare.compareUnsigned(a2, a3) < 0);
            }
        }
    }

    // ==================================================================
    // IRI — full form (0x82)
    // ==================================================================
    @Nested
    class FullIri {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                String iri = "http://example.com/foo";
                MemorySegment s = TermCodec.encodeFullIri(iri, a);
                assertEquals(1 + iri.length(), s.byteSize());
                assertEquals(TermCodec.TAG_IRI_FULL, TermCodec.tagOf(s));
            }
        }

        @Test
        void round_trip_typical() {
            try (Arena a = Arena.ofConfined()) {
                for (String iri :
                        new String[] {
                            "http://example.com/Person",
                            "urn:isbn:0451450523",
                            "https://api.example.com:8443/v3/items/42",
                            "", // empty IRI (degenerate but valid byte-wise)
                        }) {
                    String rt =
                            TermCodec.decodeFullIri(
                                    TermCodec.payloadOf(TermCodec.encodeFullIri(iri, a)));
                    assertEquals(iri, rt);
                }
            }
        }

        @Test
        void unicode_iri_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                String iri = "https://日本.example/パス";
                String rt =
                        TermCodec.decodeFullIri(
                                TermCodec.payloadOf(TermCodec.encodeFullIri(iri, a)));
                assertEquals(iri, rt);
            }
        }

        @Test
        void lex_order_matches_byte_compare() {
            try (Arena a = Arena.ofConfined()) {
                String[] sortedAsc = {
                    "http://a.example/",
                    "http://b.example/",
                    "https://a.example/", // 'h' < 'p' if both are at byte 4: h<t<t<p vs h<t<t<p<s
                    // ... actually 'http:/' < 'https:'
                };
                MemorySegment[] enc = new MemorySegment[sortedAsc.length];
                for (int i = 0; i < sortedAsc.length; i++) {
                    enc[i] = TermCodec.encodeFullIri(sortedAsc[i], a);
                }
                for (int i = 0; i + 1 < enc.length; i++) {
                    assertTrue(
                            Compare.compareUnsigned(enc[i], enc[i + 1]) < 0,
                            "lex order at " + i + ": " + sortedAsc[i] + " vs " + sortedAsc[i + 1]);
                }
            }
        }

        @Test
        void cross_form_lex_short_before_long_before_full() {
            try (Arena a = Arena.ofConfined()) {
                // Tag bytes: 0x80 < 0x81 < 0x82, so short < long < full lex-wise.
                MemorySegment shortForm = TermCodec.encodeShortPrefixIri(1, "x", a);
                MemorySegment longForm = TermCodec.encodeLongPrefixIri(1, 2, "x", a);
                MemorySegment fullForm = TermCodec.encodeFullIri("http://example/x", a);
                assertTrue(Compare.compareUnsigned(shortForm, longForm) < 0);
                assertTrue(Compare.compareUnsigned(longForm, fullForm) < 0);
            }
        }
    }

    // ==================================================================
    // BNode — UUID (0xA0)
    // ==================================================================
    @Nested
    class BNodeUuidT {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeBNodeUuid(UUID.randomUUID(), a);
                assertEquals(17, s.byteSize());
                assertEquals(TermCodec.TAG_BNODE_UUID, TermCodec.tagOf(s));
            }
        }

        @Test
        void round_trip_random() {
            SplittableRandom r = new SplittableRandom(123);
            try (Arena a = Arena.ofConfined()) {
                for (int i = 0; i < 50; i++) {
                    UUID u = new UUID(r.nextLong(), r.nextLong());
                    UUID rt =
                            TermCodec.decodeBNodeUuid(
                                    TermCodec.payloadOf(TermCodec.encodeBNodeUuid(u, a)));
                    assertEquals(u, rt);
                }
            }
        }

        @Test
        void nil_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                UUID nil = new UUID(0L, 0L);
                UUID rt =
                        TermCodec.decodeBNodeUuid(
                                TermCodec.payloadOf(TermCodec.encodeBNodeUuid(nil, a)));
                assertEquals(nil, rt);
            }
        }

        @Test
        void max_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                UUID max = new UUID(-1L, -1L);
                UUID rt =
                        TermCodec.decodeBNodeUuid(
                                TermCodec.payloadOf(TermCodec.encodeBNodeUuid(max, a)));
                assertEquals(max, rt);
            }
        }

        @Test
        void bnode_uuid_distinct_from_xsd_uuid() {
            try (Arena a = Arena.ofConfined()) {
                UUID u = UUID.randomUUID();
                MemorySegment bnode = TermCodec.encodeBNodeUuid(u, a);
                MemorySegment xsdUuid = TermCodec.encodeUuid(u, a);
                assertNotEquals(TermCodec.tagOf(bnode), TermCodec.tagOf(xsdUuid));
                // tags differ (0xA0 vs 0x30), but payloads are byte-identical
                assertEquals(
                        0,
                        Compare.compareUnsigned(
                                TermCodec.payloadOf(bnode), TermCodec.payloadOf(xsdUuid)));
            }
        }
    }

    // ==================================================================
    // BNode — labelled (0xA1)
    // ==================================================================
    @Nested
    class BNodeLabel {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeBNodeLabel("b1", a);
                assertEquals(1 + 2, s.byteSize());
                assertEquals(TermCodec.TAG_BNODE_LABEL, TermCodec.tagOf(s));
            }
        }

        @Test
        void typical_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                for (String label : new String[] {"b1", "node_42", "X-Y-Z", "abc123"}) {
                    String rt =
                            TermCodec.decodeBNodeLabel(
                                    TermCodec.payloadOf(TermCodec.encodeBNodeLabel(label, a)));
                    assertEquals(label, rt);
                }
            }
        }

        @Test
        void empty_label_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                String rt =
                        TermCodec.decodeBNodeLabel(
                                TermCodec.payloadOf(TermCodec.encodeBNodeLabel("", a)));
                assertEquals("", rt);
            }
        }

        @Test
        void unicode_label_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                String rt =
                        TermCodec.decodeBNodeLabel(
                                TermCodec.payloadOf(TermCodec.encodeBNodeLabel("ñode", a)));
                assertEquals("ñode", rt);
            }
        }

        @Test
        void lex_order_byte_compare() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment a1 = TermCodec.encodeBNodeLabel("a", a);
                MemorySegment a2 = TermCodec.encodeBNodeLabel("b", a);
                assertTrue(Compare.compareUnsigned(a1, a2) < 0);
            }
        }
    }

    // ==================================================================
    // BNode — canonical (0xA2)
    // ==================================================================
    @Nested
    class BNodeCanon {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeBNodeCanon(0, a);
                assertEquals(5, s.byteSize());
                assertEquals(TermCodec.TAG_BNODE_CANON, TermCodec.tagOf(s));
            }
        }

        @Test
        void typical_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                for (int idx : new int[] {0, 1, 42, 1_000_000, Integer.MAX_VALUE}) {
                    int rt =
                            TermCodec.decodeBNodeCanon(
                                    TermCodec.payloadOf(TermCodec.encodeBNodeCanon(idx, a)));
                    assertEquals(idx, rt);
                }
            }
        }

        @Test
        void negative_index_rejected() {
            try (Arena a = Arena.ofConfined()) {
                assertThrows(
                        IllegalArgumentException.class, () -> TermCodec.encodeBNodeCanon(-1, a));
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TermCodec.encodeBNodeCanon(Integer.MIN_VALUE, a));
            }
        }

        @Test
        void lex_order_by_index() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment c0 = TermCodec.encodeBNodeCanon(0, a);
                MemorySegment c1 = TermCodec.encodeBNodeCanon(1, a);
                MemorySegment c42 = TermCodec.encodeBNodeCanon(42, a);
                assertTrue(Compare.compareUnsigned(c0, c1) < 0);
                assertTrue(Compare.compareUnsigned(c1, c42) < 0);
            }
        }
    }

    // ==================================================================
    // Cross-form BNode partitioning
    // ==================================================================
    @Test
    void bnode_forms_partition_in_expected_tag_order() {
        try (Arena a = Arena.ofConfined()) {
            // 0xA0 < 0xA1 < 0xA2 — UUID-form < label-form < canonical-form (lex)
            MemorySegment uuid = TermCodec.encodeBNodeUuid(UUID.randomUUID(), a);
            MemorySegment label = TermCodec.encodeBNodeLabel("z-very-late", a);
            MemorySegment canon = TermCodec.encodeBNodeCanon(0, a);
            assertTrue(Compare.compareUnsigned(uuid, label) < 0);
            assertTrue(Compare.compareUnsigned(label, canon) < 0);
        }
    }
}
