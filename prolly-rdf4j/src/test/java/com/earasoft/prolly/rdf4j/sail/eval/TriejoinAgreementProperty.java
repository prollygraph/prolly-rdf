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
package com.earasoft.prolly.rdf4j.sail.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Step 10 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md} — the Sail-level agreement
 * gate.
 *
 * <p>Over random small directed graphs, a <b>cyclic</b> SPARQL query evaluated with the triejoin
 * flag <b>ON</b> (routed through the WCOJ engine) must return the <b>identical result multiset</b>
 * as with the flag <b>OFF</b> (RDF4J's bind-join). This is the correctness contract a wired-in
 * optimization must hold: a faster engine that changes results is a bug. Multiset (not set)
 * comparison guards cardinality too.
 */
class TriejoinAgreementProperty {

    private static final String E = "urn:e";

    record Edge(int from, int to) {}

    @Provide
    Arbitrary<Set<Edge>> graphs() {
        Arbitrary<Integer> v =
                Arbitraries.integers().between(0, 5); // 6 vertices → dense-ish small graphs
        return Combinators.combine(v, v).as(Edge::new).set().ofMaxSize(14);
    }

    @Property(tries = 40)
    void routedTriangleEqualsBindJoin(@ForAll @From("graphs") Set<Edge> edges) {
        String triangle =
                "SELECT ?x ?y ?z WHERE { "
                        + "?x <"
                        + E
                        + "> ?y . ?y <"
                        + E
                        + "> ?z . ?z <"
                        + E
                        + "> ?x }";
        assertEquals(
                run(edges, triangle, true, "x", "y", "z"),
                run(edges, triangle, false, "x", "y", "z"),
                "flag-ON (triejoin) must equal flag-OFF (bind-join) for the triangle");
    }

    @Property(tries = 40)
    void routedFourCycleEqualsBindJoin(@ForAll @From("graphs") Set<Edge> edges) {
        String fourCycle =
                "SELECT ?a ?b ?c ?d WHERE { "
                        + "?a <"
                        + E
                        + "> ?b . ?b <"
                        + E
                        + "> ?c . ?c <"
                        + E
                        + "> ?d . ?d <"
                        + E
                        + "> ?a }";
        assertEquals(
                run(edges, fourCycle, true, "a", "b", "c", "d"),
                run(edges, fourCycle, false, "a", "b", "c", "d"),
                "flag-ON (triejoin) must equal flag-OFF (bind-join) for the 4-cycle");
    }

    @Property(tries = 40)
    void cardinalityOrderTriangleEqualsBindJoin(@ForAll @From("graphs") Set<Edge> edges) {
        // The flag-ON cardinality-ordering path on a definitely-routed cyclic query (the symmetric
        // triangle is neutral — cardinality == first-appearance — so this confirms the flag-ON
        // branch
        // runs SelectivityVariableOrder safely and stays answer-correct vs the bind-join oracle).
        String triangle =
                "SELECT ?x ?y ?z WHERE { "
                        + "?x <"
                        + E
                        + "> ?y . ?y <"
                        + E
                        + "> ?z . ?z <"
                        + E
                        + "> ?x }";
        assertEquals(
                run(edges, triangle, true, true, "x", "y", "z"), // triejoin + cardinality order
                run(edges, triangle, false, false, "x", "y", "z"), // bind-join ground truth
                "cardinality-order triejoin must equal the bind-join for the triangle");
    }

    @Property(tries = 40)
    void selectiveCyclicCardinalityOrderEqualsBindJoin(@ForAll @From("graphs") Set<Edge> edges) {
        // A SELECTIVE cyclic query: the triangle + a bound-subject constraint <urn:v0> e ?z. The
        // bound term gives SelectivityVariableOrder a cardinality signal so it REORDERS (?z first)
        // —
        // exercising the cardinality path's genuinely-different order — while the bind-join is the
        // ground-truth oracle. Answer-invariance (ordering changes only cost) is the contract.
        String selective =
                "SELECT ?x ?y ?z WHERE { "
                        + "?x <"
                        + E
                        + "> ?y . ?y <"
                        + E
                        + "> ?z . ?z <"
                        + E
                        + "> ?x . "
                        + "<urn:v0> <"
                        + E
                        + "> ?z }";
        assertEquals(
                run(edges, selective, true, true, "x", "y", "z"), // triejoin + cardinality order
                run(edges, selective, false, false, "x", "y", "z"), // bind-join ground truth
                "cardinality-order triejoin must equal the bind-join on a selective cyclic query");
    }

    /**
     * Run {@code query} over {@code edges} (default graph) with the triejoin flag {@code on}; the
     * sorted multiset of result rows (one entry per binding name).
     */
    private static List<List<String>> run(
            Set<Edge> edges, String query, boolean on, String... vars) {
        return run(edges, query, on, false, vars); // default: first-appearance variable order
    }

    /**
     * As {@link #run(Set, String, boolean, String...)} but also toggling cardinality-aware variable
     * ordering ({@code plans/prepublic/sparql-baseline-cardinality-aware.md}) on the routed
     * triejoin.
     */
    private static List<List<String>> run(
            Set<Edge> edges,
            String query,
            boolean triejoinOn,
            boolean cardinalityOrder,
            String... vars) {
        ProllySail sail = new ProllySail();
        sail.setTriejoinEnabled(triejoinOn);
        sail.setTriejoinCardinalityOrder(cardinalityOrder);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(E);
            conn.begin();
            for (Edge edge : edges) {
                conn.add(vf.createIRI("urn:v" + edge.from()), e, vf.createIRI("urn:v" + edge.to()));
            }
            conn.commit();
            List<List<String>> rows = new ArrayList<>();
            try (TupleQueryResult r =
                    conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
                while (r.hasNext()) {
                    BindingSet b = r.next();
                    List<String> row = new ArrayList<>(vars.length);
                    for (String v : vars) row.add(b.getValue(v).stringValue());
                    rows.add(row);
                }
            }
            rows.sort((a, b) -> a.toString().compareTo(b.toString())); // multiset-as-sorted-list
            return rows;
        } finally {
            repo.shutDown();
        }
    }
}
