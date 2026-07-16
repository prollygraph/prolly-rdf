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

import com.earasoft.prolly.rdf4j.sync.GraphIriResolver;
import java.util.HashSet;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;

/**
 * Phase 8 Step 28 of {@code prolly-rdf4j-test-strategy.md} (S-10, the part below REST) — a
 * <b>reserved auth graph behaves as an ordinary named graph through the Sail API</b>. This is the
 * load-bearing design invariant of auth-as-data: the {@code ProllySail} enforces <b>no</b>
 * privilege; the only thing that gates access to {@code <urn:prolly-rdf4j:auth/*>} is the REST
 * controller (ADR-0014). If the Sail itself special-cased these graphs, the privilege model would
 * have two boundaries instead of one, and a non-REST caller (sync, an embedded consumer, a future
 * face) could be silently mis-gated.
 *
 * <p><b>Metamorphic relation:</b> apply the same statements to a reserved auth-graph context and to
 * an ordinary data-graph context in the same store, then assert the Sail's observable behaviour is
 * identical (modulo the context IRI): the scoped scan reads back the same set, the auth graph is a
 * first-class member of {@code getContextIDs()} (not hidden), and clearing the auth graph empties
 * it while leaving the data graph intact (ordinary context isolation, no auth branch). The
 * auth-graph IRIs are taken from {@code GraphIriResolver.DEFAULT_AUTH_GRAPHS} so the test tracks
 * the real reserved set.
 *
 * <p>The sibling half of S-10 — that sync's {@code ChunkGraphFilter} never ships an auth-graph
 * chunk — is pinned by {@code SyncAuthGraphFilterProperty} (Step 25): the privilege boundary is
 * REST + the sync filter, never the Sail.
 */
class SailAuthGraphAsOrdinaryProperty {

    private static Set<String> scopedSubjects(SailConnection c, IRI graph) {
        Set<String> out = new HashSet<>();
        try (CloseableIteration<? extends Statement> it =
                c.getStatements(null, null, null, false, graph)) {
            while (it.hasNext()) {
                out.add(it.next().getSubject().stringValue());
            }
        }
        return out;
    }

    private static Set<String> contextIds(SailConnection c) {
        Set<String> out = new HashSet<>();
        try (CloseableIteration<? extends org.eclipse.rdf4j.model.Resource> it =
                c.getContextIDs()) {
            while (it.hasNext()) {
                out.add(it.next().stringValue());
            }
        }
        return out;
    }

    @Property(tries = 20)
    void a_reserved_auth_graph_behaves_as_an_ordinary_named_graph(
            @ForAll @IntRange(min = 1, max = 8) int n) {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI dataGraph = vf.createIRI("urn:graph:data");

            for (String authIri : GraphIriResolver.DEFAULT_AUTH_GRAPHS) {
                IRI authGraph = vf.createIRI(authIri);

                // Same n statements into the auth graph and the ordinary data graph.
                try (SailConnection c = sail.getConnection()) {
                    c.begin();
                    for (int i = 0; i < n; i++) {
                        IRI s = vf.createIRI("urn:s" + i);
                        IRI p = vf.createIRI("urn:p");
                        IRI o = vf.createIRI("urn:o" + i);
                        c.addStatement(s, p, o, authGraph);
                        c.addStatement(s, p, o, dataGraph);
                    }
                    c.commit();
                }

                // Scoped read + context enumeration: the auth graph is an ordinary, visible
                // context.
                try (SailConnection c = sail.getConnection()) {
                    assertEquals(
                            scopedSubjects(c, dataGraph),
                            scopedSubjects(c, authGraph),
                            authIri
                                    + " must read back through the Sail exactly like an ordinary named graph");
                    assertEquals(n, scopedSubjects(c, authGraph).size());
                    Set<String> ctxs = contextIds(c);
                    assertTrue(
                            ctxs.contains(authIri),
                            "the auth graph must be a first-class context in getContextIDs (the Sail hides nothing)");
                    assertTrue(ctxs.contains("urn:graph:data"));
                }

                // Clearing the auth graph empties it and leaves the data graph intact — ordinary
                // context isolation, no special-casing.
                try (SailConnection c = sail.getConnection()) {
                    c.begin();
                    c.clear(authGraph);
                    c.commit();
                }
                try (SailConnection c = sail.getConnection()) {
                    assertTrue(
                            scopedSubjects(c, authGraph).isEmpty(),
                            "clearing the auth graph empties it like any context");
                    assertEquals(
                            n,
                            scopedSubjects(c, dataGraph).size(),
                            "clearing the auth graph must not touch the data graph");
                }

                // Reset the shared data graph for the next auth-graph iteration.
                try (SailConnection c = sail.getConnection()) {
                    c.begin();
                    c.clear(dataGraph);
                    c.commit();
                }
            }
        } finally {
            sail.shutDown();
        }
    }
}
