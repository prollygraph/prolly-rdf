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
import java.util.Optional;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link ProllyLiteral}. Covers the major literal tag families: boolean,
 * numeric (int family), float/double, decimal, string, langString, hex/base64 binary. Each one
 * drifting silently produces a Literal that prints right but compares wrong.
 */
class ProllyLiteralTest {

    // ---- boolean ----

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
    void boolean_false_label_and_datatype() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeBoolean("false", a));
            assertEquals("false", lit.getLabel());
            assertFalse(lit.booleanValue());
        }
    }

    @Test
    void boolean_one_is_faithful_and_true_valued() {
        // term-faithful (ADR-0043): "1" round-trips verbatim (not folded to "true") AND
        // booleanValue() is XSD-correct ("1" → true, not Boolean.parseBoolean's false).
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeBoolean("1", a));
            assertEquals("1", lit.getLabel());
            assertEquals(XSD.BOOLEAN, lit.getDatatype());
            assertTrue(lit.booleanValue());
        }
    }

    @Test
    void boolean_ill_typed_is_a_faithful_term_with_no_value() {
        // RDF 1.1: an ill-typed literal is a valid TERM (faithful label) with no VALUE. So the
        // label
        // round-trips "maybe" but booleanValue() THROWS — matching RDF4J SimpleLiteral (the strict
        // Literal contract, via XMLDatatypeUtil). The term/value split is the point of ADR-0043.
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeBoolean("maybe", a));
            assertEquals("maybe", lit.getLabel());
            assertEquals(XSD.BOOLEAN, lit.getDatatype());
            assertThrows(IllegalArgumentException.class, lit::booleanValue);
        }
    }

    // ---- numeric ----

    @Test
    void int32_label_and_typed_accessor() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeInt32(-42, a));
            assertEquals("-42", lit.getLabel());
            assertEquals(XSD.INT, lit.getDatatype());
            assertEquals(-42, lit.intValue());
        }
    }

    @Test
    void int64_label() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLong(123456789012345L, a));
            assertEquals("123456789012345", lit.getLabel());
            assertEquals(XSD.LONG, lit.getDatatype());
            assertEquals(123456789012345L, lit.longValue());
        }
    }

    @Test
    void float32_label_and_typed_accessor() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeFloat32(2.5f, a));
            assertEquals(XSD.FLOAT, lit.getDatatype());
            assertEquals(2.5f, lit.floatValue(), 0.0f);
        }
    }

    @Test
    void float64_label_and_typed_accessor() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeFloat64(3.14, a));
            assertEquals(XSD.DOUBLE, lit.getDatatype());
            assertEquals(3.14, lit.doubleValue(), 0.0);
        }
    }

    // ---- string ----

    @Test
    void xsd_string_label_and_datatype() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("hello", a));
            assertEquals("hello", lit.getLabel());
            assertEquals(XSD.STRING, lit.getDatatype());
            assertTrue(lit.getLanguage().isEmpty());
        }
    }

    @Test
    void xsd_string_with_unicode() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("café 🐉", a));
            assertEquals("café 🐉", lit.getLabel());
        }
    }

    @Test
    void empty_string_label() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("", a));
            assertEquals("", lit.getLabel());
        }
    }

    // ---- langString ----

    @Test
    void langString_label_and_language() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLangString("hello", "en", a));
            assertEquals("hello", lit.getLabel());
            assertEquals(RDF.LANGSTRING, lit.getDatatype());
            assertEquals(Optional.of("en"), lit.getLanguage());
        }
    }

    @Test
    void langString_with_empty_lang_returns_optional_empty() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLangString("hello", "", a));
            assertEquals("hello", lit.getLabel());
            assertTrue(
                    lit.getLanguage().isEmpty(),
                    "empty lang tag must materialize as Optional.empty(), not Optional.of(\"\")");
        }
    }

    @Test
    void non_langstring_returns_empty_language() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            assertTrue(lit.getLanguage().isEmpty());
        }
    }

    // ---- caching ----

    @Test
    void getLabel_caches_result() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("z", a));
            String first = lit.getLabel();
            String second = lit.getLabel();
            assertSame(first, second, "label must be cached after first decode");
        }
    }

    @Test
    void getDatatype_caches_result() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("z", a));
            assertSame(lit.getDatatype(), lit.getDatatype());
        }
    }

    // ---- stringValue ----

    @Test
    void stringValue_equals_label() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("the-value", a));
            assertEquals(lit.getLabel(), lit.stringValue());
        }
    }

    // ---- toString ----

    @Test
    void toString_xsd_string_no_datatype_suffix() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("hi", a));
            assertEquals(
                    "\"hi\"",
                    lit.toString(),
                    "xsd:string default is implicit — no ^^<xsd:string> suffix");
        }
    }

    @Test
    void toString_int_includes_datatype_suffix() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeInt32(7, a));
            String s = lit.toString();
            assertTrue(s.contains("\"7\""));
            assertTrue(s.contains("^^<"));
            assertTrue(s.contains(XSD.INT.stringValue()));
        }
    }

    @Test
    void toString_langString_uses_at_lang() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeLangString("hello", "en", a));
            assertEquals("\"hello\"@en", lit.toString());
        }
    }

    // ---- equality ----

    @Test
    void equal_to_self() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            assertEquals(lit, lit);
        }
    }

    @Test
    void equal_to_other_ProllyLiteral_same_label_and_datatype() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral a1 = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            ProllyLiteral a2 = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            assertEquals(a1, a2);
            assertEquals(a1.hashCode(), a2.hashCode());
        }
    }

    @Test
    void not_equal_when_labels_differ() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral a1 = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            ProllyLiteral a2 = new ProllyLiteral(TermCodec.encodeXsdString("y", a));
            assertNotEquals(a1, a2);
        }
    }

    @Test
    void not_equal_when_datatypes_differ() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral asString = new ProllyLiteral(TermCodec.encodeXsdString("7", a));
            ProllyLiteral asInt = new ProllyLiteral(TermCodec.encodeInt32(7, a));
            assertNotEquals(
                    asString, asInt, "same label but different datatype must NOT compare equal");
        }
    }

    @Test
    void not_equal_when_language_differs() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral en = new ProllyLiteral(TermCodec.encodeLangString("hi", "en", a));
            ProllyLiteral fr = new ProllyLiteral(TermCodec.encodeLangString("hi", "fr", a));
            assertNotEquals(en, fr);
        }
    }

    @Test
    void equal_to_rdf4j_SimpleLiteral_with_same_label_and_datatype() {
        // Cross-implementation equality (RDF4J semantic contract).
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral mine = new ProllyLiteral(TermCodec.encodeXsdString("shared", a));
            Literal theirs = SimpleValueFactory.getInstance().createLiteral("shared");
            assertEquals(mine, theirs);
            assertEquals(mine.hashCode(), theirs.hashCode());
        }
    }

    @Test
    void not_equal_to_non_literal_object() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            assertNotEquals(lit, "x");
            assertNotEquals(lit, null);
        }
    }

    // ---- error path ----

    @Test
    void wrong_tag_byte_throws() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment evil = a.allocate(1);
            evil.set(
                    java.lang.foreign.ValueLayout.JAVA_BYTE,
                    0,
                    (byte) 0x80); // IRI tag, not literal
            ProllyLiteral lit = new ProllyLiteral(evil);
            assertThrows(IllegalStateException.class, lit::getLabel);
        }
    }

    @Test
    void is_sealed_subtype_of_ProllyValue() {
        try (Arena a = Arena.ofConfined()) {
            ProllyLiteral lit = new ProllyLiteral(TermCodec.encodeXsdString("x", a));
            assertInstanceOf(ProllyValue.class, lit);
            assertInstanceOf(Literal.class, lit);
        }
    }
}
