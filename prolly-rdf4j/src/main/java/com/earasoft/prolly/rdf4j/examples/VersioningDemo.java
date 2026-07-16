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
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.MergeEngine;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.FileNodeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of ProllySail's <b>versioning &amp; time-travel</b>.
 *
 * <p>Unlike a plain RDF store, every {@code commit()} on a ProllySail is a durable,
 * content-addressed snapshot. This demo:
 *
 * <ol>
 *   <li>makes three commits — each with a human-readable message — that catalog books, one of them
 *       <em>correcting</em> an earlier mistake;
 *   <li>prints the commit history straight from the {@link CommitLog};
 *   <li><b>time-travels</b>: re-opens each historical commit as a read-only snapshot ({@link
 *       ProllySail#openSnapshotAt}) and queries it — showing the graph exactly as it was at that
 *       point in history;
 *   <li><b>branches &amp; merges — both policies</b>: forks branches off HEAD and shows
 *       ProllySail's two merge modes: the loose set-union {@link MergeEngine#merge} (combines
 *       disjoint changes into the union of both branches' books) and the strict 3-way {@link
 *       MergeEngine#mergeStructural} (which detects a key→value conflict — two branches rebinding
 *       the same prefix to different IRIs — and refuses it). RDF triples are a set, so only a
 *       key→value tree like the namespace map can actually conflict;
 *   <li><b>squash-merges</b> a multi-commit feature branch ({@link MergeEngine#squashMerge}) — the
 *       branch's two commits collapse into a single net-change commit on main (linear history);
 *   <li><b>diffs</b> two commits by set-differencing their snapshots — the {@code +}/{@code -}
 *       delta behind the history (Dune's year correction shows as a {@code -} old / {@code +} new
 *       pair).
 * </ol>
 *
 * <p>Run from CLI:
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.VersioningDemo \
 *     -Dexec.args="--store=file /tmp/prolly-versioning-demo"
 * </pre>
 *
 * <p>Arguments: an optional {@code --store=file|rocks} chooses the {@link NodeStore} backend
 * (default {@code rocks}) — the {@code ProllySail} narrative is identical over both, since the Sail
 * is typed to the {@code NodeStore} interface; an optional {@code <store-dir>} sets the store
 * location (a temp directory is used and removed on normal exit when omitted).
 */
public final class VersioningDemo {

    private VersioningDemo() {
        // static main only
    }

    private static final String USAGE =
            "Usage: VersioningDemo [--store=file|rocks] [<store-dir>]\n"
                    + "  --store=rocks  packed RocksDB backend (default)\n"
                    + "  --store=file   git-loose-objects filesystem backend\n"
                    + "  <store-dir>    optional; a temp dir is created + deleted when omitted";

    /**
     * Which {@link NodeStore} backend the demo runs on, chosen by {@code --store=}. The Sail is
     * identical over both — {@code ProllySail} is typed entirely to the {@code NodeStore}
     * interface, so the only place the concrete backend appears is where the store is constructed
     * (see {@link #run(Path, StoreKind, PrintStream)}). This enum makes that one choice explicit.
     */
    public enum StoreKind {
        ROCKS,
        FILE;

        /** A one-line description of the backend, for the demo header. */
        String label() {
            return switch (this) {
                case ROCKS -> "RocksNodeStore — packed RocksDB (production default)";
                case FILE -> "FileNodeStore — git-loose-objects on the filesystem (<dir>/chunks/)";
            };
        }

        /**
         * Parses a {@code --store=} value (case-insensitive via {@code Locale.ROOT}); throws with
         * guidance on an unknown value.
         */
        static StoreKind fromArg(String value) {
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "rocks" -> ROCKS;
                case "file" -> FILE;
                default ->
                        throw new IllegalArgumentException(
                                "unknown --store value '"
                                        + value
                                        + "'; expected 'file' or 'rocks'");
            };
        }
    }

    public static void main(String[] args) throws IOException, RocksDBException {
        StoreKind storeKind = StoreKind.ROCKS;
        String dirArg = null;
        for (String arg : args) {
            if (arg.equals("-h") || arg.equals("--help")) {
                System.out.println(USAGE);
                return;
            } else if (arg.startsWith("--store=")) {
                try {
                    storeKind = StoreKind.fromArg(arg.substring("--store=".length()));
                } catch (IllegalArgumentException e) {
                    System.err.println(e.getMessage());
                    System.err.println(USAGE);
                    return;
                }
            } else if (dirArg == null) {
                dirArg = arg;
            } else {
                System.err.println("unexpected argument: " + arg);
                System.err.println(USAGE);
                return;
            }
        }

        Path dir;
        boolean ephemeral;
        if (dirArg != null) {
            dir = Path.of(dirArg);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-versioning-demo-");
            ephemeral = true;
        }

        run(dir, storeKind, System.out);

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
     * Run the demo against {@code dir} on the default RocksDB backend. Public so tests can drive it
     * with a captured stream and assert on the output.
     */
    public static void run(Path dir, PrintStream out) throws IOException, RocksDBException {
        run(dir, StoreKind.ROCKS, out);
    }

    /**
     * Run the demo against {@code dir} on the chosen {@code storeKind} backend. The <b>only</b>
     * place the concrete backend appears: it constructs the matching {@link NodeStore} in a
     * try-with-resources, then hands it to the backend-agnostic {@link #runDemo} — which is typed
     * to the {@code NodeStore} interface, exactly as {@code ProllySail} itself is. Swapping
     * backends changes this one construction site and nothing downstream.
     */
    public static void run(Path dir, StoreKind storeKind, PrintStream out)
            throws IOException, RocksDBException {
        out.println("=== ProllySail versioning & time-travel demo ===");
        out.println("Store dir: " + dir);
        out.println("Backend  : " + storeKind.label());
        out.println();

        switch (storeKind) {
            case ROCKS -> {
                try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                    runDemo(store, dir, out);
                }
            }
            case FILE -> {
                try (FileNodeStore store = new FileNodeStore(dir.resolve("chunks"))) {
                    runDemo(store, dir, out);
                }
            }
        }
    }

    /** The backend-agnostic demo body — operates purely on the {@link NodeStore} interface. */
    private static void runDemo(NodeStore store, Path dir, PrintStream out)
            throws IOException, RocksDBException {
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
        IRI dune = vf.createIRI("urn:book:dune");
        IRI foundation = vf.createIRI("urn:book:foundation");
        IRI title = vf.createIRI("urn:book:title");
        IRI year = vf.createIRI("urn:book:year");

        // --- Commit 1: catalog Dune (with a deliberately wrong year) ---
        out.println("[1] Commit 1 — catalog 'Dune' (year recorded as 1965)");
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.add(dune, title, vf.createLiteral("Dune"));
            conn.add(dune, year, vf.createLiteral(1965));
            sail.setNextCommitMessage("Catalog Dune");
            conn.commit();
        }
        byte[] c1 = Objects.requireNonNull(sail.currentCommitHash());

        // --- Commit 2: add Foundation ---
        out.println("[2] Commit 2 — add 'Foundation'");
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.add(foundation, title, vf.createLiteral("Foundation"));
            conn.add(foundation, year, vf.createLiteral(1951));
            sail.setNextCommitMessage("Add Foundation");
            conn.commit();
        }
        byte[] c2 = Objects.requireNonNull(sail.currentCommitHash());

        // --- Commit 3: correct Dune's publication year ---
        out.println("[3] Commit 3 — correct Dune's year (1965 → 1966)");
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.remove(dune, year, vf.createLiteral(1965));
            conn.add(dune, year, vf.createLiteral(1966));
            sail.setNextCommitMessage("Fix Dune year");
            conn.commit();
        }
        byte[] c3 = Objects.requireNonNull(sail.currentCommitHash());

        // --- Commit history, straight from the commit log ---
        out.println();
        out.println("[4] Commit history (oldest first):");
        for (CommitLog.Entry e : sail.commitLog().orElseThrow().entries()) {
            out.printf(
                    "    %s  %s  \"%s\"%n", e.hashHex().substring(0, 12), e.rfc1123(), e.message());
        }

        // --- Time-travel: query each historical commit as a snapshot ---
        out.println();
        out.println("[5] Time-travel — full catalog at each commit (every book + its year):");
        out.println("    commit 1        : " + catalogAt(store, pool, c1));
        out.println("    commit 2        : " + catalogAt(store, pool, c2));
        out.println("    commit 3 (HEAD) : " + catalogAt(store, pool, c3));

        out.println();
        out.println("[6] Time-travel — zoom on one fact: Dune's year (watch the correction):");
        out.println("    commit 1        : " + duneYearAt(store, pool, c1));
        out.println("    commit 2        : " + duneYearAt(store, pool, c2));
        out.println("    commit 3 (HEAD) : " + duneYearAt(store, pool, c3));

        out.println();
        out.println("[7] Time-travel — catalog size at each commit (growth vs in-place fix):");
        out.println("    commit 1        : " + bookCountAt(store, pool, c1));
        out.println("    commit 2        : " + bookCountAt(store, pool, c2));
        out.println("    commit 3 (HEAD) : " + bookCountAt(store, pool, c3));

        // --- Branch & merge: fork off HEAD, let main diverge, then 3-way merge back ---
        out.println();
        out.println("[8] Branch 'expansion' — fork from HEAD (commit 3), add 'Neuromancer' (1984)");
        byte[] expansionHead;
        ProllySail branchSnap =
                ProllySail.openSnapshotAt(
                        store,
                        pool,
                        new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                        c3);
        SailRepository branchRepo = new SailRepository(branchSnap);
        branchRepo.init();
        try {
            IRI neuromancer = vf.createIRI("urn:book:neuromancer");
            try (RepositoryConnection conn = branchRepo.getConnection()) {
                conn.begin();
                conn.add(neuromancer, title, vf.createLiteral("Neuromancer"));
                conn.add(neuromancer, year, vf.createLiteral(1984));
                branchSnap.setNextCommitMessage("expansion: add Neuromancer");
                conn.commit();
            }
            expansionHead = Objects.requireNonNull(branchSnap.currentCommitHash());
        } finally {
            branchRepo.shutDown();
        }
        sail.recordBranchCommit(
                "expansion", expansionHead, c3, "branch expansion: add Neuromancer");

        out.println("[9] main — diverge independently: add 'Hyperion' (1989)");
        IRI hyperion = vf.createIRI("urn:book:hyperion");
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.add(hyperion, title, vf.createLiteral("Hyperion"));
            conn.add(hyperion, year, vf.createLiteral(1989));
            sail.setNextCommitMessage("main: add Hyperion");
            conn.commit();
        }

        // ProllySail has two merge policies — a loose set-union and a strict 3-way. Show both.
        out.println();
        out.println(
                "[10] Policy 1 — set-union merge (MergeEngine.merge): combine disjoint changes;"
                        + " RDF triples are a set, so nothing conflicts:");
        MergeEngine.MergeResult union = MergeEngine.merge(sail, repo, expansionHead);
        out.print("    result: " + union.kind());
        if (union.newCommit() != null) {
            out.print(" — merge commit " + HashUtils.toHex(union.newCommit()).substring(0, 12));
        }
        out.println();
        out.println(
                "    catalog at merged HEAD (union of both branches): "
                        + catalogAt(store, pool, Objects.requireNonNull(sail.currentCommitHash())));

        // --- A merge that CONFLICTS: same key, two different values ---
        // RDF triples are a set, so adding different books never conflicts (above). A genuine
        // conflict needs the same KEY to take two different VALUES — e.g. a namespace prefix
        // remapped both ways. The strict 3-way structural merge detects it, installs nothing,
        // and reports the clashing pair.
        out.println();
        out.println(
                "[11] Policy 2 — strict 3-way merge (MergeEngine.mergeStructural): detects a"
                        + " conflict when two branches rebind prefix 'shelf' to different IRIs:");
        byte[] mergedHead = Objects.requireNonNull(sail.currentCommitHash());
        byte[] relabelA;
        ProllySail relabelSnap =
                ProllySail.openSnapshotAt(
                        store,
                        pool,
                        new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                        mergedHead);
        SailRepository relabelRepo = new SailRepository(relabelSnap);
        relabelRepo.init();
        try {
            try (RepositoryConnection conn = relabelRepo.getConnection()) {
                conn.begin();
                conn.setNamespace("shelf", "http://example.org/library-a#");
                relabelSnap.setNextCommitMessage("relabel: shelf -> library-a");
                conn.commit();
            }
            relabelA = Objects.requireNonNull(relabelSnap.currentCommitHash());
        } finally {
            relabelRepo.shutDown();
        }
        sail.recordBranchCommit("relabel-a", relabelA, mergedHead, "branch relabel-a");
        out.println("    branch 'relabel-a' : shelf -> http://example.org/library-a#");

        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.setNamespace("shelf", "http://example.org/library-b#");
            sail.setNextCommitMessage("main: shelf -> library-b");
            conn.commit();
        }
        out.println("    main (HEAD)        : shelf -> http://example.org/library-b#");

        byte[] headBeforeConflict = Objects.requireNonNull(sail.currentCommitHash());
        MergeEngine.MergeResult conflicted = MergeEngine.mergeStructural(sail, relabelA);
        out.println();
        out.println("    structural merge result: " + conflicted.kind());
        for (MergeEngine.Conflict c : conflicted.conflicts()) {
            out.println(
                    "      conflict on prefix '"
                            + c.subject().value()
                            + "' — main has <"
                            + (c.targetValue() == null ? "(unset)" : c.targetValue().value())
                            + ">, incoming has <"
                            + (c.sourceValue() == null ? "(unset)" : c.sourceValue().value())
                            + ">");
        }
        out.println(
                "    refused safely — HEAD unchanged: "
                        + java.util.Arrays.equals(headBeforeConflict, sail.currentCommitHash())
                        + ", nothing installed (no merge commit: "
                        + (conflicted.newCommit() == null)
                        + ")");

        // --- Squash merge: a multi-commit feature branch collapses to ONE clean commit ---
        out.println();
        out.println(
                "[12] Squash merge — a feature branch's two commits collapse into ONE clean"
                        + " commit on main (linear history, no merge commit):");
        byte[] curationBase = Objects.requireNonNull(sail.currentCommitHash());
        IRI snowCrash = vf.createIRI("urn:book:snowcrash");
        IRI anathem = vf.createIRI("urn:book:anathem");
        byte[] cur1 =
                forkCommit(
                        store,
                        pool,
                        sail,
                        curationBase,
                        "curation",
                        "curation: add Snow Crash",
                        conn -> {
                            conn.add(snowCrash, title, vf.createLiteral("Snow Crash"));
                            conn.add(snowCrash, year, vf.createLiteral(1992));
                        });
        forkCommit(
                store,
                pool,
                sail,
                cur1,
                "curation",
                "curation: add Anathem",
                conn -> {
                    conn.add(anathem, title, vf.createLiteral("Anathem"));
                    conn.add(anathem, year, vf.createLiteral(2008));
                });
        out.println("    branch 'curation' made 2 commits — + Snow Crash (1992), + Anathem (2008)");
        MergeEngine.SquashResult sq =
                MergeEngine.squashMerge(sail, repo, "curation", "Squash: curation branch");
        out.print("    squash result: " + sq.added() + " triple(s) applied as ONE commit");
        if (sq.newCommit() != null) {
            out.print(" " + HashUtils.toHex(sq.newCommit()).substring(0, 12));
        }
        out.println();
        out.println(
                "    catalog at HEAD: "
                        + catalogAt(store, pool, Objects.requireNonNull(sail.currentCommitHash())));

        // --- Diff: the +/- delta between commits (set-difference of their snapshots) ---
        out.println();
        out.println("[13] Diff — what each commit changed (set-difference of the catalogs):");
        printCatalogDiff(store, pool, out, "1 → 2", c1, c2);
        printCatalogDiff(store, pool, out, "2 → 3", c2, c3);

        repo.shutDown();

        out.println();
        out.println("=== Done. Every commit stays independently queryable. ===");
    }

    /**
     * A query result paired with how long its SPARQL evaluation took, in milliseconds — timing the
     * {@code prepareTupleQuery + evaluate + drain}, not the snapshot open. Its {@code toString}
     * appends the timing so a caller can print {@code value + " (X ms)"} just by string-concat.
     */
    private record Timed<T>(T value, double millis) {
        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%s  (%.2f ms)", value, millis);
        }
    }

    /**
     * ALL of Dune's {@code urn:book:year} values at {@code commit}, as a list — kept multi-valued
     * on purpose (a debugging affordance): a single-fact predicate should hold exactly one year, so
     * a stray duplicate (e.g. a {@code remove} that failed to match, leaving {@code [1965, 1966]})
     * is visible here rather than hidden behind a "first value wins" scalar. Do not simplify back
     * to a scalar — that would re-hide exactly the correctness bug this view is meant to catch.
     */
    private static Timed<List<String>> duneYearAt(NodeStore store, BufferPool pool, byte[] commit) {
        return onSnapshot(
                store,
                pool,
                commit,
                conn -> {
                    String q = "SELECT ?y WHERE { <urn:book:dune> <urn:book:year> ?y }";
                    long t0 = System.nanoTime();
                    List<String> years = new ArrayList<>();
                    try (TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
                        while (r.hasNext()) {
                            years.add(r.next().getValue("y").stringValue());
                        }
                    }
                    return new Timed<>(years, (System.nanoTime() - t0) / 1_000_000.0);
                });
    }

    /**
     * Every catalogued book as {@code "title (year)"} at {@code commit}, sorted by title — the full
     * graph state, so the catalog's growth (Foundation added at commit 2) AND Dune's in-place year
     * correction (commit 3) are both visible across the snapshots.
     */
    private static Timed<List<String>> catalogAt(NodeStore store, BufferPool pool, byte[] commit) {
        return onSnapshot(
                store,
                pool,
                commit,
                conn -> {
                    String q =
                            "SELECT ?t ?y WHERE { ?b <urn:book:title> ?t ; <urn:book:year> ?y }"
                                    + " ORDER BY ?t";
                    long t0 = System.nanoTime();
                    List<String> books = new ArrayList<>();
                    try (TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
                        while (r.hasNext()) {
                            var row = r.next();
                            books.add(
                                    row.getValue("t").stringValue()
                                            + " ("
                                            + row.getValue("y").stringValue()
                                            + ")");
                        }
                    }
                    return new Timed<>(books, (System.nanoTime() - t0) / 1_000_000.0);
                });
    }

    /** Number of distinct catalogued books at {@code commit}, rendered as {@code "N book(s)"}. */
    private static Timed<String> bookCountAt(NodeStore store, BufferPool pool, byte[] commit) {
        return onSnapshot(
                store,
                pool,
                commit,
                conn -> {
                    String q = "SELECT (COUNT(DISTINCT ?b) AS ?n) WHERE { ?b <urn:book:title> ?t }";
                    long t0 = System.nanoTime();
                    String count;
                    try (TupleQueryResult r = conn.prepareTupleQuery(q).evaluate()) {
                        count = r.hasNext() ? r.next().getValue("n").stringValue() : "0";
                    }
                    return new Timed<>(count + " book(s)", (System.nanoTime() - t0) / 1_000_000.0);
                });
    }

    /**
     * Open {@code commit} as a read-only snapshot Sail, run {@code work} against a connection to
     * it, and tear the snapshot down. The snapshot shares the live store (content-addressed, so
     * this is safe) but carries no sidecars — writes through it could never escape.
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

    /**
     * Fork {@code branch} from {@code base}, apply {@code work} in a single commit on a snapshot
     * Sail, register the branch in the live Sail's commit log + refs, and return the new head. The
     * same fork-then-commit pattern {@code BranchMergeDemo} uses, hoisted so a multi-commit feature
     * branch is just two calls.
     */
    private static byte[] forkCommit(
            NodeStore store,
            BufferPool pool,
            ProllySail sail,
            byte[] base,
            String branch,
            String message,
            Consumer<RepositoryConnection> work)
            throws IOException {
        ProllySail snap =
                ProllySail.openSnapshotAt(
                        store,
                        pool,
                        new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                        base);
        SailRepository snapRepo = new SailRepository(snap);
        snapRepo.init();
        byte[] head;
        try {
            try (RepositoryConnection conn = snapRepo.getConnection()) {
                conn.begin();
                work.accept(conn);
                snap.setNextCommitMessage(message);
                conn.commit();
            }
            head = Objects.requireNonNull(snap.currentCommitHash());
        } finally {
            snapRepo.shutDown();
        }
        sail.recordBranchCommit(branch, head, base, message);
        return head;
    }

    /**
     * Print the {@code +}/{@code -} catalog delta between two commits — the same set-difference
     * approach {@code SparqlDiffDemo} uses, scoped to the book catalog. Reuses {@link #catalogAt}
     * (the timing it captures is unused here).
     */
    private static void printCatalogDiff(
            NodeStore store,
            BufferPool pool,
            PrintStream out,
            String label,
            byte[] from,
            byte[] to) {
        List<String> before = catalogAt(store, pool, from).value();
        List<String> after = catalogAt(store, pool, to).value();
        List<String> removed = new ArrayList<>(before);
        removed.removeAll(after);
        List<String> added = new ArrayList<>(after);
        added.removeAll(before);
        out.println("    commit " + label + ":");
        for (String s : removed) {
            out.println("      - " + s);
        }
        for (String s : added) {
            out.println("      + " + s);
        }
        if (removed.isEmpty() && added.isEmpty()) {
            out.println("      (no change)");
        }
    }
}
