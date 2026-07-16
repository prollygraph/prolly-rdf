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
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link RepoSync#push} — the client-side push with a fast-forward check.
 *
 * <p>Post-ADR-0071 a branch ref holds a commit <b>id</b>, not a tree hash. {@link RepoSync#push}
 * reads the local ref (an id), advances the remote ref to that id, and returns it — so the head the
 * test tracks for push/ref comparisons is {@link ProllySail#currentCommitId()}. The separate
 * <em>tree</em> hash ({@link ProllySail#currentCommitHash()}) is still what opens a tree in the
 * remote store ({@link RootMetaTree#readFrom}).
 */
class RepoSyncPushTest {

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
    void push_creates_the_branch_on_an_empty_remote(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        commitTriple(local, "c", "p", "d");
        byte[] head = local.currentCommitId(); // the ref handle push publishes
        byte[] headTree = local.currentCommitHash(); // the tree hash that opens the snapshot

        ProllySail remote = initedSail(remoteDir);
        byte[] pushed =
                new RepoSync(local).push(new InProcessRemoteRepository(remote), "origin", "main");

        assertArrayEquals(head, pushed, "push returns the published head");
        assertArrayEquals(
                head,
                remote.refsStore().orElseThrow().get("main").orElseThrow(),
                "the remote branch now points at the pushed head");
        assertTrue(
                RootMetaTree.readFrom(remote.store(), headTree).isPresent(),
                "the head tree landed in the remote store");
        assertEquals(
                2,
                remote.commitLog().orElseThrow().entries().size(),
                "both commits landed in the remote log");
    }

    @Test
    void fast_forward_push_advances_an_existing_branch(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        RepoSync sync = new RepoSync(local);
        InProcessRemoteRepository origin = new InProcessRemoteRepository(remote);
        sync.push(origin, "origin", "main"); // remote main @ commit 1

        commitTriple(local, "c", "p", "d"); // local advances → commit 2
        byte[] head2 = local.currentCommitId();
        byte[] pushed = sync.push(origin, "origin", "main");

        assertArrayEquals(head2, pushed);
        assertArrayEquals(
                head2,
                remote.refsStore().orElseThrow().get("main").orElseThrow(),
                "the remote branch fast-forwarded to the new head");
        assertEquals(2, remote.commitLog().orElseThrow().entries().size());
    }

    @Test
    void non_fast_forward_push_is_rejected(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "x", "p", "y"); // the remote has its own, unrelated commit on main
        byte[] remoteHead = remote.currentCommitId();

        assertThrows(
                IllegalStateException.class,
                () ->
                        new RepoSync(local)
                                .push(new InProcessRemoteRepository(remote), "origin", "main"));
        assertArrayEquals(
                remoteHead,
                remote.refsStore().orElseThrow().get("main").orElseThrow(),
                "a rejected push must not move the remote ref");
    }

    @Test
    void push_when_already_up_to_date_is_a_noop(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        RepoSync sync = new RepoSync(local);
        InProcessRemoteRepository origin = new InProcessRemoteRepository(remote);

        byte[] head = sync.push(origin, "origin", "main");
        byte[] again = sync.push(origin, "origin", "main"); // nothing changed since
        assertArrayEquals(head, again, "a redundant push is a no-op");
    }

    @Test
    void push_of_an_unknown_local_branch_is_rejected(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepoSync(local)
                                .push(new InProcessRemoteRepository(remote), "origin", "nope"));
    }
}
