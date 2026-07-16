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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Atomicity coverage for plan Step 23 — an interrupted transfer must leave the <em>ref
 * unmoved</em>. The transfer order in {@link RepoSync#push} is: 1) {@code receivePack(pack)}
 * (chunks first), then 2) {@code compareAndSetRef(...)} (ref last). So a {@code receivePack} that
 * fails mid-flight must abort the push <em>before</em> the CAS runs, leaving the remote branch
 * where it was. Symmetrically for {@link RepoSync#fetch}, a pack that breaches the limits or fails
 * integrity must leave the local tracking ref untouched — already covered for the integrity path by
 * {@code SyncIntegrityTest}; this test pins the limit-breach + receivePack- throws variants.
 */
class InterruptedTransferTest {

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

    private static void commitTriple(ProllySail sail, String s, String p, String o) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:" + s), vf.createIRI("urn:" + p), vf.createIRI("urn:" + o));
            conn.commit();
        }
    }

    @Test
    void a_push_whose_receivePack_throws_does_not_move_the_remote_ref(
            @TempDir Path localDir, @TempDir Path remoteDir) throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        byte[] localHead = local.currentCommitId(); // the head handle refs/sync exchange (ADR-0071)

        ProllySail remote = initedSail(remoteDir);
        // Remote starts empty — the push would normally create main pointing
        // at localHead. If receivePack throws, neither the chunks nor the ref
        // should advance.

        InProcessRemoteRepository real = new InProcessRemoteRepository(remote);
        RemoteRepository failsOnReceive =
                new RemoteRepository() {
                    @Override
                    public Map<String, byte[]> advertiseRefs() throws IOException {
                        return real.advertiseRefs();
                    }

                    @Override
                    public SyncPack fetchPack(byte[] want, Collection<byte[]> have)
                            throws IOException {
                        return real.fetchPack(want, have);
                    }

                    @Override
                    public void receivePack(SyncPack pack) {
                        // Mid-flight network failure / I/O error / disk full. The
                        // push must abort *here*, before compareAndSetRef.
                        throw new java.io.UncheckedIOException(
                                new IOException("simulated disk full mid-receivePack"));
                    }

                    @Override
                    public boolean compareAndSetRef(String b, byte[] e, byte[] d) {
                        throw new AssertionError(
                                "compareAndSetRef must NOT run after receivePack threw — "
                                        + "RepoSync.push violates chunks-first / ref-last atomicity");
                    }
                };

        assertThrows(
                java.io.UncheckedIOException.class,
                () -> new RepoSync(local).push(failsOnReceive, "origin", "main"));

        // The remote ref didn't move (still empty), and the remote commit log
        // is empty too — RepoSync didn't get past the failed receivePack.
        assertTrue(
                remote.refsStore().orElseThrow().get("main").isEmpty(),
                "the remote ref is unchanged after a failed receivePack");
        assertTrue(
                remote.commitLog().orElseThrow().entries().isEmpty(),
                "the remote commit log is unchanged after a failed receivePack");
        // And local state is also untouched.
        assertArrayEquals(localHead, local.currentCommitId());
    }

    @Test
    void a_fetch_whose_pack_breaches_limits_does_not_advance_the_tracking_ref(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");

        ProllySail local = initedSail(localDir);
        // A draconian limit: cap maxChunks at 1, so any real fetch trips.
        // The fetch must fail BEFORE the tracking ref is written.
        SyncLimits tight = new SyncLimits(1, 1L << 30);
        RepoSync sync = new RepoSync(local, null, tight);

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> sync.fetch(new InProcessRemoteRepository(remote), "origin", "main"));
        assertTrue(ex.getMessage().contains("maxChunks"), ex.getMessage());

        assertTrue(
                local.refsStore().orElseThrow().get("remotes/origin/main").isEmpty(),
                "a limit-breach fetch must leave the tracking ref untouched");
        assertTrue(
                local.commitLog().orElseThrow().entries().isEmpty(),
                "and the local commit log must stay empty — chunks-first/ref-last");
    }

    @Test
    void a_fetch_against_a_byte_capped_limit_is_rejected_with_a_clear_message(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");

        ProllySail local = initedSail(localDir);
        // A real fetch produces several hundred bytes of chunks; 16 is below
        // even one chunk and definitely below the running total.
        SyncLimits tight = new SyncLimits(1_000_000, 16);

        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new RepoSync(local, null, tight)
                                        .fetch(
                                                new InProcessRemoteRepository(remote),
                                                "origin",
                                                "main"));
        assertTrue(ex.getMessage().contains("maxBytes"), ex.getMessage());
    }

    @Test
    void a_legitimate_fetch_under_default_limits_succeeds_unchanged(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        // Regression: introducing the limit gate must not break the happy
        // path. A real two-commit fetch under default limits still works.
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");
        // fetch returns (and refs/advertiseRefs exchange) a commit id (ADR-0071), so the expected
        // head is the remote's current commit id — not its tree hash.
        byte[] head = remote.currentCommitId();

        ProllySail local = initedSail(localDir);
        byte[] fetched =
                new RepoSync(local).fetch(new InProcessRemoteRepository(remote), "origin", "main");

        assertArrayEquals(head, fetched);
        assertEquals(2, local.commitLog().orElseThrow().entries().size());
    }
}
