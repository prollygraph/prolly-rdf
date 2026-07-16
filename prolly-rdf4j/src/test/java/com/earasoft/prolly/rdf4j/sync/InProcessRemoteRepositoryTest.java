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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link InProcessRemoteRepository} — the in-process sync transport.
 *
 * <p>Post-ADR-0071 a branch ref holds the head <b>commit id</b>, and the sync protocol addresses
 * history by commit id: {@code advertiseRefs()} returns commit ids and {@code fetchPack(want,
 * have)} takes commit ids ({@code RepoSync.fetch} reads {@code want} straight off the advertised
 * refs, then resolves it to a tree hash separately). So these tests pass {@link
 * ProllySail#currentCommitId()} wherever a ref value / fetch handle is exchanged, and reserve
 * {@link ProllySail#currentCommitHash()} (the head's <em>tree</em> hash) for the one place a tree
 * is opened — {@link RootMetaTree#readFrom}.
 */
class InProcessRemoteRepositoryTest {

    /** A ProllySail with file-backed sidecars under {@code dir}, wrapped + initialised. */
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

    /** Commit one triple as its own versioned commit. */
    private static void commitTriple(ProllySail sail, String s, String p, String o) {
        SailRepository repo = new SailRepository(sail);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:" + s), vf.createIRI("urn:" + p), vf.createIRI("urn:" + o));
            conn.commit();
        }
    }

    @Test
    void advertiseRefs_returns_the_branch_head(@TempDir Path dir) throws IOException {
        ProllySail sail = initedSail(dir);
        commitTriple(sail, "a", "p", "b");
        commitTriple(sail, "c", "p", "d");

        Map<String, byte[]> refs = new InProcessRemoteRepository(sail).advertiseRefs();
        assertTrue(refs.containsKey("main"), "the default branch is advertised");
        // Refs hold commit ids (ADR-0071), so the advertised value is the head commit id.
        assertArrayEquals(sail.currentCommitId(), refs.get("main"));
    }

    @Test
    void fetchPack_carries_the_data_and_the_history(@TempDir Path dir) throws IOException {
        ProllySail sail = initedSail(dir);
        commitTriple(sail, "a", "p", "b");
        commitTriple(sail, "c", "p", "d");

        // fetchPack addresses history by commit id (ADR-0071).
        SyncPack pack =
                new InProcessRemoteRepository(sail).fetchPack(sail.currentCommitId(), List.of());
        assertFalse(pack.chunks().isEmpty(), "a full fetch carries chunks");
        assertEquals(2, pack.commits().size(), "and the two commits of history");
    }

    @Test
    void fetchPack_is_empty_when_the_requester_already_has_the_head(@TempDir Path dir)
            throws IOException {
        ProllySail sail = initedSail(dir);
        commitTriple(sail, "a", "p", "b");
        // The fetch handle is the head commit id (ADR-0071).
        byte[] head = sail.currentCommitId();

        SyncPack pack = new InProcessRemoteRepository(sail).fetchPack(head, List.of(head));
        assertTrue(pack.isEmpty(), "nothing to send — the requester is already up to date");
    }

    @Test
    void receivePack_lands_the_chunks_and_commits(@TempDir Path remoteDir, @TempDir Path freshDir)
            throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        commitTriple(remote, "c", "p", "d");
        // fetchPack addresses by commit id; the tree-presence assertion below opens the tree by
        // its tree hash — two distinct handles for the one head (ADR-0071).
        byte[] headId = remote.currentCommitId();
        byte[] headTree = remote.currentCommitHash();
        SyncPack pack = new InProcessRemoteRepository(remote).fetchPack(headId, List.of());

        ProllySail fresh = initedSail(freshDir);
        new InProcessRemoteRepository(fresh).receivePack(pack);

        assertTrue(
                RootMetaTree.readFrom(fresh.store(), headTree).isPresent(),
                "the head RootMetaTree landed in the fresh store");
        assertEquals(
                2,
                fresh.commitLog().orElseThrow().entries().size(),
                "both commit-log entries landed");
    }

    @Test
    void compareAndSetRef_enforces_the_expected_value(@TempDir Path dir) throws IOException {
        ProllySail sail = initedSail(dir);
        commitTriple(sail, "a", "p", "b");
        // A ref's value is a commit id (ADR-0071).
        byte[] head = sail.currentCommitId();
        InProcessRemoteRepository remote = new InProcessRemoteRepository(sail);

        byte[] stale = new byte[20];
        stale[0] = 0x7f;

        // Create a brand-new branch — expected == null requires it not to exist.
        assertTrue(remote.compareAndSetRef("feature", null, head));
        assertArrayEquals(head, remote.advertiseRefs().get("feature"));

        // A stale expected loses the race; the ref does not move.
        assertFalse(remote.compareAndSetRef("feature", stale, head));
        assertArrayEquals(head, remote.advertiseRefs().get("feature"));

        // The correct expected wins.
        assertTrue(remote.compareAndSetRef("feature", head, stale));
        assertArrayEquals(stale, remote.advertiseRefs().get("feature"));
    }
}
