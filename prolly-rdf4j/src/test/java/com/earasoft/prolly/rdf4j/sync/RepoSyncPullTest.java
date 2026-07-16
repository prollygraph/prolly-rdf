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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage for {@link RepoSync#pull} — fetch + integrate (fast-forward or 3-way merge). */
class RepoSyncPullTest {

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

    /** The statement count visible through a fresh connection on {@code sail}. */
    private static long size(ProllySail sail) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            return conn.size();
        }
    }

    @Test
    void pull_into_an_empty_local_brings_the_remote_data(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");

        ProllySail local = initedSail(localDir);
        byte[] head =
                new RepoSync(local).pull(new InProcessRemoteRepository(remote), "origin", "main");

        assertNotNull(head, "pull returns the integrated head");
        assertEquals(2, size(local), "the local repo now holds the remote's two triples");
    }

    @Test
    void pull_merges_divergent_histories_into_their_union(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        // Local and remote each committed independently — pull must 3-way merge.
        ProllySail local = initedSail(localDir);
        commitTriple(local, "a", "p", "b");
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "x", "p", "y");

        byte[] head =
                new RepoSync(local).pull(new InProcessRemoteRepository(remote), "origin", "main");

        assertNotNull(head);
        assertEquals(2, size(local), "the merge commit holds the union of both sides' triples");
    }

    @Test
    void a_redundant_pull_is_up_to_date(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");

        ProllySail local = initedSail(localDir);
        RepoSync sync = new RepoSync(local);
        InProcessRemoteRepository origin = new InProcessRemoteRepository(remote);
        sync.pull(origin, "origin", "main");
        byte[] again = sync.pull(origin, "origin", "main"); // nothing changed remotely

        assertNotNull(again);
        assertEquals(1, size(local), "a redundant pull leaves the data unchanged");
    }

    @Test
    void pull_of_an_unknown_branch_is_rejected(@TempDir Path remoteDir, @TempDir Path localDir)
            throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        ProllySail local = initedSail(localDir);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new RepoSync(local)
                                .pull(new InProcessRemoteRepository(remote), "origin", "nope"));
    }
}
