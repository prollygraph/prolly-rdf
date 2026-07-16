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
import java.io.IOException;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage for {@link BareRepo} — {@code openBare} / {@code init --bare} + {@code clone}. */
class BareRepoTest {

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
    void open_creates_a_bare_repo_with_empty_sidecars(@TempDir Path dir) throws IOException {
        BareRepo bare = BareRepo.open(dir, new InMemoryNodeStore());
        assertTrue(bare.refs().list().isEmpty(), "no refs in a fresh bare");
        assertTrue(bare.commitLog().entries().isEmpty(), "no commits in a fresh bare");
        assertTrue(bare.remotes().list().isEmpty(), "no remotes in a fresh bare");
    }

    @Test
    void cloneInto_brings_the_remote_data_and_registers_origin(
            @TempDir Path remoteDir, @TempDir Path bareDir) throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");
        // The fetched head is the remote branch's head *commit id* (ADR-0071) — the handle that
        // refs hold, that the remote advertises, and that RepoSync.fetch transfers and returns.
        // cloneInto sets the local branch and the meta-head literally to this same id.
        byte[] remoteHead = remote.currentCommitId();

        BareRepo bare =
                BareRepo.cloneInto(
                        new InProcessRemoteRepository(remote),
                        "http://test.example",
                        bareDir,
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        "main");

        assertArrayEquals(
                remoteHead,
                bare.refs().get("main").orElseThrow(),
                "local main is set literally at the fetched head commit id — no re-commit");
        assertEquals(
                "http://test.example",
                bare.remotes().get("origin").orElseThrow(),
                "origin is registered in the remotes registry");
        assertEquals(
                2, bare.commitLog().entries().size(), "both commits landed in the bare repo's log");
        assertArrayEquals(
                remoteHead,
                bare.rootMetaTreeStore().get().orElseThrow(),
                "meta-head is set to the fetched head so a later ProllySail.init() restores at this"
                        + " commit");
    }

    @Test
    void cloneInto_rejects_a_non_empty_target(@TempDir Path remoteDir, @TempDir Path bareDir)
            throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");

        // Pre-create a ref so the target dir is no longer "fresh".
        RefsStore.beside(bareDir).put("main", new byte[20]);

        assertThrows(
                IllegalStateException.class,
                () ->
                        BareRepo.cloneInto(
                                new InProcessRemoteRepository(remote),
                                "http://test",
                                bareDir,
                                new InMemoryNodeStore(),
                                new HeapBufferPool(),
                                "main"));
    }
}
