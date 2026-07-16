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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * CHARACTERIZATION of the lexical-fidelity divergence documented in {@code
 * spec-compliance/semantics/lexical-fidelity.md} ({@code LEXFID-1}).
 *
 * <p>RDF 1.1 literal <em>term</em> equality is lexical (char-by-char on the lexical form and
 * datatype IRI; language tag lower-cased). So two literals with the same value but different
 * lexical form — {@code "1"} vs {@code "01"} of {@code xsd:integer} — are <b>different RDF
 * terms</b> and a faithful store must keep them distinct. The port's codec does not, for the
 * value-canonicalized datatypes: it stores the value and drops the lexical form, over-merging the
 * two terms and changing the lexical form on round-trip.
 *
 * <p><b>This test does not endorse that behavior — it pins it, loudly.</b> It is a characterization
 * in the same spirit as {@code SpocKeyTest}'s signed-vs-unsigned mismatch: the divergence is real,
 * latent, and W3C-invisible, so it is captured here as an explicit contract. The
 * term-faithful-vs-value-canonical decision is open (see the doc's ADR section); if it lands on
 * term-faithful, the {@code assertEquals(1, ...)} lines below flip to {@code 2} and that flip is a
 * conscious, reviewed change rather than a silent drift.
 *
 * <p>Unlike a langString count, this is confound-free: the two inputs have different labels, so
 * {@code Value.equals}/{@code Statement.equals} do not collapse them — a count of 1 is genuine
 * over-merge by the store, not a Set artifact.
 */
class LexicalFidelityCharacterizationTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    /** Insert both literals under one (s, p) and return how many distinct objects survive. */
    private static int distinctStoredObjects(Literal a, Literal b) {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            IRI s = VF.createIRI("http://ex/s");
            IRI p = VF.createIRI("http://ex/p");
            try (SailConnection c = sail.getConnection()) {
                c.begin();
                c.addStatement(s, p, a);
                c.addStatement(s, p, b);
                c.commit();
            }
            try (SailConnection c = sail.getConnection();
                    CloseableIteration<? extends Statement> it =
                            c.getStatements(s, p, null, false)) {
                List<String> labels = new ArrayList<>();
                while (it.hasNext()) labels.add(((Literal) it.next().getObject()).getLabel());
                return labels.size();
            }
        } finally {
            sail.shutDown();
        }
    }

    /** Store one literal, read it back — the faithful round-trip (exact label + datatype IRI). */
    private static Literal storeAndReadBack(Literal lit) {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            IRI s = VF.createIRI("http://ex/s");
            IRI p = VF.createIRI("http://ex/p");
            try (SailConnection c = sail.getConnection()) {
                c.begin();
                c.addStatement(s, p, lit);
                c.commit();
            }
            try (SailConnection c = sail.getConnection();
                    CloseableIteration<? extends Statement> it =
                            c.getStatements(s, p, null, false)) {
                return (Literal) it.next().getObject();
            }
        } finally {
            sail.shutDown();
        }
    }

    /**
     * ADR-0043 corollary (decided 2026-06-12): an ill-typed literal — a lexical form outside the
     * datatype's value space, here {@code "maybe"^^xsd:boolean} — is a valid RDF <em>term</em> and
     * is stored faithfully end-to-end, NOT rejected. The value encoder had to throw (no 0/1); the
     * lexical encoder round-trips it. Write-time lexical validation is deliberately a higher-layer
     * opt-in, not the term store's job (see the ADR's ill-typed-literals corollary).
     */
    @Test
    void ill_typed_boolean_literal_round_trips_faithfully() {
        Literal back = storeAndReadBack(VF.createLiteral("maybe", XSD.BOOLEAN));
        assertEquals("maybe", back.getLabel());
        assertEquals(XSD.BOOLEAN, back.getDatatype());
    }

    // ---- OVER-MERGE: RDF says 2 distinct terms; the store keeps 1 (lexical form lost). ----
    // Each assertEquals(1, ...) documents a current RDF-1.1-term-equality violation.

    @Test
    void integer_leading_zero_distinct() {
        // FIXED — ADR-0043 Step 4a (xsd:integer term-faithful). Was a characterization (asserted
        // 1).
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("1", XSD.INTEGER), VF.createLiteral("01", XSD.INTEGER)),
                "FAITHFUL: \"1\" and \"01\" xsd:integer are distinct RDF terms; stored as 2 distinct terms");
    }

    @Test
    void integer_explicit_plus_distinct() {
        // FIXED — ADR-0043 Step 4a. "+1" and "1" differ char-by-char → distinct terms.
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("1", XSD.INTEGER), VF.createLiteral("+1", XSD.INTEGER)),
                "FAITHFUL: \"1\" and \"+1\" xsd:integer are distinct RDF terms; stored as 2 distinct terms");
    }

    @Test
    void boolean_one_vs_true_distinct() {
        // FIXED — ADR-0043 Phase 1 Step 3 (xsd:boolean term-faithful). Was a characterization of
        // the
        // over-merge (asserted 1); now a regression test asserting the two distinct terms store as
        // 2.
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("true", XSD.BOOLEAN), VF.createLiteral("1", XSD.BOOLEAN)),
                "FAITHFUL: \"true\" and \"1\" xsd:boolean are distinct RDF terms; stored as 2 distinct terms");
    }

    @Test
    void double_exponent_form_distinct() {
        // FIXED — ADR-0043 Step 5 (xsd:double term-faithful). "1.0E0" and "1.0e0" differ
        // char-by-char.
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("1.0E0", XSD.DOUBLE),
                        VF.createLiteral("1.0e0", XSD.DOUBLE)),
                "FAITHFUL: \"1.0E0\" and \"1.0e0\" xsd:double are distinct RDF terms; stored as 2 distinct terms");
    }

    @Test
    void dateTime_timezone_form_distinct() {
        // FIXED — ADR-0043 Step 6 (xsd:dateTime term-faithful). "Z" and "+00:00" denote the same
        // instant but differ char-by-char → distinct RDF terms.
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("2020-01-01T00:00:00Z", XSD.DATETIME),
                        VF.createLiteral("2020-01-01T00:00:00+00:00", XSD.DATETIME)),
                "FAITHFUL: \"Z\" and \"+00:00\" xsd:dateTime are distinct RDF terms; stored as 2 distinct terms");
    }

    // ---- xsd:decimal is now FULLY faithful (ADR-0043 Step 6d). It stores the verbatim lexical
    // bytes
    //      like xsd:string / xsd:anyURI — preserving trailing-zero scale AND leading-zero /
    // explicit-plus /
    //      bare-dot, all of which the old BigDecimal value encoding folded. The "only PARTIALLY
    // faithful"
    //      framing (2026-06-04) is retired here. ----

    @Test
    void decimal_preserves_trailing_zero_scale() {
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("1.0", XSD.DECIMAL),
                        VF.createLiteral("1.00", XSD.DECIMAL)),
                "xsd:decimal keeps trailing zeros — \"1.0\" and \"1.00\" are distinct lexical forms → distinct terms");
    }

    @Test
    void decimal_leading_zero_and_bare_dot_now_distinct() {
        // FIXED (ADR-0043 Step 6d): the old BigDecimal encoding folded
        // BigDecimal("01.0")==BigDecimal("1.0")
        // and ".5"==("0.5"); RDF 1.1 term equality is lexical, so these are DISTINCT terms.
        // Verbatim lexical
        // storage now keeps them distinct (was over-merge: 1 → faithful: 2).
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("1.0", XSD.DECIMAL),
                        VF.createLiteral("01.0", XSD.DECIMAL)),
                "FAITHFUL: \"1.0\" and \"01.0\" xsd:decimal are distinct RDF terms — stored distinct");
        assertEquals(
                2,
                distinctStoredObjects(
                        VF.createLiteral("0.5", XSD.DECIMAL), VF.createLiteral(".5", XSD.DECIMAL)),
                "FAITHFUL: \"0.5\" and \".5\" xsd:decimal are distinct RDF terms — stored distinct");
    }

    // ---- CORRECT: RDF 1.1 abolished plain literals, so a simple literal IS xsd:string. ----

    @Test
    void simple_literal_equals_xsd_string_correctly_merges() {
        assertEquals(
                1,
                distinctStoredObjects(VF.createLiteral("foo"), VF.createLiteral("foo", XSD.STRING)),
                "RDF 1.1: a simple literal IS xsd:string — merging to one term is correct here");
    }
}
