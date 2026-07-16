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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPARQL-level proof of term-faithful literal storage ({@code
 * prolly-rdf4j/docs/adr/0043-literal-lexical-fidelity.md}, {@code
 * plans/literal-lexical-fidelity.md}).
 *
 * <p>Where {@code TermFaithfulnessGateTest} pins the codec, and {@code
 * LexicalFidelityCharacterizationTest} pins the Sail API ({@code addStatement}/{@code
 * getStatements}), THIS suite drives the <b>full SPARQL stack</b> — parse → encode → store → query
 * → decode → result — to prove that an operator gets back the <em>exact</em> literal they wrote.
 * Each datatype that has gone term-faithful gets two proofs:
 *
 * <ul>
 *   <li><b>exact lexical round-trip</b> — a non-canonical lexical form (a leading zero, an
 *       alternate boolean spelling) survives {@code INSERT DATA} then {@code SELECT} verbatim; a
 *       value encoding would canonicalize it (e.g. {@code "01"} → {@code "1"});
 *   <li><b>no over-merge</b> — two value-equal but lexically-distinct literals are <em>two</em>
 *       triples.
 * </ul>
 *
 * Plus the two dual invariants term-faithfulness must <em>not</em> break: the correct merges still
 * hold (no under-merge — a plain literal still equals its {@code xsd:string} form), and
 * numeric/boolean <em>value</em> semantics are unaffected (the index byte-order is lexical, but
 * {@code FILTER}/{@code ORDER BY} compute the value above the Sail — see also {@code
 * SparqlTest.numeric_order_by_and_filter_are_value_based_not_lexical}).
 *
 * <p><b>Discriminating by construction:</b> every chosen lexical form differs from its canonical
 * value form, so a green result is only possible if NO layer (SPARQL parser, encoder, dictionary,
 * decoder, query engine) canonicalizes. Phase 0 (Steps 1–2) is the gate/contract and has no
 * datatype to round-trip; coverage here starts at the first datatype (Step 3, boolean) and grows
 * with each step.
 */
class TermFaithfulSparqlTest {

    private static final String P = "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> ";
    private Repository repo;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new ProllySail());
        repo.init();
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private void update(String body) {
        try (RepositoryConnection c = repo.getConnection()) {
            c.prepareUpdate(QueryLanguage.SPARQL, P + body).execute();
        }
    }

    /** Every object of {@code <urn:s> <urn:p>}, as {@link Literal}s, fetched via SPARQL SELECT. */
    private List<Literal> objects() {
        List<Literal> out = new ArrayList<>();
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL, "SELECT ?o WHERE { <urn:s> <urn:p> ?o }");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) out.add((Literal) r.next().getValue("o"));
            }
        }
        return out;
    }

    /**
     * INSERT one literal via SPARQL, SELECT it back, assert the exact lexical form + datatype
     * survive.
     */
    private void assertRoundTrip(String literalSparql, String expectLex, IRI expectDatatype) {
        update("INSERT DATA { <urn:s> <urn:p> " + literalSparql + " }");
        List<Literal> objs = objects();
        assertEquals(1, objs.size(), "exactly one object expected");
        assertEquals(
                expectLex,
                objs.get(0).getLabel(),
                "lexical form must round-trip verbatim through SPARQL");
        assertEquals(expectDatatype, objs.get(0).getDatatype(), "datatype IRI must round-trip");
    }

    /**
     * INSERT two literals under one (s,p) via SPARQL, assert they remain distinct triples (no
     * over-merge).
     */
    private void assertDistinct(String litA, String litB) {
        update("INSERT DATA { <urn:s> <urn:p> " + litA + " . <urn:s> <urn:p> " + litB + " }");
        assertEquals(
                2,
                objects().size(),
                litA + " and " + litB + " are distinct RDF terms → two triples");
    }

    // ---- Step 3 — xsd:boolean ----

    @Test
    void boolean_one_round_trips_not_folded_to_true() {
        assertRoundTrip("\"1\"^^xsd:boolean", "1", XSD.BOOLEAN);
    }

    @Test
    void boolean_true_and_one_are_two_triples() {
        assertDistinct("\"true\"^^xsd:boolean", "\"1\"^^xsd:boolean");
    }

    // ---- Step 4a — xsd:integer + xsd:long ----

    @Test
    void integer_leading_zero_round_trips() {
        assertRoundTrip("\"01\"^^xsd:integer", "01", XSD.INTEGER);
    }

    @Test
    void integer_one_and_leading_zero_are_two_triples() {
        assertDistinct("\"1\"^^xsd:integer", "\"01\"^^xsd:integer");
    }

    @Test
    void arbitrary_precision_integer_round_trips() {
        // far over Long range — proves the removed TAG_XSD_INTEGER_BIG is unnecessary under lexical
        // storage
        String big = "123456789012345678901234567890123456789012345678901234567890";
        assertRoundTrip("\"" + big + "\"^^xsd:integer", big, XSD.INTEGER);
    }

    @Test
    void long_leading_zero_round_trips() {
        assertRoundTrip("\"042\"^^xsd:long", "042", XSD.LONG);
    }

    @Test
    void integer_and_long_same_digits_are_two_triples() {
        assertDistinct("\"5\"^^xsd:integer", "\"5\"^^xsd:long");
    }

    // ---- Step 4b — fixed-width integer subtypes ----

    @Test
    void int_leading_zero_round_trips() {
        assertRoundTrip("\"042\"^^xsd:int", "042", XSD.INT);
    }

    @Test
    void int_and_integer_same_digits_are_two_triples() {
        assertDistinct("\"5\"^^xsd:int", "\"5\"^^xsd:integer");
    }

    @Test
    void byte_leading_zero_round_trips() {
        assertRoundTrip("\"042\"^^xsd:byte", "042", XSD.BYTE);
    }

    @Test
    void short_leading_zero_round_trips() {
        assertRoundTrip("\"01234\"^^xsd:short", "01234", XSD.SHORT);
    }

    @Test
    void unsigned_int_leading_zero_round_trips() {
        assertRoundTrip("\"042\"^^xsd:unsignedInt", "042", XSD.UNSIGNED_INT);
    }

    @Test
    void unsigned_long_max_round_trips() {
        assertRoundTrip(
                "\"18446744073709551615\"^^xsd:unsignedLong",
                "18446744073709551615",
                XSD.UNSIGNED_LONG);
    }

    // ---- Step 5 — xsd:double / xsd:float ----

    @Test
    void double_exponent_form_round_trips() {
        assertRoundTrip("\"1.0E0\"^^xsd:double", "1.0E0", XSD.DOUBLE);
    }

    @Test
    void double_exponent_case_is_two_triples() {
        assertDistinct("\"1.0E0\"^^xsd:double", "\"1.0e0\"^^xsd:double");
    }

    @Test
    void double_positive_infinity_round_trips() {
        assertRoundTrip("\"INF\"^^xsd:double", "INF", XSD.DOUBLE);
    }

    @Test
    void double_nan_round_trips() {
        assertRoundTrip("\"NaN\"^^xsd:double", "NaN", XSD.DOUBLE);
    }

    @Test
    void double_negative_zero_round_trips() {
        assertRoundTrip("\"-0.0\"^^xsd:double", "-0.0", XSD.DOUBLE);
    }

    @Test
    void float_exponent_form_round_trips() {
        assertRoundTrip("\"1.0E0\"^^xsd:float", "1.0E0", XSD.FLOAT);
    }

    @Test
    void double_order_by_and_filter_are_value_based_not_lexical() {
        // value semantics over the IEEE-754 path: lexical "0.5" < "10.0" < "2.0" differs from the
        // numeric order 0.5 < 2.0 < 10.0 — so a green here proves ORDER BY/FILTER use the double
        // value.
        update(
                "INSERT DATA { <urn:a> <urn:p> \"0.5\"^^xsd:double . "
                        + "<urn:b> <urn:p> \"2.0\"^^xsd:double . <urn:c> <urn:p> \"10.0\"^^xsd:double }");
        List<Double> ordered = new ArrayList<>();
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL, "SELECT ?n WHERE { ?s <urn:p> ?n } ORDER BY ?n");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) ordered.add(((Literal) r.next().getValue("n")).doubleValue());
            }
        }
        assertEquals(
                List.of(0.5, 2.0, 10.0),
                ordered,
                "ORDER BY ?n must sort by NUMERIC double value, not lexical");

        Set<Double> gtOne = new HashSet<>();
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL,
                            "SELECT ?n WHERE { ?s <urn:p> ?n . FILTER(?n > 1.0) }");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) gtOne.add(((Literal) r.next().getValue("n")).doubleValue());
            }
        }
        assertEquals(
                Set.of(2.0, 10.0), gtOne, "FILTER(?n > 1.0) must compare NUMERIC double value");
    }

    // ---- Step 6 — xsd:dateTime ----

    @Test
    void datetime_round_trips() {
        assertRoundTrip(
                "\"2020-01-01T00:00:00Z\"^^xsd:dateTime", "2020-01-01T00:00:00Z", XSD.DATETIME);
    }

    @Test
    void datetime_z_and_offset_zero_are_two_triples() {
        assertDistinct(
                "\"2020-01-01T00:00:00Z\"^^xsd:dateTime",
                "\"2020-01-01T00:00:00+00:00\"^^xsd:dateTime");
    }

    @Test
    void datetime_timezone_absent_round_trips() {
        // term-faithful: a tz-less dateTime is no longer coerced to "…Z" (the old value-encoding
        // wart)
        assertRoundTrip(
                "\"2020-01-01T00:00:00\"^^xsd:dateTime", "2020-01-01T00:00:00", XSD.DATETIME);
    }

    @Test
    void datetime_fractional_trailing_zeros_round_trip() {
        assertRoundTrip(
                "\"2020-01-01T00:00:00.500Z\"^^xsd:dateTime",
                "2020-01-01T00:00:00.500Z",
                XSD.DATETIME);
    }

    @Test
    void datetime_order_by_is_chronological_not_lexical() {
        // Discriminating: A = 05:00Z (= 05:00 UTC) is chronologically BEFORE B = 00:00-06:00 (=
        // 06:00 UTC),
        // but lexically "…T05:…" > "…T00:…" — the REVERSE. So ORDER BY must sort by the instant
        // (value).
        update(
                "INSERT DATA { <urn:a> <urn:p> \"2020-01-01T05:00:00Z\"^^xsd:dateTime . "
                        + "<urn:b> <urn:p> \"2020-01-01T00:00:00-06:00\"^^xsd:dateTime }");
        List<String> ordered = new ArrayList<>();
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL, "SELECT ?n WHERE { ?s <urn:p> ?n } ORDER BY ?n");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) ordered.add(((Literal) r.next().getValue("n")).getLabel());
            }
        }
        assertEquals(
                List.of("2020-01-01T05:00:00Z", "2020-01-01T00:00:00-06:00"),
                ordered,
                "ORDER BY ?n on dateTime must be CHRONOLOGICAL (by instant), not lexical");
    }

    // ---- Step 6 — calendar types (xsd:date / xsd:gYear / xsd:gYearMonth) ----

    @Test
    void date_round_trips() {
        assertRoundTrip("\"2026-05-12\"^^xsd:date", "2026-05-12", XSD.DATE);
    }

    @Test
    void date_timezone_form_round_trips() {
        // Maximally discriminating: the OLD fixed-width date encoding REJECTED a timezoned date
        // (no tz field). Term-faithful storage keeps the trailing "Z" as verbatim text, so it now
        // survives the full SPARQL stack — proving the rejection is gone end-to-end.
        assertRoundTrip("\"2026-05-12Z\"^^xsd:date", "2026-05-12Z", XSD.DATE);
    }

    @Test
    void date_z_and_no_z_are_two_triples() {
        assertDistinct("\"2026-05-12Z\"^^xsd:date", "\"2026-05-12\"^^xsd:date");
    }

    @Test
    void gyear_leading_zero_round_trips() {
        // "02026" and "2026" are the same gYear value but distinct RDF terms — a value (Int16)
        // encoding would parse to 2026 and re-emit "2026", losing the leading zero.
        assertRoundTrip("\"02026\"^^xsd:gYear", "02026", XSD.GYEAR);
    }

    @Test
    void gyear_five_digit_round_trips() {
        // The OLD Int16 year cap (±32767) could not represent this; lexical storage has no range
        // cap.
        assertRoundTrip("\"99999\"^^xsd:gYear", "99999", XSD.GYEAR);
    }

    @Test
    void gyear_plain_and_leading_zero_are_two_triples() {
        assertDistinct("\"2026\"^^xsd:gYear", "\"02026\"^^xsd:gYear");
    }

    @Test
    void gyearmonth_round_trips() {
        assertRoundTrip("\"2026-05\"^^xsd:gYearMonth", "2026-05", XSD.GYEARMONTH);
    }

    // ---- Step 6 (time) — xsd:time ----

    @Test
    void time_round_trips() {
        assertRoundTrip("\"12:30:00\"^^xsd:time", "12:30:00", XSD.TIME);
    }

    @Test
    void time_timezone_form_round_trips() {
        // The OLD value encoding canonicalized the timezone away; lexical storage keeps it
        // verbatim.
        assertRoundTrip("\"12:30:00+05:30\"^^xsd:time", "12:30:00+05:30", XSD.TIME);
    }

    @Test
    void time_z_and_offset_zero_are_two_triples() {
        assertDistinct("\"12:30:00Z\"^^xsd:time", "\"12:30:00+00:00\"^^xsd:time");
    }

    @Test
    void time_fractional_trailing_zeros_round_trip() {
        // the old value->XSD_TIME-formatter path trimmed ".500" to ".5"; verbatim storage keeps it
        assertRoundTrip("\"12:30:00.500Z\"^^xsd:time", "12:30:00.500Z", XSD.TIME);
    }

    @Test
    void time_order_by_is_value_based_not_lexical() {
        // Discriminating: A = 05:00Z (= 05:00 UTC) is chronologically BEFORE B = 00:00-06:00 (=
        // 06:00
        // UTC), but lexically "05:…" > "00:…" — the REVERSE. A green here proves ORDER BY on
        // xsd:time
        // compares the timezone-normalized value, not the stored lexical bytes.
        update(
                "INSERT DATA { <urn:a> <urn:p> \"05:00:00Z\"^^xsd:time . "
                        + "<urn:b> <urn:p> \"00:00:00-06:00\"^^xsd:time }");
        List<String> ordered = new ArrayList<>();
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL, "SELECT ?n WHERE { ?s <urn:p> ?n } ORDER BY ?n");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) ordered.add(((Literal) r.next().getValue("n")).getLabel());
            }
        }
        assertEquals(
                List.of("05:00:00Z", "00:00:00-06:00"),
                ordered,
                "ORDER BY ?n on xsd:time must be by the timezone-normalized value, not lexical");
    }

    // ---- Step 6a — custom-datatype literals (DTYPE-2): every datatype storable, via the general
    // path ----

    @Test
    void custom_datatype_literal_round_trips() {
        // The datatype IRI has no dedicated tag — it is interned in the dictionary and stored as a
        // TermId beside the verbatim lexical. Both must come back exactly. This used to THROW
        // ("unsupported datatype") at the write path; now it round-trips.
        assertRoundTrip(
                "\"hello\"^^<http://example.org/myType>",
                "hello",
                Values.iri("http://example.org/myType"));
    }

    @Test
    void custom_datatype_preserves_exact_lexical() {
        // "007" would be canonicalized to "7" by a value (integer) encoding; under the custom path
        // it
        // round-trips verbatim under its own datatype IRI — discriminating that nothing
        // canonicalizes.
        assertRoundTrip(
                "\"007\"^^<http://example.org/code>", "007", Values.iri("http://example.org/code"));
    }

    @Test
    void custom_and_xsd_string_same_label_are_two_triples() {
        // "x"^^ex:t and "x"^^xsd:string are distinct RDF terms (different datatype IRIs) → two
        // triples.
        assertDistinct("\"x\"^^<http://example.org/t>", "\"x\"^^xsd:string");
    }

    @Test
    void two_custom_datatypes_same_label_are_two_triples() {
        assertDistinct("\"x\"^^<http://example.org/t1>", "\"x\"^^<http://example.org/t2>");
    }

    // ---- Step 6b — derived-integer subtypes preserve their datatype IRI (DTYPE-1) ----

    @Test
    void derived_nonNegativeInteger_preserves_its_iri() {
        // "5"^^xsd:nonNegativeInteger used to collapse to xsd:integer (DTYPE-1, datatype IRI lost).
        // The
        // Sail now routes it through the custom path (isDedicatedDatatype excludes it) so the exact
        // IRI
        // round-trips — even though the codec's Dictionary-less fallback would still collapse it.
        assertRoundTrip("\"5\"^^xsd:nonNegativeInteger", "5", XSD.NON_NEGATIVE_INTEGER);
    }

    @Test
    void derived_negativeInteger_preserves_its_iri() {
        assertRoundTrip("\"-5\"^^xsd:negativeInteger", "-5", XSD.NEGATIVE_INTEGER);
    }

    @Test
    void derived_integer_and_xsd_integer_same_label_are_two_triples() {
        // The DTYPE-1 over-merge, fixed: "5"^^xsd:nonNegativeInteger and "5"^^xsd:integer are
        // distinct
        // RDF terms (different datatype IRIs) → two triples, not one.
        assertDistinct("\"5\"^^xsd:nonNegativeInteger", "\"5\"^^xsd:integer");
    }

    // ---- Step 6d — xsd:decimal (full faithfulness: leading-zero / bare-dot / explicit-plus) ----

    @Test
    void decimal_leading_zero_round_trips() {
        // "01.0" would be canonicalized to "1.0" by the old BigDecimal value encoding; lexical
        // keeps it verbatim.
        assertRoundTrip("\"01.0\"^^xsd:decimal", "01.0", XSD.DECIMAL);
    }

    @Test
    void decimal_bare_dot_round_trips() {
        assertRoundTrip("\".5\"^^xsd:decimal", ".5", XSD.DECIMAL);
    }

    @Test
    void decimal_leading_zero_and_canonical_are_two_triples() {
        // The PARTIAL over-merge fixed (Step 6d): "01.0" and "1.0" are the same value but distinct
        // terms.
        assertDistinct("\"01.0\"^^xsd:decimal", "\"1.0\"^^xsd:decimal");
    }

    @Test
    void decimal_order_by_is_value_based_not_lexical() {
        // lexical "0.5" < "10.0" < "2.0" differs from numeric 0.5 < 2.0 < 10.0 — green proves
        // value-based.
        update(
                "INSERT DATA { <urn:a> <urn:p> \"0.5\"^^xsd:decimal . "
                        + "<urn:b> <urn:p> \"2.0\"^^xsd:decimal . <urn:c> <urn:p> \"10.0\"^^xsd:decimal }");
        List<String> ordered = new ArrayList<>();
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL, "SELECT ?n WHERE { ?s <urn:p> ?n } ORDER BY ?n");
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) ordered.add(((Literal) r.next().getValue("n")).getLabel());
            }
        }
        assertEquals(
                List.of("0.5", "2.0", "10.0"),
                ordered,
                "ORDER BY ?n on xsd:decimal must sort by NUMERIC value, not lexical");
    }

    // ---- Dual invariant — NO under-merge (the correct merges must still hold) ----

    @Test
    void plain_literal_and_xsd_string_are_one_triple() {
        // RDF 1.1 §3.3: a plain literal "foo" IS "foo"^^xsd:string — the same RDF term → ONE
        // triple.
        update("INSERT DATA { <urn:s> <urn:p> \"foo\" . <urn:s> <urn:p> \"foo\"^^xsd:string }");
        assertEquals(1, objects().size(), "\"foo\" and \"foo\"^^xsd:string are the same RDF term");
    }

    // ---- Dual invariant — VALUE semantics unaffected by lexical storage ----

    @Test
    void boolean_effective_value_of_one_is_true() {
        // EBV of "1"^^xsd:boolean is true (value semantics) even though it is stored lexically as
        // "1".
        update("INSERT DATA { <urn:s> <urn:p> \"1\"^^xsd:boolean }");
        boolean kept;
        try (RepositoryConnection c = repo.getConnection()) {
            TupleQuery q =
                    c.prepareTupleQuery(
                            QueryLanguage.SPARQL,
                            "SELECT ?o WHERE { <urn:s> <urn:p> ?o . FILTER(?o) }");
            try (TupleQueryResult r = q.evaluate()) {
                kept = r.hasNext();
            }
        }
        assertTrue(
                kept, "FILTER(?o) keeps \"1\"^^xsd:boolean — its effective boolean value is true");
    }
}
