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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * Phase 7 Step 24 of {@code prolly-rdf4j-test-strategy.md} (S-9) — the sync <b>anti-entropy
 * convergence</b> property: N replicas exchanging <b>divergent histories (multi-peer
 * divergence)</b> must <b>set-reconcile</b> to the same dataset. Builds N peers, applies a
 * generated interleaving of divergent commits and pulls, runs an all-pull-all fixed-point sync, and
 * asserts the property the system must guarantee: <b>every peer ends up with the same
 * triple-set</b>.
 *
 * <p><b>Provenance-agnostic (both settings).</b> The convergence property is driven under
 * <b>both</b> provenance settings — a jqwik {@code @ForAll boolean provenanceEnabled} is folded
 * into the generated op-sequence and threaded into every peer's {@link ProllySail} (its 6-arg
 * constructor's trailing flag, per ADR-0071). Both settings reach the <em>same</em>
 * triple-set-convergence assertion, so this pins that the dangling-parent fix in the commit closure
 * ({@link com.earasoft.prolly.rdf4j.sync.CommitClosure}, which keys the closure by commit <i>id</i>
 * rather than tree hash) holds <b>regardless of provenance</b> — identity is sound under either
 * setting. (Historical: 2026-06-25 this was KNOWN-RED — the closure collapsed two distinct commits
 * that shared a tree hash, so a pull aborted with a dangling-parent rejection before convergence;
 * the id-keyed closure fixed it. Scoped by {@code
 * plans/prepublic/sync-anti-entropy-completeness.md}.)
 *
 * <p><b>The Step-24 upgrade.</b> This was a seeded {@code Random}-driven fuzz that could only
 * <i>log</i> the failing seed. It is now a jqwik property: the commit/pull op-sequence is
 * <i>generated</i>, so a divergence shrinks to a <b>minimal failing op-sequence</b> — the smallest
 * interleaving that breaks convergence, not just a seed to re-run. (The name is kept — docs + the
 * distributed-sync protocol spec reference {@code SyncFuzzTest} as the convergence pin, and it
 * still drives random divergent histories; jqwik only adds the shrinking.)
 *
 * <p>Head identity is <em>not</em> asserted — when peers A and B both produce merge commits in
 * different pull-orders, both merge commits cover the same ancestor set but stay permanently
 * distinct by hash. That asymmetry is an accepted property of merge-commit semantics; the
 * user-visible data still converges, which is what this pins.
 */
class SyncFuzzTest {

    private static final int PEERS = 3;
    private static final int MAX_CONVERGENCE_ITERATIONS = 50;

    /**
     * One generated step: a commit on {@code peer}, or (if {@code pull}) a pull of {@code main}
     * from {@code from}.
     */
    record SyncOp(boolean pull, int peer, int from) {}

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

    private static void commitTriple(ProllySail sail, int n) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s" + n), vf.createIRI("urn:p"), vf.createIRI("urn:o" + n));
            conn.commit();
        }
    }

    private static Set<String> triplesOf(ProllySail sail) {
        Set<String> out = new HashSet<>();
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.getStatements(null, null, null, false)
                    .forEach(
                            st ->
                                    out.add(
                                            st.getSubject()
                                                    + " "
                                                    + st.getPredicate()
                                                    + " "
                                                    + st.getObject()));
        }
        return out;
    }

    @Property(tries = 15)
    void random_divergent_histories_eventually_converge(
            @ForAll @From("ops") List<SyncOp> ops, @ForAll boolean provenanceEnabled)
            throws IOException {
        List<Path> dirs = new ArrayList<>();
        List<ProllySail> peers = new ArrayList<>();
        try {
            for (int i = 0; i < PEERS; i++) {
                Path dir = Files.createTempDirectory("prolly-sync-fuzz-" + i + "-");
                dirs.add(dir);
                peers.add(initedSail(dir, provenanceEnabled));
            }
            // Seed every peer with one commit so main exists everywhere before any pull — a
            // freshly-init'd
            // peer has no refs/main, and a pull from it would 400 on ref advertisement.
            int counter = 0;
            for (int i = 0; i < PEERS; i++) {
                commitTriple(peers.get(i), ++counter);
            }

            // ---- Apply the generated op-sequence: commits create divergence, pulls fold it into
            // 3-way merges.
            for (SyncOp op : ops) {
                int p = op.peer();
                if (op.pull()) {
                    int from = op.from() == p ? (p + 1) % PEERS : op.from(); // pulls are cross-peer
                    new RepoSync(peers.get(p))
                            .pull(new InProcessRemoteRepository(peers.get(from)), "origin", "main");
                } else {
                    commitTriple(peers.get(p), ++counter);
                }
            }

            // ---- Fixed-point convergence: every iteration, every peer pulls from every other;
            // stop when
            //      no peer's head moved.
            boolean converged = false;
            for (int iter = 0; iter < MAX_CONVERGENCE_ITERATIONS && !converged; iter++) {
                boolean anyChange = false;
                for (int i = 0; i < PEERS; i++) {
                    for (int j = 0; j < PEERS; j++) {
                        if (i == j) {
                            continue;
                        }
                        byte[] before = peers.get(i).currentCommitHash();
                        new RepoSync(peers.get(i))
                                .pull(
                                        new InProcessRemoteRepository(peers.get(j)),
                                        "origin",
                                        "main");
                        if (!Arrays.equals(before, peers.get(i).currentCommitHash())) {
                            anyChange = true;
                        }
                    }
                }
                converged = !anyChange;
            }

            assertTrue(
                    converged,
                    "peers did not reach a sync fixed point within "
                            + MAX_CONVERGENCE_ITERATIONS
                            + " full-mesh iterations (provenanceEnabled="
                            + provenanceEnabled
                            + ")");
            // The property that matters: every peer exposes the same triple-set (head identity is
            // not a
            // guarantee — see the class javadoc).
            Set<String> triples = triplesOf(peers.get(0));
            for (int i = 1; i < PEERS; i++) {
                assertEquals(
                        triples,
                        triplesOf(peers.get(i)),
                        "peer "
                                + i
                                + " has a different triple set than peer 0 — convergence is broken"
                                + " (provenanceEnabled="
                                + provenanceEnabled
                                + ")");
            }
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
     * 0–16 ops over {commit, pull} × peer × from; jqwik shrinks toward fewer/lower on a divergence.
     */
    @Provide
    Arbitrary<List<SyncOp>> ops() {
        Arbitrary<Integer> idx = Arbitraries.integers().between(0, PEERS - 1);
        Arbitrary<SyncOp> op =
                Combinators.combine(Arbitraries.of(true, false), idx, idx).as(SyncOp::new);
        return op.list().ofMinSize(0).ofMaxSize(16);
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
