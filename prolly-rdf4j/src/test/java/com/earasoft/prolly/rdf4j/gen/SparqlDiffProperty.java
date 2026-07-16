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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.AfterTry;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Phase 1 Step 6 of {@code prolly-rdf4j-test-strategy.md} — <b>differential SPARQL</b> (S-2). A
 * generated dataset over a small shared vocabulary is loaded into both Sails; for each of six query
 * shapes — plain BGP, bound-predicate, 2-hop join, {@code OPTIONAL}, {@code FILTER}, {@code GRAPH}
 * — the {@code SELECT} binding <b>multiset</b> must equal RDF4J {@code MemoryStore}'s (so a
 * cardinality, OPTIONAL-null, or graph-scoping divergence is caught). The small vocabulary
 * (constants drawn from it) makes joins / filters / graph scopes bind non-trivially. jqwik shrinks
 * any mismatch to a minimal dataset.
 *
 * <p>Both Sails evaluate via RDF4J's algebra over their {@code getStatements}; Step 5 proved that
 * BGP layer equal, so a divergence here points at the SPARQL composition (OPTIONAL/FILTER/GRAPH)
 * over the prolly indexes.
 */
class SparqlDiffProperty {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final String NS = "urn:test:";
    private static final IRI[] S = {iri("s0"), iri("s1"), iri("s2")};
    private static final IRI[] P = {iri("p0"), iri("p1")};
    private static final Value[] O = {
        iri("s0"), iri("s1"), VF.createLiteral("x"), VF.createLiteral("y")
    };
    private static final Resource[] G = {null, iri("g1"), iri("g2")}; // null = default graph

    private final List<Path> tempDirs = new ArrayList<>();

    @Property(tries = 120)
    void sparqlBindingsAgree(
            @ForAll @From("datasets") List<int[]> quads,
            @ForAll @IntRange(min = 0, max = 5) int shape)
            throws IOException {
        Path dir = Files.createTempDirectory("sparql-diff-");
        tempDirs.add(dir);
        try (SailDifferentialHarness h = new SailDifferentialHarness(dir)) {
            for (int[] q : quads) {
                Statement st =
                        G[q[3]] == null
                                ? VF.createStatement(S[q[0]], P[q[1]], O[q[2]])
                                : VF.createStatement(S[q[0]], P[q[1]], O[q[2]], G[q[3]]);
                h.apply(new Op(Op.Kind.ADD, st, null));
            }
            h.flush();

            String query = query(shape);
            assertTrue(
                    h.bindingsAgree(query),
                    () ->
                            "SPARQL bindings diverged for shape "
                                    + shape
                                    + ":\n"
                                    + query
                                    + "\nover "
                                    + quads.size()
                                    + " quads");
        }
    }

    private static String query(int shape) {
        String p0 = "<" + NS + "p0>", p1 = "<" + NS + "p1>", g1 = "<" + NS + "g1>";
        return switch (shape) {
            case 0 -> "SELECT ?s ?p ?o WHERE { ?s ?p ?o }";
            case 1 -> "SELECT ?s ?o WHERE { ?s " + p0 + " ?o }";
            case 2 -> "SELECT ?s ?o WHERE { ?s " + p0 + " ?mid . ?mid " + p1 + " ?o }";
            case 3 -> "SELECT ?s ?o WHERE { ?s " + p0 + " ?x OPTIONAL { ?x " + p1 + " ?o } }";
            case 4 -> "SELECT ?s ?o WHERE { ?s " + p0 + " ?o FILTER(isLiteral(?o)) }";
            default -> "SELECT ?s ?o WHERE { GRAPH " + g1 + " { ?s " + p0 + " ?o } }";
        };
    }

    @Provide
    Arbitrary<List<int[]>> datasets() {
        Arbitrary<int[]> quad =
                Combinators.combine(
                                Arbitraries.integers().between(0, S.length - 1),
                                Arbitraries.integers().between(0, P.length - 1),
                                Arbitraries.integers().between(0, O.length - 1),
                                Arbitraries.integers().between(0, G.length - 1))
                        .as((s, p, o, g) -> new int[] {s, p, o, g});
        return quad.list().ofMinSize(0).ofMaxSize(25);
    }

    private static IRI iri(String local) {
        return VF.createIRI(NS + local);
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
        tempDirs.clear();
    }
}
