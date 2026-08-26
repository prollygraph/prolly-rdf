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

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Bounded random SPARQL SELECT queries for the query-level differential oracle (roadmap T4).
 * Shapes: BGP joins, OPTIONAL, UNION, GRAPH ?g / GRAPH &lt;g&gt;, FILTER (comparisons including
 * incomparable-type shapes, BOUND), property paths ({@code *}, {@code +}, {@code ?}, {@code ^},
 * {@code /}), DISTINCT, projected-var subsets — ≤4 triple patterns, ≤2 operator nestings.
 *
 * <p><b>Deliberate v1 exclusions (adversarial review of the roadmap):</b> NO {@code LIMIT} — over
 * unordered solutions it is nondeterministic ACROSS stores, a false-positive machine for the oracle
 * (v2 may compare count≤n plus subset-of-unlimited); NO {@code ORDER BY} (same class); and the
 * oracle's DATASET must contain NO BNODES (ids differ across stores) — see the oracle's dataset
 * generator, which draws from {@link #POOL} so constants actually match data.
 *
 * <p>The vocabulary is a SMALL FIXED POOL ({@code urn:qg:*}): random wide IRIs would make every
 * constant miss every triple, testing nothing but empty results.
 */
public final class SparqlQueryGen {

    private SparqlQueryGen() {}

    /** The shared query/data vocabulary: queries and oracle datasets draw from exactly this. */
    public static final String NS = "urn:qg:";

    public static final List<String> POOL_SUBJECTS =
            List.of(NS + "s1", NS + "s2", NS + "s3", NS + "s4", NS + "s5");
    public static final List<String> POOL_PREDICATES =
            List.of(NS + "p1", NS + "p2", NS + "p3", NS + "p4");
    public static final List<String> POOL_GRAPHS = List.of(NS + "g1", NS + "g2");
    public static final List<String> POOL_LITERALS =
            List.of(
                    "\"alpha\"",
                    "\"beta\"",
                    "\"7\"",
                    "\"2002\"^^<http://www.w3.org/2001/XMLSchema#string>",
                    "7",
                    "3.5");

    private static final List<String> VARS = List.of("?v0", "?v1", "?v2", "?v3", "?v4");

    private static Arbitrary<String> var() {
        return Arbitraries.of(VARS.toArray(String[]::new));
    }

    private static Arbitrary<String> subjectTerm() {
        return Arbitraries.oneOf(
                var(),
                Arbitraries.of(POOL_SUBJECTS.toArray(String[]::new)).map(i -> "<" + i + ">"));
    }

    private static Arbitrary<String> objectTerm() {
        return Arbitraries.oneOf(
                var(),
                Arbitraries.of(POOL_SUBJECTS.toArray(String[]::new)).map(i -> "<" + i + ">"),
                Arbitraries.of(POOL_LITERALS.toArray(String[]::new)));
    }

    /** Predicate position: a constant, a variable, or a small property path over constants. */
    private static Arbitrary<String> predicateTerm() {
        Arbitrary<String> constant =
                Arbitraries.of(POOL_PREDICATES.toArray(String[]::new)).map(i -> "<" + i + ">");
        Arbitrary<String> path =
                Combinators.combine(
                                constant, constant, Arbitraries.of("*", "+", "?", "^", "/", "|"))
                        .as(
                                (a, b, op) ->
                                        switch (op) {
                                            case "^" -> "^" + a;
                                            case "/" -> a + "/" + b;
                                            case "|" -> a + "|" + b;
                                            default -> a + op;
                                        });
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, constant),
                net.jqwik.api.Tuple.of(2, var()),
                net.jqwik.api.Tuple.of(3, path));
    }

    private static Arbitrary<String> triplePattern() {
        return Combinators.combine(subjectTerm(), predicateTerm(), objectTerm())
                .as((s, p, o) -> s + " " + p + " " + o + " .");
    }

    private static Arbitrary<String> bgp() {
        return triplePattern().list().ofMinSize(1).ofMaxSize(3).map(l -> String.join(" ", l));
    }

    private static Arbitrary<String> filter() {
        Arbitrary<String> comparison =
                Combinators.combine(
                                var(),
                                Arbitraries.of("=", "!=", "<", ">", "<=", ">="),
                                Arbitraries.oneOf(
                                        var(),
                                        Arbitraries.of(POOL_LITERALS.toArray(String[]::new)),
                                        Arbitraries.of(POOL_SUBJECTS.toArray(String[]::new))
                                                .map(i -> "<" + i + ">")))
                        .as((a, op, b) -> "FILTER(" + a + " " + op + " " + b + ")");
        Arbitrary<String> bound = var().map(v -> "FILTER(BOUND(" + v + "))");
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, comparison), net.jqwik.api.Tuple.of(1, bound));
    }

    /** A group pattern with bounded operator nesting. */
    private static Arbitrary<String> group(int depth) {
        Arbitrary<String> base = bgp();
        if (depth <= 0) {
            return base;
        }
        Arbitrary<String> inner = group(depth - 1);
        Arbitrary<String> optional =
                Combinators.combine(base, inner).as((a, b) -> a + " OPTIONAL { " + b + " }");
        Arbitrary<String> union =
                Combinators.combine(inner, inner).as((a, b) -> "{ " + a + " } UNION { " + b + " }");
        Arbitrary<String> graphVar = inner.map(g -> "GRAPH ?g { " + g + " }");
        Arbitrary<String> graphConst =
                Combinators.combine(Arbitraries.of(POOL_GRAPHS.toArray(String[]::new)), inner)
                        .as((g, b) -> "GRAPH <" + g + "> { " + b + " }");
        Arbitrary<String> filtered = Combinators.combine(base, filter()).as((a, f) -> a + " " + f);
        Arbitrary<String> joined = Combinators.combine(base, inner).as((a, b) -> a + " " + b);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, base),
                net.jqwik.api.Tuple.of(2, optional),
                net.jqwik.api.Tuple.of(2, union),
                net.jqwik.api.Tuple.of(2, graphVar),
                net.jqwik.api.Tuple.of(1, graphConst),
                net.jqwik.api.Tuple.of(2, filtered),
                net.jqwik.api.Tuple.of(2, joined));
    }

    /** A complete SELECT query. */
    public static Arbitrary<String> queries() {
        return Combinators.combine(
                        Arbitraries.of("SELECT ", "SELECT DISTINCT "),
                        Arbitraries.oneOf(
                                Arbitraries.just("*"),
                                Arbitraries.of(VARS.toArray(String[]::new))
                                        .list()
                                        .ofMinSize(1)
                                        .ofMaxSize(3)
                                        .map(
                                                l ->
                                                        String.join(
                                                                " ",
                                                                new java.util.LinkedHashSet<>(l)))),
                        group(2))
                .as((sel, proj, g) -> sel + proj + " WHERE { " + g + " }");
    }
}
