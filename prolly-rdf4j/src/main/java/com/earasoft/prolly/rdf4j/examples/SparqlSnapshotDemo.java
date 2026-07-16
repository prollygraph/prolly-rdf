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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>SPARQL time-travel</b> — running ordinary SPARQL queries against a
 * historical commit's snapshot.
 *
 * <p>A ProllySail commit is a durable, content-addressed snapshot of the whole RDF dataset — the
 * default graph (triples) <em>and</em> every named graph (quads). {@link ProllySail#openSnapshotAt}
 * re-opens any commit as a read-only Sail, which is just an RDF4J {@code Repository} — so the full
 * SPARQL surface works against it.
 *
 * <p>What it shows:
 *
 * <ol>
 *   <li>three commits that evolve a price list (default graph) and an audit log (a named graph);
 *   <li>the <em>same</em> aggregate SPARQL query run against each commit's snapshot — one query,
 *       three answers, one per point in history;
 *   <li>a named-graph SPARQL query ({@code GRAPH ?g { ... }}) over a snapshot — time-travel works
 *       for quads exactly as it does for triples.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.SparqlSnapshotDemo \
 *     -Dexec.args="/tmp/prolly-sparql-snapshot-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class SparqlSnapshotDemo {

    private SparqlSnapshotDemo() {
        // static main only
    }

    private static final String P = "PREFIX x: <urn:x:> ";

    /** Total price across the default-graph catalogue. */
    private static final String SUM_PRICES =
            P + "SELECT (SUM(?price) AS ?total) WHERE { ?item x:price ?price }";

    /** The widget's current price (default graph). */
    private static final String WIDGET_PRICE =
            P + "SELECT ?price WHERE { x:widget x:price ?price }";

    /** Every audit note — these live in the x:audit NAMED graph (quads). */
    private static final String AUDIT_NOTES =
            P + "SELECT ?note WHERE { GRAPH x:audit { ?c x:note ?note } } ORDER BY ?c";

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-sparql-snapshot-demo-");
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
        out.println("=== ProllySail SPARQL time-travel demo ===");
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

            // --- Commit 1: initial price list + a note in the x:audit named graph ---
            out.println("[1] Commit 1 — initial price list (widget 10, gadget 20)");
            commit(
                    repo,
                    sail,
                    "Initial price list",
                    P
                            + """
                INSERT DATA {
                  x:widget x:price 10 .
                  x:gadget x:price 20 .
                  GRAPH x:audit { x:c1 x:note "initial prices" }
                }""");
            byte[] c1 = Objects.requireNonNull(sail.currentCommitHash());

            // --- Commit 2: a SPARQL UPDATE rewriting the widget's price ---
            out.println("[2] Commit 2 — widget price 10 → 12 (SPARQL DELETE/INSERT)");
            commit(
                    repo,
                    sail,
                    "Widget price up",
                    P
                            + """
                DELETE { x:widget x:price ?old }
                INSERT { x:widget x:price 12 }
                WHERE  { x:widget x:price ?old }""",
                    P + "INSERT DATA { GRAPH x:audit { x:c2 x:note \"widget price +2\" } }");
            byte[] c2 = Objects.requireNonNull(sail.currentCommitHash());

            // --- Commit 3: add a new product ---
            out.println("[3] Commit 3 — add 'gizmo' at price 30");
            commit(
                    repo,
                    sail,
                    "Add gizmo",
                    P
                            + """
                INSERT DATA {
                  x:gizmo x:price 30 .
                  GRAPH x:audit { x:c3 x:note "added gizmo" }
                }""");
            byte[] c3 = Objects.requireNonNull(sail.currentCommitHash());

            repo.shutDown();

            // --- The SAME SPARQL aggregate, run against each commit's snapshot ---
            out.println();
            out.println("[4] Time-travel SPARQL — total catalogue value at each commit");
            out.println("    query: SELECT (SUM(?price) AS ?total) WHERE { ?item x:price ?price }");
            out.println("    commit 1        : " + scalarAt(store, pool, c1, SUM_PRICES, "total"));
            out.println("    commit 2        : " + scalarAt(store, pool, c2, SUM_PRICES, "total"));
            out.println("    commit 3 (HEAD) : " + scalarAt(store, pool, c3, SUM_PRICES, "total"));

            out.println();
            out.println("[5] Time-travel SPARQL — the widget's price at each commit");
            out.println(
                    "    commit 1        : " + scalarAt(store, pool, c1, WIDGET_PRICE, "price"));
            out.println(
                    "    commit 2        : " + scalarAt(store, pool, c2, WIDGET_PRICE, "price"));
            out.println(
                    "    commit 3 (HEAD) : " + scalarAt(store, pool, c3, WIDGET_PRICE, "price"));

            // --- A named-graph (quad) SPARQL query, against a snapshot ---
            out.println();
            out.println("[6] Time-travel SPARQL over a NAMED GRAPH — audit log at HEAD");
            out.println("    query: SELECT ?note WHERE { GRAPH x:audit { ?c x:note ?note } }");
            for (String note : columnAt(store, pool, c3, AUDIT_NOTES, "note")) {
                out.println("    • " + note);
            }
            out.println(
                    "    (and at commit 1 the same query sees only: "
                            + columnAt(store, pool, c1, AUDIT_NOTES, "note")
                            + ")");
        }

        out.println();
        out.println("=== Done. One SPARQL query, any commit — triples and quads alike. ===");
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

    /** First binding of {@code var} from {@code query} run against {@code commit}'s snapshot. */
    private static String scalarAt(
            NodeStore store, BufferPool pool, byte[] commit, String query, String var) {
        return onSnapshot(
                store,
                pool,
                commit,
                conn -> {
                    try (TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
                        return r.hasNext() ? r.next().getValue(var).stringValue() : "(no result)";
                    }
                });
    }

    /** All bindings of {@code var} from {@code query} run against {@code commit}'s snapshot. */
    private static List<String> columnAt(
            NodeStore store, BufferPool pool, byte[] commit, String query, String var) {
        return onSnapshot(
                store,
                pool,
                commit,
                conn -> {
                    List<String> out = new ArrayList<>();
                    try (TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
                        while (r.hasNext()) {
                            out.add(r.next().getValue(var).stringValue());
                        }
                    }
                    return out;
                });
    }

    /**
     * Open {@code commit} as a read-only snapshot Sail — itself a full RDF4J {@code Repository}, so
     * any SPARQL query (triples or named-graph quads) runs against it — apply {@code work}, then
     * tear the snapshot down.
     */
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
}
