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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TermCodecQuotedAndExtendedTest {

    // ==================================================================
    // QuotedTriple (0xC0 / 0xC1)
    // ==================================================================
    @Nested
    class QuotedTriple {

        @Test
        void asserted_tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
                assertEquals(25, s.byteSize());
                assertEquals(TermCodec.TAG_QUOTED_TRIPLE_ASSERTED, TermCodec.tagOf(s));
            }
        }

        @Test
        void unasserted_tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(2L), TermId.of(3L), false, a);
                assertEquals(25, s.byteSize());
                assertEquals(TermCodec.TAG_QUOTED_TRIPLE_UNASSERTED, TermCodec.tagOf(s));
            }
        }

        @Test
        void round_trip_asserted() {
            try (Arena a = Arena.ofConfined()) {
                TermId s = TermId.of(0x1111L);
                TermId p = TermId.of(0x2222L);
                TermId o = TermId.of(0x3333L);
                TermCodec.QuotedTriple rt =
                        TermCodec.decodeQuotedTriple(
                                TermCodec.encodeQuotedTriple(s, p, o, true, a));
                assertEquals(s, rt.s());
                assertEquals(p, rt.p());
                assertEquals(o, rt.o());
                assertTrue(rt.asserted());
            }
        }

        @Test
        void round_trip_unasserted() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.QuotedTriple rt =
                        TermCodec.decodeQuotedTriple(
                                TermCodec.encodeQuotedTriple(
                                        TermId.of(1L), TermId.of(2L), TermId.of(3L), false, a));
                assertFalse(rt.asserted());
            }
        }

        @Test
        void extension_termIds_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermId s = TermId.ofExtensionSlot(0L);
                TermId p = TermId.ofExtensionSlot(42L);
                TermId o = TermId.ofExtensionSlot(Long.MAX_VALUE >>> 1);
                TermCodec.QuotedTriple rt =
                        TermCodec.decodeQuotedTriple(
                                TermCodec.encodeQuotedTriple(s, p, o, true, a));
                assertTrue(rt.s().isExtension());
                assertTrue(rt.p().isExtension());
                assertTrue(rt.o().isExtension());
                assertEquals(s, rt.s());
                assertEquals(p, rt.p());
                assertEquals(o, rt.o());
            }
        }

        @Test
        void boundary_termId_values_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermId[] samples = {
                    TermId.of(0L),
                    TermId.of(Long.MIN_VALUE),
                    TermId.of(Long.MAX_VALUE),
                    TermId.of(-1L),
                    TermId.of(1L),
                };
                for (TermId s : samples) {
                    for (TermId p : samples) {
                        for (TermId o : samples) {
                            TermCodec.QuotedTriple rt =
                                    TermCodec.decodeQuotedTriple(
                                            TermCodec.encodeQuotedTriple(s, p, o, true, a));
                            assertEquals(s, rt.s(), "s mismatch");
                            assertEquals(p, rt.p(), "p mismatch");
                            assertEquals(o, rt.o(), "o mismatch");
                        }
                    }
                }
            }
        }

        @Test
        void asserted_and_unasserted_same_payload_differ_only_in_tag() {
            try (Arena a = Arena.ofConfined()) {
                TermId s = TermId.of(1L), p = TermId.of(2L), o = TermId.of(3L);
                MemorySegment asserted = TermCodec.encodeQuotedTriple(s, p, o, true, a);
                MemorySegment unassert = TermCodec.encodeQuotedTriple(s, p, o, false, a);
                assertNotEquals(TermCodec.tagOf(asserted), TermCodec.tagOf(unassert));
                assertEquals(
                        0,
                        Compare.compareUnsigned(
                                TermCodec.payloadOf(asserted), TermCodec.payloadOf(unassert)));
            }
        }

        @Test
        void asserted_lex_precedes_unasserted() {
            try (Arena a = Arena.ofConfined()) {
                // 0xC0 < 0xC1
                MemorySegment asserted =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
                MemorySegment unassert =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(2L), TermId.of(3L), false, a);
                assertTrue(Compare.compareUnsigned(asserted, unassert) < 0);
            }
        }

        @Test
        void lex_order_within_assertedness_by_components_unsigned() {
            try (Arena a = Arena.ofConfined()) {
                // BE64 unsigned compare on s, then p, then o
                MemorySegment t1 =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(1L), TermId.of(1L), true, a);
                MemorySegment t2 =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(1L), TermId.of(2L), true, a);
                MemorySegment t3 =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(2L), TermId.of(0L), true, a);
                MemorySegment t4 =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(2L), TermId.of(0L), TermId.of(0L), true, a);
                assertTrue(Compare.compareUnsigned(t1, t2) < 0);
                assertTrue(Compare.compareUnsigned(t2, t3) < 0);
                assertTrue(Compare.compareUnsigned(t3, t4) < 0);
            }
        }

        @Test
        void decoder_rejects_non_quoted_tag() {
            try (Arena a = Arena.ofConfined()) {
                // Some non-quoted segment (e.g., an xsd:integer)
                MemorySegment notQuoted = TermCodec.encodeInteger(42L, a);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TermCodec.decodeQuotedTriple(notQuoted));
            }
        }

        @Test
        void decoder_rejects_quad_tag() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment quad =
                        TermCodec.encodeQuotedQuad(
                                TermId.of(1L),
                                TermId.of(2L),
                                TermId.of(3L),
                                TermId.of(4L),
                                true,
                                a);
                assertThrows(
                        IllegalArgumentException.class, () -> TermCodec.decodeQuotedTriple(quad));
            }
        }

        @Test
        void random_corpus_round_trip() {
            SplittableRandom r = new SplittableRandom(0xC0DECAFEL);
            try (Arena a = Arena.ofConfined()) {
                for (int i = 0; i < 100; i++) {
                    TermId s = TermId.of(r.nextLong());
                    TermId p = TermId.of(r.nextLong());
                    TermId o = TermId.of(r.nextLong());
                    boolean asserted = r.nextBoolean();
                    TermCodec.QuotedTriple rt =
                            TermCodec.decodeQuotedTriple(
                                    TermCodec.encodeQuotedTriple(s, p, o, asserted, a));
                    assertEquals(s, rt.s());
                    assertEquals(p, rt.p());
                    assertEquals(o, rt.o());
                    assertEquals(asserted, rt.asserted());
                }
            }
        }
    }

    // ==================================================================
    // QuotedQuad (0xC2 / 0xC3)
    // ==================================================================
    @Nested
    class QuotedQuad {

        @Test
        void asserted_tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s =
                        TermCodec.encodeQuotedQuad(
                                TermId.of(1L),
                                TermId.of(2L),
                                TermId.of(3L),
                                TermId.of(4L),
                                true,
                                a);
                assertEquals(33, s.byteSize());
                assertEquals(TermCodec.TAG_QUOTED_QUAD_ASSERTED, TermCodec.tagOf(s));
            }
        }

        @Test
        void unasserted_tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s =
                        TermCodec.encodeQuotedQuad(
                                TermId.of(1L),
                                TermId.of(2L),
                                TermId.of(3L),
                                TermId.of(4L),
                                false,
                                a);
                assertEquals(33, s.byteSize());
                assertEquals(TermCodec.TAG_QUOTED_QUAD_UNASSERTED, TermCodec.tagOf(s));
            }
        }

        @Test
        void round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.QuotedQuad rt =
                        TermCodec.decodeQuotedQuad(
                                TermCodec.encodeQuotedQuad(
                                        TermId.of(0xAAAA_AAAAL),
                                        TermId.of(0xBBBB_BBBBL),
                                        TermId.of(0xCCCC_CCCCL),
                                        TermId.of(0xDDDD_DDDDL),
                                        true,
                                        a));
                assertEquals(0xAAAA_AAAAL, rt.s().value());
                assertEquals(0xBBBB_BBBBL, rt.p().value());
                assertEquals(0xCCCC_CCCCL, rt.o().value());
                assertEquals(0xDDDD_DDDDL, rt.c().value());
                assertTrue(rt.asserted());
            }
        }

        @Test
        void lex_partition_with_triples() {
            try (Arena a = Arena.ofConfined()) {
                // Triple tags 0xC0/0xC1 < quad tags 0xC2/0xC3
                MemorySegment tripleAsserted =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(1L), TermId.of(1L), true, a);
                MemorySegment tripleUnasserted =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(1L), TermId.of(1L), false, a);
                MemorySegment quadAsserted =
                        TermCodec.encodeQuotedQuad(
                                TermId.of(1L),
                                TermId.of(1L),
                                TermId.of(1L),
                                TermId.of(1L),
                                true,
                                a);
                assertTrue(Compare.compareUnsigned(tripleAsserted, tripleUnasserted) < 0);
                assertTrue(Compare.compareUnsigned(tripleUnasserted, quadAsserted) < 0);
            }
        }

        @Test
        void decoder_rejects_triple_tag() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment triple =
                        TermCodec.encodeQuotedTriple(
                                TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
                assertThrows(
                        IllegalArgumentException.class, () -> TermCodec.decodeQuotedQuad(triple));
            }
        }

        @Test
        void context_distinguishes_quads() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment q1 =
                        TermCodec.encodeQuotedQuad(
                                TermId.of(1L),
                                TermId.of(2L),
                                TermId.of(3L),
                                TermId.of(10L),
                                true,
                                a);
                MemorySegment q2 =
                        TermCodec.encodeQuotedQuad(
                                TermId.of(1L),
                                TermId.of(2L),
                                TermId.of(3L),
                                TermId.of(20L),
                                true,
                                a);
                assertTrue(Compare.compareUnsigned(q1, q2) < 0);
            }
        }
    }

    // ==================================================================
    // CustomLiteral (0xE0)
    // ==================================================================
    @Nested
    class CustomLit {

        @Test
        void tag_and_minimum_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeCustomLiteral(TermId.of(42L), "", a);
                assertEquals(9, s.byteSize()); // tag + 8B datatype-id + 0 lex bytes
                assertEquals(TermCodec.TAG_CUSTOM_LITERAL, TermCodec.tagOf(s));
            }
        }

        @Test
        void simple_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermId datatype = TermId.of(0xCAFE_BABEL);
                TermCodec.CustomLiteral rt =
                        TermCodec.decodeCustomLiteral(
                                TermCodec.payloadOf(
                                        TermCodec.encodeCustomLiteral(datatype, "value", a)));
                assertEquals(datatype, rt.datatypeIri());
                assertEquals("value", rt.lex());
            }
        }

        @Test
        void empty_lex_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.CustomLiteral rt =
                        TermCodec.decodeCustomLiteral(
                                TermCodec.payloadOf(
                                        TermCodec.encodeCustomLiteral(TermId.of(1L), "", a)));
                assertEquals("", rt.lex());
            }
        }

        @Test
        void unicode_lex_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermCodec.CustomLiteral rt =
                        TermCodec.decodeCustomLiteral(
                                TermCodec.payloadOf(
                                        TermCodec.encodeCustomLiteral(TermId.of(1L), "値 🎌", a)));
                assertEquals("値 🎌", rt.lex());
            }
        }

        @Test
        void extension_datatypeId_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                TermId ext = TermId.ofExtensionSlot(5L);
                TermCodec.CustomLiteral rt =
                        TermCodec.decodeCustomLiteral(
                                TermCodec.payloadOf(TermCodec.encodeCustomLiteral(ext, "x", a)));
                assertEquals(ext, rt.datatypeIri());
                assertTrue(rt.datatypeIri().isExtension());
            }
        }

        @Test
        void different_datatype_gives_distinct_encoding() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment a1 = TermCodec.encodeCustomLiteral(TermId.of(1L), "same", a);
                MemorySegment a2 = TermCodec.encodeCustomLiteral(TermId.of(2L), "same", a);
                assertTrue(Compare.compareUnsigned(a1, a2) < 0); // sort by datatype-id first
            }
        }

        @Test
        void same_datatype_lex_order_by_value_utf8() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment a1 = TermCodec.encodeCustomLiteral(TermId.of(1L), "apple", a);
                MemorySegment a2 = TermCodec.encodeCustomLiteral(TermId.of(1L), "banana", a);
                assertTrue(Compare.compareUnsigned(a1, a2) < 0);
            }
        }

        @Test
        void boundary_datatype_id_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                for (long v : new long[] {0L, 1L, Long.MIN_VALUE, Long.MAX_VALUE, -1L}) {
                    TermCodec.CustomLiteral rt =
                            TermCodec.decodeCustomLiteral(
                                    TermCodec.payloadOf(
                                            TermCodec.encodeCustomLiteral(TermId.of(v), "x", a)));
                    assertEquals(v, rt.datatypeIri().value());
                }
            }
        }

        @Test
        void large_lex_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 10_000; i++) sb.append('x');
                String lex = sb.toString();
                TermCodec.CustomLiteral rt =
                        TermCodec.decodeCustomLiteral(
                                TermCodec.payloadOf(
                                        TermCodec.encodeCustomLiteral(TermId.of(1L), lex, a)));
                assertEquals(lex, rt.lex());
            }
        }
    }
}
