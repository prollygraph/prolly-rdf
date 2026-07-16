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

class TermCodecTemporalTest {

    // ==================================================================
    // xsd:dateTime (0x20)
    // ==================================================================
    @Nested
    class DateTime {

        @Test
        void verbatim_lexical_round_trips() {
            // term-faithful: timezone forms, fractional seconds, AND tz-absent all round-trip
            // EXACTLY
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {
                            "2026-05-12T17:30:00Z",
                            "2026-05-12T17:30:00+00:00",
                            "2026-05-12T12:00:00+05:30",
                            "2026-05-12T12:00:00-08:00",
                            "2026-05-12T17:30:42.123456789Z",
                            "1969-12-31T23:59:59Z",
                            "2026-05-12T17:30:00"
                        }) { // tz-absent — the old value encoding coerced this to "…Z"
                    MemorySegment s = TermCodec.encodeDateTime(lex, a);
                    assertEquals(TermCodec.TAG_XSD_DATETIME, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void timezone_spelling_is_distinct() {
            // LEXFID: "…Z" and "…+00:00" denote the same instant but are distinct RDF terms
            // (char-by-char)
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeDateTime("2026-01-01T12:00:00Z", a),
                                        TermCodec.encodeDateTime("2026-01-01T12:00:00+00:00", a))
                                != 0);
            }
        }

        @Test
        void far_future_no_longer_overflows() {
            // The old Int48 epoch-ms cap (~±4458 years) is gone — a year-999999999 dateTime stores
            // its lexical form like any other (no overflow), since nothing is parsed to an epoch.
            try (Arena a = Arena.ofConfined()) {
                String farFuture = "999999999-12-31T23:59:59Z";
                MemorySegment s = TermCodec.encodeDateTime(farFuture, a);
                assertEquals(farFuture, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
            }
        }
    }

    // ==================================================================
    // xsd:time (0x22)
    // ==================================================================
    @Nested
    class Time {

        @Test
        void verbatim_lexical_round_trips() {
            // term-faithful: tz variants, fractional seconds, tz-absent, AND the XSD end-of-day
            // "24:00:00" (which java.time's LocalTime.parse rejected) all round-trip EXACTLY.
            try (Arena a = Arena.ofConfined()) {
                for (String lex :
                        new String[] {
                            "00:00:00Z",
                            "12:00:00+05:30",
                            "12:00:00-08:00",
                            "23:59:59.999999999Z",
                            "12:00:00", // tz-absent — the old value encoding coerced this to UTC
                            "24:00:00", // XSD end-of-day — LocalTime.parse rejects it; lexical
                            // stores it
                            "11:28:01.500Z"
                        }) { // trailing-zero fraction — the old value→formatter path trimmed to
                    // ".5"
                    MemorySegment s = TermCodec.encodeTime(lex, a);
                    assertEquals(TermCodec.TAG_XSD_TIME, TermCodec.tagOf(s));
                    assertEquals(lex, TermCodec.decodeLexical(TermCodec.payloadOf(s)));
                }
            }
        }

        @Test
        void timezone_spelling_is_distinct() {
            // LEXFID: "…Z" and "…+00:00" denote the same time-of-day but are distinct RDF terms
            // (char-by-char)
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeTime("12:00:00Z", a),
                                        TermCodec.encodeTime("12:00:00+00:00", a))
                                != 0);
            }
        }

        @Test
        void tz_absent_and_z_are_distinct() {
            // "12:00:00" (no tz) and "12:00:00Z" (UTC) are distinct terms — the old value encoding
            // coerced the tz-less form to UTC, collapsing the two.
            try (Arena a = Arena.ofConfined()) {
                assertTrue(
                        Compare.compareUnsigned(
                                        TermCodec.encodeTime("12:00:00", a),
                                        TermCodec.encodeTime("12:00:00Z", a))
                                != 0);
            }
        }
    }

    // ==================================================================
    // xsd:duration (0x25)
    // ==================================================================
    @Nested
    class Duration {

        @Test
        void tag_and_size() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeDuration(12, 3600L * 1_000_000_000L, a);
                assertEquals(13, s.byteSize());
                assertEquals(TermCodec.TAG_XSD_DURATION, TermCodec.tagOf(s));
            }
        }

        @Test
        void zero_duration_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment s = TermCodec.encodeDuration(0, 0L, a);
                var rt = TermCodec.decodeDuration(TermCodec.payloadOf(s));
                assertEquals(0, rt.months());
                assertEquals(0L, rt.nanos());
            }
        }

        @Test
        void positive_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                int months = 13; // 1 year, 1 month
                long nanos = 86400L * 1_000_000_000L; // 1 day in ns
                var rt =
                        TermCodec.decodeDuration(
                                TermCodec.payloadOf(TermCodec.encodeDuration(months, nanos, a)));
                assertEquals(months, rt.months());
                assertEquals(nanos, rt.nanos());
            }
        }

        @Test
        void negative_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                int months = -12;
                long nanos = -3600L * 1_000_000_000L;
                var rt =
                        TermCodec.decodeDuration(
                                TermCodec.payloadOf(TermCodec.encodeDuration(months, nanos, a)));
                assertEquals(months, rt.months());
                assertEquals(nanos, rt.nanos());
            }
        }

        @Test
        void boundary_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                int[] months = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
                long[] nanos = {Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE};
                for (int m : months) {
                    for (long n : nanos) {
                        var rt =
                                TermCodec.decodeDuration(
                                        TermCodec.payloadOf(TermCodec.encodeDuration(m, n, a)));
                        assertEquals(m, rt.months());
                        assertEquals(n, rt.nanos());
                    }
                }
            }
        }

        @Test
        void lex_order_by_months_first() {
            try (Arena a = Arena.ofConfined()) {
                // (months=1, nanos=0) > (months=0, nanos=Long.MAX_VALUE)
                // because months column is the prefix
                MemorySegment shortYear = TermCodec.encodeDuration(0, Long.MAX_VALUE, a);
                MemorySegment longYear = TermCodec.encodeDuration(1, 0, a);
                assertTrue(Compare.compareUnsigned(shortYear, longYear) < 0);
            }
        }

        @Test
        void lex_order_same_months_then_nanos() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment d1 = TermCodec.encodeDuration(3, 100L, a);
                MemorySegment d2 = TermCodec.encodeDuration(3, 200L, a);
                assertTrue(Compare.compareUnsigned(d1, d2) < 0);
            }
        }

        @Test
        void negative_months_lex_before_positive() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment neg = TermCodec.encodeDuration(-1, 0L, a);
                MemorySegment zero = TermCodec.encodeDuration(0, 0L, a);
                MemorySegment pos = TermCodec.encodeDuration(1, 0L, a);
                assertTrue(Compare.compareUnsigned(neg, zero) < 0);
                assertTrue(Compare.compareUnsigned(zero, pos) < 0);
            }
        }
    }

    // (Removed: int48_signFlip_round_trips_boundary_values — the lex-flipped Int48 epoch-ms helpers
    //  were deleted with the value xsd:dateTime encoding; dateTime is verbatim lexical now,
    // ADR-0043
    //  Step 6. Far-future/no-overflow is pinned by DateTime.far_future_no_longer_overflows.)
}
