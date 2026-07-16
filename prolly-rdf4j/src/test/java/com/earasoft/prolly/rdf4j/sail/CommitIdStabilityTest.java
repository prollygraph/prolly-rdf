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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration-level pin for <b>cross-peer commit-id determinism</b> (ADR-0071 D-2) — the property
 * that makes distributed sync converge <i>by construction</i>. Where {@link CommitIdTest} pins the
 * {@link CommitId#of} field encoding in isolation, this drives the identity through the live commit
 * path of two <b>independent</b> {@link ProllySail}s (separate {@link InMemoryNodeStore}s, separate
 * sidecar directories) and asserts the end-to-end guarantee: two peers that commit the <b>same</b>
 * triples via the <b>same</b> sequence of operations land on the <b>same</b> {@link
 * ProllySail#currentCommitId()} at every corresponding step.
 *
 * <p><b>Why this holds — and why it is the load-bearing one.</b> A commit id is {@code hash(tree ‖
 * parents ‖ author ‖ message)} (ADR-0071); the wall-clock timestamp is deliberately
 * <i>excluded</i>. The tree hash is content-addressed (the same triples shred to the same
 * RootMetaTree), the author and message both default to the empty string on an unattributed commit,
 * and the parent chain is equal by induction (equal ids at step <i>k</i> imply equal parents at
 * step <i>k+1</i>). So two peers committing at <i>different</i> wall-clock times still produce
 * byte-identical ids — exactly the asymmetry that lets a fixed-point merge loop terminate (a merge
 * of two equal-tree commits yields the same id on both peers). If the timestamp leaked into
 * identity, two peers' "same" commits would diverge forever and sync could never converge; this
 * test is the guard that it does not.
 *
 * <p>The negative direction — that commits differing only in parents, author, or message get
 * <b>different</b> ids — is pinned via {@link CommitId#of} directly (the same primitive the live
 * path calls at {@code ProllySail} commit time), because forcing the live Sail to emit a chosen
 * parent / author / message graph is exactly what {@code CommitId.of} already lets us express
 * precisely. Both directions together state the contract: identity is a pure function of logical
 * content — equal content collapses to one id, any load-bearing difference splits it.
 */
class CommitIdStabilityTest {

    /** Provenance is irrelevant to identity (ADR-0071); the determinism path runs with it off. */
    private static ProllySail initedSail(Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        new SailRepository(sail).init();
        return sail;
    }

    /** One unattributed commit of {@code (urn:s{n}, urn:p, urn:o{n})} — no author, no message. */
    private static void commitTriple(ProllySail sail, int n) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s" + n), vf.createIRI("urn:p"), vf.createIRI("urn:o" + n));
            conn.commit();
        }
    }

    // ---- Positive: independent peers, same ops -> same id at every step ------------------------

    @Test
    void two_independent_sails_same_ops_produce_the_same_commit_id_at_each_step(
            @TempDir Path dirA, @TempDir Path dirB) throws IOException {
        ProllySail a = initedSail(dirA);
        ProllySail b = initedSail(dirB);
        try {
            // Apply the identical op-sequence to each peer step by step, asserting the head commit
            // id agrees after every step. The two stores never communicate — convergence of the id
            // is a pure consequence of identical logical content, not of any transfer between them.
            for (int n = 1; n <= 5; n++) {
                commitTriple(a, n);
                commitTriple(b, n);

                byte[] idA = a.currentCommitId();
                byte[] idB = b.currentCommitId();
                assertNotNull(idA, "peer A must have a head commit id after step " + n);
                assertNotNull(idB, "peer B must have a head commit id after step " + n);
                assertArrayEquals(
                        idA,
                        idB,
                        "independent peers committing the same triples via the same ops must"
                                + " produce the same commit id at step "
                                + n
                                + " — cross-peer determinism (ADR-0071 D-2) is what makes sync"
                                + " converge by construction");

                // Sanity: the tree hash agrees too (the id's tree component is content-addressed),
                // so the id-equality above is not an accident of two empty/null ids colliding.
                assertArrayEquals(
                        a.currentCommitHash(),
                        b.currentCommitHash(),
                        "the content-addressed tree hash must also agree at step " + n);
            }
        } finally {
            shutDownQuietly(a);
            shutDownQuietly(b);
        }
    }

    @Test
    void identical_single_commit_on_two_fresh_sails_collides_to_one_genesis_id(
            @TempDir Path dirA, @TempDir Path dirB) throws IOException {
        // The minimal case: two fresh peers each make ONE genesis commit of the same triple. The id
        // is hash(tree ‖ <no parents> ‖ "" ‖ "") on both — identical despite different wall-clock
        // commit times, because the timestamp is excluded from identity (ADR-0071 D-2).
        ProllySail a = initedSail(dirA);
        ProllySail b = initedSail(dirB);
        try {
            commitTriple(a, 1);
            commitTriple(b, 1);
            assertArrayEquals(
                    a.currentCommitId(),
                    b.currentCommitId(),
                    "a genesis commit of identical content must have the same id on two independent"
                            + " peers, regardless of when each was made");
        } finally {
            shutDownQuietly(a);
            shutDownQuietly(b);
        }
    }

    // ---- Negative: a difference in any identity field splits the id ----------------------------
    // These drive CommitId.of directly — the exact primitive the live ProllySail commit path
    // invokes (persistRootMetaTreePointer calls CommitId.of(tree, parents, author, message)) — so
    // they pin the same function that produces currentCommitId, with the field under test isolated.

    private static final byte[] TREE = HashUtils.hash("same-tree".getBytes(StandardCharsets.UTF_8));
    private static final byte[] PARENT_A =
            HashUtils.hash("parent-a".getBytes(StandardCharsets.UTF_8));
    private static final byte[] PARENT_B =
            HashUtils.hash("parent-b".getBytes(StandardCharsets.UTF_8));

    @Test
    void same_tree_different_parents_get_different_ids() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(PARENT_A), "alice", "msg"),
                        CommitId.of(TREE, List.of(PARENT_B), "alice", "msg")),
                "commits that differ only in parents must get different ids");
    }

    @Test
    void same_tree_different_author_get_different_ids() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(PARENT_A), "alice", "msg"),
                        CommitId.of(TREE, List.of(PARENT_A), "bob", "msg")),
                "commits that differ only in author must get different ids");
    }

    @Test
    void same_tree_different_message_get_different_ids() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(PARENT_A), "alice", "first"),
                        CommitId.of(TREE, List.of(PARENT_A), "alice", "second")),
                "commits that differ only in message must get different ids");
    }

    private static void shutDownQuietly(ProllySail sail) {
        try {
            sail.shutDown();
        } catch (Exception ignored) {
            // best-effort teardown
        }
    }
}
