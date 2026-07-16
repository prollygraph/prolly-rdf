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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for plan Step 20 — the {@code force=true} variant of {@link
 * RepoSync#push(RemoteRepository, String, String, boolean)} and the actionability of the
 * non-fast-forward rejection.
 *
 * <p>The semantics are git's {@code --force-with-lease}: skip the FF check, but still CAS against
 * the head this client observed in the advertisement — so a force push that races a concurrent
 * remote move still fails loudly instead of silently clobbering work the caller didn't see.
 *
 * <p>Refs hold commit <b>ids</b> (ADR-0071), so the head {@link RepoSync#push} publishes/returns —
 * and the value parked in {@link RefsStore} — is a commit id ({@link
 * ProllySail#currentCommitId()}), not the tree hash.
 */
class RepoSyncForcePushTest {

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
    void non_fast_forward_push_error_names_force_as_the_override(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "x", "p", "y"); // remote has an unrelated commit on main

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new RepoSync(local)
                                        .push(
                                                new InProcessRemoteRepository(remote),
                                                "origin",
                                                "main"));

        // The message has to point operators at the override, otherwise the
        // rejection turns into a support ticket.
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertTrue(msg.contains("non-fast-forward"), msg);
        assertTrue(msg.contains("force=true"), "the error must name the force override: " + msg);
    }

    @Test
    void non_fast_forward_push_with_force_succeeds(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        // The pushed head + the published ref value are commit ids (ADR-0071), so compare against
        // the local commit id, not the tree hash.
        byte[] localHead = local.currentCommitId();

        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "x", "p", "y");
        byte[] divergedRemoteHead = remote.currentCommitHash();

        byte[] pushed =
                new RepoSync(local)
                        .push(new InProcessRemoteRepository(remote), "origin", "main", true);

        assertArrayEquals(
                localHead,
                pushed,
                "the force push returns the local head now published on the remote");
        assertArrayEquals(
                localHead,
                remote.refsStore().orElseThrow().get("main").orElseThrow(),
                "the remote ref was overwritten — diverged head replaced");
        assertEquals(remote.currentCommitHash().length, divergedRemoteHead.length); // sanity
    }

    @Test
    void force_push_still_fails_when_the_remote_moved_during_the_push(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "x", "p", "y");

        // A RemoteRepository that simulates a concurrent third-party move:
        // advertise the current head, then before the CAS, slip a *different*
        // ref value in. The CAS lease must fail loud.
        InProcessRemoteRepository real = new InProcessRemoteRepository(remote);
        byte[] sneakHead = new byte[20];
        sneakHead[0] = 0x42; // a hash the local has never seen
        RemoteRepository racy =
                new RemoteRepository() {
                    @Override
                    public java.util.Map<String, byte[]> advertiseRefs() throws IOException {
                        return real.advertiseRefs();
                    }

                    @Override
                    public SyncPack fetchPack(byte[] want, java.util.Collection<byte[]> have)
                            throws IOException {
                        return real.fetchPack(want, have);
                    }

                    @Override
                    public void receivePack(SyncPack pack) throws IOException {
                        // Between the (already-completed) advertisement and the
                        // upcoming CAS, a concurrent peer overwrites the remote ref.
                        remote.refsStore().orElseThrow().put("main", sneakHead);
                        real.receivePack(pack);
                    }

                    @Override
                    public boolean compareAndSetRef(String branch, byte[] expected, byte[] desired)
                            throws IOException {
                        return real.compareAndSetRef(branch, expected, desired);
                    }
                };

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> new RepoSync(local).push(racy, "origin", "main", true));
        assertTrue(
                ex.getMessage().contains("moved concurrently"),
                "lease semantics: force push must still detect a concurrent move — "
                        + ex.getMessage());
        assertArrayEquals(
                sneakHead,
                remote.refsStore().orElseThrow().get("main").orElseThrow(),
                "the concurrent third party's value is preserved — the force push did not clobber it");
    }

    @Test
    void already_up_to_date_push_with_force_is_still_a_noop(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        // force=true must not turn a no-op into a needless rewrite — the
        // early "already at this head" return runs before the FF check.
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        RepoSync sync = new RepoSync(local);
        InProcessRemoteRepository origin = new InProcessRemoteRepository(remote);

        byte[] first = sync.push(origin, "origin", "main", true);
        byte[] again = sync.push(origin, "origin", "main", true);
        assertArrayEquals(first, again);
    }
}
