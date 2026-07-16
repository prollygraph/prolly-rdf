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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Regression for the <b>all-contexts remove</b> contract of {@code
 * ProllySailConnection.removeStatementsInternal}. RDF4J's {@code SailConnection.removeStatements(s,
 * p, o)} with an <b>empty</b> contexts array removes the matching triple from <b>every</b> graph; a
 * single {@code {null}} removes only from the default graph. The remove path previously conflated
 * the two for a fully-bound {@code (s,p,o)} — it deleted only the default-graph copy ({@code
 * TermId.ZERO}), silently leaving named-graph copies — even though the <i>read</i> path's {@code
 * wantedContexts} already documented the distinction. Surfaced by {@code
 * SailReadYourWritesProperty}'s forced-collision regime (a small statement pool), which the
 * large-term-space {@code SailDifferentialProperty} structurally never reached; fixed by routing
 * the all-contexts case through scan-and-delete. Driven against a real {@link ProllySail} — no
 * mocks.
 */
class ProllySailRemoveAllContextsTest {

    private static IRI iri(ValueFactory vf, String s) {
        return vf.createIRI("urn:test:" + s);
    }

    @Test
    void all_contexts_remove_reaches_a_named_graph() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = iri(vf, "s");
            IRI p = iri(vf, "p");
            IRI o = iri(vf, "o");
            IRI g = iri(vf, "g1");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, o, g); // lives in named graph g1
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.removeStatements(s, p, o); // empty contexts == ALL graphs
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        0L,
                        conn.size(),
                        "removeStatements(s,p,o) with no contexts must remove the named-graph copy");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void all_contexts_remove_reaches_a_same_tx_named_graph_add() {
        // Read-your-writes ACROSS the all-graphs wildcard: a buffered g1 add must be removed
        // by a same-transaction contextless remove, and the read must reflect it.
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = iri(vf, "s");
            IRI p = iri(vf, "p");
            IRI o = iri(vf, "o");
            IRI g = iri(vf, "g1");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, o, g);
                conn.removeStatements(s, p, o); // all graphs, same transaction
                assertEquals(
                        0L,
                        conn.size(),
                        "a same-tx all-contexts remove must reach the buffered named-graph add");
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(0L, conn.size(), "and the removal must survive commit");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void all_contexts_remove_clears_the_triple_from_every_graph_at_once() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = iri(vf, "s");
            IRI p = iri(vf, "p");
            IRI o = iri(vf, "o");
            IRI g1 = iri(vf, "g1");
            IRI g2 = iri(vf, "g2");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, o); // default graph
                conn.addStatement(s, p, o, g1); // g1
                conn.addStatement(s, p, o, g2); // g2
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.removeStatements(s, p, o); // all graphs
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        0L,
                        conn.size(),
                        "one all-contexts remove must clear the triple from default + g1 + g2");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void default_only_remove_does_not_touch_a_named_graph() {
        // The complementary guard the fix must preserve: {null} == default graph ONLY must
        // NOT remove a triple that lives in a named graph.
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = iri(vf, "s");
            IRI p = iri(vf, "p");
            IRI o = iri(vf, "o");
            IRI g = iri(vf, "g1");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, o, g);
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.removeStatements(s, p, o, (Resource) null); // default graph only
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(
                        1L,
                        conn.size(),
                        "a default-graph-only remove must leave the named-graph copy intact");
            }
        } finally {
            sail.shutDown();
        }
    }
}
