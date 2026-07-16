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
 * SQLite-grade coverage for {@link QuadPattern}. SPARQL planner depends on {@link
 * QuadPattern#findVarIdx} and {@link QuadPattern#isVar} to distinguish constants from variables —
 * silent regressions corrupt every BGP query plan.
 */
class QuadPatternTest {

    @Test
    void of_factory_builds_pattern_with_iri_fields() {
        QuadPattern q = QuadPattern.of("?s", "p", "?o", "g1");
        assertTrue(q.s().isVar());
        assertFalse(q.p().isVar());
        assertTrue(q.o().isVar());
        assertEquals("g1", q.c());
    }

    @Test
    void record_equality_by_value() {
        QuadPattern a = QuadPattern.of("?s", "p", "?o", "g");
        QuadPattern b = QuadPattern.of("?s", "p", "?o", "g");
        QuadPattern c = QuadPattern.of("?s", "p", "?x", "g");
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertEquals(a.hashCode(), b.hashCode());
    }

    // ---- isVar(Iri) ----

    @Test
    void isVar_returns_true_for_variable_iri() {
        QuadPattern q = QuadPattern.of("?x", "p", "o", "g");
        assertTrue(q.isVar(q.s()));
        assertFalse(q.isVar(q.p()));
        assertFalse(q.isVar(q.o()));
    }

    @Test
    void isVar_handles_null_input_safely() {
        QuadPattern q = QuadPattern.of("?x", "p", "o", "g");
        assertFalse(q.isVar(null), "null Iri must not be classified as a variable");
    }

    // ---- findVarIdx ----

    @Test
    void findVarIdx_returns_subject_index_for_subject_var() {
        QuadPattern q = QuadPattern.of("?s", "p", "o", "g");
        assertEquals(0, q.findVarIdx("?s"));
    }

    @Test
    void findVarIdx_returns_predicate_index() {
        QuadPattern q = QuadPattern.of("s", "?p", "o", "g");
        assertEquals(1, q.findVarIdx("?p"));
    }

    @Test
    void findVarIdx_returns_object_index() {
        QuadPattern q = QuadPattern.of("s", "p", "?o", "g");
        assertEquals(2, q.findVarIdx("?o"));
    }

    @Test
    void findVarIdx_returns_context_index() {
        QuadPattern q = QuadPattern.of("s", "p", "o", "?g");
        assertEquals(3, q.findVarIdx("?g"));
    }

    @Test
    void findVarIdx_returns_minus_one_for_unknown_var() {
        QuadPattern q = QuadPattern.of("?s", "?p", "?o", "?g");
        assertEquals(-1, q.findVarIdx("?notInPattern"));
    }

    @Test
    void findVarIdx_returns_first_match_when_var_appears_twice() {
        // Same variable in subject AND object — implementation returns subject's index.
        QuadPattern q = QuadPattern.of("?x", "p", "?x", "g");
        assertEquals(0, q.findVarIdx("?x"), "first matching field (subject) wins on tie");
    }

    @Test
    void findVarIdx_handles_null_context() {
        QuadPattern q = new QuadPattern(Iri.of("s"), Iri.of("p"), Iri.of("o"), null);
        assertEquals(-1, q.findVarIdx("?g"), "null context can't match — must return -1, not NPE");
    }

    @Test
    void findVarIdx_distinguishes_constants_from_vars() {
        // A constant named identically to the query string still goes through
        // the equals check on the Iri's value().
        QuadPattern q = QuadPattern.of("s", "p", "o", "g");
        assertEquals(
                0,
                q.findVarIdx("s"),
                "findVarIdx matches by value(), not by isVar() — constants count too");
    }

    @Test
    void context_is_carried_through_record_field() {
        QuadPattern q = QuadPattern.of("s", "p", "o", "my-graph");
        assertEquals("my-graph", q.c());
    }
}
