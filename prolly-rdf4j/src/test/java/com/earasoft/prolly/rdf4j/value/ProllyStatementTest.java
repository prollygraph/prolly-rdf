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
import com.earasoft.prolly.rdf4j.index.QuadRole;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

class ProllyStatementTest {

    private Dictionary dict;
    private PrefixTable prefixes;
    private DictionaryTermResolver resolver;

    private void setup() {
        var store = new InMemoryNodeStore();
        var pool = new HeapBufferPool();
        this.dict = new Dictionary(store, pool, HashFunctions.defaultHash());
        this.prefixes = new PrefixTable(store, pool);
        this.resolver = new DictionaryTermResolver(dict, prefixes);
    }

    @Test
    void simple_iri_subject_predicate_object_no_context() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/alice", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/knows", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/bob", a));
            SpocKey key = new SpocKey(s, p, o, TermId.ZERO);

            ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);

            assertEquals("http://example/alice", st.getSubject().stringValue());
            assertEquals("http://example/knows", st.getPredicate().stringValue());
            assertEquals("http://example/bob", st.getObject().stringValue());
            assertNull(st.getContext(), "context = default graph sentinel → null");
        }
    }

    @Test
    void statement_with_literal_object() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/alice", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/age", a));
            TermId o = dict.encode(TermCodec.encodeInteger(30L, a));
            SpocKey key = new SpocKey(s, p, o, TermId.ZERO);

            ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);

            assertInstanceOf(Literal.class, st.getObject());
            assertEquals(30L, ((Literal) st.getObject()).longValue());
        }
    }

    @Test
    void statement_with_explicit_context_graph() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            TermId g = dict.encode(TermCodec.encodeFullIri("http://example/named-graph", a));
            SpocKey key = new SpocKey(s, p, o, g);

            ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);

            assertNotNull(st.getContext());
            assertEquals("http://example/named-graph", st.getContext().stringValue());
        }
    }

    @Test
    void bnode_subject_resolves_as_resource() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeBNodeLabel("b1", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            SpocKey key = new SpocKey(s, p, o, TermId.ZERO);

            ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);

            assertInstanceOf(org.eclipse.rdf4j.model.BNode.class, st.getSubject());
            assertEquals("b1", ((org.eclipse.rdf4j.model.BNode) st.getSubject()).getID());
        }
    }

    @Test
    void posc_role_resolves_correct_positions() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            // POSC layout in the underlying tuple: column 0 = predicate, 1 = object, 2 = subject, 3
            // = context.
            // QuadRole.POSC says s=col2, p=col0, o=col1, c=col3 — meaning logical s/p/o/c maps to
            // physical 2/0/1/3.
            TermId pTerm = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId oTerm = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            TermId sTerm = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            SpocKey key = new SpocKey(pTerm, oTerm, sTerm, TermId.ZERO);

            ProllyStatement st = new ProllyStatement(key, QuadRole.POSC, resolver, TermId.ZERO);
            assertEquals("http://example/s", st.getSubject().stringValue());
            assertEquals("http://example/p", st.getPredicate().stringValue());
            assertEquals("http://example/o", st.getObject().stringValue());
        }
    }

    @Test
    void cached_accessors_return_same_instance() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            SpocKey key = new SpocKey(s, p, o, TermId.ZERO);

            ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);
            assertSame(st.getSubject(), st.getSubject());
            assertSame(st.getPredicate(), st.getPredicate());
            assertSame(st.getObject(), st.getObject());
        }
    }

    @Test
    void equals_with_simple_statement_is_symmetric() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            SpocKey key = new SpocKey(s, p, o, TermId.ZERO);

            ProllyStatement prolly = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);
            var vf = SimpleValueFactory.getInstance();
            Statement simple =
                    vf.createStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
            assertEquals(prolly, simple);
            assertEquals(simple, prolly);
            assertEquals(prolly.hashCode(), simple.hashCode());
        }
    }

    @Test
    void distinct_subjects_not_equal() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s1 = dict.encode(TermCodec.encodeFullIri("http://example/s1", a));
            TermId s2 = dict.encode(TermCodec.encodeFullIri("http://example/s2", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            SpocKey k1 = new SpocKey(s1, p, o, TermId.ZERO);
            SpocKey k2 = new SpocKey(s2, p, o, TermId.ZERO);
            ProllyStatement st1 = new ProllyStatement(k1, QuadRole.SPOC, resolver, TermId.ZERO);
            ProllyStatement st2 = new ProllyStatement(k2, QuadRole.SPOC, resolver, TermId.ZERO);
            assertNotEquals(st1, st2);
        }
    }

    @Test
    void resolver_wraps_each_tag_family_correctly() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            assertInstanceOf(
                    ProllyIRI.class, resolver.wrap(TermCodec.encodeFullIri("http://x/", a)));
            assertInstanceOf(ProllyBNode.class, resolver.wrap(TermCodec.encodeBNodeLabel("b1", a)));
            assertInstanceOf(ProllyLiteral.class, resolver.wrap(TermCodec.encodeInteger(42L, a)));
            assertInstanceOf(
                    ProllyLiteral.class, resolver.wrap(TermCodec.encodeXsdString("hello", a)));
            assertInstanceOf(ProllyLiteral.class, resolver.wrap(TermCodec.encodeFloat64(3.14, a)));
            assertInstanceOf(
                    ProllyTriple.class,
                    resolver.wrap(
                            TermCodec.encodeQuotedTriple(
                                    TermId.of(1L), TermId.of(2L), TermId.of(3L), true, a)));
        }
    }

    @Test
    void unknown_term_id_throws_on_resolve() {
        setup();
        SpocKey key =
                new SpocKey(
                        TermId.of(0xDEAD_BEEFL),
                        TermId.of(0xCAFE_BABEL),
                        TermId.of(0xFEED_FACEL),
                        TermId.ZERO);
        ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);
        assertThrows(IllegalStateException.class, st::getSubject);
    }

    @Test
    void getPredicate_throws_when_term_is_not_an_iri() {
        // A literal sitting in the predicate column → getPredicate must reject
        // it (RDF predicates are always IRIs).
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId litP = dict.encode(TermCodec.encodeXsdString("not-an-iri", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            ProllyStatement st =
                    new ProllyStatement(
                            new SpocKey(s, litP, o, TermId.ZERO),
                            QuadRole.SPOC,
                            resolver,
                            TermId.ZERO);
            assertThrows(IllegalStateException.class, st::getPredicate);
        }
    }

    @Test
    void getContext_throws_when_term_is_not_a_resource() {
        // A literal in the context column → getContext must reject it.
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            TermId litCtx = dict.encode(TermCodec.encodeInteger(7L, a));
            ProllyStatement st =
                    new ProllyStatement(
                            new SpocKey(s, p, o, litCtx), QuadRole.SPOC, resolver, TermId.ZERO);
            assertThrows(IllegalStateException.class, st::getContext);
        }
    }

    @Test
    void equals_is_reflexive_and_rejects_non_statements() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            ProllyStatement st =
                    new ProllyStatement(
                            new SpocKey(s, p, o, TermId.ZERO),
                            QuadRole.SPOC,
                            resolver,
                            TermId.ZERO);
            assertEquals(st, st, "a statement equals itself");
            assertNotEquals(st, "not a statement");
            assertNotEquals(st, null);
        }
    }

    @Test
    void toString_renders_triple_and_quad_forms() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId s = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId p = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId o = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            TermId g = dict.encode(TermCodec.encodeFullIri("http://example/g", a));
            ProllyStatement triple =
                    new ProllyStatement(
                            new SpocKey(s, p, o, TermId.ZERO),
                            QuadRole.SPOC,
                            resolver,
                            TermId.ZERO);
            String ts = triple.toString();
            assertTrue(ts.contains("http://example/s") && ts.contains("http://example/o"));
            assertFalse(
                    ts.contains("http://example/g"),
                    "a default-graph statement has no 4th term in toString");

            ProllyStatement quad =
                    new ProllyStatement(
                            new SpocKey(s, p, o, g), QuadRole.SPOC, resolver, TermId.ZERO);
            assertTrue(
                    quad.toString().contains("http://example/g"),
                    "an explicit context must appear in toString");
        }
    }

    @Test
    void quadRole_col_rejects_out_of_range_position() {
        SpocKey key = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        assertThrows(IllegalArgumentException.class, () -> QuadRole.SPOC.col(key, 4));
        assertThrows(IllegalArgumentException.class, () -> QuadRole.SPOC.col(key, -1));
    }

    @Test
    void ospc_and_cspo_roles_resolve_correct_positions() {
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId sTerm = dict.encode(TermCodec.encodeFullIri("http://example/s", a));
            TermId pTerm = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId oTerm = dict.encode(TermCodec.encodeFullIri("http://example/o", a));
            TermId gTerm = dict.encode(TermCodec.encodeFullIri("http://example/g", a));
            // OSPC physical layout: col0=o, col1=s, col2=p, col3=c.
            ProllyStatement stO =
                    new ProllyStatement(
                            new SpocKey(oTerm, sTerm, pTerm, gTerm),
                            QuadRole.OSPC,
                            resolver,
                            TermId.ZERO);
            assertEquals("http://example/s", stO.getSubject().stringValue());
            assertEquals("http://example/p", stO.getPredicate().stringValue());
            assertEquals("http://example/o", stO.getObject().stringValue());
            assertEquals("http://example/g", stO.getContext().stringValue());
            // CSPO physical layout: col0=c, col1=s, col2=p, col3=o.
            ProllyStatement stC =
                    new ProllyStatement(
                            new SpocKey(gTerm, sTerm, pTerm, oTerm),
                            QuadRole.CSPO,
                            resolver,
                            TermId.ZERO);
            assertEquals("http://example/s", stC.getSubject().stringValue());
            assertEquals("http://example/p", stC.getPredicate().stringValue());
            assertEquals("http://example/o", stC.getObject().stringValue());
            assertEquals("http://example/g", stC.getContext().stringValue());
        }
    }

    @Test
    void prolly_triple_via_resolver_works_recursively() {
        // Insert a quoted triple as a subject of an outer statement.
        setup();
        try (Arena a = Arena.ofConfined()) {
            TermId innerS = dict.encode(TermCodec.encodeFullIri("http://example/x", a));
            TermId innerP = dict.encode(TermCodec.encodeFullIri("http://example/p", a));
            TermId innerO = dict.encode(TermCodec.encodeFullIri("http://example/y", a));
            TermId quotedS =
                    dict.encode(TermCodec.encodeQuotedTriple(innerS, innerP, innerO, true, a));
            TermId outerP = dict.encode(TermCodec.encodeFullIri("http://example/source", a));
            TermId outerO = dict.encode(TermCodec.encodeFullIri("http://example/wiki", a));
            SpocKey key = new SpocKey(quotedS, outerP, outerO, TermId.ZERO);

            ProllyStatement st = new ProllyStatement(key, QuadRole.SPOC, resolver, TermId.ZERO);
            assertInstanceOf(ProllyTriple.class, st.getSubject());
            ProllyTriple inner = (ProllyTriple) st.getSubject();
            assertEquals("http://example/x", inner.getSubject().stringValue());
            assertEquals("http://example/p", inner.getPredicate().stringValue());
            assertEquals("http://example/y", inner.getObject().stringValue());
        }
    }
}
