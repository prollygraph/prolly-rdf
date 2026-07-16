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
import java.util.function.Function;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>named graphs (quads)</b> in a ProllySail.
 *
 * <p>An RDF4J statement can carry a fourth component — a <em>context</em>, aka the named graph it
 * belongs to. ProllySail maintains a {@code CSPO} index for exactly this. A classic use is
 * <b>provenance</b>: put each data source's facts in its own named graph, so you always know who
 * said what.
 *
 * <p>What it shows:
 *
 * <ol>
 *   <li>load facts into two named graphs (one per source) plus one fact into the unnamed
 *       <em>default graph</em> — the 4th arg of {@code conn.add};
 *   <li>query a single named graph, and query <em>across</em> graphs with {@code GRAPH ?g { ... }}
 *       to recover provenance;
 *   <li>show the default graph stays separate from the named graphs;
 *   <li>count triples per graph via the {@code getStatements} context API;
 *   <li>drop a whole named graph with {@code conn.clear(graph)}.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.NamedGraphDemo \
 *     -Dexec.args="/tmp/prolly-namedgraph-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class NamedGraphDemo {

    private NamedGraphDemo() {
        // static main only
    }

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-namedgraph-demo-");
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
        out.println("=== ProllySail named-graph (quads) demo ===");
        out.println("Store dir: " + dir);
        out.println();

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(dir));
            Repository repo = new SailRepository(sail);
            repo.init();
            ValueFactory vf = repo.getValueFactory();

            IRI crm = vf.createIRI("urn:src:crm");
            IRI linkedin = vf.createIRI("urn:src:linkedin");
            IRI alice = vf.createIRI("urn:p:alice");
            IRI bob = vf.createIRI("urn:p:bob");
            IRI worksAt = vf.createIRI("urn:p:worksAt");
            IRI knows = vf.createIRI("urn:p:knows");

            // [1]+[2] — the 4th arg of conn.add is the named graph; omitting it
            // targets the unnamed default graph.
            out.println("[1] Loading facts — each data source into its own named graph:");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(alice, worksAt, vf.createIRI("urn:org:acme"), crm); // → crm
                conn.add(alice, worksAt, vf.createIRI("urn:org:acme-co"), linkedin); // → linkedin
                conn.add(bob, worksAt, vf.createIRI("urn:org:beta"), linkedin); // → linkedin
                conn.add(alice, knows, bob); // → default graph
                conn.commit();
            }
            out.println("    graph 'crm'      : alice worksAt acme");
            out.println("    graph 'linkedin' : alice worksAt acme-co, bob worksAt beta");
            out.println("    default graph    : alice knows bob");

            try (RepositoryConnection conn = repo.getConnection()) {
                // [3] — query one named graph
                out.println();
                out.println("[3] Query the 'crm' graph alone — GRAPH <urn:src:crm> { ... }:");
                query(
                        conn,
                        out,
                        "SELECT ?o WHERE { GRAPH <urn:src:crm> { <urn:p:alice> <urn:p:worksAt> ?o } }",
                        bs -> "    alice works at " + bs.getValue("o"));

                // [4] — cross-graph query recovers provenance
                out.println();
                out.println("[4] Cross-graph — which SOURCE claims which employer for alice?");
                query(
                        conn,
                        out,
                        "SELECT ?g ?o WHERE { GRAPH ?g { <urn:p:alice> <urn:p:worksAt> ?o } } "
                                + "ORDER BY ?g",
                        bs -> "    " + bs.getValue("g") + "  claims  " + bs.getValue("o"));

                // [5] — a plain BGP sees the UNION of every graph
                out.println();
                out.println("[5] A plain BGP (no GRAPH clause) — SPARQL's default dataset is the");
                out.println("    UNION of the default graph and every named graph:");
                query(
                        conn,
                        out,
                        "SELECT (COUNT(*) AS ?n) WHERE { ?s ?p ?o }",
                        bs ->
                                "    "
                                        + bs.getValue("n").stringValue()
                                        + " statements visible across all graphs");
                out.println("    → to scope a query: GRAPH <g> { } for one named graph (see [3]),");
                out.println("      or the getStatements context API for one graph (see [6]).");

                // [6] — the getStatements context API
                out.println();
                out.println("[6] getStatements(...) with a context argument — triples per graph:");
                out.println(
                        "    default graph : "
                                + count(
                                        conn.getStatements(
                                                null, null, null, false, (Resource) null)));
                out.println(
                        "    crm           : "
                                + count(conn.getStatements(null, null, null, false, crm)));
                out.println(
                        "    linkedin      : "
                                + count(conn.getStatements(null, null, null, false, linkedin)));
                out.println(
                        "    all graphs    : "
                                + count(conn.getStatements(null, null, null, false)));
            }

            // [7] — drop a whole named graph
            out.println();
            out.println("[7] Dropping the 'linkedin' graph — conn.clear(linkedin)...");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.clear(linkedin);
                conn.commit();
            }
            try (RepositoryConnection conn = repo.getConnection()) {
                out.println("    cross-graph re-query — sources still naming alice's employer:");
                query(
                        conn,
                        out,
                        "SELECT ?g ?o WHERE { GRAPH ?g { <urn:p:alice> <urn:p:worksAt> ?o } }",
                        bs -> "    " + bs.getValue("g") + "  claims  " + bs.getValue("o"));
            }

            repo.shutDown();
        }

        out.println();
        out.println(
                "=== Done. Named graphs keep each source's facts — and provenance — distinct. ===");
    }

    /** Run {@code sparql} as a SELECT and print each row through {@code fmt}. */
    private static void query(
            RepositoryConnection conn,
            PrintStream out,
            String sparql,
            Function<BindingSet, String> fmt) {
        try (TupleQueryResult r = conn.prepareTupleQuery(sparql).evaluate()) {
            while (r.hasNext()) {
                out.println(fmt.apply(r.next()));
            }
        }
    }

    /** Drain a statement iteration, returning how many it held. */
    private static long count(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try (it) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }
}
