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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Term-faithful (ADR-0043 Step 6, calendar types) coverage for {@code xsd:gYear} / {@code
 * xsd:gYearMonth} / {@code xsd:date}. They now store the <b>verbatim lexical form</b> — the old
 * sign-flipped Int16-year value encoding (capped at ±32767, with month/day range checks) is gone.
 * So any year (BCE {@code "-…"}, 5+-digit), the exact lexical (leading zeros, optional timezone on
 * a date), and value-equal-but- lexically-distinct forms all round-trip / stay distinct. (Index
 * order is lexical, non-load-bearing — SPARQL ORDER BY/FILTER on temporals compute the value above
 * the Sail.)
 */
class TermCodecDateTest {

    @Nested
    class GYear {
        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex : new String[] {"2026", "0001", "-100", "0", "-0044", "99999"}) {
                    MemorySegment s = TermCodec.encodeGYear(lex, a);
                    assertEquals(TermCodec.TAG_XSD_GYEAR, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void leading_zero_year_is_distinct() {
            // LEXFID: "2026" and "02026" are the same value but distinct RDF terms (char-by-char)
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeGYear("2026", a),
                                        TermCodec.encodeGYear("02026", a))
                                != 0);
            }
        }

        @Test
        void five_digit_year_stores_no_range_cap() {
            // The old Int16 year cap (±32767) rejected this; lexical storage accepts any
            // well-formed year.
            try (Arena a = Arena.ofConfined()) {
                assertEquals(
                        "99999",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(TermCodec.encodeGYear("99999", a))));
            }
        }
    }

    @Nested
    class GYearMonth {
        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex : new String[] {"2026-05", "0001-12", "-0044-03", "99999-01"}) {
                    MemorySegment s = TermCodec.encodeGYearMonth(lex, a);
                    assertEquals(TermCodec.TAG_XSD_GYEARMONTH, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }
    }

    @Nested
    class Date {
        @Test
        void verbatim_lexical_round_trips() {
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {
                            "2026-05-12",
                            "0001-01-01",
                            "-3000-01-01",
                            "0000-12-31",
                            "2000-02-29",
                            "2026-05-12Z",
                            "99999-12-31"
                        }) { // incl. a timezoned date (now storable)
                    MemorySegment s = TermCodec.encodeDate(lex, a);
                    assertEquals(TermCodec.TAG_XSD_DATE, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void leading_zero_date_is_distinct() {
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeDate("2026-05-12", a),
                                        TermCodec.encodeDate("02026-05-12", a))
                                != 0);
            }
        }
    }
}
