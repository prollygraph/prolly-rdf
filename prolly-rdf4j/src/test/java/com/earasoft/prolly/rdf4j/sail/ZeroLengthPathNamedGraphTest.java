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
import java.util.Collections;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the graph-scoped zero-length-path walk ({@link GraphScopedZeroLengthPathIteration} via
 * {@link ProllyDefaultEvaluationStrategy}) — the W3C {@code (pp35) Named Graph 2} defect class,
 * reproduced independently of the compliance harness.
 *
 * <p>The trap: a vertex living in SEVERAL named graphs. Upstream's zero-length walk deduplicates
 * vertices in one global set across an unbound {@code GRAPH ?g}, so the vertex's zero-length row
 * survives only for whichever graph the statement scan surfaces first — an enumeration-order
 * accident (RDF4J's memory store passes W3C pp35 by insertion order; this store's content-addressed
 * ordering surfaced the other graph and lost the row). The fix keys the dedup on {@code (vertex,
 * graph)}: one zero-length row per graph a vertex inhabits, order-independent — SPARQL 1.1's
 * per-active-graph semantics.
 */
class ZeroLengthPathNamedGraphTest {

    private SailRepository repo;
    private IRI g1;
    private IRI g2;
    private IRI a;
    private IRI b;
    private IRI c;
    private IRI p1;
    private IRI p2;

    @BeforeEach
    void setUp() {
        repo = new SailRepository(new ProllySail());
        repo.init();
        ValueFactory vf = repo.getValueFactory();
        g1 = vf.createIRI("urn:g1");
        g2 = vf.createIRI("urn:g2");
        a = vf.createIRI("urn:a");
        b = vf.createIRI("urn:b");
        c = vf.createIRI("urn:c");
        p1 = vf.createIRI("urn:p1");
        p2 = vf.createIRI("urn:p2");
        try (SailRepositoryConnection con = repo.getConnection()) {
            // THE trap shape: :a inhabits BOTH graphs, so an order-dependent
            // global dedup loses one graph's zero-length row for it.
            con.add(a, p1, b, g1);
            con.add(a, p2, c, g2);
        }
    }

    @AfterEach
    void tearDown() {
        repo.shutDown();
    }

    private List<String> tBindings(String query) {
        List<String> out = new ArrayList<>();
        try (SailRepositoryConnection con = repo.getConnection();
                TupleQueryResult r =
                        con.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (r.hasNext()) {
                BindingSet bs = r.next();
                out.add(
                        (bs.hasBinding("g") ? bs.getValue("g").stringValue() + "|" : "")
                                + bs.getValue("t").stringValue());
            }
        }
        Collections.sort(out);
        return out;
    }

    /** The pp35 shape: FILTER pins ?g to one graph; the shared vertex's row must survive. */
    @Test
    void sharedVertexKeepsItsZeroLengthRowInEveryGraph_filtered() {
        List<String> got =
                tBindings(
                        "SELECT ?t WHERE { GRAPH ?g { ?s <urn:p1>* ?t } FILTER (?g = <urn:g1>) }");
        // g1 holds {a p1 b}: zero-length rows for vertices a and b, plus the one-step a->b.
        assertEquals(List.of("urn:a", "urn:b", "urn:b"), got, "t rows for g1 (sorted)");
    }

    /** Unfiltered: each graph contributes its OWN node set — one row per (vertex, graph). */
    @Test
    void zeroLengthRowsArePerGraphNotFirstGraphWins() {
        List<String> got = tBindings("SELECT ?g ?t WHERE { GRAPH ?g { ?s <urn:p1>* ?t } }");
        List<String> expected =
                new ArrayList<>(
                        List.of(
                                "urn:g1|urn:a", // zero-length in g1
                                "urn:g1|urn:b", // zero-length in g1
                                "urn:g1|urn:b", // the one-step a -p1-> b
                                "urn:g2|urn:a", // zero-length in g2 — the row upstream's global
                                // dedup loses
                                "urn:g2|urn:c")); // zero-length in g2
        Collections.sort(expected);
        assertEquals(expected, got);
    }

    /**
     * Default-graph zero-length paths keep upstream's exact semantics (value-only dedup). The
     * repository default graph is the union of everything, so the vertex walk sees BOTH graphs'
     * statements: vertices {a, b, c} (c via the g2 statement, any predicate) plus the one p1 step.
     */
    @Test
    void defaultGraphZeroLengthUnchanged() {
        try (SailRepositoryConnection con = repo.getConnection()) {
            con.add(a, p1, b);
        }
        List<String> got = tBindings("SELECT ?t WHERE { ?s <urn:p1>* ?t }");
        assertEquals(List.of("urn:a", "urn:b", "urn:b", "urn:c"), got);
    }

    /**
     * The T4 generator's first catch (hardening round 2): {@code ?t p* ?t} — BOTH path endpoints
     * the same variable — crashed the vertex walk with "variable already bound" (the emit bound the
     * one variable twice; upstream has the identical latent crash, masked in production by disabled
     * assertions). Zero-length semantics for the shape: every vertex pairs with itself, so the rows
     * are simply the per-graph vertex sets.
     */
    @Test
    void sameVariableAtBothEndpointsIsTheVertexSet() {
        assertEquals(
                List.of("urn:g1|urn:a", "urn:g1|urn:b", "urn:g2|urn:a", "urn:g2|urn:c"),
                tBindings("SELECT ?g ?t WHERE { GRAPH ?g { ?t <urn:p1>* ?t } }"),
                "?t p* ?t under GRAPH ?g: each graph's vertices, once each");
        assertEquals(
                List.of("urn:a", "urn:b", "urn:c"),
                tBindings("SELECT ?t WHERE { ?t <urn:p1>* ?t }"),
                "default graph (union): the distinct vertex set");
    }

    /**
     * Bound-endpoint branches: mirror semantics, untouched by the graph-aware walk — the
     * zero-length self row for a bound subject, with {@code ?g} left unbound exactly as upstream
     * leaves it (the bound branch never consults the store, so it has no graph to bind).
     */
    @Test
    void boundEndpointsMirrorUpstream() {
        assertEquals(
                List.of("urn:a"),
                tBindings("SELECT ?t WHERE { GRAPH ?g { <urn:a> <urn:p9>* ?t } }"),
                "subject bound, dead predicate: the zero-length self row");
    }
}
