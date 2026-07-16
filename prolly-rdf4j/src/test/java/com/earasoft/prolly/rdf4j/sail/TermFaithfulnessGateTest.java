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

import com.earasoft.prolly.rdf4j.gen.RdfValueGen;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * THE ACCEPTANCE GATE for "all datatypes faithful" (ADR-0043 Option A, {@code
 * plans/literal-lexical-fidelity.md}). When these tests are green, the content-addressed store
 * satisfies the governing bijection — {@code bytesEqual(encode a, encode b) ⟺ a.equals(b)} — across
 * the <b>entire</b> RDF term space: every datatype is storable (`DTYPE-2`), preserves its exact
 * lexical form (`LEXFID-1`) and datatype IRI (`DTYPE-1`), and round-trips losslessly.
 *
 * <p><b>ENABLED + GREEN (ADR-0043 Phase 2, 2026-06-12).</b> The term-faithful campaign landed:
 * every datatype now stores its verbatim lexical form + exact datatype IRI — built-in tags
 * 1:1-faithful, the long tail (custom datatypes, derived integers, gMonth/duration/…) via the
 * dictionary custom path. This gate's green is the definition of done — it is exhaustive over the
 * datatype list below, so no datatype can be silently left unfaithful. (The earlier divergence is
 * still pinned, as history, by the renamed {@code LexicalFidelityCharacterizationTest} / {@code
 * DatatypeIdentityCharacterizationTest}.)
 */
class TermFaithfulnessGateTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI CUSTOM = VF.createIRI("http://example.org/myDatatype");

    /** A literal as (datatype, lexical form) — the lexical form must round-trip verbatim. */
    private record Case(IRI dt, String lex) {}

    /**
     * Exhaustive datatype coverage: every XSD type the store should accept, plus a custom one, each
     * with lexical variants chosen to expose over-merge if the lexical form were dropped.
     */
    private static List<Case> allCases() {
        List<Case> c = new ArrayList<>();
        // string family (each a DISTINCT datatype; lexical must be verbatim)
        for (IRI dt :
                new IRI[] {
                    XSD.STRING, XSD.NORMALIZEDSTRING, XSD.TOKEN, XSD.NAME, XSD.NCNAME, XSD.NMTOKEN
                }) {
            c.add(new Case(dt, "foo"));
        }
        c.add(new Case(XSD.LANGUAGE, "en-US"));
        c.add(new Case(XSD.ANYURI, "http://example.org/x"));
        // boolean — all four lexical forms are distinct RDF terms
        for (String b : new String[] {"true", "false", "1", "0"}) c.add(new Case(XSD.BOOLEAN, b));
        // integer + lexical variants (leading zero / sign) + the full derived hierarchy
        for (String n : new String[] {"1", "01", "+1", "-1"}) c.add(new Case(XSD.INTEGER, n));
        for (IRI dt :
                new IRI[] {
                    XSD.INT,
                    XSD.LONG,
                    XSD.SHORT,
                    XSD.BYTE,
                    XSD.UNSIGNED_INT,
                    XSD.UNSIGNED_LONG,
                    XSD.NON_NEGATIVE_INTEGER,
                    XSD.POSITIVE_INTEGER,
                    XSD.UNSIGNED_SHORT,
                    XSD.UNSIGNED_BYTE
                }) {
            c.add(new Case(dt, "1"));
        }
        for (IRI dt : new IRI[] {XSD.NEGATIVE_INTEGER, XSD.NON_POSITIVE_INTEGER})
            c.add(new Case(dt, "-1"));
        // decimal — trailing zero (kept today) AND leading-zero/bare-dot (over-merged today)
        for (String d : new String[] {"1.0", "1.00", "01.0", "+1.0", ".5"})
            c.add(new Case(XSD.DECIMAL, d));
        // float / double — exponent + case variants are distinct terms
        c.add(new Case(XSD.FLOAT, "1.5"));
        for (String d : new String[] {"1.5", "1.0E0", "1.0e0"}) c.add(new Case(XSD.DOUBLE, d));
        // temporal — timezone + fractional variants are distinct terms
        c.add(new Case(XSD.DATE, "2020-01-01"));
        c.add(new Case(XSD.TIME, "00:00:00"));
        for (String t :
                new String[] {
                    "2020-01-01T00:00:00Z", "2020-01-01T00:00:00+00:00", "2020-01-01T00:00:00.000Z"
                }) {
            c.add(new Case(XSD.DATETIME, t));
        }
        c.add(new Case(XSD.DATETIMESTAMP, "2020-01-01T00:00:00Z"));
        c.add(new Case(XSD.GYEAR, "2020"));
        c.add(new Case(XSD.GYEARMONTH, "2020-01"));
        c.add(new Case(XSD.GMONTH, "--01"));
        c.add(new Case(XSD.GDAY, "---01"));
        c.add(new Case(XSD.GMONTHDAY, "--01-01"));
        c.add(new Case(XSD.DURATION, "P1Y"));
        c.add(new Case(XSD.DAYTIMEDURATION, "P1D"));
        c.add(new Case(XSD.YEARMONTHDURATION, "P1Y"));
        // binary
        c.add(new Case(XSD.HEXBINARY, "0A"));
        c.add(new Case(XSD.BASE64BINARY, "Zm9v"));
        // custom datatype (must be storable + faithful, not throw)
        c.add(new Case(CUSTOM, "5"));
        c.add(new Case(CUSTOM, "any lexical value"));
        return c;
    }

    /**
     * Exhaustive: every datatype is storable and round-trips to its EXACT lexical form + datatype.
     * Green ⟺ no datatype is left unfaithful — `LEXFID-1` (verbatim lexical), `DTYPE-1` (datatype
     * IRI kept), and `DTYPE-2` (no unsupported-datatype throw) all resolved at once.
     */
    @Test
    void every_datatype_round_trips_exact_lexical_and_datatype() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            IRI p = VF.createIRI("http://ex/p");
            List<Case> cases = allCases();
            for (int i = 0; i < cases.size(); i++) {
                Case cs = cases.get(i);
                IRI s = VF.createIRI("http://ex/s" + i);
                Literal lit = VF.createLiteral(cs.lex(), cs.dt());
                try (SailConnection c = sail.getConnection()) {
                    c.begin();
                    c.addStatement(s, p, lit);
                    c.commit();
                }
                try (SailConnection c = sail.getConnection();
                        CloseableIteration<? extends Statement> it =
                                c.getStatements(s, p, null, false)) {
                    Literal back = (Literal) it.next().getObject();
                    assertEquals(
                            cs.lex(),
                            back.getLabel(),
                            "lexical form must round-trip verbatim for " + cs.dt());
                    assertEquals(
                            cs.dt(),
                            back.getDatatype(),
                            "datatype IRI must round-trip exactly for lexical \""
                                    + cs.lex()
                                    + "\"");
                    assertEquals(
                            lit,
                            back,
                            "round-trip literal must equal the original (RDF term equality)");
                }
            }
        } finally {
            sail.shutDown();
        }
    }

    @Provide
    Arbitrary<Value> objs() {
        return RdfValueGen
                .objectsNoStar(); // IRIs, BNodes, plain/lang/typed literals (no quoted triples)
    }

    /**
     * Breadth: the encoding is a bijection over the generated RDF term distribution — byte-equality
     * matches RDF4J term-equality. Catches any over/under-merge a curated list misses. RED today
     * (over-merge of value-canonicalized datatypes); enable with the fix.
     */
    @Property(tries = 2000)
    void encode_is_bijective_with_term_equality(@ForAll("objs") Value a, @ForAll("objs") Value b) {
        try (Arena ar = Arena.ofConfined()) {
            boolean byteEq =
                    Arrays.equals(
                            TermEncoder.encode(a, ar).toArray(ValueLayout.JAVA_BYTE),
                            TermEncoder.encode(b, ar).toArray(ValueLayout.JAVA_BYTE));
            assertEquals(
                    a.equals(b),
                    byteEq,
                    "CAS bijection: byteEqual must match Value.equals for ["
                            + a
                            + "] vs ["
                            + b
                            + "]");
        }
    }
}
