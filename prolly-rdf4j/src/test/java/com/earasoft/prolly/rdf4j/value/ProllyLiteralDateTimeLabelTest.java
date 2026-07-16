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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.Arena;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the {@code getLabel()} XSD-canonical rendering bug (fixed 2026-05-15, caught
 * by the W3C SPARQL 1.1 conformance suite).
 *
 * <p>{@code OffsetDateTime.toString()} / {@code OffsetTime.toString()} drop the seconds field when
 * seconds <em>and</em> nanos are zero, producing {@code "2008-06-20T23:59Z"} — which is not a valid
 * {@code xsd:dateTime} lexical form. RDF4J function evaluation (YEAR(), TZ(), ...) then threw and
 * silently returned unbound. The bug only bit on the exact {@code :00}-seconds edge case, so it hid
 * behind every literal with non-zero seconds.
 */
class ProllyLiteralDateTimeLabelTest {

    private static String dateTimeLabel(String iso) {
        try (Arena a = Arena.ofConfined()) {
            // term-faithful (ADR-0043 Step 6): store the verbatim lexical; getLabel returns it
            // exactly
            return new ProllyLiteral(TermCodec.encodeDateTime(iso, a)).getLabel();
        }
    }

    private static String timeLabel(String iso) {
        try (Arena a = Arena.ofConfined()) {
            // term-faithful (ADR-0043 Step 6): store the verbatim lexical; getLabel returns it
            // exactly
            // (the old value→XSD_TIME-formatter path that this regression test guarded is now
            // subsumed).
            return new ProllyLiteral(TermCodec.encodeTime(iso, a)).getLabel();
        }
    }

    @Test
    void dateTime_zeroSeconds_keepsSecondsField() {
        // The exact case that broke the W3C YEAR()/MONTH()/DAY() tests.
        assertEquals("2008-06-20T23:59:00Z", dateTimeLabel("2008-06-20T23:59:00Z"));
    }

    @Test
    void dateTime_nonZeroSeconds_roundTrips() {
        assertEquals("2010-06-21T11:28:01Z", dateTimeLabel("2010-06-21T11:28:01Z"));
    }

    @Test
    void dateTime_nonUtcOffset_roundTrips() {
        assertEquals("2010-12-21T15:38:02-08:00", dateTimeLabel("2010-12-21T15:38:02-08:00"));
    }

    @Test
    void dateTime_fractionalSeconds_preserved_verbatim() {
        // term-faithful (ADR-0043 Step 6): trailing zeros are KEPT — "…01.500Z" stays "…01.500Z"
        // (the old value→formatter path trimmed them to ".5"; verbatim storage preserves the term).
        assertEquals("2010-06-21T11:28:01.500Z", dateTimeLabel("2010-06-21T11:28:01.500Z"));
    }

    @Test
    void dateTime_label_isParseableAsXsdDateTime() {
        // The whole point: the label must round-trip through a strict parser.
        assertDoesNotThrow(() -> OffsetDateTime.parse(dateTimeLabel("2008-06-20T23:59:00Z")));
    }

    @Test
    void time_zeroSeconds_keepsSecondsField() {
        assertEquals("23:59:00Z", timeLabel("23:59:00Z"));
    }

    @Test
    void time_nonZeroSeconds_roundTrips() {
        assertEquals("11:28:01-08:00", timeLabel("11:28:01-08:00"));
    }
}
