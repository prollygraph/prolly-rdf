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
package com.earasoft.prolly.rdf4j.examples;

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.GraphQueryResult;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of driving a ProllySail purely through <b>SPARQL</b> — the way most
 * applications use an RDF store.
 *
 * <p>What it shows, in order:
 *
 * <ol>
 *   <li>load data with a SPARQL {@code INSERT DATA} update;
 *   <li>{@code SELECT} with a {@code FILTER};
 *   <li>{@code SELECT} with {@code GROUP BY} + {@code COUNT}/{@code AVG};
 *   <li>{@code CONSTRUCT} to derive new triples;
 *   <li>{@code ASK} for a boolean answer;
 *   <li>a {@code DELETE}/{@code INSERT WHERE} update that rewrites existing data — then re-runs the
 *       FILTER query to show the change.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.SparqlDemo \
 *     -Dexec.args="/tmp/prolly-sparql-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class SparqlDemo {

    private SparqlDemo() {
        // static main only
    }

    private static final String PREFIX = "PREFIX p: <urn:p:> ";

    private static final String INSERT_DATA =
            PREFIX
                    + """
        INSERT DATA {
          p:alice p:age 34 ; p:city "Paris" .
          p:bob   p:age 29 ; p:city "Lyon"  .
          p:carol p:age 41 ; p:city "Paris" .
          p:dave  p:age 25 ; p:city "Lyon"  .
        }""";

    private static final String OVER_30 =
            PREFIX
                    + "SELECT ?who ?age WHERE { ?who p:age ?age . FILTER(?age > 30) } "
                    + "ORDER BY DESC(?age)";

    private static final String BY_CITY =
            PREFIX
                    + "SELECT ?city (COUNT(?who) AS ?n) (AVG(?age) AS ?avgAge) "
                    + "WHERE { ?who p:city ?city ; p:age ?age } GROUP BY ?city ORDER BY ?city";

    private static final String CONSTRUCT_ADULTS =
            PREFIX + "CONSTRUCT { ?who a p:Adult } WHERE { ?who p:age ?age . FILTER(?age >= 18) }";

    private static final String ASK_PARIS = PREFIX + "ASK { ?who p:city \"Paris\" }";

    private static final String BIRTHDAY =
            PREFIX
                    + """
        DELETE { ?who p:age ?old }
        INSERT { ?who p:age ?new }
        WHERE  { ?who p:age ?old . BIND(?old + 1 AS ?new) }""";

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-sparql-demo-");
            ephemeral = true;
        }

        run(dir, System.out);

        if (ephemeral) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }

    /**
     * Run the demo against {@code dir}. Public so tests can drive it with a captured stream and
     * assert on the output.
     */
    public static void run(Path dir, PrintStream out) throws IOException, RocksDBException {
        out.println("=== ProllySail SPARQL demo ===");
        out.println("Store dir: " + dir);
        out.println();

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(dir));
            Repository repo = new SailRepository(sail);
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {

                // [1] load via SPARQL INSERT DATA
                out.println("[1] Loading 4 people with SPARQL INSERT DATA...");
                conn.begin();
                conn.prepareUpdate(INSERT_DATA).execute();
                conn.commit();
                out.println("    committed.");

                // [2] SELECT + FILTER
                out.println();
                out.println("[2] SELECT — people over 30 (FILTER):");
                printAges(conn, out);

                // [3] aggregation
                out.println();
                out.println("[3] SELECT — count & average age per city (GROUP BY):");
                try (TupleQueryResult r = conn.prepareTupleQuery(BY_CITY).evaluate()) {
                    while (r.hasNext()) {
                        var bs = r.next();
                        out.println(
                                "    "
                                        + bs.getValue("city").stringValue()
                                        + ": "
                                        + bs.getValue("n").stringValue()
                                        + " people, avg age "
                                        + bs.getValue("avgAge").stringValue());
                    }
                }

                // [4] CONSTRUCT
                out.println();
                out.println("[4] CONSTRUCT — derive 'Adult' typing:");
                try (GraphQueryResult r = conn.prepareGraphQuery(CONSTRUCT_ADULTS).evaluate()) {
                    while (r.hasNext()) {
                        Statement st = r.next();
                        out.println(
                                "    " + local(st.getSubject()) + " a " + local(st.getObject()));
                    }
                }

                // [5] ASK
                out.println();
                boolean anyParis = conn.prepareBooleanQuery(ASK_PARIS).evaluate();
                out.println("[5] ASK — does anyone live in Paris? → " + anyParis);

                // [6] UPDATE — DELETE/INSERT WHERE
                out.println();
                out.println("[6] UPDATE — everyone has a birthday (age + 1):");
                conn.begin();
                conn.prepareUpdate(BIRTHDAY).execute();
                conn.commit();
                out.println("    re-running query [2] after the update:");
                printAges(conn, out);
            }
            repo.shutDown();
        }

        out.println();
        out.println("=== Done. Load, query, derive, and mutate — all via SPARQL. ===");
    }

    /** Run the over-30 FILTER query and print each match. */
    private static void printAges(RepositoryConnection conn, PrintStream out) {
        try (TupleQueryResult r = conn.prepareTupleQuery(OVER_30).evaluate()) {
            while (r.hasNext()) {
                var bs = r.next();
                out.println(
                        "    "
                                + local(bs.getValue("who"))
                                + " — age "
                                + bs.getValue("age").stringValue());
            }
        }
    }

    /** Local name of an IRI ({@code urn:p:alice} → {@code alice}); literals pass through. */
    private static String local(Value v) {
        String s = v.stringValue();
        int colon = s.lastIndexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }
}
