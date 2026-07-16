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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * The <b>governing invariant</b> of the content-addressed store, in executable form:
 *
 * <blockquote>
 *
 * {@code bytesEqual(encode(a), encode(b))} <b>if and only if</b> {@code a} and {@code b} are the
 * same RDF term (RDF4J {@code Value.equals}).
 *
 * </blockquote>
 *
 * <p>This is a <b>bijection</b> between byte identity and RDF term identity, and it is the single
 * most important correctness property of the codec — "the store must not over- or under-merge":
 *
 * <ul>
 *   <li><b>Forward</b> (byteEqual ⇒ term-equal) forbids <b>over-merge</b>: distinct RDF terms must
 *       never collapse to one content address (which would lose data — see {@code
 *       spec-compliance/semantics/lexical-fidelity.md}).
 *   <li><b>Reverse</b> (term-equal ⇒ byteEqual) forbids <b>under-merge</b>: equal RDF terms must
 *       never get two content addresses (the langString case bug — see {@code
 *       spec-compliance/semantics/canonicalization.md}).
 * </ul>
 *
 * <p>RDF term identity (RDF 1.1 Concepts §3.3, as RDF4J implements it): lexical form and datatype
 * IRI compared character-by-character; language tag lower-cased (the value space of tags is
 * lower-case). A bare {@code "foo"} <em>is</em> {@code "foo"^^xsd:string} (RDF 1.1 abolished plain
 * literals), so those must share one address.
 */
class TermIdentityBijectionTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private static boolean byteEqual(Value a, Value b) {
        try (Arena ar = Arena.ofConfined()) {
            return Arrays.equals(
                    TermEncoder.encode(a, ar).toArray(ValueLayout.JAVA_BYTE),
                    TermEncoder.encode(b, ar).toArray(ValueLayout.JAVA_BYTE));
        }
    }

    /** Equal RDF terms must share one content address (no under-merge). Holds today. */
    @Test
    void no_under_merge__term_equal_implies_byte_equal() {
        // Each pair is a single RDF term written two ways; encode() must agree.
        assertBijection(
                VF.createLiteral("x", "en-US"), VF.createLiteral("x", "en-us")); // tag case (fixed)
        assertBijection(
                VF.createLiteral("foo"),
                VF.createLiteral("foo", XSD.STRING)); // simple == xsd:string
        assertBijection(
                VF.createLiteral("1", XSD.INTEGER),
                VF.createLiteral("1", XSD.INTEGER)); // identical
        assertBijection(
                VF.createIRI("http://ex/a"), VF.createIRI("http://ex/a")); // IRI char-by-char
    }

    /**
     * Distinct RDF terms must get distinct content addresses (no over-merge).
     *
     * <p><b>ENABLED + GREEN (ADR-0043 Phase 2, 2026-06-12).</b> The codec no longer
     * value-canonicalizes: {@code "1"} and {@code "01"} xsd:integer (different RDF terms — RDF 1.1
     * §3.3: "not term-equal because their lexical form differs") now get distinct content
     * addresses, as do the boolean / double / temporal lexical variants below. This is the
     * acceptance gate for ADR-0043 Option A (term-faithful storage); the earlier divergence is
     * pinned, as history, by {@code LexicalFidelityCharacterizationTest}.
     */
    @Test
    void no_over_merge__term_distinct_implies_byte_distinct() {
        assertBijection(VF.createLiteral("1", XSD.INTEGER), VF.createLiteral("01", XSD.INTEGER));
        assertBijection(VF.createLiteral("1", XSD.INTEGER), VF.createLiteral("+1", XSD.INTEGER));
        assertBijection(VF.createLiteral("true", XSD.BOOLEAN), VF.createLiteral("1", XSD.BOOLEAN));
        assertBijection(
                VF.createLiteral("1.0E0", XSD.DOUBLE), VF.createLiteral("1.0e0", XSD.DOUBLE));
        assertBijection(
                VF.createLiteral("2020-01-01T00:00:00Z", XSD.DATETIME),
                VF.createLiteral("2020-01-01T00:00:00+00:00", XSD.DATETIME));
    }

    /** The bijection: byte-equality must match RDF4J term-equality, both directions. */
    private static void assertBijection(Value a, Value b) {
        assertEquals(
                a.equals(b),
                byteEqual(a, b),
                "CAS bijection violated for ["
                        + a
                        + "] vs ["
                        + b
                        + "]: "
                        + "term-equal="
                        + a.equals(b)
                        + " but byte-equal="
                        + byteEqual(a, b));
    }
}
