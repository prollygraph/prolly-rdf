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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * {@link TermEncoder} handling of timezoned {@code xsd:date} / {@code xsd:gYear} / {@code
 * xsd:gYearMonth} — <b>flipped invariant</b> (ADR-0043 Step 6, calendar types).
 *
 * <p><b>What this used to assert, and why it is now the opposite.</b> The old fixed-width date
 * encoders carried no timezone field, so a timezoned value (XSD permits a trailing {@code 'Z'} or
 * {@code '±HH:MM'}) could not be represented — this suite pinned that such a value was
 * <em>rejected</em> with a clear "timezoned … xsd:date" message. Term-faithful storage removed the
 * fixed-width encoding entirely: a calendar literal is now stored as its <em>verbatim lexical
 * bytes</em>, so a timezone is just more text and round-trips exactly. The rejection is gone, and
 * these tests now pin the round-trip that replaced it. (This is the retraction-in-place the
 * calibrated-honesty convention asks for — the refuted claim is rewritten where it lived, not
 * silently deleted.)
 *
 * <p>The companion {@code TermCodecDateTest} pins the same round-trip at the codec layer; this
 * suite pins it one level up, at the {@link TermEncoder} dispatch from an RDF4J {@link
 * org.eclipse.rdf4j.model.Literal} — i.e. that the dispatcher passes the label through unchanged
 * for the timezoned forms too.
 */
class TermEncoderTimezonedDateTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static String roundTrip(String lexical, org.eclipse.rdf4j.model.IRI dt, Arena a) {
        return TermCodec.decodeLexical(
                TermCodec.payloadOf(TermEncoder.encode(VF.createLiteral(lexical, dt), a)));
    }

    @Test
    void plain_date_gyear_gyearmonth_round_trip_verbatim() {
        try (Arena a = Arena.ofConfined()) {
            assertEquals("2026-05-15", roundTrip("2026-05-15", XSD.DATE, a));
            assertEquals("2026", roundTrip("2026", XSD.GYEAR, a));
            assertEquals(
                    "-0044",
                    roundTrip("-0044", XSD.GYEAR, a),
                    "a BCE year (leading '-') round-trips exactly — it is not a timezone");
            assertEquals("2026-05", roundTrip("2026-05", XSD.GYEARMONTH, a));
        }
    }

    @Test
    void timezoned_date_now_round_trips_verbatim() {
        // Previously rejected; term-faithful storage keeps the timezone as verbatim text.
        try (Arena a = Arena.ofConfined()) {
            for (String tz : new String[] {"2026-05-15Z", "2026-05-15+05:30", "2026-05-15-08:00"}) {
                assertEquals(
                        tz,
                        roundTrip(tz, XSD.DATE, a),
                        "a timezoned xsd:date must round-trip exactly (the rejection is gone): "
                                + tz);
            }
        }
    }

    @Test
    void timezoned_gyear_and_gyearmonth_now_round_trip_verbatim() {
        try (Arena a = Arena.ofConfined()) {
            assertEquals("2026Z", roundTrip("2026Z", XSD.GYEAR, a));
            assertEquals("2026-05+01:00", roundTrip("2026-05+01:00", XSD.GYEARMONTH, a));
        }
    }

    @Test
    void z_and_no_z_are_distinct_terms() {
        // RDF 1.1 §3.3: same value, distinct terms (char-by-char on the lexical form). A content-
        // addressed store MUST keep them distinct, or one logical literal collapses into another.
        try (Arena a = Arena.ofConfined()) {
            assertTrue(
                    Compare.compareUnsigned(
                                    TermEncoder.encode(
                                            VF.createLiteral("2026-05-15Z", XSD.DATE), a),
                                    TermEncoder.encode(VF.createLiteral("2026-05-15", XSD.DATE), a))
                            != 0,
                    "\"2026-05-15Z\" and \"2026-05-15\" are distinct xsd:date terms → distinct bytes");
        }
    }
}
