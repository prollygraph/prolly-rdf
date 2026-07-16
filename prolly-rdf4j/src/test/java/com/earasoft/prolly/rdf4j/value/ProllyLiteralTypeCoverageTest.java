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

import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * Type-by-type coverage for {@link ProllyLiteral#getLabel()} and {@link
 * ProllyLiteral#getDatatype()}.
 *
 * <p>{@code ProllyLiteralTest} exercises the common tags (boolean, the int/long/float/double
 * family, xsd:string, rdf:langString). This file fills in the rest of the {@code getLabel}/{@code
 * getDatatype} {@code switch}-on-tag bodies — the numeric edge types, the date/time family, binary,
 * and UUID — plus the date/time lexical helpers ({@code pad4}/{@code yearOnly}) and the cross-type
 * typed accessors (float/double now parse the verbatim lexical label, incl. xsd's ±INF/NaN, via
 * {@code XMLDatatypeUtil}).
 */
class ProllyLiteralTypeCoverageTest {

    /** Encodes within a confined Arena — the encoder needs one for native scratch. */
    private interface Enc {
        MemorySegment encode(Arena a);
    }

    private static String label(Enc e) {
        try (Arena a = Arena.ofConfined()) {
            return new ProllyLiteral(e.encode(a)).getLabel();
        }
    }

    private static IRI datatype(Enc e) {
        try (Arena a = Arena.ofConfined()) {
            return new ProllyLiteral(e.encode(a)).getDatatype();
        }
    }

    /** Runs {@code fn} against a live ProllyLiteral while its Arena is open. */
    private static <T> T withLiteral(Enc e, Function<ProllyLiteral, T> fn) {
        try (Arena a = Arena.ofConfined()) {
            return fn.apply(new ProllyLiteral(e.encode(a)));
        }
    }

    // ---- integral edge types -------------------------------------------

    @Test
    void byte_literal_label_and_datatype() {
        assertEquals("42", label(a -> TermCodec.encodeInt8((byte) 42, a)));
        assertEquals(XSD.BYTE, datatype(a -> TermCodec.encodeInt8((byte) 42, a)));
    }

    @Test
    void short_literal_label_and_datatype() {
        assertEquals("1000", label(a -> TermCodec.encodeInt16((short) 1000, a)));
        assertEquals(XSD.SHORT, datatype(a -> TermCodec.encodeInt16((short) 1000, a)));
    }

    @Test
    void integer_literal_label_and_datatype() {
        assertEquals("123456789", label(a -> TermCodec.encodeInteger(123456789L, a)));
        assertEquals(XSD.INTEGER, datatype(a -> TermCodec.encodeInteger(123456789L, a)));
    }

    @Test
    void unsigned_int_literal_renders_as_unsigned() {
        // -1 as a signed int is 0xFFFFFFFF → the max unsigned 32-bit value.
        assertEquals("4294967295", label(a -> TermCodec.encodeUInt32(-1, a)));
        assertEquals(XSD.UNSIGNED_INT, datatype(a -> TermCodec.encodeUInt32(-1, a)));
    }

    @Test
    void unsigned_long_literal_renders_as_unsigned() {
        assertEquals("18446744073709551615", label(a -> TermCodec.encodeUInt64(-1L, a)));
        assertEquals(XSD.UNSIGNED_LONG, datatype(a -> TermCodec.encodeUInt64(-1L, a)));
    }

    @Test
    void big_integer_literal_label_and_datatype() {
        BigInteger huge = new BigInteger("123456789012345678901234567890");
        assertEquals(huge.toString(), label(a -> TermCodec.encodeInteger(huge.toString(), a)));
        assertEquals(
                XSD.INTEGER,
                datatype(a -> TermCodec.encodeInteger(huge.toString(), a)),
                "an arbitrary-precision xsd:integer reports the xsd:integer datatype (TAG_XSD_INTEGER, lexical)");
    }

    @Test
    void decimal_literal_label_and_datatype() {
        BigDecimal d = new BigDecimal("3.14");
        assertEquals("3.14", label(a -> TermCodec.encodeDecimal(d, a)));
        assertEquals(XSD.DECIMAL, datatype(a -> TermCodec.encodeDecimal(d, a)));
    }

    // ---- date / time family --------------------------------------------

    @Test
    void gyear_literal_label_and_datatype() {
        assertEquals("2026", label(a -> TermCodec.encodeGYear("2026", a)));
        assertEquals(XSD.GYEAR, datatype(a -> TermCodec.encodeGYear("2026", a)));
    }

    @Test
    void gyear_stores_verbatim_lexical_incl_leading_zeros_and_bce() {
        // Term-faithful (ADR-0043 Step 6): no padding-from-int — the lexical form IS the term.
        // These were the old pad4/negative-path outputs; now they are simply round-tripped verbatim
        // (and "0005" vs "5" would be DISTINCT terms, which the padded int encoder could not
        // express).
        assertEquals("0005", label(a -> TermCodec.encodeGYear("0005", a)));
        assertEquals("0050", label(a -> TermCodec.encodeGYear("0050", a)));
        assertEquals("0300", label(a -> TermCodec.encodeGYear("0300", a)));
        assertEquals("-0044", label(a -> TermCodec.encodeGYear("-0044", a)));
    }

    @Test
    void gyearmonth_literal_label_and_datatype() {
        assertEquals("2026-05", label(a -> TermCodec.encodeGYearMonth("2026-05", a)));
        assertEquals("2026-12", label(a -> TermCodec.encodeGYearMonth("2026-12", a)));
        assertEquals(XSD.GYEARMONTH, datatype(a -> TermCodec.encodeGYearMonth("2026-05", a)));
    }

    @Test
    void date_literal_label_and_datatype() {
        assertEquals("2026-05-15", label(a -> TermCodec.encodeDate("2026-05-15", a)));
        assertEquals(XSD.DATE, datatype(a -> TermCodec.encodeDate("2026-05-15", a)));
    }

    @Test
    void duration_literal_label_and_datatype() {
        assertEquals("P3M1000N", label(a -> TermCodec.encodeDuration(3, 1000L, a)));
        assertEquals(XSD.DURATION, datatype(a -> TermCodec.encodeDuration(3, 1000L, a)));
    }

    // ---- binary / uri / uuid -------------------------------------------

    @Test
    void any_uri_literal_label_and_datatype() {
        assertEquals(
                "http://example.org/",
                label(a -> TermCodec.encodeAnyURI("http://example.org/", a)));
        assertEquals(XSD.ANYURI, datatype(a -> TermCodec.encodeAnyURI("http://example.org/", a)));
    }

    @Test
    void base64_binary_literal_label_and_datatype() {
        // term-faithful (Step 6e): verbatim lexical — "AQID" round-trips as "AQID" (not
        // byte-decoded).
        assertEquals("AQID", label(a -> TermCodec.encodeBase64Binary("AQID", a)));
        assertEquals(XSD.BASE64BINARY, datatype(a -> TermCodec.encodeBase64Binary("AQID", a)));
    }

    @Test
    void hex_binary_literal_label_and_datatype() {
        // verbatim lexical: an UPPER-case "0AFF10" stays upper-case — the old byte encoding
        // rendered it
        // lowercase ("0aff10"), over-merging the two distinct RDF terms.
        assertEquals("0AFF10", label(a -> TermCodec.encodeHexBinary("0AFF10", a)));
        assertEquals(XSD.HEXBINARY, datatype(a -> TermCodec.encodeHexBinary("0AFF10", a)));
    }

    @Test
    void uuid_literal_label_and_datatype() {
        UUID u = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        assertEquals(u.toString(), label(a -> TermCodec.encodeUuid(u, a)));
        assertEquals(
                "http://www.w3.org/2001/XMLSchema#UUID",
                datatype(a -> TermCodec.encodeUuid(u, a)).stringValue());
    }

    // ---- xsdFloat: the non-finite lexical forms ------------------------

    @Test
    void float_infinity_and_nan_use_xsd_canonical_forms() {
        assertEquals("INF", label(a -> TermCodec.encodeFloat32(Float.POSITIVE_INFINITY, a)));
        assertEquals("-INF", label(a -> TermCodec.encodeFloat32(Float.NEGATIVE_INFINITY, a)));
        assertEquals("NaN", label(a -> TermCodec.encodeFloat32(Float.NaN, a)));
    }

    // ---- typed accessors -----------------------------------------------

    @Test
    void byte_and_short_value_parse_the_label() {
        assertEquals(
                (byte) 7,
                withLiteral(a -> TermCodec.encodeInt8((byte) 7, a), ProllyLiteral::byteValue));
        assertEquals(
                (short) 777,
                withLiteral(a -> TermCodec.encodeInt16((short) 777, a), ProllyLiteral::shortValue));
    }

    @Test
    void float_and_double_value_cross_decode_between_tags() {
        // floatValue on a double-tagged literal — parses the verbatim lexical via XMLDatatypeUtil.
        assertEquals(
                2.5f,
                withLiteral(a -> TermCodec.encodeFloat64(2.5d, a), ProllyLiteral::floatValue));
        // doubleValue on a float-tagged literal — same XSD-aware label parse.
        assertEquals(
                2.5d,
                withLiteral(a -> TermCodec.encodeFloat32(2.5f, a), ProllyLiteral::doubleValue));
        // floatValue on an integer literal — parses its lexical too (no per-tag special-casing
        // now).
        assertEquals(
                9f, withLiteral(a -> TermCodec.encodeInteger(9L, a), ProllyLiteral::floatValue));
        assertEquals(
                9d, withLiteral(a -> TermCodec.encodeInteger(9L, a), ProllyLiteral::doubleValue));
    }

    @Test
    void calendar_and_temporal_accessors_parse_a_datetime_literal() {
        OffsetDateTime odt = OffsetDateTime.parse("2026-05-15T10:30:00Z");
        assertNotNull(
                withLiteral(
                        a -> TermCodec.encodeDateTime("2026-05-15T10:30:00Z", a),
                        ProllyLiteral::calendarValue),
                "calendarValue yields an XMLGregorianCalendar");
        assertEquals(
                odt,
                withLiteral(
                        a -> TermCodec.encodeDateTime("2026-05-15T10:30:00Z", a),
                        lit -> OffsetDateTime.from(lit.temporalAccessorValue())));
    }
}
