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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Concurrent-push race test (plan Step 21). Multiple local peers race a push of an FF-valid (but
 * mutually-divergent) head against the same remote branch. The {@link RefsStore#compareAndSet} that
 * backs {@link InProcessRemoteRepository#compareAndSetRef} must serialize them so exactly one push
 * wins; the losers must surface "moved concurrently — retry" rather than silently overwriting.
 *
 * <p>The trick is making the race actually contested: simulate a slow CAS round-trip via {@link
 * InProcessRemoteRepository#setLatency} so every peer lands inside the others' read-then-CAS
 * window.
 *
 * <p><b>Parameterized over both provenance settings (ADR-0071).</b> Commit identity must be sound
 * under <i>either</i> provenance setting, so the race is run with {@code provenanceEnabled} both
 * {@code false} <i>and</i> {@code true}: in both cases exactly one of three concurrent pushes wins
 * the CAS, the other two surface a CAS-related rejection, and the remote head settles on exactly
 * one contender's {@link ProllySail#currentCommitId()}. This is the axis the withdrawn Option C
 * broke under provenance-on — pinning it here keeps the redesign's promise (identity holds
 * regardless of the provenance flag) honest.
 */
class ConcurrentPushRaceTest {

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

    private static void commitTriple(ProllySail sail, String s, String p, String o) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:" + s), vf.createIRI("urn:" + p), vf.createIRI("urn:" + o));
            conn.commit();
        }
    }

    /**
     * Seed {@code local} with the remote's current history so a subsequent commit produces an
     * FF-eligible child. {@link RepoSync#pull} (rather than the bare {@link RepoSync#fetch}) is
     * what we need here — pull routes through {@link com.earasoft.prolly.rdf4j.sail.MergeEngine} so
     * the live Sail's <i>in-memory</i> commit state advances to the remote head. A bare fetch only
     * updates durable state, leaving the Sail believing its current head is the empty initial — and
     * a follow-up commit would then branch off that empty head instead of the remote's ancestor,
     * breaking the FF chain.
     */
    private static void seedFromRemote(ProllySail local, ProllySail remote) throws IOException {
        new RepoSync(local).pull(new InProcessRemoteRepository(remote), "origin", "main");
    }

    @ParameterizedTest(name = "provenanceEnabled={0}")
    @ValueSource(booleans = {false, true})
    void concurrent_pushes_to_the_same_branch_serialize_to_exactly_one_winner(
            boolean provenanceEnabled,
            @TempDir Path remoteDir,
            @TempDir Path aDir,
            @TempDir Path bDir,
            @TempDir Path cDir)
            throws IOException, InterruptedException, ExecutionException {

        ProllySail remote = initedSail(remoteDir, provenanceEnabled);
        commitTriple(remote, "ancestor", "p", "0");

        // Three peers, each FF-extends the shared ancestor with its own
        // unique commit. Each push (in isolation) would be a valid FF.
        ProllySail a = initedSail(aDir, provenanceEnabled);
        seedFromRemote(a, remote);
        commitTriple(a, "x", "p", "a");
        ProllySail b = initedSail(bDir, provenanceEnabled);
        seedFromRemote(b, remote);
        commitTriple(b, "y", "p", "b");
        ProllySail c = initedSail(cDir, provenanceEnabled);
        seedFromRemote(c, remote);
        commitTriple(c, "z", "p", "c");

        // Slow the CAS round-trip so every push lands inside every other's
        // advertise-then-CAS window — the race is then guaranteed, not lucky.
        // (Each push performs one advertise + one CAS, so 10 ms of latency
        // per call ≈ 20 ms total per push, plenty of overlap.)
        InProcessRemoteRepository remoteA = new InProcessRemoteRepository(remote);
        remoteA.setLatency(10);
        InProcessRemoteRepository remoteB = new InProcessRemoteRepository(remote);
        remoteB.setLatency(10);
        InProcessRemoteRepository remoteC = new InProcessRemoteRepository(remote);
        remoteC.setLatency(10);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger raceLosses = new AtomicInteger();

        List<Future<?>> tasks = new ArrayList<>();
        tasks.add(submitPush(pool, start, a, remoteA, winners, raceLosses));
        tasks.add(submitPush(pool, start, b, remoteB, winners, raceLosses));
        tasks.add(submitPush(pool, start, c, remoteC, winners, raceLosses));

        start.countDown();
        for (Future<?> t : tasks) t.get();
        pool.shutdown();

        assertEquals(1, winners.get(), "exactly one of three concurrent pushes must win the CAS");
        assertEquals(
                2,
                raceLosses.get(),
                "the other two pushes must fail with 'moved concurrently — retry'");

        // The remote landed at one of the three peers' heads — not at the
        // common ancestor (which would mean every push lost, a torn state),
        // and not at some unrelated value.
        // The ref head is a commit id (ADR-0071); compare against the contenders' ids, and resolve
        // it to a tree hash for the chunk-store read.
        byte[] remoteHead = remote.refsStore().orElseThrow().get("main").orElseThrow();
        byte[] aHead = a.currentCommitId();
        byte[] bHead = b.currentCommitId();
        byte[] cHead = c.currentCommitId();
        boolean wonByOne =
                java.util.Arrays.equals(remoteHead, aHead)
                        || java.util.Arrays.equals(remoteHead, bHead)
                        || java.util.Arrays.equals(remoteHead, cHead);
        assertTrue(
                wonByOne,
                "the remote head must be exactly one of the contenders, not a torn third value");
        assertTrue(
                RootMetaTree.readFrom(remote.store(), remote.treeHashOf(remoteHead)).isPresent(),
                "the winner's tree landed in the remote chunk store");
    }

    private static Future<?> submitPush(
            ExecutorService pool,
            CountDownLatch start,
            ProllySail local,
            InProcessRemoteRepository remote,
            AtomicInteger winners,
            AtomicInteger raceLosses) {
        return pool.submit(
                () -> {
                    start.await();
                    try {
                        new RepoSync(local).push(remote, "origin", "main");
                        winners.incrementAndGet();
                    } catch (IllegalStateException raceLost) {
                        // The expected loser case. Two distinct rejection paths exist
                        // depending on race timing — both mean "someone else got
                        // there first; retry after fetch + merge":
                        //   - "moved concurrently" — CAS-side detection: this peer's
                        //     advertise saw the old head, but the CAS at the end
                        //     found the ref had moved.
                        //   - "non-fast-forward" — advertise-side detection: an
                        //     earlier peer's CAS landed before this peer's
                        //     advertiseRefs returned, so this peer already sees the
                        //     winner's head and computes its own push as non-FF.
                        String msg = raceLost.getMessage();
                        assertNotNull(msg);
                        assertTrue(
                                msg.contains("moved concurrently")
                                        || msg.contains("non-fast-forward"),
                                "race-loser must surface a CAS-related rejection — got: " + msg);
                        raceLosses.incrementAndGet();
                    }
                    return null;
                });
    }
}
