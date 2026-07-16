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
package com.earasoft.prolly.flatsail.examples;

import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDB;

/**
 * A runnable end-to-end example of {@link RocksDbFlatSail}: open it, wrap it in a {@link
 * SailRepository}, load a handful of triples (including one in a named graph), then run SPARQL over
 * it.
 *
 * <p>Run it directly — {@code java ... FlatSailDemo} — or see {@code FlatSailDemoTest}, which
 * exercises {@link #run} in CI.
 */
public final class FlatSailDemo {

    private static final String EX = "http://example.org/";

    private FlatSailDemo() {}

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("flatsail-demo");
        run(dir, System.out);
    }

    /** Run the demo against a flat Sail rooted at {@code dir}, printing to {@code out}. */
    public static void run(Path dir, PrintStream out) {
        RocksDB.loadLibrary();
        out.println("=== RocksDbFlatSail demo ===");

        SailRepository repo = new SailRepository(new RocksDbFlatSail(dir));
        repo.init();
        try {
            ValueFactory vf = repo.getValueFactory();

            // 1. Load data — three triples in the default graph, one in a named graph.
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(
                        vf.createIRI(EX + "alice"),
                        vf.createIRI(EX + "knows"),
                        vf.createIRI(EX + "bob"));
                conn.add(
                        vf.createIRI(EX + "alice"),
                        vf.createIRI(EX + "name"),
                        vf.createLiteral("Alice"));
                conn.add(
                        vf.createIRI(EX + "bob"),
                        vf.createIRI(EX + "name"),
                        vf.createLiteral("Bob"));
                conn.add(
                        vf.createIRI(EX + "carol"),
                        vf.createIRI(EX + "name"),
                        vf.createLiteral("Carol"),
                        vf.createIRI(EX + "peopleGraph"));
                conn.commit();
            }
            out.println("loaded 4 statements");

            try (RepositoryConnection conn = repo.getConnection()) {
                out.println("total statements: " + conn.size());

                // 2. A SPARQL SELECT — every name, sorted.
                out.println("query: all names");
                String names = "SELECT ?name WHERE { ?p <" + EX + "name> ?name } ORDER BY ?name";
                try (TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, names).evaluate()) {
                    while (r.hasNext()) {
                        out.println("  - " + r.next().getValue("name").stringValue());
                    }
                }

                // 3. A SPARQL basic-graph-pattern join.
                out.println("query: who does alice know");
                String join =
                        "SELECT ?friendName WHERE {"
                                + " <"
                                + EX
                                + "alice> <"
                                + EX
                                + "knows> ?friend ."
                                + " ?friend <"
                                + EX
                                + "name> ?friendName . }";
                try (TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, join).evaluate()) {
                    while (r.hasNext()) {
                        out.println("  - " + r.next().getValue("friendName").stringValue());
                    }
                }

                // 4. A SPARQL GRAPH clause — read the named graph.
                out.println("query: names in peopleGraph");
                String graph =
                        "SELECT ?name WHERE { GRAPH <"
                                + EX
                                + "peopleGraph> {"
                                + " ?p <"
                                + EX
                                + "name> ?name } }";
                try (TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, graph).evaluate()) {
                    while (r.hasNext()) {
                        out.println("  - " + r.next().getValue("name").stringValue());
                    }
                }
            }

            out.println("=== demo complete ===");
        } finally {
            repo.shutDown();
        }
    }
}
