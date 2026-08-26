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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Assertions;

/**
 * THE QUERY-LEVEL DIFFERENTIAL ORACLE (roadmap T5): every generated SPARQL query must produce the
 * same solution BAG on {@code ProllySail} and RDF4J's {@code MemoryStore} over the same dataset.
 * This is the systematic form of what the conformance rounds did one W3C test at a time — the pp35,
 * join-combo, and QueryEvaluationMode defect classes all fall inside its net (and the T4
 * generator's smoke run caught the {@code ?x p* ?x} crash before this class even existed).
 *
 * <p><b>Comparison:</b> unordered bag of solution keys; terms canonicalized by string (the dataset
 * is BNODE-FREE by design — bnode ids differ across stores; see the roadmap's adversarial-review
 * note). Queries carry no LIMIT/ORDER BY (nondeterministic across stores).
 *
 * <p><b>Divergence triage (BINDING — read before "fixing" anything):</b> a divergence is (a) a
 * prolly bug → fix it; promote the failing seed to a fixed-seed regression method first; (b) an
 * UPSTREAM bug with prolly correct (the pp35 precedent — memory store passes some W3C tests by
 * luck) → record the query SHAPE in {@code known-query-divergences.txt} (governed: loaded, counted,
 * capped) with the ruling, and teach the generator to skip it; (c) a generator bug → fix the
 * generator. Never blanket-exclude without a written ruling. The governed file starts EMPTY; its
 * cap lives in this class ({@code KNOWN_DIVERGENCES_MAX}).
 */
class SailQueryDifferentialProperty {

    private static final int KNOWN_DIVERGENCES_MAX =
            2; // ruled SHAPES (header lines of entries); see the file

    @Provide
    Arbitrary<String> queries() {
        return SparqlQueryGen.queries();
    }

    /** Pool-vocabulary, bnode-free dataset: query constants actually match data. */
    @Provide
    Arbitrary<List<String[]>> datasets() {
        Arbitrary<String[]> quad =
                Combinators.combine(
                                Arbitraries.of(SparqlQueryGen.POOL_SUBJECTS.toArray(String[]::new)),
                                Arbitraries.of(
                                        SparqlQueryGen.POOL_PREDICATES.toArray(String[]::new)),
                                Arbitraries.oneOf(
                                        Arbitraries.of(
                                                SparqlQueryGen.POOL_SUBJECTS.toArray(
                                                        String[]::new)),
                                        Arbitraries.of("alpha", "beta", "7", "3.5")),
                                Arbitraries.of(SparqlQueryGen.POOL_GRAPHS.toArray(String[]::new))
                                        .injectNull(0.4))
                        .as((s, p, o, g) -> new String[] {s, p, o, g});
        return quad.list().ofMinSize(8).ofMaxSize(30);
    }

    private static void load(Repository repo, List<String[]> quads) {
        ValueFactory vf = repo.getValueFactory();
        try (RepositoryConnection con = repo.getConnection()) {
            for (String[] q : quads) {
                IRI s = vf.createIRI(q[0]);
                IRI p = vf.createIRI(q[1]);
                Value o =
                        q[2].startsWith(SparqlQueryGen.NS)
                                ? vf.createIRI(q[2])
                                : q[2].matches("\\d+")
                                        ? vf.createLiteral(Integer.parseInt(q[2]))
                                        : q[2].matches("\\d+\\.\\d+")
                                                ? vf.createLiteral(Double.parseDouble(q[2]))
                                                : vf.createLiteral(q[2]);
                if (q[3] == null) {
                    con.add(s, p, o);
                } else {
                    con.add(s, p, o, vf.createIRI(q[3]));
                }
            }
        }
    }

    private static String term(Value v) {
        if (v == null) return "∅";
        if (v.isIRI()) return "I:" + v.stringValue();
        Literal l = (Literal) v; // dataset is bnode-free by construction
        return "L:"
                + l.getLabel()
                + "^^"
                + l.getDatatype().stringValue()
                + l.getLanguage().map(x -> "@" + x).orElse("");
    }

    private static List<String> solutions(Repository repo, String query) {
        List<String> rows = new ArrayList<>();
        try (RepositoryConnection con = repo.getConnection();
                var r = con.prepareTupleQuery(query).evaluate()) {
            List<String> names = new ArrayList<>(r.getBindingNames());
            Collections.sort(names);
            while (r.hasNext()) {
                BindingSet bs = r.next();
                StringBuilder row = new StringBuilder();
                for (String n : names) {
                    row.append(n).append('=').append(term(bs.getValue(n))).append('|');
                }
                rows.add(row.toString());
            }
        }
        Collections.sort(rows);
        return rows;
    }

    /**
     * The one ruled shape (see {@code known-query-divergences.txt}): a zero-length-capable path
     * ({@code *} or {@code ?}) under {@code GRAPH ?g}. MemoryStore's graph-blind path dedup drops
     * per-graph zero-length rows for terms living in several graphs; prolly's per-graph rows are
     * the W3C-pp35-verified semantics, so the REFERENCE is wrong here, not the store under test.
     * Deliberately over-broad (any {@code *}/{@code ?} path inside a variable-graph group).
     */
    private static boolean isKnownDivergentShape(String query) {
        int g = query.indexOf("GRAPH ?g");
        if (g < 0) {
            return false;
        }
        String scope = query.substring(g);
        return scope.contains(">*") || scope.contains(">?");
    }

    /**
     * Ruled shape #2 (see the file): a same-variable zero-length-capable path crashes the REFERENCE
     * (upstream ZeroLengthPathIteration double-binds the shared variable under -ea); prolly
     * evaluates it correctly, so the reference cannot arbitrate.
     */
    private static final java.util.regex.Pattern SAME_VAR_ZERO_PATH =
            java.util.regex.Pattern.compile("(\\?\\w+) <[^>]+>[*?] \\1[ .]");

    private static boolean referenceCrashesOn(String query) {
        return SAME_VAR_ZERO_PATH.matcher(query).find();
    }

    private void diff(List<String[]> quads, String query, ProllySail prollySail) {
        Repository prolly = new SailRepository(prollySail);
        Repository memory = new SailRepository(new MemoryStore());
        prolly.init();
        memory.init();
        try {
            load(prolly, quads);
            load(memory, quads);
            Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () ->
                            assertEquals(
                                    solutions(memory, query),
                                    solutions(prolly, query),
                                    "DIVERGENCE (triage per class javadoc) on query: " + query),
                    () -> "query hung: " + query);
        } finally {
            prolly.shutDown();
            memory.shutDown();
        }
    }

    @Property(tries = 300)
    void prollyAgreesWithMemoryStore(
            @ForAll("datasets") List<String[]> quads, @ForAll("queries") String query) {
        net.jqwik.api.Assume.that(!isKnownDivergentShape(query) && !referenceCrashesOn(query));
        diff(quads, query, new ProllySail());
    }

    /** Roadmap T6: the same oracle with the triejoin routing enabled. */
    @Property(tries = 150)
    void prollyAgreesWithMemoryStoreUnderTriejoin(
            @ForAll("datasets") List<String[]> quads, @ForAll("queries") String query) {
        net.jqwik.api.Assume.that(!isKnownDivergentShape(query) && !referenceCrashesOn(query));
        ProllySail sail = new ProllySail();
        sail.setTriejoinEnabled(true);
        diff(quads, query, sail);
    }

    /** Roadmap T6: and with the bind-join memo enabled. */
    @Property(tries = 150)
    void prollyAgreesWithMemoryStoreUnderMemo(
            @ForAll("datasets") List<String[]> quads, @ForAll("queries") String query) {
        net.jqwik.api.Assume.that(!isKnownDivergentShape(query) && !referenceCrashesOn(query));
        ProllySail sail = new ProllySail();
        sail.setBindJoinMemoEnabled(true);
        diff(quads, query, sail);
    }

    /** The governed divergence file may only grow with a written ruling — cap gate. */
    @Property(tries = 1)
    void knownDivergencesWithinCap() {
        java.io.InputStream in = getClass().getResourceAsStream("/known-query-divergences.txt");
        int lines = 0;
        if (in != null) {
            try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(in))) {
                lines =
                        (int)
                                r.lines()
                                        .filter(
                                                l ->
                                                        !l.isBlank()
                                                                && !l.startsWith("#")
                                                                && !l.startsWith(" "))
                                        .count();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }
        assertEquals(
                KNOWN_DIVERGENCES_MAX,
                lines,
                "known-query-divergences.txt grew — every entry needs a written ruling and a"
                        + " deliberate cap raise");
    }
}
