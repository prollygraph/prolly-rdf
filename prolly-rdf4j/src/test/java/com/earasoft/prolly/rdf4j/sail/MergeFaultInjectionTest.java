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
import com.earasoft.prolly.ErrorInjectingNodeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fault-injection coverage for {@link MergeEngine}'s structural-merge error path. {@code
 * doStructuralMerge} acquires the Sail write lock, opens snapshot Sails, merges each tree, and
 * commits — releasing the lock in a {@code finally}. These tests drive a real storage failure into
 * that path via an {@link ErrorInjectingNodeStore} and verify the failure surfaces and the write
 * lock is still released.
 */
class MergeFaultInjectionTest {

    private static boolean causedByInjectedFailure(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains("Injected IO Failure")) {
                return true;
            }
        }
        return false;
    }

    /** A live main Sail over a fault-injectable store, with branch-fork helpers. */
    private static final class Harness implements AutoCloseable {
        final InMemoryNodeStore backing = new InMemoryNodeStore();
        final ErrorInjectingNodeStore store = new ErrorInjectingNodeStore(backing);
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

        byte[] commitMain(Consumer<RepositoryConnection> work) {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                work.accept(c);
                c.commit();
            }
            return sail.currentCommitHash();
        }

        byte[] forkBranch(String branch, byte[] base, Consumer<RepositoryConnection> work)
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
                try (RepositoryConnection c = snapRepo.getConnection()) {
                    c.begin();
                    work.accept(c);
                    c.commit();
                }
                head = snap.currentCommitHash();
            } finally {
                snapRepo.shutDown();
            }
            sail.recordBranchCommit(branch, head, base, "branch " + branch);
            return head;
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

    /** Build a main branch and a divergent "feature" branch off a shared base. */
    private static byte[] divergentFeature(Harness h) throws IOException {
        byte[] base = h.commitMain(c -> add(c, "a", "knows", "b"));
        byte[] feature = h.forkBranch("feature", base, c -> add(c, "a", "knows", "c"));
        h.commitMain(c -> add(c, "a", "knows", "d")); // main diverges from feature
        return feature;
    }

    @Test
    void structural_merge_surfaces_an_injected_store_failure(@TempDir Path dir) throws Exception {
        try (Harness h = new Harness(dir)) {
            byte[] feature = divergentFeature(h);
            h.store.injectErrorAfter(1); // fail the next store op during the merge
            Throwable ex =
                    assertThrows(
                            Throwable.class,
                            () -> MergeEngine.mergeStructural(h.sail, feature),
                            "a storage failure during a structural merge must abort it");
            assertTrue(
                    causedByInjectedFailure(ex),
                    "the injected failure must surface in the merge exception chain, got: " + ex);
        }
    }

    @Test
    void write_lock_released_after_a_failed_structural_merge(@TempDir Path dir) throws Exception {
        // doStructuralMerge acquires the Sail write lock and releases it in a
        // finally — so even after a mid-merge storage failure, a fresh commit
        // on main must still acquire the lock and succeed.
        try (Harness h = new Harness(dir)) {
            byte[] feature = divergentFeature(h);
            h.store.injectErrorAfter(1);
            assertThrows(Throwable.class, () -> MergeEngine.mergeStructural(h.sail, feature));

            // Injection has tripped (disarmed); the lock must be free.
            assertDoesNotThrow(
                    () -> h.commitMain(c -> add(c, "a", "knows", "e")),
                    "the write lock must be released after a failed structural merge");
        }
    }

    @Test
    void clean_structural_merge_through_the_wrapper_succeeds(@TempDir Path dir) throws Exception {
        // Control: the unarmed wrapper is transparent, so the merge proceeds
        // and unions both branches — isolating the injection in the tests above.
        try (Harness h = new Harness(dir)) {
            byte[] feature = divergentFeature(h);
            MergeEngine.MergeResult r = MergeEngine.mergeStructural(h.sail, feature);
            assertEquals(
                    MergeEngine.MergeResult.Kind.OK,
                    r.kind(),
                    "a clean divergent structural merge succeeds");
        }
    }

    // ---- non-structural (triple-replay) merge ----

    @Test
    void replay_merge_surfaces_an_injected_store_failure(@TempDir Path dir) throws Exception {
        // MergeEngine.merge replays the source's triples onto the target via an
        // RDF4J connection commit; a storage failure during it must abort.
        try (Harness h = new Harness(dir)) {
            byte[] feature = divergentFeature(h);
            h.store.injectErrorAfter(1);
            Throwable ex =
                    assertThrows(
                            Throwable.class,
                            () -> MergeEngine.merge(h.sail, h.repo, feature),
                            "a storage failure during a replay merge must abort it");
            assertTrue(
                    causedByInjectedFailure(ex),
                    "the injected failure must surface in the merge exception chain, got: " + ex);
        }
    }

    @Test
    void write_lock_released_after_a_failed_replay_merge(@TempDir Path dir) throws Exception {
        // The replay merge commits through the normal commitInternal path,
        // which releases the write lock in a finally — a fresh commit on main
        // must still succeed after a mid-merge storage failure.
        try (Harness h = new Harness(dir)) {
            byte[] feature = divergentFeature(h);
            h.store.injectErrorAfter(1);
            assertThrows(Throwable.class, () -> MergeEngine.merge(h.sail, h.repo, feature));

            assertDoesNotThrow(
                    () -> h.commitMain(c -> add(c, "a", "knows", "e")),
                    "the write lock must be released after a failed replay merge");
        }
    }

    // ---- squash merge ----
    //
    // squashMerge / squashMergeStructural resolve the source via the RefsStore
    // and collapse the branch into one commit on the target. The "feature"
    // branch divergentFeature() registers (via recordBranchCommit) is the
    // squash source.

    @Test
    void squash_merge_surfaces_an_injected_store_failure(@TempDir Path dir) throws Exception {
        // squashMerge materialises the source branch's net diff and commits it
        // as a single squash commit — a storage failure during it must abort.
        try (Harness h = new Harness(dir)) {
            divergentFeature(h); // registers branch "feature"
            h.store.injectErrorAfter(1);
            Throwable ex =
                    assertThrows(
                            Throwable.class,
                            () -> MergeEngine.squashMerge(h.sail, h.repo, "feature", "squash"),
                            "a storage failure during a squash merge must abort it");
            assertTrue(
                    causedByInjectedFailure(ex),
                    "the injected failure must surface in the squash exception chain, got: " + ex);
        }
    }

    @Test
    void write_lock_released_after_a_failed_squash_merge(@TempDir Path dir) throws Exception {
        // squashMerge commits through the normal commitInternal path — a fresh
        // commit on main must still succeed after a mid-squash storage failure.
        try (Harness h = new Harness(dir)) {
            divergentFeature(h);
            h.store.injectErrorAfter(1);
            assertThrows(
                    Throwable.class,
                    () -> MergeEngine.squashMerge(h.sail, h.repo, "feature", "squash"));

            assertDoesNotThrow(
                    () -> h.commitMain(c -> add(c, "a", "knows", "e")),
                    "the write lock must be released after a failed squash merge");
        }
    }

    @Test
    void structural_squash_merge_surfaces_an_injected_store_failure(@TempDir Path dir)
            throws Exception {
        // squashMergeStructural runs the per-tree merge then squash-commits;
        // a storage failure during it must abort.
        try (Harness h = new Harness(dir)) {
            divergentFeature(h);
            h.store.injectErrorAfter(1);
            Throwable ex =
                    assertThrows(
                            Throwable.class,
                            () -> MergeEngine.squashMergeStructural(h.sail, "feature", "squash"),
                            "a storage failure during a structural squash merge must abort it");
            assertTrue(
                    causedByInjectedFailure(ex),
                    "the injected failure must surface in the squash exception chain, got: " + ex);
        }
    }
}
