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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link Iri}. Small surface, but {@link Iri#isVar()} drives the SPARQL planner's
 * variable detection — silent regressions there would break every BGP query.
 */
class IriTest {

    @Test
    void of_constructs_via_factory() {
        Iri a = Iri.of("http://example.org/x");
        assertEquals("http://example.org/x", a.value());
    }

    @Test
    void record_equality_by_value() {
        Iri a = new Iri("http://example.org/x");
        Iri b = Iri.of("http://example.org/x");
        Iri c = Iri.of("http://example.org/y");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void isVar_true_for_question_mark_prefix() {
        assertTrue(Iri.of("?x").isVar());
        assertTrue(Iri.of("?subject").isVar());
    }

    @Test
    void isVar_false_for_iri_value() {
        assertFalse(Iri.of("http://example.org/x").isVar());
        assertFalse(Iri.of("urn:isbn:0451450523").isVar());
    }

    @Test
    void isVar_false_for_empty_string() {
        assertFalse(Iri.of("").isVar(), "empty string is not a variable");
    }

    @Test
    void isVar_false_for_null_value() {
        // Constructed with null — the record accepts null; isVar must not NPE.
        Iri nullIri = new Iri(null);
        assertFalse(nullIri.isVar());
    }

    @Test
    void toString_wraps_iri_in_angle_brackets() {
        assertEquals("<http://example.org/x>", Iri.of("http://example.org/x").toString());
    }

    @Test
    void toString_keeps_variable_unwrapped() {
        assertEquals(
                "?x",
                Iri.of("?x").toString(),
                "variables are NOT wrapped — the ? prefix is the variable marker");
    }

    @Test
    void isVar_detects_at_first_character_only() {
        // A '?' that appears mid-string is not a variable.
        assertFalse(Iri.of("http://example.org/?query=x").isVar());
        assertFalse(Iri.of("urn:x:?embedded").isVar());
    }

    @Test
    void single_question_mark_is_var() {
        // Edge case: '?' alone is technically a variable per the prefix rule.
        assertTrue(Iri.of("?").isVar());
    }

    @Test
    void unicode_value_roundtrips() {
        Iri i = Iri.of("http://example.org/café");
        assertEquals("http://example.org/café", i.value());
        assertEquals("<http://example.org/café>", i.toString());
    }
}
