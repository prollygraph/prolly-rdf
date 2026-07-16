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
import java.math.BigDecimal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TermCodecBigNumericsTest {

    // ==================================================================
    // xsd:decimal
    // ==================================================================
    @Nested
    class Decimal {

        @Test
        void tag_correct() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeDecimal("3.14", a);
                assertEquals(TermCodec.TAG_XSD_DECIMAL, TermCodec.tagOf(s));
            }
        }

        @Test
        void verbatim_lexical_round_trips() {
            // term-faithful (ADR-0043 Step 6d): trailing-zero scale AND the forms the value
            // (BigDecimal)
            // encoding folded — leading zero, explicit '+', bare dot — all round-trip EXACTLY.
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {
                            "3.14",
                            "-3.14",
                            "0",
                            "1.00",
                            "01.0",
                            ".5",
                            "+1.0",
                            "-.5",
                            "123456789012345.6789",
                            "0.00000001"
                        }) {
                    MemorySegment s = TermCodec.encodeDecimal(lex, a);
                    assertEquals(TermCodec.TAG_XSD_DECIMAL, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void non_canonical_forms_are_distinct_terms() {
            // The PARTIAL over-merge fixed: the value (BigDecimal) encoding folded these
            // value-equal but
            // lexically-distinct forms onto one encoding
            // (BigDecimal("01.0").equals(BigDecimal("1.0")));
            // lexical storage keeps them distinct RDF terms.
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeDecimal("1.0", a),
                                        TermCodec.encodeDecimal("01.0", a))
                                != 0,
                        "\"1.0\" vs \"01.0\" — distinct terms (leading zero)");
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeDecimal("0.5", a),
                                        TermCodec.encodeDecimal(".5", a))
                                != 0,
                        "\"0.5\" vs \".5\" — distinct terms (bare dot)");
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeDecimal("1.0", a),
                                        TermCodec.encodeDecimal("+1.0", a))
                                != 0,
                        "\"1.0\" vs \"+1.0\" — distinct terms (explicit plus)");
            }
        }

        @Test
        void trailing_zero_scale_still_distinct() {
            // The value encoding ALREADY kept these distinct (via BigDecimal scale); lexical does
            // too.
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeDecimal("1.0", a),
                                        TermCodec.encodeDecimal("1.00", a))
                                != 0,
                        "\"1.0\" vs \"1.00\" — distinct terms (trailing-zero scale)");
            }
        }

        @Test
        void bigdecimal_convenience_overload_uses_canonical_toString() {
            // The BigDecimal overload encodes v.toString() — so the stored lexical IS BigDecimal's
            // canonical
            // form (scale preserved), sparing callers that hold a BigDecimal value.
            try (Arena a = Arena.ofConfined()) {
                BigDecimal v = new BigDecimal("1.00");
                assertEquals(
                        v.toString(),
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(TermCodec.encodeDecimal(v, a))));
            }
        }
    }

    // The xsd:integer-big value encoding (TAG_XSD_INTEGER_BIG) was removed when xsd:integer went
    // term-faithful (ADR-0043, plans/literal-lexical-fidelity.md Step 4a): a big integer now stores
    // its verbatim lexical form under TAG_XSD_INTEGER like any other. The arbitrary-precision
    // round-trip is pinned by
    // TermCodecNumericsTest.Int64.arbitrary_precision_round_trips_under_one_tag.
}
