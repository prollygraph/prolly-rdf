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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link MergeEngine#squashMerge} — the squash variant of the three-way merge that
 * collapses a source branch's net diff into a single commit on the target.
 *
 * <p>{@code MergeEngineTest} exercises the regular {@code merge}; this file adds {@code
 * squashMerge}: its guard clauses (null target, blank source, unknown / already-merged branch) and
 * a full divergent-branch squash built with the {@code openSnapshotAt} + {@code recordBranchCommit}
 * fork pattern from {@code StructuralMergeTest}.
 *
 * <p>Post-ADR-0071 the branch handles are commit <b>ids</b>, not tree hashes: {@code squashMerge}
 * reads {@code refs.get(sourceBranch)} as an id, compares it to {@link ProllySail#currentCommitId},
 * and resolves it to a tree via {@link ProllySail#treeHashOf} only to open the snapshot. So the
 * fork helper tracks each commit's id (a fork's parent is its base's id) and the snapshot opens at
 * {@code treeHashOf(id)} — the tree hash is needed solely for reading.
 */
class SquashMergeTest {

    /** A disk-sidecar-backed target Sail plus the fork plumbing for source branches. */
    private static final class Target implements AutoCloseable {
        final NodeStore store = new InMemoryNodeStore();
        final HeapBufferPool pool = new HeapBufferPool();
        final ProllySail sail;
        final SailRepository repo;

        Target(Path dir) {
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

        /**
         * Commit one triple on the live (target) branch; return the new head <b>commit id</b>
         * (ADR-0071) — the handle refs hold and {@code squashMerge} compares against, and (via
         * {@link ProllySail#treeHashOf}) the value a snapshot opens from.
         */
        byte[] commitMain(String s, String p, String o) {
            try (RepositoryConnection c = repo.getConnection()) {
                ValueFactory vf = repo.getValueFactory();
                c.begin();
                c.add(vf.createIRI(s), vf.createIRI(p), vf.createIRI(o));
                c.commit();
            }
            return sail.currentCommitId();
        }

        /**
         * Fork {@code branch} off {@code base} (a commit <b>id</b>), add one triple, register it;
         * return the new fork's commit id. The snapshot opens at {@code treeHashOf(base)} (a
         * snapshot needs the tree hash), and {@code recordBranchCommit} records the new commit with
         * {@code base} as its parent id and points {@code refs/branch} at the returned id
         * (ADR-0071).
         */
        byte[] forkBranch(String branch, byte[] base, String s, String p, String o)
                throws IOException {
            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            sail.treeHashOf(base));
            SailRepository snapRepo = new SailRepository(snap);
            snapRepo.init();
            byte[] head;
            try {
                try (RepositoryConnection c = snapRepo.getConnection()) {
                    ValueFactory vf = snapRepo.getValueFactory();
                    c.begin();
                    c.add(vf.createIRI(s), vf.createIRI(p), vf.createIRI(o));
                    c.commit();
                }
                // The fork's RootMetaTree (tree) hash — the value recordBranchCommit persists; it
                // computes + returns the commit id from this tree + the base parent id.
                head = snap.currentCommitHash();
            } finally {
                snapRepo.shutDown();
            }
            return sail.recordBranchCommit(branch, head, base, "branch " + branch);
        }

        long size() {
            try (RepositoryConnection c = repo.getConnection()) {
                return c.size();
            }
        }

        @Override
        public void close() {
            repo.shutDown();
        }
    }

    // ---- guard clauses --------------------------------------------------

    @Test
    void squashMerge_rejects_a_null_target() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MergeEngine.squashMerge(null, null, "feature", "msg"),
                "a null target Sail is a programming error, not a merge outcome");
    }

    @Test
    void squashMerge_rejects_a_blank_source_branch(@TempDir Path dir) {
        try (Target t = new Target(dir)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MergeEngine.squashMerge(t.sail, t.repo, "   ", "msg"),
                    "a blank source branch name is rejected");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> MergeEngine.squashMerge(t.sail, t.repo, null, "msg"),
                    "a null source branch name is rejected");
        }
    }

    @Test
    void squashMerge_with_an_unknown_source_branch_is_empty(@TempDir Path dir) throws Exception {
        try (Target t = new Target(dir)) {
            t.commitMain("urn:s", "urn:p", "urn:o");
            MergeEngine.SquashResult r =
                    MergeEngine.squashMerge(t.sail, t.repo, "no-such-branch", "msg");
            assertTrue(r.isEmpty(), "a source branch with no ref squashes to nothing");
            assertNull(r.newCommit());
        }
    }

    @Test
    void squashMerge_when_source_is_already_at_target_is_empty(@TempDir Path dir) throws Exception {
        try (Target t = new Target(dir)) {
            byte[] head = t.commitMain("urn:s", "urn:p", "urn:o");
            // Point a branch ref straight at the current target head commit id (refs hold ids,
            // ADR-0071) — so squashMerge sees source == target and squashes nothing.
            t.sail.resetBranchRef("mirror", head);
            MergeEngine.SquashResult r = MergeEngine.squashMerge(t.sail, t.repo, "mirror", "msg");
            assertTrue(r.isEmpty(), "a source already at the target head has nothing to squash");
        }
    }

    // ---- full divergent squash -----------------------------------------

    @Test
    void squashMerge_collapses_a_divergent_branch_into_the_target(@TempDir Path dir)
            throws Exception {
        try (Target t = new Target(dir)) {
            // Base commit (its commit id), the shared ancestor of target and the feature branch.
            byte[] base = t.commitMain("urn:test:a", "urn:test:p", "urn:test:b");
            // Target advances independently of the fork point.
            t.commitMain("urn:test:a", "urn:test:p", "urn:test:c");
            // Feature forks off base and adds its own triple.
            t.forkBranch("feature", base, "urn:test:a", "urn:test:p", "urn:test:d");

            long before = t.size(); // target sees a-b and a-c
            assertEquals(2L, before, "target holds the base triple plus its own commit");

            MergeEngine.SquashResult r =
                    MergeEngine.squashMerge(t.sail, t.repo, "feature", "squash feature");

            assertFalse(r.isEmpty(), "a divergent branch yields a squash commit");
            assertNotNull(r.newCommit(), "the squash produces a new commit hash");
            assertTrue(r.added() >= 1, "the feature's net-new triple is recorded as added");
            assertEquals(
                    before + 1,
                    t.size(),
                    "after the squash the target also holds the feature triple (a-d)");
        }
    }
}
