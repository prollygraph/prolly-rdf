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

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.impl.MapBindingSet;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Characterizes the "pre-set query bindings" surface — across the <b>two different code paths</b>
 * the phrase covers, which is the whole point of this test (follow-ons plan Step 4 / Step 27).
 *
 * <p><b>Path 1 — high-level {@code TupleQuery.setBinding} (works).</b> The four {@code
 * setBinding_*} cases pre-bind a variable on a {@code TupleQuery} before {@code evaluate()} and
 * assert the result is <b>constrained, not zeroed</b>: a subject IRI, an object IRI, a typed
 * literal, and the start node of a 2-hop join all return the correct rows. This is the path normal
 * SPARQL clients use, and it is sound.
 *
 * <p><b>Path 2 — low-level {@code SailConnection.evaluate(rawTupleExpr, dataset, bindings, ...)}
 * (has a narrow gap).</b> This is the RDF4J Storage-And-Inference-Layer SPI corner that {@code
 * RDFStoreTest.testQueryBindings} exercises — and the one actually baselined as a known failure on
 * <b>both</b> sails ({@code ProllyRdfStoreContractTest} and {@code RocksDbFlatSailContractTest}).
 * Here a pre-set binding is fed straight to the Sail with a raw (un-optimized) algebra tree. Two
 * sub-cases pin the precise boundary:
 *
 * <ul>
 *   <li>a binding on a variable that <b>appears in the basic graph pattern</b> (e.g. {@code ?Y} in
 *       {@code ?X a ?Y}) constrains correctly — the binding flows into the pattern scan;
 *   <li>a binding on a variable that appears <b>only in a {@code FILTER}</b> (e.g. {@code ?Z} in
 *       {@code filter(?Y = ?Z)}, with {@code ?Z} nowhere in the patterns) <b>was dropped</b> (0
 *       rows) until fixed 2026-06-11; it is now honoured (see the fix note below).
 * </ul>
 *
 * <p><b>Finding, then fix — 2026-06-11 (follow-ons Step 4).</b> Step 27's earlier conclusion that
 * "the bug did <i>not</i> reproduce" was a <b>false negative</b>: it tested Path 1 only.
 * Re-anchoring to the <i>actually-baselined</i> test ({@code RDFStoreTest.testQueryBindings},
 * traced from {@code RocksDbFlatSail-impl.md}) and running it un-skipped reproduced the failure
 * deterministically — {@code testQueryBindings:696}, expected 1 row but got 0, on exactly the
 * filter-only-binding sub-case. <b>Root cause:</b> both sails' {@code evaluateInternal} called
 * {@code strategy.evaluate(expr, bindings)} <b>without</b> RDF4J's binding-inlining optimizer —
 * which the stock {@code SailSourceConnection} runs via {@code strategy.optimize(...)} — so a
 * binding on a variable absent from the basic graph pattern never reached the algebra. <b>Fixed</b>
 * by inlining the initial bindings with {@code BindingAssignerOptimizer} (guarded on non-empty
 * bindings, cloning the tree first since the caller may reuse it); the {@code @Disabled
 * testQueryBindings} baselines on both sails were removed in the same change. All six cases below
 * now pass.
 *
 * <p><b>apiNote</b> — this had been low practical impact (normal SPARQL clients use the sound Path
 * 1, and the W3C query suite is 171/176); only a direct SPI caller passing a filter-only pre-set
 * binding to {@code evaluate} hit it. Now fixed in both sails' {@code evaluateInternal}; the
 * empty-bindings common path is untouched (the inline is guarded on non-empty bindings).
 */
class PresetBindingCharacterizationTest {

    // ----------------------------------------------------------------------------------------------
    // Path 1 — high-level TupleQuery.setBinding (sound)
    // ----------------------------------------------------------------------------------------------

    @Test
    void setBinding_on_subject_constrains_not_zeroes() {
        ProllySail sail = new ProllySail();
        sail.init();
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection con = repo.getConnection()) {
            ValueFactory vf = con.getValueFactory();
            con.add(vf.createIRI("urn:alice"), vf.createIRI("urn:knows"), vf.createIRI("urn:bob"));
            con.add(
                    vf.createIRI("urn:alice"),
                    vf.createIRI("urn:knows"),
                    vf.createIRI("urn:carol"));
            con.add(vf.createIRI("urn:dave"), vf.createIRI("urn:knows"), vf.createIRI("urn:erin"));

            TupleQuery q = con.prepareTupleQuery("SELECT ?o WHERE { ?s <urn:knows> ?o }");
            q.setBinding("s", vf.createIRI("urn:alice"));
            int count = 0;
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    r.next();
                    count++;
                }
            }
            assertEquals(2, count, "pre-binding ?s=alice must yield {bob, carol}, not 0 rows");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void setBinding_on_a_literal_object_constrains_not_zeroes() {
        ProllySail sail = new ProllySail();
        sail.init();
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection con = repo.getConnection()) {
            ValueFactory vf = con.getValueFactory();
            con.add(vf.createIRI("urn:alice"), vf.createIRI("urn:age"), vf.createLiteral(30));
            con.add(vf.createIRI("urn:bob"), vf.createIRI("urn:age"), vf.createLiteral(30));
            con.add(vf.createIRI("urn:carol"), vf.createIRI("urn:age"), vf.createLiteral(41));

            TupleQuery q = con.prepareTupleQuery("SELECT ?s WHERE { ?s <urn:age> ?age }");
            q.setBinding("age", vf.createLiteral(30));
            int count = 0;
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    r.next();
                    count++;
                }
            }
            assertEquals(
                    2,
                    count,
                    "pre-binding ?age=30 (typed literal) must yield {alice, bob}, not 0 rows");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void setBinding_on_a_two_pattern_join_constrains_not_zeroes() {
        ProllySail sail = new ProllySail();
        sail.init();
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection con = repo.getConnection()) {
            ValueFactory vf = con.getValueFactory();
            // alice -> bob -> carol ; alice -> dave -> erin
            con.add(vf.createIRI("urn:alice"), vf.createIRI("urn:knows"), vf.createIRI("urn:bob"));
            con.add(vf.createIRI("urn:bob"), vf.createIRI("urn:knows"), vf.createIRI("urn:carol"));
            con.add(vf.createIRI("urn:alice"), vf.createIRI("urn:knows"), vf.createIRI("urn:dave"));
            con.add(vf.createIRI("urn:dave"), vf.createIRI("urn:knows"), vf.createIRI("urn:erin"));

            // 2-hop join; pre-bind the start node — the triejoin path with a constant join seed.
            TupleQuery q =
                    con.prepareTupleQuery(
                            "SELECT ?x WHERE { ?s <urn:knows> ?o . ?o <urn:knows> ?x }");
            q.setBinding("s", vf.createIRI("urn:alice"));
            int count = 0;
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    r.next();
                    count++;
                }
            }
            assertEquals(
                    2,
                    count,
                    "pre-binding ?s=alice on a 2-hop join must yield {carol, erin}, not 0 rows");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void setBinding_on_object_constrains_not_zeroes() {
        ProllySail sail = new ProllySail();
        sail.init();
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try (RepositoryConnection con = repo.getConnection()) {
            ValueFactory vf = con.getValueFactory();
            con.add(vf.createIRI("urn:alice"), vf.createIRI("urn:knows"), vf.createIRI("urn:bob"));
            con.add(vf.createIRI("urn:dave"), vf.createIRI("urn:knows"), vf.createIRI("urn:bob"));
            con.add(
                    vf.createIRI("urn:alice"),
                    vf.createIRI("urn:knows"),
                    vf.createIRI("urn:carol"));

            TupleQuery q = con.prepareTupleQuery("SELECT ?s WHERE { ?s <urn:knows> ?o }");
            q.setBinding("o", vf.createIRI("urn:bob"));
            int count = 0;
            try (TupleQueryResult r = q.evaluate()) {
                while (r.hasNext()) {
                    r.next();
                    count++;
                }
            }
            assertEquals(2, count, "pre-binding ?o=bob must yield {alice, dave}, not 0 rows");
        } finally {
            repo.shutDown();
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Path 2 — low-level SailConnection.evaluate(rawTupleExpr, dataset, bindings, ...) — the
    // baselined SPI
    // ----------------------------------------------------------------------------------------------

    private static int evaluateCount(SailConnection con, String sparql, BindingSet bindings) {
        TupleExpr expr =
                QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, sparql, null).getTupleExpr();
        int count = 0;
        try (CloseableIteration<? extends BindingSet> it =
                con.evaluate(expr, null, bindings, false)) {
            while (it.hasNext()) {
                it.next();
                count++;
            }
        }
        return count;
    }

    /**
     * Path 2, the part that WORKS: a pre-set binding on a basic-graph-pattern variable constrains.
     */
    @Test
    void low_level_evaluate_binding_on_a_bgp_variable_constrains() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI person = vf.createIRI("urn:Person");
            IRI animal = vf.createIRI("urn:Animal");
            try (SailConnection con = sail.getConnection()) {
                con.begin();
                con.addStatement(person, RDF.TYPE, RDFS.CLASS);
                con.addStatement(animal, RDF.TYPE, RDFS.CLASS);
                con.addStatement(vf.createIRI("urn:alice"), RDF.TYPE, person);
                con.addStatement(vf.createIRI("urn:rex"), RDF.TYPE, animal);
                con.commit();

                MapBindingSet b = new MapBindingSet();
                b.addBinding("Y", person);
                int n = evaluateCount(con, "SELECT ?X WHERE { ?X a ?Y . ?Y a rdfs:Class }", b);
                assertEquals(1, n, "binding ?Y=Person (a BGP variable) must constrain to {alice}");
            }
        } finally {
            sail.shutDown();
        }
    }

    /**
     * Path 2, the case that was the documented gap and is now <b>fixed</b> (2026-06-11): a pre-set
     * binding on a variable that appears <b>only in a {@code FILTER}</b> is honoured — the query
     * returns the expected match, not 0 rows. The fix inlines the initial bindings via {@code
     * BindingAssignerOptimizer} in {@code ProllySailConnection.evaluateInternal} (and the flat
     * sail's), so a filter-only variable's binding is no longer dropped. Mirrors {@code
     * RDFStoreTest.testQueryBindings:696}, now passing on both sails (their {@code @Disabled}
     * baselines removed in the same change).
     */
    @Test
    void low_level_evaluate_binding_on_a_filter_only_variable_is_honoured() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI person = vf.createIRI("urn:Person");
            try (SailConnection con = sail.getConnection()) {
                con.begin();
                con.addStatement(person, RDF.TYPE, RDFS.CLASS);
                con.addStatement(vf.createIRI("urn:alice"), RDF.TYPE, person);
                con.commit();

                // ?Z appears ONLY in the filter, supplied entirely by the pre-set binding.
                MapBindingSet b = new MapBindingSet();
                b.addBinding("Z", person);
                int n =
                        evaluateCount(
                                con,
                                "SELECT ?X WHERE { ?X a ?Y . ?Y a rdfs:Class . filter(?Y = ?Z) }",
                                b);
                assertEquals(
                        1,
                        n,
                        "a filter-only pre-set binding must be honoured → {alice} — fixed 2026-06-11 by "
                                + "inlining initial bindings via BindingAssignerOptimizer in evaluateInternal "
                                + "(was 0 rows before the fix).");
            }
        } finally {
            sail.shutDown();
        }
    }
}
