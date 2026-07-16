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

import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the default-graph / named-graph context isolation bug (fixed 2026-05-15,
 * caught by the W3C SPARQL 1.1 update conformance suite).
 *
 * <p>{@code ProllySailConnection.wantedContexts} treated a single {@code null} context as "no
 * filter" and returned every graph. Per the RDF4J {@code SailConnection} contract a {@code null}
 * context denotes the <em>default graph specifically</em>. The leak meant default-graph reads
 * returned named-graph statements — ~60 of the 90 W3C update tests failed because each compares the
 * post-update store graph-by-graph.
 */
class ProllySailContextTest {

    private static Set<Statement> drain(CloseableIteration<? extends Statement> it) {
        Set<Statement> out = new HashSet<>();
        try {
            while (it.hasNext()) out.add(it.next());
        } finally {
            it.close();
        }
        return out;
    }

    @Test
    void contextFiltering_isolatesDefaultGraphFromNamedGraphs() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = vf.createIRI("http://example/s");
            IRI p = vf.createIRI("http://example/p");
            IRI dft = vf.createIRI("http://example/in-default");
            IRI named = vf.createIRI("http://example/in-g1");
            IRI g1 = vf.createIRI("http://example/g1");

            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(s, p, dft); // default graph
                conn.addStatement(s, p, named, g1); // named graph g1
                conn.commit();
            }

            try (SailConnection conn = sail.getConnection()) {
                // No context varargs → every graph.
                assertEquals(
                        2,
                        drain(conn.getStatements(null, null, null, false)).size(),
                        "no-context read should see both graphs");

                // A single null context → DEFAULT GRAPH ONLY (this was the bug).
                Set<Statement> dftOnly =
                        drain(conn.getStatements(null, null, null, false, (Resource) null));
                assertEquals(1, dftOnly.size(), "null context must see only the default graph");
                assertEquals(dft, dftOnly.iterator().next().getObject());

                // A named context → that graph only.
                Set<Statement> g1Only = drain(conn.getStatements(null, null, null, false, g1));
                assertEquals(1, g1Only.size(), "named context must see only that graph");
                assertEquals(named, g1Only.iterator().next().getObject());

                // null + named → both of those graphs.
                assertEquals(
                        2,
                        drain(conn.getStatements(null, null, null, false, null, g1)).size(),
                        "null+named must see exactly those two graphs");
            }
        } finally {
            sail.shutDown();
        }
    }
}
