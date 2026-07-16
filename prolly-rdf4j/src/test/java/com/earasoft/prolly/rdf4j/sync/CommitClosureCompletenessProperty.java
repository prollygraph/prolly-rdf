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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * The <b>anti-entropy completeness</b> property of distributed sync, stated on the receiver: for
 * every pull a converging mesh performs over <b>generated divergent multi-peer histories</b>, the
 * commit-log batch the pull would apply is <b>parent-closed</b> against the receiver — every parent
 * of every commit in the batch is itself in the batch or already in the receiver's log. No commit
 * ever has a <b>dangling parent</b>.
 *
 * <p><b>How the invariant is observed.</b> The receiver-side guard {@link CommitLogSync#mergeInto}
 * (reached through {@link RepoSync#fetch} → {@link RepoSync#pull}) is the formal
 * <i>specification</i> of batch-completeness: it validates the whole batch <em>before</em>
 * appending anything and throws {@link IllegalArgumentException} {@code "...has a dangling
 * parent..."} if the sender's batch is not ancestor-closed. So the clean way to assert completeness
 * end-to-end is exactly the convergence loop the sync uses: run a generated commit/pull op-sequence
 * across {@value #PEERS} peers, then a full-mesh fixed-point sync, and assert that <b>no pull
 * aborts with the dangling-parent rejection</b> — i.e. the convergence loop completes. A pull that
 * returns, or that surfaces a legitimate 3-way-merge {@link IllegalStateException} conflict, does
 * not violate completeness; only the dangling-parent {@link IllegalArgumentException} does.
 *
 * <p><b>Why this is not trivially-always-true — it is the test that was RED before ADR-0071.</b>
 * The dangling parent had a single root cause: the old commit identity was the <b>tree hash</b>
 * alone ({@code CommitLog.Entry.hashHex == metaTreeHash}, no parents). Under provenance-off, two
 * peers that merge the <em>same</em> triples in <em>different</em> pull-orders produce identical
 * trees → identical ids → but <em>different</em> parent lineages; the closure (then keyed by tree
 * hash) collapsed those two distinct merge commits into one, so a later pull's batch referenced a
 * parent the closure had silently dropped — a dangling parent, and the convergence loop aborted.
 * ADR-0071 made the id {@code hash(tree + parents + author + message)}, so those two merge commits
 * now have <b>distinct ids</b>, the closure ({@link CommitClosure}, now id-keyed) walks both, and
 * the batch stays ancestor-closed.
 *
 * <p>To genuinely re-enter that regime — rather than measure a workload where the variable cannot
 * act — the generator deliberately maximizes <b>cross-peer tree collisions</b>: every commit draws
 * its triple from a <b>small shared pool</b> ({@value #TRIPLE_POOL} subjects, one shared
 * predicate), so independent peers routinely reach the <em>same</em> tree, and cross-peer pulls in
 * varying orders manufacture the same-tree / different-parents merge commits that broke the
 * tree-hash-keyed closure. (Contrast {@link SyncFuzzTest}, whose per-commit-unique {@code
 * urn:s<counter>} triples make such collisions <i>rare</i>; this property is constructed to make
 * them <i>common</i>.) The property is driven under <b>both</b> provenance settings — and crucially
 * provenance-<b>off</b>, the regime the collision lived in (ADR-0071's promise: identity is sound
 * under <em>either</em> setting). Run flag-on against the pre-ADR-0071 tree-hash identity this
 * would shrink to a minimal failing op-sequence; run against the id-keyed closure it is green.
 *
 * <p>Scoped by {@code plans/prepublic/sync-anti-entropy-completeness.md} (the direct invariant
 * test, D-3) — the generative sibling of {@link CommitClosureTest}'s hand-built closure cases and
 * of {@link SyncFuzzTest}'s triple-set-convergence assertion.
 */
class CommitClosureCompletenessProperty {

    private static final int PEERS = 3;
    private static final int MAX_CONVERGENCE_ITERATIONS = 50;

    /**
     * The shared subject pool. Kept small so independent peers routinely commit the <em>same</em>
     * triple and reach the <em>same</em> tree — the cross-peer tree collision that, under the old
     * tree-hash identity, made two distinct merge commits share an id (the dangling-parent
     * trigger).
     */
    private static final int TRIPLE_POOL = 3;

    /**
     * One generated step: a commit on {@code peer} of the shared-pool triple {@code triple}, or (if
     * {@code pull}) a pull of {@code main} from {@code from}.
     */
    record SyncOp(boolean pull, int peer, int from, int triple) {}

    private static ProllySail initedSail(Path dir, boolean provenanceEnabled) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        provenanceEnabled);
        new SailRepository(sail).init();
        return sail;
    }

    /**
     * Commit the shared-pool triple {@code (urn:s<n>, urn:p, urn:o<n>)} where {@code n = triple %
     * TRIPLE_POOL}. Re-adding a triple a peer already has is a content no-op (the Sail skips the
     * commit), which is itself part of the divergence surface — two peers can hold byte-identical
     * trees.
     */
    private static void commitFromPool(ProllySail sail, int triple) {
        int n = Math.floorMod(triple, TRIPLE_POOL);
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s" + n), vf.createIRI("urn:p"), vf.createIRI("urn:o" + n));
            conn.commit();
        }
    }

    /**
     * Pull {@code main} from {@code from} into {@code into}, treating the <b>dangling-parent
     * rejection as the property violation</b> and a legitimate merge conflict as an accepted
     * outcome. Returns normally whether the pull fast-forwarded, no-oped, merged, or surfaced a
     * merge <i>conflict</i>; throws {@link AssertionError} only on the dangling-parent abort.
     */
    private static void pullExpectingClosure(ProllySail into, ProllySail from) throws IOException {
        try {
            new RepoSync(into).pull(new InProcessRemoteRepository(from), "origin", "main");
        } catch (IllegalArgumentException ex) {
            // The completeness failure: the sender's batch was not ancestor-closed against the
            // receiver, so mergeInto aborted on a dangling parent. This is exactly the RED-before-
            // ADR-0071 symptom this property exists to catch — re-throw as an assertion failure
            // with
            // the offending message attached.
            String msg = String.valueOf(ex.getMessage());
            if (msg.contains("dangling parent")) {
                throw new AssertionError(
                        "anti-entropy completeness violated — a pull's commit-log batch had a"
                                + " dangling parent (the batch was not parent-closed against the"
                                + " receiver): "
                                + msg,
                        ex);
            }
            // Any other IllegalArgumentException (e.g. an unknown branch) is a genuine error in the
            // op-sequence setup, not the invariant under test — let it surface.
            throw ex;
        } catch (IllegalStateException mergeConflict) {
            // A 3-way merge over divergent histories may legitimately conflict; that is NOT a
            // completeness violation (the batch was parent-closed — the merge just couldn't
            // auto-resolve). Swallow it so the loop can keep converging the other peers.
        }
    }

    @Property(tries = 30)
    void every_pull_batch_is_parent_closed_no_dangling_parent(
            @ForAll @From("ops") List<SyncOp> ops, @ForAll boolean provenanceEnabled)
            throws IOException {
        List<Path> dirs = new ArrayList<>();
        List<ProllySail> peers = new ArrayList<>();
        try {
            for (int i = 0; i < PEERS; i++) {
                Path dir = Files.createTempDirectory("prolly-closure-completeness-" + i + "-");
                dirs.add(dir);
                peers.add(initedSail(dir, provenanceEnabled));
            }
            // Seed every peer with one commit so refs/main exists everywhere before any pull — a
            // freshly-init'd peer has no main, and a pull from it would 400 on ref advertisement.
            // Seeding from the shared pool (peer i ← triple i) means peers can already share a
            // tree.
            for (int i = 0; i < PEERS; i++) {
                commitFromPool(peers.get(i), i);
            }

            // ---- Apply the generated op-sequence. Commits from the shared pool create divergence
            // with frequent cross-peer tree collisions; pulls fold that divergence into 3-way
            // merges
            // — the same-tree / different-parents merge commits that broke the old tree-hash
            // closure.
            for (SyncOp op : ops) {
                int p = op.peer();
                if (op.pull()) {
                    int from = op.from() == p ? (p + 1) % PEERS : op.from(); // pulls are cross-peer
                    pullExpectingClosure(peers.get(p), peers.get(from));
                } else {
                    commitFromPool(peers.get(p), op.triple());
                }
            }

            // ---- Full-mesh fixed-point sync: every iteration, every peer pulls from every other.
            // EVERY one of these pulls must also be parent-closed — the convergence tail is where
            // the
            // dangling parent historically surfaced (a merge commit's second-parent lineage). Stop
            // when no peer's head moves (or the iteration cap is hit, which we then assert
            // against).
            boolean converged = false;
            for (int iter = 0; iter < MAX_CONVERGENCE_ITERATIONS && !converged; iter++) {
                boolean anyChange = false;
                for (int i = 0; i < PEERS; i++) {
                    for (int j = 0; j < PEERS; j++) {
                        if (i == j) {
                            continue;
                        }
                        byte[] before = peers.get(i).currentCommitId();
                        pullExpectingClosure(peers.get(i), peers.get(j));
                        if (!java.util.Arrays.equals(before, peers.get(i).currentCommitId())) {
                            anyChange = true;
                        }
                    }
                }
                converged = !anyChange;
            }

            // Reaching a fixed point is the end-to-end witness that no pull ever aborted on a
            // dangling
            // parent: a dangling-parent rejection would have thrown above (failing the property)
            // rather than let the mesh quiesce. The iteration cap guards against a livelock that
            // would
            // otherwise let a silent non-completeness masquerade as "still working".
            assertTrue(
                    converged,
                    "the converging mesh did not reach a fixed point within "
                            + MAX_CONVERGENCE_ITERATIONS
                            + " full-mesh iterations — no dangling-parent abort fired, but the pulls"
                            + " never quiesced (provenanceEnabled="
                            + provenanceEnabled
                            + ")");
        } finally {
            for (ProllySail peer : peers) {
                try {
                    peer.shutDown();
                } catch (Exception ignored) {
                    // best-effort teardown
                }
            }
            for (Path dir : dirs) {
                deleteRecursively(dir);
            }
        }
    }

    /**
     * 1–18 ops over {commit, pull} × peer × from × triple. {@code ofMinSize(1)} guarantees at least
     * one op so a degenerate empty sequence (which exercises nothing) is never drawn; jqwik shrinks
     * a counterexample toward the fewest/lowest ops — the minimal divergent interleaving that
     * produces a dangling parent.
     */
    @Provide
    Arbitrary<List<SyncOp>> ops() {
        Arbitrary<Integer> peerIdx = Arbitraries.integers().between(0, PEERS - 1);
        Arbitrary<Integer> triple = Arbitraries.integers().between(0, TRIPLE_POOL - 1);
        Arbitrary<SyncOp> op =
                Combinators.combine(Arbitraries.of(true, false), peerIdx, peerIdx, triple)
                        .as(SyncOp::new);
        return op.list().ofMinSize(1).ofMaxSize(18);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            pth -> {
                                try {
                                    Files.deleteIfExists(pth);
                                } catch (IOException ignored) {
                                    // best-effort temp cleanup
                                }
                            });
        }
    }
}
