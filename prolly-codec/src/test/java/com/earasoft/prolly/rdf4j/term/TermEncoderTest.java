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
import java.lang.foreign.ValueLayout;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link TermEncoder}. The dispatcher routes every {@code Value} →
 * byte-encoded segment — a missed datatype mapping means the wrong tag goes on disk, and the
 * decoder will either fail or silently return the wrong type.
 */
class TermEncoderTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private static byte tagOf(MemorySegment seg) {
        return seg.get(ValueLayout.JAVA_BYTE, 0);
    }

    private static byte[] bytes(MemorySegment seg) {
        return seg.toArray(ValueLayout.JAVA_BYTE);
    }

    // ---- XSD object-type dispatch (mutation hardening) ----
    // The tag-only checks above verify the literal routed to the right TermCodec, but not
    // the VALUE. These assert TermEncoder.encode(literal) is byte-identical to the direct
    // TermCodec.encodeX(value) call — so a flipped parse, a wrong nibble, or a return-null
    // dispatch arm is caught, not just a wrong tag.

    @Test
    void boolean_lexical_forms_are_stored_verbatim_and_distinct() {
        try (Arena a = Arena.ofConfined()) {
            // The dispatch stores the verbatim lexical form (term-faithful, ADR-0043) — each of the
            // four xsd:boolean lexicals encodes to its own bytes, byte-identical to
            // encodeBoolean(lex).
            for (String lex : new String[] {"true", "false", "1", "0"}) {
                assertArrayEquals(
                        bytes(TermCodec.encodeBoolean(lex, a)),
                        bytes(TermEncoder.encode(VF.createLiteral(lex, XSD.BOOLEAN), a)),
                        "xsd:boolean \"" + lex + "\" must encode to its verbatim lexical form");
            }
            // LEXFID-1 fixed: "1" and "true" are DISTINCT RDF terms → distinct bytes (was
            // over-merged).
            assertFalse(
                    java.util.Arrays.equals(
                            bytes(TermEncoder.encode(VF.createLiteral("true", XSD.BOOLEAN), a)),
                            bytes(TermEncoder.encode(VF.createLiteral("1", XSD.BOOLEAN), a))),
                    "\"true\" and \"1\" xsd:boolean must not over-merge");
        }
    }

    @Test
    void gYearMonth_dispatches_verbatim_lexical() {
        // Term-faithful (ADR-0043 Step 6): the dispatcher passes the label through to the lexical
        // encoder unchanged — no year/month parse. So the dispatched bytes equal the codec's
        // verbatim encoding of the same lexical string.
        try (Arena a = Arena.ofConfined()) {
            assertArrayEquals(
                    bytes(TermCodec.encodeGYearMonth("2024-06", a)),
                    bytes(TermEncoder.encode(VF.createLiteral("2024-06", XSD.GYEARMONTH), a)));
        }
    }

    @Test
    void hexBinary_dispatches_verbatim_lexical() {
        // term-faithful (Step 6e): the dispatcher passes the label through unchanged — "0AB0" stays
        // "0AB0" (not hex-decoded then re-rendered lowercase). So dispatch == the codec's verbatim
        // encoding.
        try (Arena a = Arena.ofConfined()) {
            assertArrayEquals(
                    bytes(TermCodec.encodeHexBinary("0AB0", a)),
                    bytes(TermEncoder.encode(VF.createLiteral("0AB0", XSD.HEXBINARY), a)));
        }
    }

    // NOTE: the encodeLiteral `dt.equals(RDF.LANGSTRING)` arm (no language tag) is a
    // justified-equivalent / unreachable mutation: every real rdf:langString literal carries
    // a language and so takes the language-present path; RDF4J's SimpleValueFactory throws
    // "datatype rdf:langString requires a language tag" for a no-language langString, so that
    // dispatch arm is defensive dead code no standard Value can reach.

    @Test
    void language_tag_case_is_canonicalized_so_equal_literals_encode_identically() {
        // RDF 1.1 §3.3: the value space of language tags is always lower-case, and lexical
        // representations MAY be lowercased — so "hello"@en-US and "hello"@en-us are the same
        // value, and RDF4J's Literal.equals treats them as the same term (asserted below). In a
        // content-addressed store they MUST encode to identical bytes, or one logical literal
        // gets two TermIds → broken dedup and a different root hash for logically identical
        // graphs. encodeLangString lowercases the tag to guarantee this.
        Literal upper = VF.createLiteral("hello", "en-US");
        Literal lower = VF.createLiteral("hello", "en-us");
        assertEquals(upper, lower, "RDF4J itself treats these as the same literal term");
        try (Arena a = Arena.ofConfined()) {
            assertArrayEquals(
                    bytes(TermEncoder.encode(lower, a)),
                    bytes(TermEncoder.encode(upper, a)),
                    "case-variant language tags are one RDF literal → must encode to identical bytes");
            // And the canonical bytes are the lowercased tag (not the as-given upper-case form).
            assertArrayEquals(
                    bytes(TermCodec.encodeLangString("hello", "en-us", a)),
                    bytes(TermEncoder.encode(upper, a)),
                    "the canonical encoding lowercases the region subtag: en-US → en-us");
        }
    }

    @Test
    void ill_typed_time_stores_verbatim_not_rejected() {
        // xsd:time was the LAST value-encoded temporal; with it term-faithful (ADR-0043 Step 6) NO
        // temporal validates at encode time. An ill-typed "not-a-time"^^xsd:time is a faithful RDF
        // term (the ill-typed-literals corollary) — it stores verbatim under TAG_XSD_TIME instead
        // of
        // throwing. This retires the old unencodableTemporal return-null mutation-killer: the
        // method
        // is gone (no caller left once time stopped parsing).
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("not-a-time", XSD.TIME), a);
            assertEquals(TermCodec.TAG_XSD_TIME, tagOf(enc));
            assertEquals("not-a-time", TermCodec.decodeLexical(TermCodec.payloadOf(enc)));
        }
    }

    // ---- IRI ----

    @Test
    void iri_encodes_with_full_iri_tag() {
        try (Arena a = Arena.ofConfined()) {
            IRI iri = VF.createIRI("http://example.org/x");
            MemorySegment enc = TermEncoder.encode(iri, a);
            assertEquals(TermCodec.TAG_IRI_FULL, tagOf(enc));
        }
    }

    // ---- BNode ----

    @Test
    void bnode_encodes_with_label_tag() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createBNode("b1"), a);
            assertEquals(TermCodec.TAG_BNODE_LABEL, tagOf(enc));
        }
    }

    // ---- Literal: string ----

    @Test
    void xsd_string_literal_encodes_with_string_tag() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("hello"), a);
            assertEquals(TermCodec.TAG_XSD_STRING, tagOf(enc));
        }
    }

    @Test
    void xsd_anyURI_literal() {
        try (Arena a = Arena.ofConfined()) {
            Literal lit = VF.createLiteral("http://x", XSD.ANYURI);
            MemorySegment enc = TermEncoder.encode(lit, a);
            assertEquals(TermCodec.TAG_XSD_ANYURI, tagOf(enc));
        }
    }

    // ---- Literal: boolean ----

    @Test
    void boolean_true_string_form() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("true", XSD.BOOLEAN), a);
            assertEquals(TermCodec.TAG_BOOLEAN, tagOf(enc));
        }
    }

    @Test
    void boolean_one_string_form_also_accepted() {
        try (Arena a = Arena.ofConfined()) {
            // xsd:boolean accepts "1"/"0" as well as "true"/"false".
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("1", XSD.BOOLEAN), a);
            assertEquals(TermCodec.TAG_BOOLEAN, tagOf(enc));
        }
    }

    @Test
    void ill_typed_boolean_lex_stored_verbatim() {
        // Term-faithful (ADR-0043): an ill-typed boolean lexical ("maybe") is still a valid RDF
        // *term* — RDF4J creates it, and RDF 1.1 §3.3 gives a literal identity by (lexical form,
        // datatype) regardless of value-space validity. The value encoder couldn't represent it
        // (no 0/1) and threw; the lexical encoder stores it verbatim, so the term round-trips
        // instead of being lost (refusing it would be a representability hole, cf. DTYPE-2).
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("maybe", XSD.BOOLEAN), a);
            assertEquals(TermCodec.TAG_BOOLEAN, tagOf(enc));
            assertEquals("maybe", TermCodec.decodeLexical(TermCodec.payloadOf(enc)));
        }
    }

    // ---- Literal: numeric ----

    @Test
    void xsd_byte_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("-42", XSD.BYTE), a);
            assertEquals(TermCodec.TAG_XSD_BYTE, tagOf(enc));
        }
    }

    @Test
    void xsd_short_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("12345", XSD.SHORT), a);
            assertEquals(TermCodec.TAG_XSD_SHORT, tagOf(enc));
        }
    }

    @Test
    void xsd_int_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("-7", XSD.INT), a);
            assertEquals(TermCodec.TAG_XSD_INT, tagOf(enc));
        }
    }

    @Test
    void xsd_long_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermEncoder.encode(VF.createLiteral("123456789012345", XSD.LONG), a);
            assertEquals(TermCodec.TAG_XSD_LONG, tagOf(enc));
        }
    }

    @Test
    void xsd_unsigned_int_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermEncoder.encode(VF.createLiteral("4000000000", XSD.UNSIGNED_INT), a);
            assertEquals(TermCodec.TAG_XSD_UINT, tagOf(enc));
        }
    }

    @Test
    void xsd_unsigned_long_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermEncoder.encode(
                            VF.createLiteral("18000000000000000000", XSD.UNSIGNED_LONG), a);
            assertEquals(TermCodec.TAG_XSD_ULONG, tagOf(enc));
        }
    }

    @Test
    void xsd_float_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("2.5", XSD.FLOAT), a);
            assertEquals(TermCodec.TAG_XSD_FLOAT, tagOf(enc));
        }
    }

    @Test
    void xsd_double_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("3.14159", XSD.DOUBLE), a);
            assertEquals(TermCodec.TAG_XSD_DOUBLE, tagOf(enc));
        }
    }

    @Test
    void xsd_decimal_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("123.456", XSD.DECIMAL), a);
            assertEquals(TermCodec.TAG_XSD_DECIMAL, tagOf(enc));
        }
    }

    // ---- xsd:integer dual encoding ----

    @Test
    void xsd_integer_any_magnitude_uses_the_integer_tag() {
        try (Arena a = Arena.ofConfined()) {
            // Term-faithful (ADR-0043): xsd:integer of ANY magnitude stores its verbatim lexical
            // form under TAG_XSD_INTEGER — no long-vs-big split (TAG_XSD_INTEGER_BIG was removed).
            assertEquals(
                    TermCodec.TAG_XSD_INTEGER,
                    tagOf(TermEncoder.encode(VF.createLiteral("42", XSD.INTEGER), a)),
                    "long-fitting xsd:integer → TAG_XSD_INTEGER");
            String big = new java.math.BigInteger("2").pow(100).toString(); // 2^100, far over Long
            assertEquals(
                    TermCodec.TAG_XSD_INTEGER,
                    tagOf(TermEncoder.encode(VF.createLiteral(big, XSD.INTEGER), a)),
                    "arbitrary-precision xsd:integer also → TAG_XSD_INTEGER (no escalation)");
        }
    }

    // ---- temporal ----

    @Test
    void xsd_date_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("2024-06-15", XSD.DATE), a);
            assertEquals(TermCodec.TAG_XSD_DATE, tagOf(enc));
        }
    }

    @Test
    void xsd_gYear_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("2024", XSD.GYEAR), a);
            assertEquals(TermCodec.TAG_XSD_GYEAR, tagOf(enc));
        }
    }

    @Test
    void xsd_dateTime_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermEncoder.encode(VF.createLiteral("2024-06-15T12:00:00Z", XSD.DATETIME), a);
            assertEquals(TermCodec.TAG_XSD_DATETIME, tagOf(enc));
        }
    }

    @Test
    void xsd_time_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("12:34:56Z", XSD.TIME), a);
            assertEquals(TermCodec.TAG_XSD_TIME, tagOf(enc));
        }
    }

    @Test
    void dedicated_datatype_set_matches_the_dispatch() {
        // The DTYPE-2 routing boundary (ADR-0043 Step 6a): isDedicatedDatatype MUST agree with what
        // encode() can actually handle. Drift either way mis-routes literals at the Sail write path
        // (DictionaryTermEncoder) — a "dedicated" datatype that throws would 500 a write; a
        // built-in
        // mistaken for custom would get the wrong tag. So: every built-in is dedicated AND encodes;
        // a custom datatype is non-dedicated AND throws (routing it through encodeCustomLiteral).
        record Case(IRI dt, String lex) {}
        Case[] dedicated = {
            new Case(XSD.STRING, "x"),
            new Case(XSD.ANYURI, "http://x"),
            new Case(XSD.BOOLEAN, "true"),
            new Case(XSD.BYTE, "1"),
            new Case(XSD.SHORT, "1"),
            new Case(XSD.INT, "1"),
            new Case(XSD.INTEGER, "1"),
            new Case(XSD.LONG, "1"),
            new Case(XSD.UNSIGNED_INT, "1"),
            new Case(XSD.UNSIGNED_LONG, "1"),
            new Case(XSD.FLOAT, "1.0"),
            new Case(XSD.DOUBLE, "1.0"),
            new Case(XSD.DECIMAL, "1.0"),
            new Case(XSD.DATE, "2026-01-01"),
            new Case(XSD.GYEAR, "2026"),
            new Case(XSD.GYEARMONTH, "2026-01"),
            new Case(XSD.DATETIME, "2026-01-01T00:00:00Z"),
            new Case(XSD.TIME, "12:00:00"),
            new Case(XSD.BASE64BINARY, "aGVsbG8="),
            new Case(XSD.HEXBINARY, "00")
        };
        try (Arena a = Arena.ofConfined()) {
            for (Case c : dedicated) {
                assertTrue(TermEncoder.isDedicatedDatatype(c.dt()), c.dt() + " must be dedicated");
                assertDoesNotThrow(
                        () -> TermEncoder.encode(VF.createLiteral(c.lex(), c.dt()), a),
                        c.dt() + " is in the dedicated set but encode() rejected it");
            }
            // rdf:langString is dedicated (language path); a no-language langString can't be
            // created,
            // so just assert the boolean (it must not be treated as a custom datatype).
            assertTrue(TermEncoder.isDedicatedDatatype(RDF.LANGSTRING));

            // A truly-custom datatype: NOT dedicated AND encode() throws (the Sail routes it
            // custom).
            IRI custom = VF.createIRI("http://example.org/myType");
            assertFalse(
                    TermEncoder.isDedicatedDatatype(custom),
                    "a custom datatype must NOT be dedicated");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> TermEncoder.encode(VF.createLiteral("x", custom), a),
                    "encode() must throw for a custom datatype (the Sail routes it via DictionaryTermEncoder)");

            // The six derived integers are a THIRD category (DTYPE-1, Step 6b): NOT dedicated — so
            // the
            // Sail routes them custom to preserve the exact subtype IRI — yet encode() does NOT
            // throw,
            // because the dispatch keeps a LOSSY Dictionary-less fallback (collapse onto
            // TAG_XSD_INTEGER).
            // So here, unlike the truly-custom case above, !isDedicatedDatatype does not imply a
            // throw.
            IRI[] derivedIntegers = {
                XSD.NON_NEGATIVE_INTEGER, XSD.POSITIVE_INTEGER, XSD.NON_POSITIVE_INTEGER,
                XSD.NEGATIVE_INTEGER, XSD.UNSIGNED_BYTE, XSD.UNSIGNED_SHORT
            };
            for (IRI dt : derivedIntegers) {
                assertFalse(
                        TermEncoder.isDedicatedDatatype(dt),
                        dt
                                + " must NOT be faithfully-dedicated (it collapses onto xsd:integer) — Sail routes it custom");
                String lex =
                        (dt == XSD.NEGATIVE_INTEGER || dt == XSD.NON_POSITIVE_INTEGER) ? "-1" : "1";
                assertEquals(
                        TermCodec.TAG_XSD_INTEGER,
                        tagOf(TermEncoder.encode(VF.createLiteral(lex, dt), a)),
                        dt
                                + " still has the lossy Dictionary-less fallback (collapse onto TAG_XSD_INTEGER)");
            }
        }
    }

    // ---- binary ----

    @Test
    void xsd_base64Binary_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermEncoder.encode(VF.createLiteral("aGVsbG8=", XSD.BASE64BINARY), a);
            assertEquals(TermCodec.TAG_XSD_BASE64BINARY, tagOf(enc));
        }
    }

    @Test
    void xsd_hexBinary_literal() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("deadbeef", XSD.HEXBINARY), a);
            assertEquals(TermCodec.TAG_XSD_HEXBINARY, tagOf(enc));
        }
    }

    @Test
    void hex_binary_ill_typed_stored_verbatim() {
        // FLIPPED (Step 6e): term-faithful storage + the ill-typed-literals corollary. An
        // odd-length or
        // invalid-char hexBinary is a valid RDF TERM — stored verbatim, NOT rejected (the old
        // hexDecode
        // threw "even-length"/"invalid hex char"; there is no decode on the write path now).
        try (Arena a = Arena.ofConfined()) {
            MemorySegment odd =
                    TermEncoder.encode(VF.createLiteral("abc", XSD.HEXBINARY), a); // odd length
            assertEquals(TermCodec.TAG_XSD_HEXBINARY, tagOf(odd));
            assertEquals("abc", TermCodec.decodeLexical(TermCodec.payloadOf(odd)));
            MemorySegment bad =
                    TermEncoder.encode(
                            VF.createLiteral("zZ", XSD.HEXBINARY), a); // invalid hex char
            assertEquals("zZ", TermCodec.decodeLexical(TermCodec.payloadOf(bad)));
        }
    }

    // ---- langString ----

    @Test
    void langString_literal_uses_langstring_tag() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("hello", "en"), a);
            assertEquals(TermCodec.TAG_RDF_LANGSTRING, tagOf(enc));
        }
    }

    @Test
    void langString_explicit_datatype_also_routes_via_lang_path() {
        try (Arena a = Arena.ofConfined()) {
            // Language-tagged → langString takes precedence over declared datatype.
            Literal lit = VF.createLiteral("bonjour", "fr");
            MemorySegment enc = TermEncoder.encode(lit, a);
            assertEquals(TermCodec.TAG_RDF_LANGSTRING, tagOf(enc));
        }
    }

    @Test
    void rdf_langString_datatype_dispatch() {
        // When the Value already has a language tag, the early-exit fires; this
        // case exercises the explicit RDF.LANGSTRING branch via a literal that
        // declares the datatype but happens to also have a lang tag.
        try (Arena a = Arena.ofConfined()) {
            Literal lit = VF.createLiteral("ciao", "it");
            MemorySegment enc = TermEncoder.encode(lit, a);
            assertEquals(TermCodec.TAG_RDF_LANGSTRING, tagOf(enc));
        }
    }

    // ---- error path ----

    @Test
    void unsupported_datatype_rejected_with_helpful_message() {
        try (Arena a = Arena.ofConfined()) {
            IRI customType = VF.createIRI("http://example.org/custom-type");
            Literal lit = VF.createLiteral("custom-value", customType);
            try {
                TermEncoder.encode(lit, a);
                fail("should have thrown");
            } catch (IllegalArgumentException e) {
                assertTrue(
                        e.getMessage().contains("custom-type"),
                        "error must name the unsupported datatype: " + e.getMessage());
                assertTrue(
                        e.getMessage().contains("encodeCustomLiteral"),
                        "error must point at the workaround: " + e.getMessage());
            }
        }
    }

    @Test
    void triple_value_rejected_with_helpful_message() {
        try (Arena a = Arena.ofConfined()) {
            Triple t =
                    VF.createTriple(
                            VF.createIRI("http://x"),
                            VF.createIRI("http://p"),
                            VF.createLiteral("o"));
            try {
                TermEncoder.encode(t, a);
                fail("should have thrown");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("Triple"), "error must point at Triple kind");
                assertTrue(
                        e.getMessage().contains("encodeQuotedTriple"),
                        "error must name the workaround: " + e.getMessage());
            }
        }
    }

    // ---- xsd:integer subtypes — the LOSSY Dictionary-less fallback (DTYPE-1, Step 6b) ----
    // History: Step 8 of plans/auth-on-sail.md surfaced that loading auth-ontology.ttl failed when
    // owl:cardinality literals ("1"^^xsd:nonNegativeInteger) hit TermEncoder; the fix routed the
    // subtypes to encodeXsdInteger (collapse onto xsd:integer). These tests pin THAT codec collapse
    // —
    // which is now only the Dictionary-less fallback. On the Sail write path the subtypes are
    // routed
    // through the custom path instead, preserving the exact subtype IRI (DTYPE-1 fixed there;
    // proven by
    // TermFaithfulSparqlTest's derived-integer round-trips). The TBox load is safe under both: it
    // goes
    // through addStatement -> encodeTerm -> DictionaryTermEncoder (custom-aware), not raw
    // TermEncoder.

    @Test
    void xsd_nonNegativeInteger_routes_to_integer_encoding() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermEncoder.encode(VF.createLiteral("1", XSD.NON_NEGATIVE_INTEGER), a);
            assertEquals(TermCodec.TAG_XSD_INTEGER, tagOf(enc));
        }
    }

    @Test
    void xsd_positiveInteger_routes_to_integer_encoding() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("42", XSD.POSITIVE_INTEGER), a);
            assertEquals(TermCodec.TAG_XSD_INTEGER, tagOf(enc));
        }
    }

    @Test
    void xsd_negativeInteger_routes_to_integer_encoding() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("-1", XSD.NEGATIVE_INTEGER), a);
            assertEquals(TermCodec.TAG_XSD_INTEGER, tagOf(enc));
        }
    }

    @Test
    void xsd_unsignedByte_routes_to_integer_encoding() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermEncoder.encode(VF.createLiteral("255", XSD.UNSIGNED_BYTE), a);
            assertEquals(TermCodec.TAG_XSD_INTEGER, tagOf(enc));
        }
    }
}
