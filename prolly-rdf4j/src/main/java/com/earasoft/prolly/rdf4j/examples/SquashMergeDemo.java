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
import java.util.function.Consumer;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>squash-merge</b> — collapsing a branch's work into a single commit on
 * the target.
 *
 * <p>Where {@link MergeEngine#merge} records a two-parent merge commit (it keeps both histories),
 * {@link MergeEngine#squashMerge} applies the source branch's <em>net</em> change as one ordinary
 * single-parent commit — the target's history stays linear, as if the feature work had been done in
 * one step. This demo:
 *
 * <ol>
 *   <li>commits a base on {@code main};
 *   <li>does two commits of work on a {@code feature} branch;
 *   <li>advances {@code main} independently;
 *   <li>squash-merges {@code feature} — and shows the result landed as a single <em>one-parent</em>
 *       commit carrying the branch's net diff.
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.SquashMergeDemo \
 *     -Dexec.args="/tmp/prolly-squash-demo"
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 */
public final class SquashMergeDemo {

    private SquashMergeDemo() {
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
            dir = Files.createTempDirectory("prolly-squash-demo-");
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
        out.println("=== ProllySail squash-merge demo ===");
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
            IRI alice = vf.createIRI("urn:team:alice");

            // [1] base commit on main
            out.println("[1] main — base commit: Alice joins as Engineer");
            commitMain(
                    repo,
                    sail,
                    "Base: Alice joins",
                    c -> c.add(alice, role, vf.createLiteral("Engineer")));
            byte[] base =
                    Objects.requireNonNull(
                            sail.currentCommitHash()); // tree hash — for openSnapshotAt below
            byte[] baseId =
                    Objects.requireNonNull(
                            sail.currentCommitId()); // commit id — the parent the feature forks
            // from

            // [2] two commits of work on a 'feature' branch forked from base
            out.println("[2] branch 'feature' — two commits of work off the base:");
            out.println("      commit A — add Bob, add Carol");
            out.println("      commit B — promote Alice (Engineer → Lead)");
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
                try (RepositoryConnection c = snapshotRepo.getConnection()) {
                    c.begin();
                    c.add(vf.createIRI("urn:team:bob"), role, vf.createLiteral("Designer"));
                    c.add(vf.createIRI("urn:team:carol"), role, vf.createLiteral("Manager"));
                    snapshot.setNextCommitMessage("feature A: add Bob & Carol");
                    c.commit();
                }
                try (RepositoryConnection c = snapshotRepo.getConnection()) {
                    c.begin();
                    c.remove(alice, role, vf.createLiteral("Engineer"));
                    c.add(alice, role, vf.createLiteral("Lead"));
                    snapshot.setNextCommitMessage("feature B: promote Alice");
                    c.commit();
                }
                featureHead = Objects.requireNonNull(snapshot.currentCommitHash());
            } finally {
                snapshotRepo.shutDown();
            }
            sail.recordBranchCommit("feature", featureHead, baseId, "branch feature");

            // [3] main advances independently
            out.println("[3] main — meanwhile commits independently: add Dave");
            commitMain(
                    repo,
                    sail,
                    "main: add Dave",
                    c -> c.add(vf.createIRI("urn:team:dave"), role, vf.createLiteral("Intern")));

            out.println();
            out.println("[4] Before squash — main HEAD roster:");
            printRoster(repo, out);

            // [5] squash-merge feature into main
            out.println();
            out.println("[5] Squash-merging 'feature' into main...");
            MergeEngine.SquashResult result =
                    MergeEngine.squashMerge(sail, repo, "feature", "Squash: feature branch work");
            if (result.isEmpty()) {
                out.println("    (nothing to squash)");
            } else {
                out.println(
                        "    SquashResult — added "
                                + result.added()
                                + " triple(s), removed "
                                + result.removed()
                                + " triple(s)");
                out.println(
                        "    squash commit: "
                                + HashUtils.toHex(Objects.requireNonNull(result.newCommit()))
                                        .substring(0, 12));
            }

            out.println();
            out.println("[6] After squash — main HEAD roster (feature's net changes applied):");
            printRoster(repo, out);

            // [7] the squash commit is single-parent — linear history
            out.println();
            CommitLog.Entry squashEntry =
                    sail.commitLog()
                            .orElseThrow()
                            .findById(Objects.requireNonNull(result.newCommit()))
                            .orElseThrow();
            out.println(
                    "[7] The squash landed as ONE commit with "
                            + squashEntry.parents().size()
                            + " parent — main's history stays linear.");
            out.println("    (A regular 3-way merge would instead record 2 parents.)");

            repo.shutDown();
        }

        out.println();
        out.println("=== Done. Squash-merge collapses a branch's work into one commit. ===");
    }

    /** Commit {@code work} on the live main branch with a message. */
    private static void commitMain(
            SailRepository repo,
            ProllySail sail,
            String message,
            Consumer<RepositoryConnection> work) {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            work.accept(conn);
            sail.setNextCommitMessage(message);
            conn.commit();
        }
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
