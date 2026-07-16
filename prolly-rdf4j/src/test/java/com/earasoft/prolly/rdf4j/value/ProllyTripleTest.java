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
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link ProllyTriple}. RDF-star quoted triples are tag 0xC0/0xC1; the
 * lazy resolver pattern means a missing dictionary entry surfaces only at access time.
 *
 * <p>Uses a mock {@link TermResolver} so the test stays unit-level (no Dictionary plumbing).
 */
class ProllyTripleTest {

    /** Mock resolver backed by an in-memory map of TermId → ProllyValue. */
    private static class MockResolver implements TermResolver {
        final Map<TermId, ProllyValue> table = new HashMap<>();

        @Override
        public ProllyValue resolve(TermId id) {
            ProllyValue v = table.get(id);
            if (v == null) {
                throw new IllegalStateException("unknown TermId " + id);
            }
            return v;
        }
    }

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private static ProllyIRI iriFor(Arena a, String s) {
        return new ProllyIRI(
                TermCodec.encodeFullIri(s, a),
                new com.earasoft.prolly.rdf4j.term.PrefixTable(
                        new com.dolthub.prolly.InMemoryNodeStore(),
                        new com.earasoft.prolly.pool.DirectBufferPool()));
    }

    private static ProllyLiteral litFor(Arena a, String s) {
        return new ProllyLiteral(TermCodec.encodeXsdString(s, a));
    }

    private static ProllyBNode bnodeFor(Arena a, String label) {
        return new ProllyBNode(TermCodec.encodeBNodeLabel(label, a));
    }

    // ---- decoded payload ----

    @Test
    void asserted_triple_decodes_components() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), litFor(a, "the object"));

            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);

            assertEquals("http://x/s", t.getSubject().stringValue());
            assertEquals("http://x/p", t.getPredicate().stringValue());
            assertEquals("the object", t.getObject().stringValue());
            assertTrue(t.isAsserted());
        }
    }

    @Test
    void unasserted_triple_isAsserted_false() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), litFor(a, "o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), false, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertFalse(t.isAsserted());
        }
    }

    // ---- resource subject ----

    @Test
    void bnode_subject_returns_bnode() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), bnodeFor(a, "b1"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), litFor(a, "o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertInstanceOf(BNode.class, t.getSubject());
            assertEquals("b1", ((BNode) t.getSubject()).getID());
        }
    }

    @Test
    void non_resource_subject_throws() {
        // A Literal cannot be a subject — the resolver returns a non-Resource
        // and the accessor must fail closed.
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), litFor(a, "not a resource"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), litFor(a, "o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertThrows(IllegalStateException.class, t::getSubject);
        }
    }

    // ---- IRI predicate ----

    @Test
    void non_iri_predicate_throws() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), litFor(a, "not an IRI")); // bad
            r.table.put(TermId.of(3L), litFor(a, "o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertThrows(IllegalStateException.class, t::getPredicate);
        }
    }

    // ---- object can be any Value ----

    @Test
    void iri_object_returns_iri() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertInstanceOf(IRI.class, t.getObject());
        }
    }

    @Test
    void literal_object_returns_literal() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), litFor(a, "string lit"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertInstanceOf(Literal.class, t.getObject());
            assertEquals("string lit", ((Literal) t.getObject()).getLabel());
        }
    }

    // ---- stringValue / toString ----

    @Test
    void stringValue_renders_in_turtle_star_form() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            String s = t.stringValue();
            assertTrue(s.startsWith("<<"), "Turtle-star must start with <<");
            assertTrue(s.endsWith(">>"), "Turtle-star must end with >>");
        }
    }

    @Test
    void toString_equals_stringValue() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertEquals(t.stringValue(), t.toString());
        }
    }

    // ---- equality (RDF semantic) ----

    @Test
    void equal_to_other_triple_with_same_components() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);

            Triple simple =
                    VF.createTriple(
                            VF.createIRI("http://x/s"),
                            VF.createIRI("http://x/p"),
                            VF.createIRI("http://x/o"));
            assertEquals(t, simple, "RDF-semantic equality must cross implementations");
            assertEquals(t.hashCode(), simple.hashCode());
        }
    }

    @Test
    void self_equal() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertEquals(t, t);
        }
    }

    @Test
    void not_equal_to_non_triple() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertNotEquals(t, "<<s p o>>");
            assertNotEquals(t, null);
        }
    }

    // ---- resolver delegation ----

    @Test
    void unknown_term_id_propagates_resolver_error() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver(); // empty table
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(99L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertThrows(IllegalStateException.class, t::getSubject);
        }
    }

    @Test
    void is_sealed_subtype_of_ProllyValue() {
        try (Arena a = Arena.ofConfined()) {
            MockResolver r = new MockResolver();
            r.table.put(TermId.of(1L), iriFor(a, "http://x/s"));
            r.table.put(TermId.of(2L), iriFor(a, "http://x/p"));
            r.table.put(TermId.of(3L), iriFor(a, "http://x/o"));
            MemorySegment enc =
                    TermCodec.encodeQuotedTriple(
                            TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a);
            ProllyTriple t = new ProllyTriple(enc, r);
            assertInstanceOf(ProllyValue.class, t);
            assertInstanceOf(Triple.class, t);
        }
    }
}
