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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TermCodecNumericsTest {

    // ==================================================================
    // Boolean
    // ==================================================================
    @Nested
    class BooleanT {
        @Test
        void tag_and_lexical_payload_correct() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment t = TermCodec.encodeBoolean("true", a);
                MemorySegment f = TermCodec.encodeBoolean("false", a);
                // term-faithful (ADR-0043): tag byte + verbatim UTF-8 lexical form, not a 0/1 value
                assertEquals(1 + 4, t.byteSize()); // [0x10]"true"
                assertEquals(1 + 5, f.byteSize()); // [0x10]"false"
                assertEquals(TermCodec.TAG_BOOLEAN, TermCodec.tagOf(t));
                assertEquals(TermCodec.TAG_BOOLEAN, TermCodec.tagOf(f));
                assertEquals("true", TermCodec.decodeLexical(TermCodec.payloadOf(t)));
                assertEquals("false", TermCodec.decodeLexical(TermCodec.payloadOf(f)));
            }
        }

        @Test
        void roundTrip_preserves_verbatim_lexical() {
            try (Arena a = Arena.ofConfined()) {
                // every xsd:boolean lexical form round-trips EXACTLY — "1" is NOT folded to "true"
                for (String lex : new String[] {"true", "false", "1", "0"}) {
                    assertEquals(
                            lex,
                            TermCodec.decodeLexical(
                                    TermCodec.payloadOf(TermCodec.encodeBoolean(lex, a))));
                }
            }
        }

        @Test
        void distinct_lexical_forms_get_distinct_bytes() {
            // LEXFID-1: "true" and "1" are distinct RDF terms → must get distinct content addresses
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeBoolean("true", a),
                                        TermCodec.encodeBoolean("1", a))
                                != 0);
            }
        }

        @Test
        void lex_false_lessThan_true() {
            try (Arena a = Arena.ofConfined()) {
                int cmp =
                        Compare.compareUnsigned(
                                TermCodec.encodeBoolean("false", a),
                                TermCodec.encodeBoolean("true", a));
                assertTrue(cmp < 0);
            }
        }
    }

    // ==================================================================
    // xsd:byte (Int8)
    // ==================================================================
    @Nested
    class Int8 {
        @Test
        void value_overload_tag_and_canonical_lexical() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeInt8((byte) 42, a);
                assertEquals(TermCodec.TAG_XSD_BYTE, TermCodec.tagOf(s));
                assertEquals("42", TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }

        @Test
        void verbatim_lexical_round_trips() {
            // term-faithful: each xsd:byte lexical (incl. the leading-zero LEXFID-1 case)
            // round-trips
            try (Arena a = Arena.ofConfined()) {
                for (String lex : new String[] {"-128", "-1", "0", "1", "127", "042"}) {
                    MemorySegment s = TermCodec.encodeInt8(lex, a);
                    assertEquals(TermCodec.TAG_XSD_BYTE, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }
    }

    // ==================================================================
    // xsd:short (Int16)
    // ==================================================================
    @Nested
    class Int16 {
        @Test
        void value_overload_tag_and_canonical_lexical() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeInt16((short) 1234, a);
                assertEquals(TermCodec.TAG_XSD_SHORT, TermCodec.tagOf(s));
                assertEquals("1234", TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }

        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex : new String[] {"-32768", "-1", "0", "1", "32767", "01234"}) {
                    MemorySegment s = TermCodec.encodeInt16(lex, a);
                    assertEquals(TermCodec.TAG_XSD_SHORT, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }
    }

    // ==================================================================
    // xsd:int (Int32)
    // ==================================================================
    @Nested
    class Int32 {
        @Test
        void value_overload_tag_and_canonical_lexical() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeInt32(42, a);
                assertEquals(TermCodec.TAG_XSD_INT, TermCodec.tagOf(s));
                assertEquals("42", TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }

        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {"-2147483648", "-1", "0", "1", "2147483647", "042"}) {
                    MemorySegment s = TermCodec.encodeInt32(lex, a);
                    assertEquals(TermCodec.TAG_XSD_INT, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }
    }

    // ==================================================================
    // xsd:integer / xsd:long (Int64) — alias pair
    // ==================================================================
    @Nested
    class Int64 {
        @Test
        void integer_vs_long_have_distinct_tags_but_same_payload() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment integer = TermCodec.encodeInteger("42", a);
                MemorySegment longish = TermCodec.encodeLong("42", a);
                assertNotEquals(TermCodec.tagOf(integer), TermCodec.tagOf(longish));
                assertEquals(TermCodec.TAG_XSD_INTEGER, TermCodec.tagOf(integer));
                assertEquals(TermCodec.TAG_XSD_LONG, TermCodec.tagOf(longish));
                // same digits → same lexical payload; the datatype distinction lives in the tag
                assertEquals(
                        0,
                        Compare.compareUnsigned(
                                TermCodec.payloadOf(integer), TermCodec.payloadOf(longish)));
            }
        }

        @Test
        void boundary_round_trip_verbatim_lexical() {
            try (Arena a = Arena.ofConfined()) {
                // term-faithful: the EXACT lexical form round-trips, including the Long extremes
                for (String lex :
                        new String[] {
                            "-9223372036854775808", "-1", "0", "1", "9223372036854775807"
                        }) {
                    assertEquals(
                            lex,
                            TermCodec.decodeLexical(
                                    TermCodec.payloadOf(TermCodec.encodeInteger(lex, a))));
                }
            }
        }

        @Test
        void arbitrary_precision_round_trips_under_one_tag() {
            // The removed TAG_XSD_INTEGER_BIG is unnecessary under lexical storage: a 60-digit
            // integer stores under TAG_XSD_INTEGER like any other (UTF-8 carries any magnitude).
            try (Arena a = Arena.ofConfined()) {
                String huge = "123456789012345678901234567890123456789012345678901234567890";
                MemorySegment s = TermCodec.encodeInteger(huge, a);
                assertEquals(TermCodec.TAG_XSD_INTEGER, TermCodec.tagOf(s));
                assertEquals(huge, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }

        @Test
        void leading_zero_is_distinct() {
            // LEXFID-1: "1" and "01" are distinct RDF terms → must get distinct content addresses
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeInteger("1", a),
                                        TermCodec.encodeInteger("01", a))
                                != 0);
            }
        }
    }

    // ==================================================================
    // xsd:unsignedInt
    // ==================================================================
    @Nested
    class UInt32 {
        @Test
        void value_overload_tag_and_canonical_unsigned_lexical() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s =
                        TermCodec.encodeUInt32(-1, a); // raw bits 0xFFFFFFFF → unsigned max
                assertEquals(TermCodec.TAG_XSD_UINT, TermCodec.tagOf(s));
                assertEquals("4294967295", TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }

        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex : new String[] {"0", "1", "4294967295", "042"}) {
                    MemorySegment s = TermCodec.encodeUInt32(lex, a);
                    assertEquals(TermCodec.TAG_XSD_UINT, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }
    }

    // ==================================================================
    // xsd:unsignedLong
    // ==================================================================
    @Nested
    class UInt64 {
        @Test
        void value_overload_tag_and_canonical_unsigned_lexical() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeUInt64(-1L, a); // raw bits → unsigned max
                assertEquals(TermCodec.TAG_XSD_ULONG, TermCodec.tagOf(s));
                assertEquals(
                        "18446744073709551615", TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }

        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex : new String[] {"0", "1", "18446744073709551615"}) {
                    MemorySegment s = TermCodec.encodeUInt64(lex, a);
                    assertEquals(TermCodec.TAG_XSD_ULONG, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }
    }

    // ==================================================================
    // xsd:float (Float32) — IEEE-754 lex-flip
    // ==================================================================
    @Nested
    class Float32 {
        @Test
        void verbatim_lexical_round_trips() {
            // term-faithful: exponent case, signed zero, INF/-INF/NaN, leading-zero all preserved
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {
                            "1.5", "1.0E0", "1.0e0", "-0.0", "0.0", "INF", "-INF", "NaN", "042.0"
                        }) {
                    MemorySegment s = TermCodec.encodeFloat32(lex, a);
                    assertEquals(TermCodec.TAG_XSD_FLOAT, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void exponent_case_and_signed_zero_are_distinct() {
            // LEXFID-1: "1.0E0" vs "1.0e0", and "0.0" vs "-0.0", are distinct RDF terms
            // (char-by-char)
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeFloat32("1.0E0", a),
                                        TermCodec.encodeFloat32("1.0e0", a))
                                != 0);
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeFloat32("0.0", a),
                                        TermCodec.encodeFloat32("-0.0", a))
                                != 0);
            }
        }

        @Test
        void value_overload_is_canonical_xsd_lexical() {
            // the primitive overload maps Java floats to their canonical xsd:float lexical
            // (INF/-INF/NaN)
            try (Arena a = Arena.ofConfined()) {
                assertEquals(
                        "1.5",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(TermCodec.encodeFloat32(1.5f, a))));
                assertEquals(
                        "INF",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(
                                        TermCodec.encodeFloat32(Float.POSITIVE_INFINITY, a))));
                assertEquals(
                        "-INF",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(
                                        TermCodec.encodeFloat32(Float.NEGATIVE_INFINITY, a))));
                assertEquals(
                        "NaN",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(TermCodec.encodeFloat32(Float.NaN, a))));
            }
        }
    }

    // ==================================================================
    // xsd:double (Float64) — IEEE-754 lex-flip
    // ==================================================================
    @Nested
    class Float64 {
        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {
                            "3.14", "1.0E0", "1.0e0", "-0.0", "0.0", "INF", "-INF", "NaN", "042.0"
                        }) {
                    MemorySegment s = TermCodec.encodeFloat64(lex, a);
                    assertEquals(TermCodec.TAG_XSD_DOUBLE, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void exponent_case_and_signed_zero_are_distinct() {
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeFloat64("1.0E0", a),
                                        TermCodec.encodeFloat64("1.0e0", a))
                                != 0);
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeFloat64("0.0", a),
                                        TermCodec.encodeFloat64("-0.0", a))
                                != 0);
            }
        }

        @Test
        void value_overload_is_canonical_xsd_lexical() {
            try (Arena a = Arena.ofConfined()) {
                assertEquals(
                        "1.5",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(TermCodec.encodeFloat64(1.5, a))));
                assertEquals(
                        "INF",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(
                                        TermCodec.encodeFloat64(Double.POSITIVE_INFINITY, a))));
                assertEquals(
                        "-INF",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(
                                        TermCodec.encodeFloat64(Double.NEGATIVE_INFINITY, a))));
                assertEquals(
                        "NaN",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(TermCodec.encodeFloat64(Double.NaN, a))));
            }
        }
    }

    // ==================================================================
    // Cross-tag interleaving — does NOT lex-sort by semantic value
    // (the tag byte is part of the encoded form, so different tags are
    // partitioned in index order; this is intentional and document it)
    // ==================================================================
    @Nested
    class CrossTagPartitioning {
        @Test
        void differentTags_partition_byTagByte_not_byValue() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment maxByte = TermCodec.encodeInt8(Byte.MAX_VALUE, a); // tag 0x11
                MemorySegment minShort = TermCodec.encodeInt16(Short.MIN_VALUE, a); // tag 0x12
                // tag 0x11 < tag 0x12, so Int8(MAX) < Int16(MIN) lex-wise, even though
                // semantically 127 > -32768.
                assertTrue(Compare.compareUnsigned(maxByte, minShort) < 0);
            }
        }

        @Test
        void allTagsAreInExpectedNibbleBand() {
            assertEquals(0x10, TermCodec.TAG_BOOLEAN & 0xFF);
            assertEquals(0x11, TermCodec.TAG_XSD_BYTE & 0xFF);
            assertEquals(0x12, TermCodec.TAG_XSD_SHORT & 0xFF);
            assertEquals(0x13, TermCodec.TAG_XSD_INT & 0xFF);
            assertEquals(0x14, TermCodec.TAG_XSD_INTEGER & 0xFF);
            assertEquals(0x15, TermCodec.TAG_XSD_LONG & 0xFF);
            assertEquals(0x16, TermCodec.TAG_XSD_UINT & 0xFF);
            assertEquals(0x17, TermCodec.TAG_XSD_ULONG & 0xFF);
            assertEquals(0x18, TermCodec.TAG_XSD_FLOAT & 0xFF);
            assertEquals(0x19, TermCodec.TAG_XSD_DOUBLE & 0xFF);
        }
    }

    // ==================================================================
    // Helpers — payloadOf / tagOf
    // ==================================================================
    @Nested
    class Helpers {
        @Test
        void payloadOf_excludesTag() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeInteger(42L, a);
                MemorySegment p = TermCodec.payloadOf(s);
                assertEquals(s.byteSize() - 1, p.byteSize());
            }
        }

        @Test
        void tagOf_returnsFirstByte() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeFloat64(3.14, a);
                assertEquals(TermCodec.TAG_XSD_DOUBLE, TermCodec.tagOf(s));
            }
        }
    }
}
