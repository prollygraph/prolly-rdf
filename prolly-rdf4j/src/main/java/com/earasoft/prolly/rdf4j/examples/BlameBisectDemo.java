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
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>git-style {@code blame} and {@code bisect}</b> over a ProllySail's
 * history.
 *
 * <p>ProllySail has no dedicated blame/bisect API — it doesn't need one. Every commit is a
 * queryable snapshot ({@link ProllySail#openSnapshotAt}), so both operations are a few lines on top
 * of that primitive:
 *
 * <ul>
 *   <li><b>blame</b> — walk the commits oldest→newest, return the first whose snapshot contains the
 *       triple in question;
 *   <li><b>bisect</b> — binary-search the commits for the transition point of a monotonic predicate
 *       (false early, true late), snapshot-querying each probe — O(log n) snapshots instead of
 *       O(n).
 * </ul>
 *
 * <p>The scenario: a service's {@code errorRate} is committed six times; at one commit it crosses
 * 100 and the service is marked {@code "degraded"}. Bisect finds the crossing; blame finds who
 * introduced the {@code degraded} marker — both converge on the same regression commit.
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.BlameBisectDemo \
 *     -Dexec.args="/tmp/prolly-blame-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class BlameBisectDemo {

    private BlameBisectDemo() {
        // static main only
    }

    private static final String P = "PREFIX svc: <urn:svc:> ";

    /** True once the service's errorRate has crossed 100. */
    private static final String ASK_ABOVE_100 =
            P + "ASK { svc:api svc:errorRate ?n FILTER(?n > 100) }";

    /** True once the service has been marked degraded. */
    private static final String ASK_DEGRADED = P + "ASK { svc:api svc:status \"degraded\" }";

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-blame-demo-");
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
        out.println("=== ProllySail blame & bisect demo ===");
        out.println("Store dir: " + dir);
        out.println();

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            BufferPool pool = new HeapBufferPool();
            ProllySail sail = new ProllySail(store, pool, RootMetaTreeStore.beside(dir));
            SailRepository repo = new SailRepository(sail);
            repo.init();

            // --- Record six commits; errorRate crosses 100 at commit 4 ---
            int[] errorRates = {2, 5, 8, 150, 160, 155};
            List<byte[]> commits = new ArrayList<>();
            List<String> messages = new ArrayList<>();

            out.println("[1] Recording 6 commits — svc:api's errorRate over time:");
            for (int i = 0; i < errorRates.length; i++) {
                int n = i + 1;
                int rate = errorRates[i];
                boolean degraded = (n == 4); // the regression commit

                List<String> updates = new ArrayList<>();
                updates.add(
                        P
                                + "DELETE { svc:api svc:errorRate ?o } "
                                + "WHERE { svc:api svc:errorRate ?o }");
                updates.add(P + "INSERT DATA { svc:api svc:errorRate " + rate + " }");
                if (degraded) {
                    updates.add(P + "INSERT DATA { svc:api svc:status \"degraded\" }");
                }

                String message =
                        "commit "
                                + n
                                + ": errorRate="
                                + rate
                                + (degraded ? ", status=degraded" : "");
                commit(repo, sail, message, updates.toArray(new String[0]));
                commits.add(sail.currentCommitHash());
                messages.add(message);
                out.println("    " + message);
            }
            repo.shutDown();

            // --- BISECT: O(log n) binary search for the regression commit ---
            out.println();
            out.println("[2] BISECT — first commit where errorRate crossed 100");
            out.println("    (binary search; the predicate must be monotonic, like git bisect)");
            int lo = 0;
            int hi = commits.size() - 1;
            int probes = 0;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                probes++;
                boolean above = ask(store, pool, commits.get(mid), ASK_ABOVE_100);
                out.println(
                        "      probe commit "
                                + (mid + 1)
                                + "  →  "
                                + (above ? "ABOVE 100" : "below 100"));
                if (above) {
                    hi = mid;
                } else {
                    lo = mid + 1;
                }
            }
            out.println(
                    "    ⇒ regression first appears at commit "
                            + (lo + 1)
                            + "   (\""
                            + messages.get(lo)
                            + "\")");
            out.println(
                    "    found in "
                            + probes
                            + " probes — a linear scan would take "
                            + commits.size());

            // --- BLAME: which commit introduced a specific triple ---
            out.println();
            out.println("[3] BLAME — which commit introduced  svc:api svc:status \"degraded\"");
            out.println("    (walk oldest→newest, snapshot-querying for the triple)");
            int blame = -1;
            for (int i = 0; i < commits.size(); i++) {
                if (ask(store, pool, commits.get(i), ASK_DEGRADED)) {
                    blame = i;
                    break;
                }
            }
            if (blame >= 0) {
                out.println(
                        "    ⇒ introduced by commit "
                                + (blame + 1)
                                + "   (\""
                                + messages.get(blame)
                                + "\")");
            } else {
                out.println("    ⇒ the triple was never present");
            }

            out.println();
            out.println("    Both land on commit 4 — the same regression, found two ways.");
        }

        out.println();
        out.println(
                "=== Done. blame & bisect are a few lines over snapshot queries; "
                        + "log / diff / revert compose the same way. ===");
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

    /** Evaluate a SPARQL ASK against {@code commit}'s snapshot. */
    private static boolean ask(NodeStore store, BufferPool pool, byte[] commit, String askQuery) {
        return onSnapshot(
                store, pool, commit, conn -> conn.prepareBooleanQuery(askQuery).evaluate());
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
}
