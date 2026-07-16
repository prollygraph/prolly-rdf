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
package com.earasoft.prolly.rdf4j.gen;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;

/**
 * Phase 0 Step 2 of {@code prolly-rdf4j-test-strategy.md} — the shared jqwik generators for RDF4J
 * {@link Value}s (D-3). These are the substrate the whole plan reuses: the differential oracle
 * (S-2), term-codec round-trip (S-3), context isolation (S-5), versioning (S-6), and indexing (S-7)
 * all draw from here.
 *
 * <p>Values are built with a vendor-neutral {@link SimpleValueFactory} so the same objects can be
 * added to both {@code ProllySail} and the RDF4J {@code MemoryStore} reference. Generated values
 * are deliberately <b>encodable</b> — valid lexical forms, timezone-bearing temporals, bounded
 * sizes — so op-streams don't trip the documented conformance gaps (timezone- absent temporal,
 * ill-typed lexical forms, &gt;64KB literals); those gaps are exercised as explicit negative cases
 * in Phase 8, not smuggled in here.
 *
 * <p>Coverage includes the boundary values the plan calls out: ±INF/NaN doubles, {@code
 * Long.MIN/MAX}, far-future dates, and big integers/decimals.
 */
public final class RdfValueGen {

    public static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String NS = "urn:test:";

    private RdfValueGen() {}

    // ---- IRIs / BNodes ----------------------------------------------------

    /** IRIs over a small namespace, including some odd-but-legal local names. */
    public static Arbitrary<IRI> iris() {
        Arbitrary<String> plain =
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> odd =
                Arbitraries.of("a-b", "x.y", "n42", "Mixed_Case", "p~tilde", "q%20enc");
        return Arbitraries.oneOf(plain, odd).map(local -> VF.createIRI(NS + local));
    }

    public static Arbitrary<BNode> bnodes() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(8)
                .map(id -> VF.createBNode("b" + id));
    }

    // ---- Literals ---------------------------------------------------------

    public static Arbitrary<Literal> plainLiterals() {
        return labels().map(VF::createLiteral);
    }

    /**
     * {@code rdf:langString} with a BCP-47-ish tag, in <b>canonical lower case</b>. RDF 1.1 §3.3
     * puts the language tag in lower case in the value space, and this codec canonicalizes it so
     * value-equal langStrings share one content address (commit 80288bde). Generating canonical
     * tags keeps the store-<i>parity</i> differential tests (which compare ProllySail against
     * RDF4J's case-<i>preserving</i> MemoryStore) comparing on inputs where the two agree — a
     * mixed-case tag would diverge by design (ProllySail canonicalizes; MemoryStore does not). That
     * canonicalization is asserted directly by {@code
     * TermCodecCanonicalizationTest.mixedCaseLanguageTag_canonicalizesToLowerCase}, not here.
     */
    public static Arbitrary<Literal> langLiterals() {
        Arbitrary<String> tag = Arbitraries.of("en", "en-us", "fr", "de", "ja", "pt-br", "zh-hans");
        return Combinators.combine(labels(), tag).as(VF::createLiteral);
    }

    /** One typed literal per representative supported datatype, with boundaries. */
    public static Arbitrary<Literal> typedLiterals() {
        return Arbitraries.oneOf(
                List.of(
                        Arbitraries.of("true", "false").map(s -> VF.createLiteral(s, XSD.BOOLEAN)),
                        Arbitraries.bytes().map(b -> VF.createLiteral(Byte.toString(b), XSD.BYTE)),
                        Arbitraries.shorts()
                                .map(s -> VF.createLiteral(Short.toString(s), XSD.SHORT)),
                        Arbitraries.integers()
                                .map(i -> VF.createLiteral(Integer.toString(i), XSD.INT)),
                        Arbitraries.longs().map(l -> VF.createLiteral(Long.toString(l), XSD.LONG)),
                        bigInts().map(b -> VF.createLiteral(b.toString(), XSD.INTEGER)),
                        bigDecimals().map(d -> VF.createLiteral(d.toPlainString(), XSD.DECIMAL)),
                        doubleLabels().map(s -> VF.createLiteral(s, XSD.DOUBLE)),
                        floatLabels().map(s -> VF.createLiteral(s, XSD.FLOAT)),
                        dateTimes().map(s -> VF.createLiteral(s, XSD.DATETIME)),
                        dates().map(s -> VF.createLiteral(s, XSD.DATE)),
                        times().map(s -> VF.createLiteral(s, XSD.TIME)),
                        labels().map(s -> VF.createLiteral(s, XSD.STRING)),
                        Arbitraries.of("urn:x:a", "http://example.org/p", "mailto:x@y.z")
                                .map(s -> VF.createLiteral(s, XSD.ANYURI))));
    }

    public static Arbitrary<Literal> literals() {
        return Arbitraries.oneOf(plainLiterals(), langLiterals(), typedLiterals());
    }

    // ---- terms / RDF-star -------------------------------------------------

    /** A subject/object resource: IRI or BNode. */
    public static Arbitrary<Resource> resources() {
        return Arbitraries.oneOf(iris().map(i -> i), bnodes().map(b -> b));
    }

    /** RDF-star quoted triple (one level deep — components are plain terms). */
    public static Arbitrary<Triple> quotedTriples() {
        return Combinators.combine(resources(), iris(), objectsNoStar()).as(VF::createTriple);
    }

    /** Object position WITHOUT nested RDF-star (to bound recursion). */
    public static Arbitrary<Value> objectsNoStar() {
        return Arbitraries.oneOf(
                iris().map(v -> (Value) v), bnodes().map(v -> v), literals().map(v -> v));
    }

    /** Any object: term or quoted triple. */
    public static Arbitrary<Value> objects() {
        return Arbitraries.oneOf(objectsNoStar(), quotedTriples().map(t -> (Value) t));
    }

    /**
     * Objects whose lexical form is <b>preserved identically</b> by both Sails: IRIs, BNodes, plain
     * strings, lang strings. Excludes typed literals (whose lexical may be canonicalized by
     * ProllySail's typed encoding — that fidelity is S-3 / Step 8, not structural equivalence) and
     * RDF-star. Used by the differential oracle so a mismatch means a real structural divergence.
     */
    public static Arbitrary<Value> stableObjects() {
        return Arbitraries.oneOf(
                iris().map(v -> (Value) v), bnodes().map(v -> v),
                plainLiterals().map(v -> v), langLiterals().map(v -> v));
    }

    /** Any subject: resource or quoted triple (RDF-star). */
    public static Arbitrary<Resource> subjects() {
        return Arbitraries.oneOf(resources(), quotedTriples().map(t -> (Resource) t));
    }

    // ---- building blocks --------------------------------------------------

    private static Arbitrary<String> labels() {
        // Printable, bounded (well under the 64KB tuple cap), may be empty.
        return Arbitraries.strings()
                .ofMinLength(0)
                .ofMaxLength(64)
                .filter(s -> s.chars().allMatch(c -> c >= 0x20 && c != 0x7f));
    }

    private static Arbitrary<BigInteger> bigInts() {
        return Arbitraries.bigIntegers()
                .between(
                        BigInteger.valueOf(Long.MIN_VALUE).multiply(BigInteger.TEN),
                        BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN));
    }

    private static Arbitrary<BigDecimal> bigDecimals() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-1E12"), new BigDecimal("1E12"))
                .ofScale(6);
    }

    private static Arbitrary<String> doubleLabels() {
        Arbitrary<String> normal = Arbitraries.doubles().map(d -> Double.toString(d));
        Arbitrary<String> special = Arbitraries.of("INF", "-INF", "NaN", "0.0E0", "-0.0E0");
        return Arbitraries.oneOf(normal, special);
    }

    private static Arbitrary<String> floatLabels() {
        Arbitrary<String> normal = Arbitraries.floats().map(f -> Float.toString(f));
        Arbitrary<String> special = Arbitraries.of("INF", "-INF", "NaN");
        return Arbitraries.oneOf(normal, special);
    }

    /** {@code xsd:dateTime}, always timezone-bearing (Z), year 1..9999. */
    private static Arbitrary<String> dateTimes() {
        return Combinators.combine(years(), months(), days(), hours(), minutes(), seconds())
                .as(
                        (y, mo, d, h, mi, s) ->
                                String.format(
                                        "%04d-%02d-%02dT%02d:%02d:%02dZ", y, mo, d, h, mi, s));
    }

    /**
     * {@code xsd:date}, timezone-absent. (Term-faithful storage now accepts a timezoned date too —
     * ADR-0043 Step 6; this generator stays tz-absent so the store-parity differential oracle keeps
     * comparing ProllySail against RDF4J's MemoryStore on inputs where both agree.)
     */
    private static Arbitrary<String> dates() {
        return Combinators.combine(years(), months(), days())
                .as((y, mo, d) -> String.format("%04d-%02d-%02d", y, mo, d));
    }

    private static Arbitrary<String> times() {
        return Combinators.combine(hours(), minutes(), seconds())
                .as((h, mi, s) -> String.format("%02d:%02d:%02dZ", h, mi, s));
    }

    // Year ≤ 5000 for generator sanity + readable boundary coverage. (The old Int48 epoch-ms cap
    // ~year 6429 is GONE — temporals are verbatim lexical now, ADR-0043 Step 6, so any year stores;
    // this bound is a generator choice, not an encoding limit.)
    private static Arbitrary<Integer> years() {
        return Arbitraries.integers().between(1, 5000);
    }

    private static Arbitrary<Integer> months() {
        return Arbitraries.integers().between(1, 12);
    }

    private static Arbitrary<Integer> days() {
        return Arbitraries.integers().between(1, 28);
    }

    private static Arbitrary<Integer> hours() {
        return Arbitraries.integers().between(0, 23);
    }

    private static Arbitrary<Integer> minutes() {
        return Arbitraries.integers().between(0, 59);
    }

    private static Arbitrary<Integer> seconds() {
        return Arbitraries.integers().between(0, 59);
    }
}
