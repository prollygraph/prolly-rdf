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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link MergeEngine.Term} — the RDF-term value object used in the merge-conflict
 * report. The merge code itself only builds {@code uri} / {@code literal} terms; the {@code bnode},
 * typed-literal and lang-literal factories are public API, pinned here so they don't rot uncovered.
 */
class MergeEngineTermTest {

    @Test
    void uri_factory_sets_type_and_value() {
        MergeEngine.Term t = MergeEngine.Term.uri("urn:x:1");
        assertEquals("uri", t.type());
        assertEquals("urn:x:1", t.value());
        assertNull(t.datatype());
        assertNull(t.lang());
    }

    @Test
    void bnode_factory_sets_type_and_label() {
        MergeEngine.Term t = MergeEngine.Term.bnode("b0");
        assertEquals("bnode", t.type());
        assertEquals("b0", t.value());
        assertNull(t.datatype());
        assertNull(t.lang());
    }

    @Test
    void plain_literal_factory_has_no_datatype_or_lang() {
        MergeEngine.Term t = MergeEngine.Term.literal("hello");
        assertEquals("literal", t.type());
        assertEquals("hello", t.value());
        assertNull(t.datatype());
        assertNull(t.lang());
    }

    @Test
    void typed_literal_carries_datatype_only() {
        MergeEngine.Term t =
                MergeEngine.Term.typedLiteral("42", "http://www.w3.org/2001/XMLSchema#int");
        assertEquals("literal", t.type());
        assertEquals("42", t.value());
        assertEquals("http://www.w3.org/2001/XMLSchema#int", t.datatype());
        assertNull(t.lang(), "a typed literal carries no language tag");
    }

    @Test
    void lang_literal_carries_language_tag_only() {
        MergeEngine.Term t = MergeEngine.Term.langLiteral("bonjour", "fr");
        assertEquals("literal", t.type());
        assertEquals("bonjour", t.value());
        assertNull(t.datatype(), "a lang literal carries no datatype");
        assertEquals("fr", t.lang());
    }

    @Test
    void record_equality_is_by_all_components() {
        assertEquals(MergeEngine.Term.uri("urn:a"), MergeEngine.Term.uri("urn:a"));
        assertNotEquals(MergeEngine.Term.uri("urn:a"), MergeEngine.Term.uri("urn:b"));
        // Same lexical value, different kind → not equal.
        assertNotEquals(MergeEngine.Term.literal("x"), MergeEngine.Term.uri("x"));
        // Typed vs lang literal with the same lexical form → not equal.
        assertNotEquals(
                MergeEngine.Term.typedLiteral("x", "urn:dt"),
                MergeEngine.Term.langLiteral("x", "en"));
    }

    @Test
    void equal_terms_have_equal_hashcodes() {
        assertEquals(
                MergeEngine.Term.typedLiteral("1", "urn:dt").hashCode(),
                MergeEngine.Term.typedLiteral("1", "urn:dt").hashCode());
        assertEquals(
                MergeEngine.Term.bnode("b1").hashCode(), MergeEngine.Term.bnode("b1").hashCode());
    }
}
