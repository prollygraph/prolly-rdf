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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>loading RDF files</b> into a ProllySail — the most common way real data
 * gets into a triple store.
 *
 * <p>RDF4J's {@code RepositoryConnection.add(File, baseURI, RDFFormat, ...)} parses any RDF
 * serialization. This demo writes two files — a Turtle document and an N-Triples document — then
 * loads the Turtle into the default graph and the N-Triples into a named graph, and queries the
 * result.
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.RdfFileLoadDemo \
 *     -Dexec.args="/tmp/prolly-fileload-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class RdfFileLoadDemo {

    private RdfFileLoadDemo() {
        // static main only
    }

    private static final String TURTLE_DOC =
            """
        @prefix p: <urn:person:> .
        @prefix s: <urn:schema:> .

        p:alice s:name "Alice" ; s:age 34 ; s:knows p:bob .
        p:bob   s:name "Bob"   ; s:age 29 .
        p:carol s:name "Carol" ; s:age 41 ; s:knows p:alice .
        """;

    private static final String NTRIPLES_DOC =
            """
        <urn:person:dave> <urn:schema:name> "Dave" .
        <urn:person:dave> <urn:schema:age> "27"^^<http://www.w3.org/2001/XMLSchema#integer> .
        """;

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-fileload-demo-");
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
        out.println("=== ProllySail RDF file-load demo ===");
        out.println("Store dir: " + dir);
        out.println();

        // --- Write two sample RDF files to disk ---
        Path turtle = dir.resolve("people.ttl");
        Path ntriples = dir.resolve("external.nt");
        Files.writeString(turtle, TURTLE_DOC, StandardCharsets.UTF_8);
        Files.writeString(ntriples, NTRIPLES_DOC, StandardCharsets.UTF_8);
        out.println(
                "[1] Wrote "
                        + turtle.getFileName()
                        + " (Turtle, 3 people) and "
                        + ntriples.getFileName()
                        + " (N-Triples, 1 person)");

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            ProllySail sail =
                    new ProllySail(store, new HeapBufferPool(), RootMetaTreeStore.beside(dir));
            Repository repo = new SailRepository(sail);
            repo.init();
            ValueFactory vf = repo.getValueFactory();
            IRI external = vf.createIRI("urn:g:external");

            // [2] parse + load the Turtle file into the default graph
            out.println();
            out.println(
                    "[2] Loading " + turtle.getFileName() + " (Turtle) into the default graph...");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(turtle.toFile(), null, RDFFormat.TURTLE);
                conn.commit();
            }

            // [3] parse + load the N-Triples file into a named graph
            out.println(
                    "[3] Loading "
                            + ntriples.getFileName()
                            + " (N-Triples) into the named graph <urn:g:external>...");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(ntriples.toFile(), null, RDFFormat.NTRIPLES, external);
                conn.commit();
            }

            try (RepositoryConnection conn = repo.getConnection()) {
                // [4] everyone loaded — a plain BGP spans every graph
                out.println();
                out.println("[4] Everyone loaded (a plain BGP spans default + named graphs):");
                query(
                        conn,
                        out,
                        "PREFIX s: <urn:schema:> "
                                + "SELECT ?name ?age WHERE { ?p s:name ?name ; s:age ?age } "
                                + "ORDER BY ?name",
                        bs ->
                                "    "
                                        + bs.getValue("name").stringValue()
                                        + ", age "
                                        + bs.getValue("age").stringValue());

                // [5] relationships from the Turtle file
                out.println();
                out.println("[5] 'knows' relationships parsed from the Turtle file:");
                query(
                        conn,
                        out,
                        "PREFIX s: <urn:schema:> SELECT ?a ?b WHERE { ?a s:knows ?b } ORDER BY ?a",
                        bs ->
                                "    "
                                        + local(bs.getValue("a"))
                                        + " knows "
                                        + local(bs.getValue("b")));

                // [6] triples per graph
                out.println();
                out.println("[6] Triples per graph (getStatements context API):");
                out.println(
                        "    default graph (from people.ttl)  : "
                                + count(
                                        conn.getStatements(
                                                null, null, null, false, (Resource) null)));
                out.println(
                        "    <urn:g:external> (from external.nt): "
                                + count(conn.getStatements(null, null, null, false, external)));
            }

            repo.shutDown();
        }

        out.println();
        out.println("=== Done. RDF files parse and load straight through RDF4J's add(). ===");
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

    /** Local name of an IRI ({@code urn:person:alice} → {@code alice}). */
    private static String local(Value v) {
        String s = v.stringValue();
        int colon = s.lastIndexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }
}
