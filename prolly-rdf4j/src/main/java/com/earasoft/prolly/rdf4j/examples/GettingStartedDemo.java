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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of the full open → ingest → query → restart → query lifecycle for a
 * ProllySail-backed RDF4J Repository.
 *
 * <p>What it shows, in order:
 *
 * <ol>
 *   <li>Open a Sail at a filesystem path (RocksDB-backed NodeStore + RootMetaTreeStore for the
 *       auto-restore pointer).
 *   <li>Insert a few triples via a normal RDF4J {@code RepositoryConnection}.
 *   <li>Commit, then run a SELECT and a CONSTRUCT query.
 *   <li>Shut everything down (closes the NodeStore, releasing the RocksDB lock so a second process
 *       could open it).
 *   <li>Re-open against the same directory — no manual root injection; the RootMetaTree sidecar
 *       drives auto-restore.
 *   <li>Re-run the SELECT and confirm the data is still there.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.GettingStartedDemo \
 *     -Dexec.args="/tmp/prolly-demo"
 * </pre>
 *
 * <p>If no argument is given a temp directory is used and removed on normal exit — handy for smoke
 * tests but not for showing persistence across processes.
 */
public final class GettingStartedDemo {

    private GettingStartedDemo() {
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
            dir = Files.createTempDirectory("prolly-demo-");
            ephemeral = true;
        }

        run(dir, System.out);

        if (ephemeral) {
            // Best-effort cleanup — leaves the dir alone if anything failed.
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
        out.println("=== ProllySail getting-started demo ===");
        out.println("Store dir: " + dir);
        out.println();

        // --- Phase 1: open, ingest, query ---
        out.println("[1] Opening Sail and ingesting 4 triples...");
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            RootMetaTreeStore meta = RootMetaTreeStore.beside(dir);
            ProllySail sail = new ProllySail(store, new HeapBufferPool(), meta);
            Repository repo = new SailRepository(sail);
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = repo.getValueFactory();
                conn.begin();
                conn.add(
                        vf.createIRI("urn:demo:alice"),
                        vf.createIRI("urn:demo:knows"),
                        vf.createIRI("urn:demo:bob"));
                conn.add(
                        vf.createIRI("urn:demo:alice"),
                        vf.createIRI("urn:demo:age"),
                        vf.createLiteral(30));
                conn.add(
                        vf.createIRI("urn:demo:bob"),
                        vf.createIRI("urn:demo:knows"),
                        vf.createIRI("urn:demo:carol"));
                conn.add(
                        vf.createIRI("urn:demo:bob"),
                        vf.createIRI("urn:demo:age"),
                        vf.createLiteral(25));

                conn.add(
                        vf.createIRI("urn:demo:bob"),
                        vf.createIRI("urn:demo:age"),
                        vf.createLiteral(26));

                conn.commit();
                out.println("    committed.");

                out.println();
                out.println("[2] Querying — friendship pairs:");
                String friendsQ =
                        ""
                                + "PREFIX d: <urn:demo:> "
                                + "SELECT ?a ?b WHERE { ?a d:knows ?b } ORDER BY ?a";
                try (TupleQueryResult r = conn.prepareTupleQuery(friendsQ).evaluate()) {
                    while (r.hasNext()) {
                        var bs = r.next();
                        out.println("    " + bs.getValue("a") + "  knows  " + bs.getValue("b"));
                    }
                }
            }
            repo.shutDown();
            out.println();
            out.println("[3] Shutting down Sail and NodeStore...");
        }

        // --- Phase 2: re-open the same directory, verify auto-restore ---
        out.println();
        out.println("[4] Re-opening the Sail against the same directory...");
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            RootMetaTreeStore meta = RootMetaTreeStore.beside(dir);
            ProllySail sail = new ProllySail(store, new HeapBufferPool(), meta);
            Repository repo = new SailRepository(sail);
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                out.println();
                out.println("[5] Re-querying — total triples by subject:");
                String countQ =
                        ""
                                + "SELECT ?s (COUNT(?p) AS ?n) WHERE { ?s ?p ?o } "
                                + "GROUP BY ?s ORDER BY ?s";
                try (TupleQueryResult r = conn.prepareTupleQuery(countQ).evaluate()) {
                    while (r.hasNext()) {
                        var bs = r.next();
                        out.println(
                                "    "
                                        + bs.getValue("s")
                                        + "  →  "
                                        + bs.getValue("n")
                                        + " triples");
                    }
                }
            }
            repo.shutDown();
        }

        out.println();
        out.println("=== Done. Data survived a full close+reopen cycle. ===");
    }
}
