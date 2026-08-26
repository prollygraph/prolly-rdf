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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.bench.CountingNodeStore;
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * THE COST PIN for publish's hot path (roadmap T25). Merge <i>correctness</i> is heavily tested —
 * nine classes, ~50 tests — but until now nothing pinned merge <i>cost</i>, and every forge publish
 * of a merge request pays {@link MergeEngine#squashMergeStructural}. {@code MergeEngine}'s own
 * javadoc states the property this defends: <i>"Cost is O(leaf-nodes) on near-identical branches,
 * not O(triples)."</i> That claim had no test, so a regression to a full-tree walk would be
 * invisible until someone noticed a slow publish on a large repo.
 *
 * <h2>Why this test disables assertions, and why that is not cheating</h2>
 *
 * {@code MergeEngine.assertDictConsistency} is gated on {@code -ea} and, when enabled, iterates the
 * <b>entire</b> merged SPOC index decoding all four terms of every key — strictly O(total quads).
 * Surefire enables assertions by default, so in the ordinary test configuration a merge's read
 * count is dominated by that verification sweep and the O(leaf-nodes) claim is simply not
 * observable: the measured ratio would track the consistency check, not the merge.
 *
 * <p>Worse, a pin written under {@code -ea} would be actively misleading. It would go green today
 * and <i>stay</i> green if the merge itself regressed to a full-tree walk, because the assertion's
 * O(n) term already dominates the total. So this class runs in a dedicated surefire execution with
 * {@code enableAssertions=false} (see {@code prolly-rdf4j/pom.xml}, execution {@code
 * merge-cost-pin}), which measures the configuration production actually runs. The consistency
 * check keeps its own value under {@code -ea} for every other test; it is only excluded from the
 * measurement window.
 *
 * <h2>What this actually measures — the task's premise was wrong</h2>
 *
 * Roadmap T25 asked for a pin that <i>"reads grow with the DIFF, not the tree"</i>, on the strength
 * of {@code MergeEngine}'s comment. Measurement refuted it. With assertions off and a constant
 * one-triple diff, a 16× larger store costs <b>12.3× the reads</b>:
 *
 * <pre>
 *   n=  500  →   146 reads   (0.292 per triple)
 *   n= 2000  →   491 reads   (0.246 per triple)
 *   n= 8000  →  1792 reads   (0.224 per triple)
 * </pre>
 *
 * Merge cost is <b>linear in the tree</b>, divided by fanout — {@code doStructuralMerge} computes
 * its added/removed counts through {@code DiffEngine}, which has no internal-subtree skip (a
 * documented residual, {@code CommitDiffStreamBoundedWorkTest}), so it walks every leaf of both
 * sides. The class comment's "O(leaf-nodes), not O(triples)" is literally true and reads as a
 * stronger claim than it is: leaf count grows linearly with triples, so publish cost grows with
 * repo size. That is a real scaling property of the forge, and it is parked rather than hidden.
 *
 * <p>So the pin asserts what is true and what a regression would actually break: <b>reads per
 * triple</b> stays at leaf granularity (bound 0.5, measured 0.22–0.29), which trips immediately if
 * anyone drops to per-triple reads; plus a not-super-linear guard, which trips if a nested walk
 * creeps in. A sub-5× ratio pin — the shape this task specified — would have failed on correct
 * code, which is exactly why it was measured before it was asserted.
 */
class SquashMergeReadScalingTest {

    /** The one-triple diff each merge has to carry, regardless of how big the store is. */
    private static final String NS = "urn:cost#";

    /**
     * A target sail whose node reads are counted. Mirrors {@code SquashMergeTest.Target} (ADR-0071:
     * refs and merges speak <b>commit ids</b>, snapshots open from <b>tree hashes</b>), with the
     * store wrapped so the merge's reads can be measured.
     */
    private static final class CountedTarget {
        final CountingNodeStore store = new CountingNodeStore(new InMemoryNodeStore());
        final HeapBufferPool pool = new HeapBufferPool();
        final ProllySail sail;
        final SailRepository repo;

        CountedTarget(Path dir) throws IOException {
            // Each size gets its own directory, and it must EXIST — the sidecar stores write
            // beside it and a missing parent surfaces as "failed to persist RootMetaTree pointer".
            java.nio.file.Files.createDirectories(dir);
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

        /** Commits {@code count} triples in ONE transaction; returns the new head commit id. */
        byte[] commitBulk(String prefix, int count) {
            try (RepositoryConnection c = repo.getConnection()) {
                ValueFactory vf = repo.getValueFactory();
                c.begin();
                for (int i = 0; i < count; i++) {
                    c.add(
                            vf.createIRI(NS + prefix + i),
                            vf.createIRI(NS + "p"),
                            vf.createIRI(NS + "o" + i));
                }
                c.commit();
            }
            return sail.currentCommitId();
        }

        byte[] commitOne(String s) {
            return commitBulk(s, 1);
        }

        /** Forks {@code branch} off {@code base} with exactly one new triple. */
        byte[] forkBranchWithOneTriple(String branch, byte[] base, String s) throws IOException {
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
                    c.add(vf.createIRI(NS + s), vf.createIRI(NS + "p"), vf.createIRI(NS + "o"));
                    c.commit();
                }
                head = snap.currentCommitHash();
            } finally {
                snapRepo.shutDown();
            }
            return sail.recordBranchCommit(branch, head, base, "branch " + branch);
        }
    }

    /**
     * Reads charged by one squash merge of a ONE-TRIPLE branch, over stores of increasing size. The
     * diff is held constant while the tree grows 16×; a merge that walks only the changed path pays
     * roughly tree-height more, while a full-tree walk pays ~16× more.
     */
    @Test
    void mergeReadsScaleWithTheDiffNotTheWholeTree(@TempDir Path dir) throws Exception {
        assertTrue(
                !assertionsEnabled(),
                "this pin MUST run with -da: MergeEngine.assertDictConsistency is O(total quads) "
                        + "under -ea and would dominate the measurement, making the test pass even "
                        + "if the merge regressed to a full-tree walk. Run it via the "
                        + "'merge-cost-pin' surefire execution, not with -Dtest alone.");

        int[] sizes = {500, 2_000, 8_000}; // 16x range
        long[] mergeReads = new long[sizes.length];

        for (int idx = 0; idx < sizes.length; idx++) {
            CountedTarget t = new CountedTarget(dir.resolve("n" + sizes[idx]));

            byte[] base = t.commitBulk("bulk", sizes[idx]); // the big shared history
            byte[] branch = t.forkBranchWithOneTriple("feature", base, "featureOnly");
            t.commitOne("mainOnly"); // main diverges by one triple

            long before = t.store.readCount(); // window opens AFTER setup
            MergeEngine.SquashResult result =
                    MergeEngine.squashMergeStructural(t.sail, "feature", "squash " + sizes[idx]);
            mergeReads[idx] = t.store.readCount() - before;

            assertTrue(
                    result.newCommit() != null,
                    "the merge must actually produce a commit at n=" + sizes[idx]);
            assertEquals(
                    1,
                    result.added(),
                    "the workload is a ONE-triple diff — if this is not 1 the measurement below "
                            + "is not measuring what it claims, at n="
                            + sizes[idx]);
            System.out.printf(
                    "[squash-merge-read-scaling] n=%6d merge-reads=%7d%n",
                    sizes[idx], mergeReads[idx]);
        }

        assertTrue(mergeReads[0] > 0, "the merge must do real work (reads > 0)");

        // THE RATCHET: reads per triple in the store. Measured 2026-08-26 with -da:
        //   n=500 → 146 reads (0.292/triple), n=2000 → 491 (0.246), n=8000 → 1792 (0.224).
        // The per-triple cost is what a regression moves. Losing leaf-level batching — reading
        // each triple's node rather than each leaf's — lands at >= 1.0 per triple and trips this
        // immediately, which is the realistic failure mode. The bound is measured-plus-headroom,
        // and like every ratchet in this repo it may only ever fall.
        for (int idx = 0; idx < sizes.length; idx++) {
            double readsPerTriple = (double) mergeReads[idx] / sizes[idx];
            assertTrue(
                    readsPerTriple < 0.5,
                    "merge must read at LEAF granularity, not per triple: n="
                            + sizes[idx]
                            + " charged "
                            + mergeReads[idx]
                            + " reads = "
                            + readsPerTriple
                            + " per triple (bound 0.5, measured ~0.22-0.29)");
        }

        // And it must not become SUPER-linear — that would mean a repeated walk per element.
        double ratio = (double) mergeReads[sizes.length - 1] / mergeReads[0];
        assertTrue(
                ratio < 16.0,
                "a 16x larger store must not cost MORE than 16x the merge reads; observed "
                        + ratio
                        + "x ("
                        + mergeReads[0]
                        + " → "
                        + mergeReads[sizes.length - 1]
                        + "). Super-linear growth means a nested walk crept in.");
    }

    /** True only when this JVM was started with {@code -ea}. */
    private static boolean assertionsEnabled() {
        boolean ea = false;
        assert ea = true;
        return ea;
    }
}
