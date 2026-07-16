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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeSet;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fuzz coverage (plan 08 §8.15) for {@link MergeEngine#mergeStructural}, mirroring {@code
 * prolly-port-core}'s {@code MergeEngineFuzzTest}.
 *
 * <p>For randomized branch pairs under the additive (set-union) policy:
 *
 * <ul>
 *   <li>the merged graph must equal {@code base ∪ mainAdds ∪ featureAdds};
 *   <li>no conflict may be reported — distinct triples are independent and a triple added on both
 *       sides is the same key+value (set semantics).
 * </ul>
 *
 * <p>main and feature draw their additions from a shared integer pool, so trials naturally include
 * the both-sides-add-the-same-triple case.
 */
class StructuralMergeFuzzTest {

    private static String s(int i) {
        return "urn:t:s-" + i;
    }

    private static String o(int i) {
        return "urn:t:o-" + i;
    }

    private static final String P = "urn:t:p";

    private static void commit(SailRepository repo, Set<Integer> ids) {
        try (RepositoryConnection c = repo.getConnection()) {
            ValueFactory vf = c.getValueFactory();
            c.begin();
            for (int i : ids) {
                c.add(vf.createIRI(s(i)), vf.createIRI(P), vf.createIRI(o(i)));
            }
            c.commit();
        }
    }

    /** Commit a mix of adds and removes in one transaction. */
    private static void commitEdits(SailRepository repo, Set<Integer> adds, Set<Integer> removes) {
        try (RepositoryConnection c = repo.getConnection()) {
            ValueFactory vf = c.getValueFactory();
            c.begin();
            for (int i : adds) {
                c.add(vf.createIRI(s(i)), vf.createIRI(P), vf.createIRI(o(i)));
            }
            for (int i : removes) {
                c.remove(vf.createIRI(s(i)), vf.createIRI(P), vf.createIRI(o(i)));
            }
            c.commit();
        }
    }

    private static Set<String> triples(SailRepository repo) {
        Set<String> out = new HashSet<>();
        try (RepositoryConnection c = repo.getConnection();
                var it = c.getStatements(null, null, null, false)) {
            while (it.hasNext()) {
                Statement st = it.next();
                out.add(st.getSubject() + "|" + st.getPredicate() + "|" + st.getObject());
            }
        }
        return out;
    }

    private static Set<String> expected(Set<Integer> ids) {
        Set<String> out = new HashSet<>();
        for (int i : ids) out.add(s(i) + "|" + P + "|" + o(i));
        return out;
    }

    @Test
    void random_additive_merges_equal_the_set_union(@TempDir Path dir) throws Exception {
        SplittableRandom rng = new SplittableRandom(0x5_7401_4E26L);

        for (int trial = 0; trial < 25; trial++) {
            int span = 20 + rng.nextInt(2200); // mix of single-leaf and multi-level

            // base, main-only and feature-only id sets drawn from one pool.
            Set<Integer> base = new TreeSet<>();
            for (int i = 0; i < span; i++) if (rng.nextInt(100) < 60) base.add(i);
            if (base.isEmpty()) base.add(0);

            Set<Integer> mainAdds = new TreeSet<>();
            Set<Integer> featureAdds = new TreeSet<>();
            for (int i = 0; i < span; i++) {
                if (base.contains(i)) continue; // additions only
                int roll = rng.nextInt(100);
                if (roll < 20) mainAdds.add(i);
                else if (roll < 40) featureAdds.add(i);
                else if (roll < 48) {
                    mainAdds.add(i);
                    featureAdds.add(i);
                } // same triple both sides
            }

            Path d = dir.resolve("trial-" + trial);
            Files.createDirectories(d);
            NodeStore store = new InMemoryNodeStore();
            HeapBufferPool pool = new HeapBufferPool();
            ProllySail sail =
                    new ProllySail(
                            store,
                            pool,
                            RootMetaTreeStore.beside(d),
                            CommitLog.beside(d),
                            RefsStore.beside(d));
            SailRepository repo = new SailRepository(sail);
            repo.init();
            try {
                commit(repo, base);
                // The base commit's TREE hash (to open a snapshot at it) and its
                // COMMIT ID (the feature branch's parent, ADR-0071) — captured
                // before mainAdds advances main's head.
                byte[] baseTree = sail.currentCommitHash();
                byte[] baseCommitId = sail.currentCommitId();
                if (!mainAdds.isEmpty()) commit(repo, mainAdds);

                ProllySail snap =
                        ProllySail.openSnapshotAt(
                                store,
                                pool,
                                new io.micrometer.core.instrument.composite
                                        .CompositeMeterRegistry(),
                                baseTree);
                SailRepository snapRepo = new SailRepository(snap);
                snapRepo.init();
                byte[] featureTree;
                try {
                    if (!featureAdds.isEmpty()) commit(snapRepo, featureAdds);
                    featureTree = snap.currentCommitHash();
                } finally {
                    snapRepo.shutDown();
                }
                // recordBranchCommit returns the feature commit's ID (its parent
                // is main's base commit ID, so the LCA resolves to base).
                byte[] featureCommitId =
                        sail.recordBranchCommit("feature", featureTree, baseCommitId, "feature");

                MergeEngine.MergeResult r = MergeEngine.mergeStructural(sail, featureCommitId);

                assertNotEquals(
                        MergeEngine.MergeResult.Kind.CONFLICT,
                        r.kind(),
                        "trial " + trial + ": an additive merge must never conflict");
                assertTrue(r.conflicts().isEmpty(), "trial " + trial);

                Set<Integer> union = new TreeSet<>(base);
                union.addAll(mainAdds);
                union.addAll(featureAdds);
                assertEquals(
                        expected(union),
                        triples(repo),
                        "trial "
                                + trial
                                + " (span="
                                + span
                                + "): merged graph must be the "
                                + "union of base + both branches' additions");
            } finally {
                repo.shutDown();
            }
        }
    }

    /**
     * Random merges where the feature branch <em>deletes</em> base triples (and adds others). A
     * 3-way merge must replay those deletes — the regression guard for the core {@code MergeEngine}
     * fix where a branch that emptied a tree had its deletes silently dropped.
     *
     * <p>Expected merged graph: {@code (base − featureDeletes) ∪ mainAdds ∪ featureAdds}.
     */
    @Test
    void random_merges_replay_feature_branch_deletes(@TempDir Path dir) throws Exception {
        SplittableRandom rng = new SplittableRandom(0xDE1E7E_2026L);

        for (int trial = 0; trial < 20; trial++) {
            int span = 50 + rng.nextInt(2000);

            Set<Integer> base = new TreeSet<>();
            for (int i = 0; i < span; i++) if (rng.nextInt(100) < 70) base.add(i);
            if (base.size() < 2) {
                base.add(0);
                base.add(1);
            }

            Set<Integer> mainAdds = new TreeSet<>();
            Set<Integer> featureAdds = new TreeSet<>();
            Set<Integer> featureDeletes = new TreeSet<>();
            for (int i = 0; i < span; i++) {
                if (base.contains(i)) {
                    if (rng.nextInt(100) < 25) featureDeletes.add(i); // feature drops it
                } else {
                    int roll = rng.nextInt(100);
                    if (roll < 20) mainAdds.add(i);
                    else if (roll < 40) featureAdds.add(i);
                }
            }
            // Keep at least one base triple so neither side empties to a no-op
            // edge that masks the property under test.
            featureDeletes.remove(base.iterator().next());

            Path d = dir.resolve("trial-" + trial);
            Files.createDirectories(d);
            NodeStore store = new InMemoryNodeStore();
            HeapBufferPool pool = new HeapBufferPool();
            ProllySail sail =
                    new ProllySail(
                            store,
                            pool,
                            RootMetaTreeStore.beside(d),
                            CommitLog.beside(d),
                            RefsStore.beside(d));
            SailRepository repo = new SailRepository(sail);
            repo.init();
            try {
                commit(repo, base);
                // The base commit's TREE hash (to open a snapshot at it) and its
                // COMMIT ID (the feature branch's parent, ADR-0071) — captured
                // before mainAdds advances main's head.
                byte[] baseTree = sail.currentCommitHash();
                byte[] baseCommitId = sail.currentCommitId();
                if (!mainAdds.isEmpty()) commit(repo, mainAdds);

                ProllySail snap =
                        ProllySail.openSnapshotAt(
                                store,
                                pool,
                                new io.micrometer.core.instrument.composite
                                        .CompositeMeterRegistry(),
                                baseTree);
                SailRepository snapRepo = new SailRepository(snap);
                snapRepo.init();
                byte[] featureTree;
                try {
                    commitEdits(snapRepo, featureAdds, featureDeletes);
                    featureTree = snap.currentCommitHash();
                } finally {
                    snapRepo.shutDown();
                }
                // recordBranchCommit returns the feature commit's ID (its parent
                // is main's base commit ID, so the LCA resolves to base).
                byte[] featureCommitId =
                        sail.recordBranchCommit("feature", featureTree, baseCommitId, "feature");

                MergeEngine.MergeResult r = MergeEngine.mergeStructural(sail, featureCommitId);
                assertNotEquals(
                        MergeEngine.MergeResult.Kind.CONFLICT,
                        r.kind(),
                        "trial " + trial + ": disjoint add/delete edits must not conflict");

                Set<Integer> expectedIds = new TreeSet<>(base);
                expectedIds.removeAll(featureDeletes);
                expectedIds.addAll(mainAdds);
                expectedIds.addAll(featureAdds);
                assertEquals(
                        expected(expectedIds),
                        triples(repo),
                        "trial "
                                + trial
                                + " (span="
                                + span
                                + ", deletes="
                                + featureDeletes.size()
                                + "): merge must replay the feature branch's deletes");
            } finally {
                repo.shutDown();
            }
        }
    }
}
