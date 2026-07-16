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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.UUID;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProllyValueTest {

    private static PrefixTable prefixTable() {
        return new PrefixTable(new InMemoryNodeStore(), new HeapBufferPool());
    }

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    // =================================================================
    // ProllyIRI
    // =================================================================
    @Nested
    class IRIs {

        @Test
        void full_iri_string_value() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment enc = TermCodec.encodeFullIri("http://example.com/foo", a);
                ProllyIRI iri = new ProllyIRI(enc, prefixTable());
                assertEquals("http://example.com/foo", iri.stringValue());
            }
        }

        @Test
        void short_prefix_iri_resolves_against_bootstrap() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment enc = TermCodec.encodeShortPrefixIri(PrefixTable.ID_RDF, "type", a);
                ProllyIRI iri = new ProllyIRI(enc, prefixTable());
                assertEquals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type", iri.stringValue());
            }
        }

        @Test
        void short_prefix_iri_xsd_string() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment enc = TermCodec.encodeShortPrefixIri(PrefixTable.ID_XSD, "string", a);
                ProllyIRI iri = new ProllyIRI(enc, prefixTable());
                assertEquals("http://www.w3.org/2001/XMLSchema#string", iri.stringValue());
            }
        }

        @Test
        void namespace_and_local_split_at_hash() {
            try (Arena a = Arena.ofConfined()) {
                ProllyIRI iri =
                        new ProllyIRI(
                                TermCodec.encodeFullIri("http://example.com/v1#name", a),
                                prefixTable());
                assertEquals("http://example.com/v1#", iri.getNamespace());
                assertEquals("name", iri.getLocalName());
            }
        }

        @Test
        void namespace_and_local_split_at_slash() {
            try (Arena a = Arena.ofConfined()) {
                ProllyIRI iri =
                        new ProllyIRI(
                                TermCodec.encodeFullIri("https://schema.org/Person", a),
                                prefixTable());
                assertEquals("https://schema.org/", iri.getNamespace());
                assertEquals("Person", iri.getLocalName());
            }
        }

        @Test
        void namespace_no_delimiter_local_is_whole_string() {
            try (Arena a = Arena.ofConfined()) {
                ProllyIRI iri =
                        new ProllyIRI(
                                TermCodec.encodeFullIri("urn:isbn:0451450523", a), prefixTable());
                assertEquals("", iri.getNamespace());
                assertEquals("urn:isbn:0451450523", iri.getLocalName());
            }
        }

        @Test
        void equals_with_simple_iri_is_symmetric() {
            try (Arena a = Arena.ofConfined()) {
                ProllyIRI prolly =
                        new ProllyIRI(
                                TermCodec.encodeFullIri("http://example.com/x", a), prefixTable());
                IRI simple = VF.createIRI("http://example.com/x");
                assertEquals(prolly, simple);
                assertEquals(simple, prolly);
                assertEquals(prolly.hashCode(), simple.hashCode());
            }
        }

        @Test
        void unicode_iri_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                String uri = "https://例え.jp/パス";
                ProllyIRI iri = new ProllyIRI(TermCodec.encodeFullIri(uri, a), prefixTable());
                assertEquals(uri, iri.stringValue());
            }
        }

        @Test
        void unknown_prefix_id_throws_on_resolve() {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment enc = TermCodec.encodeShortPrefixIri(99999, "x", a);
                ProllyIRI iri = new ProllyIRI(enc, prefixTable());
                assertThrows(IllegalStateException.class, iri::stringValue);
            }
        }

        @Test
        void caches_string_value() {
            try (Arena a = Arena.ofConfined()) {
                ProllyIRI iri =
                        new ProllyIRI(
                                TermCodec.encodeFullIri("http://example.com/foo", a),
                                prefixTable());
                String s1 = iri.stringValue();
                String s2 = iri.stringValue();
                assertSame(s1, s2, "stringValue should cache and return the same instance");
            }
        }
    }

    // =================================================================
    // ProllyBNode
    // =================================================================
    @Nested
    class BNodes {

        @Test
        void uuid_form_id() {
            try (Arena a = Arena.ofConfined()) {
                UUID u = UUID.fromString("12345678-1234-5678-1234-567812345678");
                ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeUuid(u, a));
                assertEquals(u.toString(), b.getID());
            }
        }

        @Test
        void label_form_id() {
            try (Arena a = Arena.ofConfined()) {
                ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("b1", a));
                assertEquals("b1", b.getID());
            }
        }

        @Test
        void canonical_form_id() {
            try (Arena a = Arena.ofConfined()) {
                ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeCanon(42, a));
                assertEquals("c14n42", b.getID());
            }
        }

        @Test
        void toString_uses_turtle_format() {
            try (Arena a = Arena.ofConfined()) {
                ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("foo", a));
                assertEquals("_:foo", b.toString());
            }
        }

        @Test
        void equals_with_simple_bnode_is_symmetric() {
            try (Arena a = Arena.ofConfined()) {
                ProllyBNode prolly = new ProllyBNode(TermCodec.encodeBNodeLabel("b1", a));
                BNode simple = VF.createBNode("b1");
                assertEquals(prolly, simple);
                assertEquals(simple, prolly);
                assertEquals(prolly.hashCode(), simple.hashCode());
            }
        }

        @Test
        void distinct_labels_not_equal() {
            try (Arena a = Arena.ofConfined()) {
                ProllyBNode a1 = new ProllyBNode(TermCodec.encodeBNodeLabel("a", a));
                ProllyBNode a2 = new ProllyBNode(TermCodec.encodeBNodeLabel("b", a));
                assertNotEquals(a1, a2);
            }
        }
    }

    // =================================================================
    // ProllyLiteral — numeric / boolean
    // =================================================================
    @Nested
    class NumericLiterals {

        @Test
        void boolean_true_label_and_datatype() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeBoolean("true", a));
                assertEquals("true", lit.getLabel());
                assertEquals(XSD.BOOLEAN, lit.getDatatype());
                assertTrue(lit.booleanValue());
                assertTrue(lit.getLanguage().isEmpty());
            }
        }

        @Test
        void integer_label_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeInteger(42L, a));
                assertEquals("42", lit.getLabel());
                assertEquals(XSD.INTEGER, lit.getDatatype());
                assertEquals(42L, lit.longValue());
            }
        }

        @Test
        void int_distinct_from_integer_via_datatype() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral intLit = new ProllyLiteral(TermCodec.encodeInt32(7, a));
                ProllyLiteral integerLit = new ProllyLiteral(TermCodec.encodeInteger(7L, a));
                assertEquals("7", intLit.getLabel());
                assertEquals("7", integerLit.getLabel());
                assertEquals(XSD.INT, intLit.getDatatype());
                assertEquals(XSD.INTEGER, integerLit.getDatatype());
                assertNotEquals(intLit, integerLit); // datatype differs
            }
        }

        @Test
        void double_label_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeFloat64(3.14, a));
                assertEquals("3.14", lit.getLabel());
                assertEquals(XSD.DOUBLE, lit.getDatatype());
                assertEquals(3.14, lit.doubleValue());
            }
        }

        @Test
        void equals_with_simple_literal_is_symmetric() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral prolly = new ProllyLiteral(TermCodec.encodeInteger(42L, a));
                Literal simple = VF.createLiteral("42", XSD.INTEGER);
                assertEquals(prolly, simple);
                assertEquals(simple, prolly);
            }
        }
    }

    // =================================================================
    // ProllyLiteral — strings
    // =================================================================
    @Nested
    class StringLiterals {

        @Test
        void xsd_string_label() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("hello", a));
                assertEquals("hello", lit.getLabel());
                assertEquals(XSD.STRING, lit.getDatatype());
                assertTrue(lit.getLanguage().isEmpty());
            }
        }

        @Test
        void lang_string_label_and_language() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLangString("Hello", "en", a));
                assertEquals("Hello", lit.getLabel());
                assertEquals(RDF.LANGSTRING, lit.getDatatype());
                assertEquals(java.util.Optional.of("en"), lit.getLanguage());
            }
        }

        @Test
        void lang_string_with_empty_lang_returns_empty_optional() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLangString("text", "", a));
                assertTrue(lit.getLanguage().isEmpty());
            }
        }

        @Test
        void anyuri_label() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit =
                        new ProllyLiteral(TermCodec.encodeAnyURI("http://example.com/", a));
                assertEquals("http://example.com/", lit.getLabel());
                assertEquals(XSD.ANYURI, lit.getDatatype());
            }
        }

        @Test
        void unicode_string_label() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("こんにちは", a));
                assertEquals("こんにちは", lit.getLabel());
            }
        }

        @Test
        void toString_includes_datatype_for_typed() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeInteger(42L, a));
                String s = lit.toString();
                assertTrue(s.contains("42"));
                assertTrue(s.contains("integer"));
            }
        }

        @Test
        void toString_omits_datatype_for_xsd_string() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("hello", a));
                assertEquals("\"hello\"", lit.toString());
            }
        }

        @Test
        void toString_includes_language_for_langString() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLangString("Hi", "en", a));
                assertEquals("\"Hi\"@en", lit.toString());
            }
        }
    }

    // =================================================================
    // ProllyLiteral — temporal
    // =================================================================
    @Nested
    class TemporalLiterals {

        @Test
        void date_label_in_iso8601() {
            try (Arena a = Arena.ofConfined()) {
                // term-faithful (ADR-0043 Step 6): the verbatim lexical form round-trips
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeDate("2026-05-12", a));
                assertEquals("2026-05-12", lit.getLabel());
                assertEquals(XSD.DATE, lit.getDatatype());
            }
        }

        @Test
        void dateTime_label_round_trip() {
            try (Arena a = Arena.ofConfined()) {
                // term-faithful (ADR-0043 Step 6): the EXACT lexical form round-trips verbatim
                ProllyLiteral lit =
                        new ProllyLiteral(TermCodec.encodeDateTime("2026-05-12T17:30:00Z", a));
                assertEquals(XSD.DATETIME, lit.getDatatype());
                assertEquals("2026-05-12T17:30:00Z", lit.getLabel());
                java.time.OffsetDateTime reparsed = java.time.OffsetDateTime.parse(lit.getLabel());
                assertEquals(
                        java.time.OffsetDateTime.parse("2026-05-12T17:30:00Z").toInstant(),
                        reparsed.toInstant());
            }
        }

        @Test
        void gYear_label() {
            try (Arena a = Arena.ofConfined()) {
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeGYear("2026", a));
                assertEquals("2026", lit.getLabel());
                assertEquals(XSD.GYEAR, lit.getDatatype());
            }
        }

        @Test
        void gYear_negative() {
            try (Arena a = Arena.ofConfined()) {
                // a BCE year round-trips verbatim — the leading '-' is lexical, not a range
                // artifact
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeGYear("-0500", a));
                assertEquals("-0500", lit.getLabel());
            }
        }

        @Test
        void uuid_label_and_datatype() {
            try (Arena a = Arena.ofConfined()) {
                UUID u = UUID.fromString("12345678-1234-5678-1234-567812345678");
                ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeUuid(u, a));
                assertEquals(u.toString(), lit.getLabel());
                assertNotNull(lit.getDatatype());
            }
        }
    }
}
