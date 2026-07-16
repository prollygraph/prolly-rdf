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
import java.math.BigDecimal;
import java.math.BigInteger;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProllyValueFactoryTest {

    private ProllyValueFactory factory() {
        return new ProllyValueFactory(
                new PrefixTable(new InMemoryNodeStore(), new HeapBufferPool()));
    }

    @Nested
    class IRIs {
        @Test
        void createIRI_singleArg() {
            IRI iri = factory().createIRI("http://example.com/foo");
            assertInstanceOf(ProllyIRI.class, iri);
            assertEquals("http://example.com/foo", iri.stringValue());
        }

        @Test
        void createIRI_twoArg() {
            IRI iri = factory().createIRI("http://example.com/", "foo");
            assertEquals("http://example.com/foo", iri.stringValue());
            assertEquals("http://example.com/", iri.getNamespace());
            assertEquals("foo", iri.getLocalName());
        }

        @Test
        void createIRI_equals_simple() {
            IRI prolly = factory().createIRI("http://example.com/foo");
            IRI simple = SimpleValueFactory.getInstance().createIRI("http://example.com/foo");
            assertEquals(prolly, simple);
            assertEquals(simple, prolly);
        }
    }

    @Nested
    class BNodes {
        @Test
        void createBNode_with_id() {
            BNode b = factory().createBNode("b1");
            assertInstanceOf(ProllyBNode.class, b);
            assertEquals("b1", b.getID());
        }

        @Test
        void createBNode_no_arg_unique() {
            ProllyValueFactory f = factory();
            BNode b1 = f.createBNode();
            BNode b2 = f.createBNode();
            assertNotEquals(b1.getID(), b2.getID());
        }

        @Test
        void createBNode_equals_simple_by_id() {
            BNode prolly = factory().createBNode("b1");
            BNode simple = SimpleValueFactory.getInstance().createBNode("b1");
            assertEquals(prolly, simple);
        }
    }

    @Nested
    class Literals {
        @Test
        void plain_string_literal() {
            Literal l = factory().createLiteral("hello");
            assertEquals("hello", l.getLabel());
            assertEquals(XSD.STRING, l.getDatatype());
        }

        @Test
        void typed_integer_via_label_and_datatype() {
            Literal l = factory().createLiteral("42", XSD.INTEGER);
            assertEquals("42", l.getLabel());
            assertEquals(XSD.INTEGER, l.getDatatype());
            assertEquals(42L, l.longValue());
        }

        @Test
        void typed_dispatch_double() {
            Literal l = factory().createLiteral("3.14", XSD.DOUBLE);
            assertEquals(XSD.DOUBLE, l.getDatatype());
            assertEquals(3.14, l.doubleValue());
        }

        @Test
        void typed_dispatch_boolean_true() {
            Literal l = factory().createLiteral("true", XSD.BOOLEAN);
            assertTrue(l.booleanValue());
        }

        @Test
        void typed_dispatch_boolean_via_one_zero_form() {
            Literal l = factory().createLiteral("1", XSD.BOOLEAN);
            assertTrue(l.booleanValue());
            Literal lz = factory().createLiteral("0", XSD.BOOLEAN);
            assertFalse(lz.booleanValue());
        }

        @Test
        void lang_string_literal() {
            Literal l = factory().createLiteral("Hello", "en");
            assertEquals("Hello", l.getLabel());
            assertEquals(RDF.LANGSTRING, l.getDatatype());
            assertEquals(java.util.Optional.of("en"), l.getLanguage());
        }

        @Test
        void boolean_overload() {
            Literal l = factory().createLiteral(true);
            assertEquals("true", l.getLabel());
            assertEquals(XSD.BOOLEAN, l.getDatatype());
        }

        @Test
        void long_overload() {
            Literal l = factory().createLiteral(42L);
            assertEquals("42", l.getLabel());
            assertEquals(XSD.LONG, l.getDatatype());
        }

        @Test
        void double_overload() {
            Literal l = factory().createLiteral(3.14);
            assertEquals(XSD.DOUBLE, l.getDatatype());
            assertEquals(3.14, l.doubleValue());
        }

        @Test
        void big_integer_fits_long_uses_int64_path() {
            Literal l = factory().createLiteral(BigInteger.valueOf(42));
            assertEquals(XSD.INTEGER, l.getDatatype());
            assertEquals(42L, l.longValue());
        }

        @Test
        void big_integer_exceeding_long_uses_big_path() {
            BigInteger huge = BigInteger.TWO.pow(200);
            Literal l = factory().createLiteral(huge);
            assertEquals(XSD.INTEGER, l.getDatatype());
            assertEquals(huge, l.integerValue());
        }

        @Test
        void big_decimal_round_trip() {
            BigDecimal v = new BigDecimal("3.14159");
            Literal l = factory().createLiteral(v);
            assertEquals(XSD.DECIMAL, l.getDatatype());
            assertEquals(v, l.decimalValue());
        }

        @Test
        void unknown_datatype_falls_back_to_simple() {
            // Custom datatype IRI not in our built-in set
            IRI customDt = SimpleValueFactory.getInstance().createIRI("http://example.com/myType");
            Literal l = factory().createLiteral("xyz", customDt);
            // Not a ProllyLiteral; it's the SimpleLiteral fallback
            assertEquals("xyz", l.getLabel());
            assertEquals(customDt, l.getDatatype());
        }

        // ---- remaining primitive overloads ----

        @Test
        void byte_overload() {
            Literal l = factory().createLiteral((byte) 7);
            assertEquals(XSD.BYTE, l.getDatatype());
            assertEquals((byte) 7, l.byteValue());
        }

        @Test
        void short_overload() {
            Literal l = factory().createLiteral((short) 300);
            assertEquals(XSD.SHORT, l.getDatatype());
            assertEquals((short) 300, l.shortValue());
        }

        @Test
        void int_overload() {
            Literal l = factory().createLiteral(123456);
            assertEquals(XSD.INT, l.getDatatype());
            assertEquals(123456, l.intValue());
        }

        @Test
        void float_overload() {
            Literal l = factory().createLiteral(2.5f);
            assertEquals(XSD.FLOAT, l.getDatatype());
            assertEquals(2.5f, l.floatValue());
        }

        // ---- CoreDatatype-carrying overloads (delegate to the IRI form) ----

        @Test
        void string_iri_coredatatype_overload() {
            Literal l =
                    factory()
                            .createLiteral(
                                    "42",
                                    XSD.INTEGER,
                                    org.eclipse.rdf4j.model.base.CoreDatatype.XSD.INTEGER);
            assertEquals(XSD.INTEGER, l.getDatatype());
            assertEquals("42", l.getLabel());
        }

        @Test
        void string_coredatatype_overload() {
            Literal l =
                    factory()
                            .createLiteral(
                                    "hello", org.eclipse.rdf4j.model.base.CoreDatatype.XSD.STRING);
            assertEquals("hello", l.getLabel());
            assertEquals(XSD.STRING, l.getDatatype());
        }

        // ---- date/time overloads (delegate to SimpleValueFactory) ----

        @Test
        void date_overload_delegates_to_simple() {
            Literal l = factory().createLiteral(new java.util.Date(0L));
            assertNotNull(l, "java.util.Date literal delegates to SimpleValueFactory");
        }

        @Test
        void calendar_overload_delegates_to_simple() throws Exception {
            var cal =
                    javax.xml.datatype.DatatypeFactory.newInstance()
                            .newXMLGregorianCalendar("2026-05-15T00:00:00Z");
            assertNotNull(factory().createLiteral(cal));
        }

        @Test
        void temporal_accessor_overload_delegates_to_simple() {
            Literal l =
                    factory().createLiteral(java.time.OffsetDateTime.parse("2026-05-15T10:00:00Z"));
            assertNotNull(l);
        }

        @Test
        void temporal_amount_overload_delegates_to_simple() {
            Literal l = factory().createLiteral(java.time.Duration.ofHours(3));
            assertNotNull(l);
        }
    }

    @Nested
    class Statements {
        @Test
        void create_statement_simple() {
            ProllyValueFactory f = factory();
            IRI s = f.createIRI("http://example/s");
            IRI p = f.createIRI("http://example/p");
            Literal o = f.createLiteral("hello");
            var st = f.createStatement(s, p, o);
            assertEquals(s, st.getSubject());
            assertEquals(p, st.getPredicate());
            assertEquals(o, st.getObject());
        }

        @Test
        void create_statement_with_context() {
            ProllyValueFactory f = factory();
            IRI s = f.createIRI("http://example/s");
            IRI p = f.createIRI("http://example/p");
            IRI o = f.createIRI("http://example/o");
            IRI g = f.createIRI("http://example/g");
            var st = f.createStatement(s, p, o, g);
            assertEquals(g, st.getContext());
        }
    }

    @Nested
    class Triples {
        @Test
        void create_triple_returns_prolly_triple() {
            ProllyValueFactory f = factory();
            IRI s = f.createIRI("http://example/s");
            IRI p = f.createIRI("http://example/p");
            IRI o = f.createIRI("http://example/o");
            Triple t = f.createTriple(s, p, o);
            assertInstanceOf(ProllyTriple.class, t);
            assertEquals(s, t.getSubject());
            assertEquals(p, t.getPredicate());
            assertEquals(o, t.getObject());
        }

        @Test
        void create_triple_with_literal_object() {
            ProllyValueFactory f = factory();
            IRI s = f.createIRI("http://example/s");
            IRI p = f.createIRI("http://example/p");
            Literal o = f.createLiteral(42L);
            Triple t = f.createTriple(s, p, o);
            assertEquals(42L, ((Literal) t.getObject()).longValue());
        }

        @Test
        void create_triple_with_bnode_subject() {
            ProllyValueFactory f = factory();
            BNode s = f.createBNode("b1");
            IRI p = f.createIRI("http://example/p");
            IRI o = f.createIRI("http://example/o");
            Triple t = f.createTriple(s, p, o);
            assertEquals(s, t.getSubject());
            assertInstanceOf(BNode.class, t.getSubject());
        }

        @Test
        void prolly_triple_equals_simple_triple() {
            ProllyValueFactory f = factory();
            IRI s = f.createIRI("http://example/s");
            IRI p = f.createIRI("http://example/p");
            IRI o = f.createIRI("http://example/o");
            Triple prolly = f.createTriple(s, p, o);
            Triple simple = SimpleValueFactory.getInstance().createTriple(s, p, o);
            assertEquals(prolly, simple);
            assertEquals(simple, prolly);
        }

        @Test
        void prolly_triple_string_value_uses_turtle_star() {
            ProllyValueFactory f = factory();
            IRI s = f.createIRI("http://example/s");
            IRI p = f.createIRI("http://example/p");
            IRI o = f.createIRI("http://example/o");
            Triple t = f.createTriple(s, p, o);
            String sv = t.stringValue();
            assertTrue(sv.startsWith("<<") && sv.endsWith(">>"), "got: " + sv);
        }

        @Test
        void cross_factory_components_work() {
            // Using values from SimpleValueFactory as components of a ProllyTriple
            ProllyValueFactory f = factory();
            SimpleValueFactory svf = SimpleValueFactory.getInstance();
            IRI s = svf.createIRI("http://example/s");
            IRI p = svf.createIRI("http://example/p");
            Value o = svf.createLiteral("hello");
            Triple t = f.createTriple(s, p, o);
            assertEquals(s, t.getSubject());
            assertEquals(p, t.getPredicate());
            assertEquals(o, t.getObject());
        }
    }
}
