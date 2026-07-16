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
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>revert / rollback</b> on a ProllySail.
 *
 * <p>A commit is identified by the content hash of its {@code RootMetaTree}; the {@link
 * RootMetaTreeStore} just holds a <em>pointer</em> to the current one. So reverting is a pointer
 * move — and because every commit's chunks are content-addressed and immutable, <b>nothing is
 * destroyed</b>: a revert is fully reversible, you can roll forward again afterward.
 *
 * <p>What it shows:
 *
 * <ol>
 *   <li>three commits adding club members Alice, Bob, Carol;
 *   <li>revert — re-point the manifest at commit 1 and re-open: HEAD shows only Alice;
 *   <li>roll forward — re-point at commit 3 and re-open: Bob and Carol are back, untouched. The
 *       "lost" commits were never lost.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.RevertDemo \
 *     -Dexec.args="/tmp/prolly-revert-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class RevertDemo {

    private RevertDemo() {
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
            dir = Files.createTempDirectory("prolly-revert-demo-");
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
        out.println("=== ProllySail revert / rollback demo ===");
        out.println("Store dir: " + dir);
        out.println();

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            BufferPool pool = new HeapBufferPool();
            RootMetaTreeStore manifest = RootMetaTreeStore.beside(dir);

            byte[] commit1;
            byte[] commit3;

            // --- Three commits: Alice, then Bob, then Carol ---
            ProllySail sail = new ProllySail(store, pool, manifest);
            SailRepository repo = new SailRepository(sail);
            repo.init();
            ValueFactory vf = repo.getValueFactory();
            IRI club = vf.createIRI("urn:club:acme");
            IRI member = vf.createIRI("urn:club:member");

            out.println("[1] Commit 1 — Alice joins");
            commit(repo, c -> c.add(club, member, vf.createLiteral("Alice")));
            commit1 = Objects.requireNonNull(sail.currentCommitHash());

            out.println("[2] Commit 2 — Bob joins");
            commit(repo, c -> c.add(club, member, vf.createLiteral("Bob")));

            out.println("[3] Commit 3 — Carol joins");
            commit(repo, c -> c.add(club, member, vf.createLiteral("Carol")));
            commit3 = Objects.requireNonNull(sail.currentCommitHash());

            out.println();
            out.println("[4] HEAD now — members: " + members(repo));
            repo.shutDown();

            // --- Revert: re-point the manifest at commit 1, then re-open ---
            out.println();
            out.println("[5] Revert — re-point the manifest at commit 1, re-open the Sail:");
            manifest.put(commit1);
            out.println("    members after revert : " + reopenMembers(store, pool, manifest));

            // --- Roll forward: the later commits were never destroyed ---
            out.println();
            out.println("[6] Roll forward — content-addressed history is immutable, so");
            out.println("    re-pointing the manifest at commit 3 brings it all back:");
            manifest.put(commit3);
            out.println("    members after roll-forward : " + reopenMembers(store, pool, manifest));
        }

        out.println();
        out.println("=== Done. Revert is a pointer move — every commit's data survives. ===");
    }

    /** Commit {@code work} on the live Sail. */
    private static void commit(SailRepository repo, Consumer<RepositoryConnection> work) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            work.accept(conn);
            conn.commit();
        }
    }

    /** Open a fresh Sail over {@code manifest} (auto-restoring HEAD) and list members. */
    private static String reopenMembers(
            NodeStore store, BufferPool pool, RootMetaTreeStore manifest) {
        ProllySail sail = new ProllySail(store, pool, manifest);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            return members(repo);
        } finally {
            repo.shutDown();
        }
    }

    /** The club's members at the repository's current HEAD, sorted. */
    private static String members(SailRepository repo) {
        List<String> names = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(
                                        "SELECT ?m WHERE { <urn:club:acme> <urn:club:member> ?m } ORDER BY ?m")
                                .evaluate()) {
            while (r.hasNext()) {
                names.add(r.next().getValue("m").stringValue());
            }
        }
        return names.toString();
    }
}
