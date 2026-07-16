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

/** Coverage for {@link RepoSync#fetch} — the client-side pure-transfer fetch. */
class RepoSyncFetchTest {

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
    void fetch_downloads_a_remote_branch_and_records_a_tracking_ref(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");
        // fetch returns + tracks a commit id (ADR-0071); the tree hash is what opens the tree.
        byte[] headId = remote.currentCommitId();
        byte[] headTree = remote.currentCommitHash();

        ProllySail local = initedSail(localDir);
        byte[] fetched =
                new RepoSync(local).fetch(new InProcessRemoteRepository(remote), "origin", "main");

        assertArrayEquals(headId, fetched, "fetch returns the remote branch head id");
        assertTrue(
                RootMetaTree.readFrom(local.store(), headTree).isPresent(),
                "the head tree is present in the local store");
        assertEquals(
                2,
                local.commitLog().orElseThrow().entries().size(),
                "both commits landed in the local log");

        RefsStore refs = local.refsStore().orElseThrow();
        assertArrayEquals(
                headId,
                refs.get("remotes/origin/main").orElseThrow(),
                "the remote-tracking ref points at the fetched head id");
        assertTrue(refs.get("main").isEmpty(), "the local branch was not touched");
    }

    @Test
    void fetch_of_an_unknown_branch_is_rejected(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        ProllySail local = initedSail(localDir);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepoSync(local)
                                .fetch(new InProcessRemoteRepository(remote), "origin", "nope"));
    }

    @Test
    void a_second_fetch_transfers_only_the_delta(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");

        ProllySail local = initedSail(localDir);
        RepoSync sync = new RepoSync(local);
        InProcessRemoteRepository origin = new InProcessRemoteRepository(remote);
        sync.fetch(origin, "origin", "main");

        // The remote advances; the second fetch should bring only the new commit.
        commitTriple(remote, "e", "p", "f");
        byte[] head2Id = remote.currentCommitId();
        byte[] head2Tree = remote.currentCommitHash();
        byte[] fetched = sync.fetch(origin, "origin", "main");

        assertArrayEquals(head2Id, fetched);
        assertEquals(
                3,
                local.commitLog().orElseThrow().entries().size(),
                "the third commit was merged into the local log");
        assertArrayEquals(
                head2Id,
                local.refsStore().orElseThrow().get("remotes/origin/main").orElseThrow(),
                "the tracking ref advanced to the new head id");
        assertTrue(
                RootMetaTree.readFrom(local.store(), head2Tree).isPresent(),
                "the advanced tree is complete locally");
    }
}
