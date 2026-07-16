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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Explicit-case coverage for {@link MergeEngine#mergeStructural} (plan 08 §8.14), plus the
 * dictionary-consistency invariant (§8.16) and the provenance fallback (§8.17).
 *
 * <p>Divergent branches are built by committing onto a snapshot Sail opened at the fork point, then
 * registering the result in the live Sail's commit log via {@code recordBranchCommit} — the same
 * mechanism staging uses. Everything runs against a real {@link ProllySail} — no mocks.
 *
 * <p>Post-ADR-0071 a commit's <b>id</b> (a content hash over tree + parents + author + message) is
 * the handle merges operate on, distinct from the head's <b>tree</b> hash. So {@link
 * MergeEngine#mergeStructural} takes a source commit <b>id</b>, {@link
 * MergeEngine.MergeResult#newCommit()} is a commit <b>id</b>, and {@link
 * ProllySail#recordBranchCommit} takes a parent <b>id</b> and returns the new commit <b>id</b>. The
 * harness therefore tracks ids (its {@code commitMain} / {@code forkBranch} return ids); when a
 * test instead needs to <em>open</em> a tree at a head, it bridges id → tree hash via {@link
 * ProllySail#treeHashOf} (or reads {@link ProllySail#currentCommitHash}).
 */
class StructuralMergeTest {

    /** A live Sail plus the shared store, with helpers to fork branches. */
    private static final class Harness implements AutoCloseable {
        final NodeStore store = new InMemoryNodeStore();
        final HeapBufferPool pool = new HeapBufferPool();
        final ProllySail sail;
        final SailRepository repo;

        Harness(Path dir) {
            sail =
                    new ProllySail(
                            store,
                            pool,
                            RootMetaTreeStore.beside(dir),
                            CommitLog.beside(dir),
                            RefsStore.beside(dir));
            repo = new SailRepository(sail);
            repo.init();
        }

        /** Commit triples on the live (main) branch; return the new head commit id (ADR-0071). */
        byte[] commitMain(Consumer<RepositoryConnection> work) {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                work.accept(c);
                c.commit();
            }
            return sail.currentCommitId();
        }

        /**
         * Fork {@code branch} from {@code base} (a commit <b>id</b>), apply {@code work}, register
         * it in the shared commit log with parent {@code base}; return the new commit <b>id</b>.
         */
        byte[] forkBranch(String branch, byte[] base, Consumer<RepositoryConnection> work)
                throws IOException {
            // base is a commit id; openSnapshotAt needs the corresponding tree hash.
            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            sail.treeHashOf(base));
            SailRepository snapRepo = new SailRepository(snap);
            snapRepo.init();
            byte[] headTree;
            try {
                try (RepositoryConnection c = snapRepo.getConnection()) {
                    c.begin();
                    work.accept(c);
                    c.commit();
                }
                headTree = snap.currentCommitHash();
            } finally {
                snapRepo.shutDown();
            }
            // recordBranchCommit takes the new tree hash + the parent commit id, and returns the
            // new commit id (the handle mergeStructural consumes).
            return sail.recordBranchCommit(branch, headTree, base, "branch " + branch);
        }

        Set<String> triples() {
            Set<String> out = new HashSet<>();
            try (RepositoryConnection c = repo.getConnection();
                    var it = c.getStatements(null, null, null, false)) {
                while (it.hasNext()) out.add(key(it.next()));
            }
            return out;
        }

        @Override
        public void close() {
            repo.shutDown();
        }
    }

    private static void add(RepositoryConnection c, String s, String p, String o) {
        ValueFactory vf = c.getValueFactory();
        c.add(vf.createIRI("urn:t:" + s), vf.createIRI("urn:t:" + p), vf.createIRI("urn:t:" + o));
    }

    private static String key(Statement st) {
        return st.getSubject() + "|" + st.getPredicate() + "|" + st.getObject();
    }

    // ---- core cases ----

    @Test
    void clean_divergent_merge_unions_both_branches(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            byte[] mainTip = h.commitMain(c -> add(c, "a", "p", "c")); // main adds (a,p,c)
            byte[] feature =
                    h.forkBranch(
                            "feature", base, c -> add(c, "a", "p", "d")); // feature adds (a,p,d)

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);

            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());
            assertNotNull(r.newCommit());
            assertEquals(
                    Set.of(
                            "urn:t:a|urn:t:p|urn:t:b",
                            "urn:t:a|urn:t:p|urn:t:c",
                            "urn:t:a|urn:t:p|urn:t:d"),
                    h.triples(),
                    "merged HEAD must see triples from both branches");
        }
    }

    /**
     * Step 16 (S-6) — the merged-snapshot-read gap that the linear-history {@code
     * SnapshotDifferentialProperty} (Step 7) doesn't cover: time-travelling to a <b>two-parent
     * merge commit</b> materializes the union of both branches, and that read is <b>invariant</b>
     * under commits that land after the merge.
     */
    @Test
    void time_travel_to_a_merge_commit_materializes_the_union(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            h.commitMain(c -> add(c, "a", "p", "c")); // main adds (a,p,c)
            byte[] feature =
                    h.forkBranch(
                            "feature", base, c -> add(c, "a", "p", "d")); // feature adds (a,p,d)

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);
            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());
            byte[] mergeCommit = r.newCommit(); // a commit id (ADR-0071)
            assertNotNull(mergeCommit);

            // A commit landing AFTER the merge must not change what reading AT the merge commit
            // returns.
            h.commitMain(c -> add(c, "z", "p", "z"));

            Set<String> expected =
                    Set.of(
                            "urn:t:a|urn:t:p|urn:t:b",
                            "urn:t:a|urn:t:p|urn:t:c",
                            "urn:t:a|urn:t:p|urn:t:d");
            // openSnapshotAt opens a tree, so bridge the merge-commit id to its tree hash.
            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            h.store,
                            h.pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            h.sail.treeHashOf(mergeCommit));
            SailRepository snapRepo = new SailRepository(snap);
            snapRepo.init();
            try {
                Set<String> got = new HashSet<>();
                try (RepositoryConnection sc = snapRepo.getConnection();
                        var it = sc.getStatements(null, null, null, false)) {
                    while (it.hasNext()) {
                        got.add(key(it.next()));
                    }
                }
                assertEquals(
                        expected,
                        got,
                        "openSnapshotAt(mergeCommit) materializes base ∪ main ∪ feature, invariant under later commits");
            } finally {
                snapRepo.shutDown();
            }
        }
    }

    @Test
    void merge_records_two_parents_in_the_commit_log(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            byte[] mainTip = h.commitMain(c -> add(c, "x", "p", "y"));
            byte[] feature = h.forkBranch("feature", base, c -> add(c, "m", "p", "n"));

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);
            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());

            CommitLog.Entry latest = h.sail.commitLog().orElseThrow().latest().orElseThrow();
            // r.newCommit() is the new commit id (ADR-0071) — it is the merge entry's id, not its
            // tree hash.
            assertArrayEquals(r.newCommit(), latest.id());
            assertEquals(
                    2, latest.parents().size(), "a structural merge commit must list both parents");
            // Parents are commit ids: first = target head (mainTip), second = source (feature).
            assertArrayEquals(mainTip, latest.parents().get(0), "first parent = target head");
            assertArrayEquals(feature, latest.parents().get(1), "second parent = source");
        }
    }

    @Test
    void merging_an_ancestor_is_up_to_date(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            byte[] mainTip = h.commitMain(c -> add(c, "a", "p", "c"));
            // base is an ancestor of mainTip → merging it contributes nothing.
            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, base);
            assertEquals(MergeEngine.MergeResult.Kind.UP_TO_DATE, r.kind());
        }
    }

    @Test
    void merging_self_is_up_to_date(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] head = h.commitMain(c -> add(c, "a", "p", "b"));
            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, head);
            assertEquals(MergeEngine.MergeResult.Kind.UP_TO_DATE, r.kind());
            assertArrayEquals(head, r.newCommit());
        }
    }

    @Test
    void fast_forward_when_target_is_an_ancestor_of_source(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            // feature descends from base (= current main head) and adds more.
            byte[] feature =
                    h.forkBranch(
                            "feature",
                            base,
                            c -> {
                                add(c, "a", "p", "c");
                                add(c, "a", "p", "d");
                            });
            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);
            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());
            assertEquals(
                    Set.of(
                            "urn:t:a|urn:t:p|urn:t:b",
                            "urn:t:a|urn:t:p|urn:t:c",
                            "urn:t:a|urn:t:p|urn:t:d"),
                    h.triples(),
                    "fast-forward merge adopts every source triple");
        }
    }

    @Test
    void both_branches_add_the_same_triple_no_conflict(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            byte[] mainTip = h.commitMain(c -> add(c, "shared", "p", "v"));
            // feature re-adds the same (shared,p,v) AND a unique triple, so the
            // merge is not a total no-op — the shared triple must not conflict.
            byte[] feature =
                    h.forkBranch(
                            "feature",
                            base,
                            c -> {
                                add(c, "shared", "p", "v");
                                add(c, "feat", "p", "uniq");
                            });

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);
            assertEquals(
                    MergeEngine.MergeResult.Kind.OK,
                    r.kind(),
                    "RDF triples are a set — the same triple on both sides is not a conflict");
            assertTrue(r.conflicts().isEmpty());
            assertEquals(
                    Set.of(
                            "urn:t:a|urn:t:p|urn:t:b",
                            "urn:t:shared|urn:t:p|urn:t:v",
                            "urn:t:feat|urn:t:p|urn:t:uniq"),
                    h.triples());
        }
    }

    @Test
    void merge_with_no_lca_unions_disjoint_histories(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] mainTip = h.commitMain(c -> add(c, "a", "p", "b"));
            // A source commit with NO shared ancestor: fork from a genesis (null base).
            // mainTip is a commit id; openSnapshotAt needs its tree hash.
            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            h.store,
                            h.pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            h.sail.treeHashOf(mainTip));
            // Build an independent commit by committing on a fresh snapshot at mainTip
            // but recording it with an empty parent — a disjoint history root.
            byte[] orphanTree;
            SailRepository snapRepo = new SailRepository(snap);
            snapRepo.init();
            try (RepositoryConnection c = snapRepo.getConnection()) {
                c.begin();
                add(c, "z", "p", "w");
                c.commit();
            }
            orphanTree = snap.currentCommitHash();
            snapRepo.shutDown();
            // No parent → disjoint root; recordBranchCommit returns the orphan's commit id.
            byte[] orphan =
                    h.sail.recordBranchCommit("orphan", orphanTree, new byte[0], "disjoint");

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, orphan);
            // No LCA → ancestor is null → every source entry is an ADD.
            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());
            assertTrue(h.triples().contains("urn:t:z|urn:t:p|urn:t:w"));
            assertTrue(h.triples().contains("urn:t:a|urn:t:p|urn:t:b"));
        }
    }

    // ---- §8.14 namespace-remap conflict ----

    @Test
    void conflicting_namespace_remap_blocks_the_merge(@TempDir Path dir) throws Exception {
        // Both branches bind prefix "ex" to *different* namespace URIs — a
        // genuine conflict. The merge must install nothing and report it.
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "a", "p", "b"));
            byte[] mainTip =
                    h.commitMain(
                            c -> {
                                add(c, "m", "p", "n");
                                c.setNamespace("ex", "http://main.example.org/");
                            });
            byte[] feature =
                    h.forkBranch(
                            "feature",
                            base,
                            c -> {
                                add(c, "f", "p", "g");
                                c.setNamespace("ex", "http://feature.example.org/");
                            });
            byte[] headBefore = h.sail.currentCommitId();

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);

            assertEquals(
                    MergeEngine.MergeResult.Kind.CONFLICT,
                    r.kind(),
                    "prefix 'ex' remapped to different URIs on both sides is a conflict");
            assertFalse(r.conflicts().isEmpty(), "the conflict must be reported");
            assertArrayEquals(
                    headBefore,
                    h.sail.currentCommitId(),
                    "a conflicting merge installs nothing — HEAD must not advance");
            assertNull(r.newCommit(), "no commit on a conflicting merge");
        }
    }

    // ---- §8.16 dictionary consistency ----

    @Test
    void merged_dictionary_resolves_every_termid_in_the_indexes(@TempDir Path dir)
            throws Exception {
        // Both branches introduce brand-new terms; the merge must leave a dict
        // in which every TermId referenced by the quad indexes resolves.
        // assertDictConsistency runs under -ea and throws if the invariant breaks.
        try (Harness h = new Harness(dir)) {
            byte[] base = h.commitMain(c -> add(c, "base", "p", "o"));
            byte[] mainTip =
                    h.commitMain(
                            c -> {
                                add(c, "main1", "mp", "mo1");
                                add(c, "main2", "mp", "mo2");
                            });
            byte[] feature =
                    h.forkBranch(
                            "feature",
                            base,
                            c -> {
                                add(c, "feat1", "fp", "fo1");
                                add(c, "feat2", "fp", "fo2");
                            });

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);
            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());
            // Every term from both branches must round-trip through the merged dict.
            Set<String> merged = h.triples();
            assertTrue(merged.contains("urn:t:main1|urn:t:mp|urn:t:mo1"));
            assertTrue(merged.contains("urn:t:feat1|urn:t:fp|urn:t:fo1"));
            assertEquals(5, merged.size(), "base + 2 main + 2 feature triples");
        }
    }

    // ---- §8.17 provenance fallback ----

    @Test
    void mergeStructural_refuses_a_provenance_enabled_sail(@TempDir Path dir) throws Exception {
        NodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        // provenanceEnabled = true (7-arg ctor).
        ProllySail sail =
                new ProllySail(
                        store,
                        pool,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        true);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "b");
                c.commit();
            }
            byte[] base = sail.currentCommitId(); // the fork-point commit id (parent)
            byte[] baseTree = sail.currentCommitHash(); // its tree hash, to open the snapshot
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "c");
                c.commit();
            }
            // Fork a divergent branch so the source is not an ancestor of the
            // target head — otherwise the up-to-date short-circuit fires first.
            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            baseTree);
            SailRepository snapRepo = new SailRepository(snap);
            snapRepo.init();
            try (RepositoryConnection c = snapRepo.getConnection()) {
                c.begin();
                add(c, "a", "p", "d");
                c.commit();
            }
            byte[] featureTree = snap.currentCommitHash();
            snapRepo.shutDown();
            // recordBranchCommit takes the parent commit id (base) and returns the source commit
            // id.
            byte[] feature = sail.recordBranchCommit("feature", featureTree, base, "feature");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> MergeEngine.mergeStructural(sail, feature),
                    "a provenance-enabled Sail must reject mergeStructural so the "
                            + "caller falls back to merge()");
        } finally {
            repo.shutDown();
        }
    }
}
