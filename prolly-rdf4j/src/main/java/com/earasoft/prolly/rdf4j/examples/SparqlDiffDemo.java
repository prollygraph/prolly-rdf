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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>diffing two commits</b> — "what changed?" — by running a SPARQL query
 * against each commit's snapshot and taking the set difference of the results.
 *
 * <p>A ProllySail commit is a content-addressed snapshot, so any two commits can be re-opened side
 * by side ({@link ProllySail#openSnapshotAt}) and compared. This demo makes three commits that add,
 * rewrite, and remove triples, then prints the {@code +}/{@code -} delta between commit pairs.
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.SparqlDiffDemo \
 *     -Dexec.args="/tmp/prolly-sparql-diff-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class SparqlDiffDemo {

    private SparqlDiffDemo() {
        // static main only
    }

    private static final String P = "PREFIX t: <urn:team:> ";

    /** Every triple in the graph — materialised so two commits can be set-differenced. */
    private static final String ALL_TRIPLES = "SELECT ?s ?p ?o WHERE { ?s ?p ?o }";

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-sparql-diff-demo-");
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
        out.println("=== ProllySail SPARQL diff demo ===");
        out.println("Store dir: " + dir);
        out.println();

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            BufferPool pool = new HeapBufferPool();
            ProllySail sail =
                    new ProllySail(
                            store,
                            pool,
                            RootMetaTreeStore.beside(dir),
                            CommitLog.beside(dir),
                            RefsStore.beside(dir));
            SailRepository repo = new SailRepository(sail);
            repo.init();

            out.println("[1] Commit 1 — initial team: Alice (Engineer), Bob (Designer)");
            commit(
                    repo,
                    sail,
                    "Initial team",
                    P + "INSERT DATA { t:alice t:role \"Engineer\" . t:bob t:role \"Designer\" }");
            byte[] c1 = Objects.requireNonNull(sail.currentCommitHash());

            out.println("[2] Commit 2 — promote Alice to Lead, hire Carol");
            commit(
                    repo,
                    sail,
                    "Promote Alice; hire Carol",
                    P
                            + "DELETE { t:alice t:role ?r } INSERT { t:alice t:role \"Lead\" } "
                            + "WHERE { t:alice t:role ?r }",
                    P + "INSERT DATA { t:carol t:role \"Designer\" }");
            byte[] c2 = Objects.requireNonNull(sail.currentCommitHash());

            out.println("[3] Commit 3 — Bob departs");
            commit(repo, sail, "Bob departs", P + "DELETE DATA { t:bob t:role \"Designer\" }");
            byte[] c3 = Objects.requireNonNull(sail.currentCommitHash());

            repo.shutDown();

            out.println();
            out.println("[4] Diff — commit 1 → commit 2:");
            printDiff(store, pool, out, c1, c2);

            out.println();
            out.println("[5] Diff — commit 2 → commit 3:");
            printDiff(store, pool, out, c2, c3);

            out.println();
            out.println("[6] Diff — commit 1 → commit 3 (cumulative):");
            printDiff(store, pool, out, c1, c3);
        }

        out.println();
        out.println("=== Done. Any two commits diff by set-differencing their snapshots. ===");
    }

    /** Print the {@code +}/{@code -} delta between two commits' triple sets. */
    private static void printDiff(
            NodeStore store, BufferPool pool, PrintStream out, byte[] from, byte[] to) {
        Set<String> before = triplesAt(store, pool, from);
        Set<String> after = triplesAt(store, pool, to);

        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        Set<String> removed = new TreeSet<>(before);
        removed.removeAll(after);

        if (added.isEmpty() && removed.isEmpty()) {
            out.println("    (no change)");
            return;
        }
        for (String t : added) out.println("    + " + t);
        for (String t : removed) out.println("    - " + t);
    }

    /** Materialise every triple in {@code commit}'s snapshot as a sorted set of strings. */
    private static Set<String> triplesAt(NodeStore store, BufferPool pool, byte[] commit) {
        return onSnapshot(
                store,
                pool,
                commit,
                conn -> {
                    Set<String> triples = new TreeSet<>();
                    try (TupleQueryResult r = conn.prepareTupleQuery(ALL_TRIPLES).evaluate()) {
                        while (r.hasNext()) {
                            BindingSet bs = r.next();
                            triples.add(
                                    local(bs.getValue("s"))
                                            + " "
                                            + local(bs.getValue("p"))
                                            + " \""
                                            + bs.getValue("o").stringValue()
                                            + "\"");
                        }
                    }
                    return triples;
                });
    }

    /** Apply each SPARQL update inside one transaction → one tagged commit. */
    private static void commit(
            SailRepository repo, ProllySail sail, String message, String... updates) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (String u : updates) {
                conn.prepareUpdate(u).execute();
            }
            sail.setNextCommitMessage(message);
            conn.commit();
        }
    }

    /** Open {@code commit} as a read-only snapshot, apply {@code work}, tear it down. */
    private static <T> T onSnapshot(
            NodeStore store,
            BufferPool pool,
            byte[] commit,
            Function<RepositoryConnection, T> work) {
        ProllySail snapshot =
                ProllySail.openSnapshotAt(
                        store,
                        pool,
                        new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                        commit);
        SailRepository repo = new SailRepository(snapshot);
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            return work.apply(conn);
        } finally {
            repo.shutDown();
        }
    }

    /** Local name of an IRI ({@code urn:team:alice} → {@code alice}); literals pass through. */
    private static String local(Value v) {
        String s = v.stringValue();
        int colon = s.lastIndexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }
}
