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
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.MergeEngine;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of ProllySail's <b>branch &amp; merge</b> — git-like version control for an
 * RDF graph.
 *
 * <p>The demo:
 *
 * <ol>
 *   <li>makes a base commit on {@code main} (Alice joins a team);
 *   <li>forks a {@code feature} branch from that base — opening the base as a snapshot, committing
 *       onto it, and registering the branch with {@link ProllySail#recordBranchCommit};
 *   <li>lets {@code main} diverge independently (Carol joins);
 *   <li><b>three-way merges</b> {@code feature} back into {@code main} with {@link
 *       MergeEngine#merge} and shows {@code main} now holds the union of both branches' work.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.BranchMergeDemo \
 *     -Dexec.args="/tmp/prolly-branch-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class BranchMergeDemo {

    private BranchMergeDemo() {
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
            dir = Files.createTempDirectory("prolly-branch-demo-");
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
        out.println("=== ProllySail branch & merge demo ===");
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

            ValueFactory vf = repo.getValueFactory();
            IRI role = vf.createIRI("urn:team:role");

            // --- Base commit on main: Alice ---
            out.println("[1] main — base commit: Alice joins as Engineer");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(vf.createIRI("urn:team:alice"), role, vf.createLiteral("Engineer"));
                sail.setNextCommitMessage("Base: Alice joins");
                conn.commit();
            }
            byte[] base =
                    Objects.requireNonNull(
                            sail.currentCommitHash()); // tree hash — for openSnapshotAt below
            byte[] baseId =
                    Objects.requireNonNull(
                            sail.currentCommitId()); // commit id — the parent the feature forks
            // from

            // --- Fork a 'feature' branch from the base, commit Dave onto it ---
            out.println("[2] branch 'feature' — fork from base, add Dave as Designer");
            byte[] featureHead;
            ProllySail snapshot =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            base);
            SailRepository snapshotRepo = new SailRepository(snapshot);
            snapshotRepo.init();
            try {
                try (RepositoryConnection conn = snapshotRepo.getConnection()) {
                    conn.begin();
                    conn.add(vf.createIRI("urn:team:dave"), role, vf.createLiteral("Designer"));
                    snapshot.setNextCommitMessage("feature: add Dave");
                    conn.commit();
                }
                featureHead = Objects.requireNonNull(snapshot.currentCommitHash());
            } finally {
                snapshotRepo.shutDown();
            }
            // Register the branch in the live Sail's commit log + refs/feature.
            sail.recordBranchCommit("feature", featureHead, baseId, "branch feature: add Dave");

            // --- main diverges independently: Carol ---
            out.println("[3] main — diverge: Carol joins as Manager");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(vf.createIRI("urn:team:carol"), role, vf.createLiteral("Manager"));
                sail.setNextCommitMessage("main: Carol joins");
                conn.commit();
            }

            out.println();
            out.println("[4] Before merge — main HEAD roster:");
            printRoster(repo, out);

            // --- Three-way merge feature → main ---
            out.println();
            out.println("[5] Merging branch 'feature' into main...");
            MergeEngine.MergeResult result = MergeEngine.merge(sail, repo, featureHead);
            out.print(
                    "    result: "
                            + result.kind()
                            + ", incoming triples: "
                            + result.incomingCount());
            if (result.newCommit() != null) {
                out.print(
                        ", merge commit: " + HashUtils.toHex(result.newCommit()).substring(0, 12));
            }
            out.println();

            out.println();
            out.println("[6] After merge — main HEAD roster (union of both branches):");
            printRoster(repo, out);

            out.println();
            out.println("[7] Branches on record:");
            RefsStore refs = sail.refsStore().orElseThrow();
            refs.list().entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    out.println(
                                            "    "
                                                    + e.getKey()
                                                    + " → "
                                                    + HashUtils.toHex(e.getValue())
                                                            .substring(0, 12)));

            repo.shutDown();
        }

        out.println();
        out.println("=== Done. 'feature' and 'main' converged via a 3-way merge. ===");
    }

    /** Print every {@code urn:team:role} statement at the repository's current HEAD. */
    private static void printRoster(SailRepository repo, PrintStream out) {
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(
                                        "SELECT ?who ?role WHERE { ?who <urn:team:role> ?role } ORDER BY ?who")
                                .evaluate()) {
            while (r.hasNext()) {
                var bs = r.next();
                out.println("    " + bs.getValue("who") + "  —  " + bs.getValue("role"));
            }
        }
    }
}
