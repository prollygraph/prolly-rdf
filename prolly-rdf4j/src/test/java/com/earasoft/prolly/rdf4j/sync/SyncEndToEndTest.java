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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the in-process distributed-sync engine — full push / pull round trips
 * between independent {@link ProllySail} repositories mediated by an {@code origin}. Closes plan
 * Step 10.
 */
class SyncEndToEndTest {

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

    private static long size(ProllySail sail) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            return conn.size();
        }
    }

    @Test
    void push_then_a_fresh_pull_converges_data_and_chunks(
            @TempDir Path aDir, @TempDir Path originDir, @TempDir Path bDir) throws IOException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "alice", "knows", "bob");
        commitTriple(a, "bob", "knows", "carol");
        // ADR-0071: the ref/sync handle is the commit id; the chunk store is keyed by the tree
        // hash.
        byte[] idA = a.currentCommitId();
        byte[] treeA = a.currentCommitHash();

        ProllySail origin = initedSail(originDir);
        new RepoSync(a).push(new InProcessRemoteRepository(origin), "origin", "main");

        // Ref convergence — origin's branch now names A's head commit id.
        assertArrayEquals(idA, origin.refsStore().orElseThrow().get("main").orElseThrow());
        // Chunk convergence — origin holds exactly A's reachable chunk set (walked by tree hash).
        assertEquals(
                ChunkReachability.from(a.store(), treeA, Set.of()),
                ChunkReachability.from(origin.store(), treeA, Set.of()),
                "origin's chunk store converged on A's");

        // A fresh repo pulls from origin and ends up with the same data + chunks.
        ProllySail b = initedSail(bDir);
        new RepoSync(b).pull(new InProcessRemoteRepository(origin), "origin", "main");
        assertEquals(2, size(b), "B converged on A's data via origin");
        assertEquals(
                ChunkReachability.from(a.store(), treeA, Set.of()),
                ChunkReachability.from(b.store(), treeA, Set.of()),
                "B's chunk store holds A's full head tree");
    }

    @Test
    void non_fast_forward_push_is_rejected_then_resolved_by_a_pull(
            @TempDir Path aDir, @TempDir Path originDir, @TempDir Path bDir) throws IOException {
        ProllySail origin = initedSail(originDir);
        InProcessRemoteRepository remote = new InProcessRemoteRepository(origin);

        // A and B each commit independently.
        ProllySail a = initedSail(aDir);
        commitTriple(a, "alice", "p", "1");
        ProllySail b = initedSail(bDir);
        commitTriple(b, "bob", "p", "2");

        // A publishes first.
        new RepoSync(a).push(remote, "origin", "main");

        // B's push is a non-fast-forward — rejected, origin untouched.
        RepoSync bSync = new RepoSync(b);
        assertThrows(IllegalStateException.class, () -> bSync.push(remote, "origin", "main"));

        // B resolves by pulling (a 3-way merge), then re-pushes — now a fast-forward.
        bSync.pull(remote, "origin", "main");
        assertEquals(2, size(b), "B merged A's commit");
        bSync.push(remote, "origin", "main");

        // A pulls and converges on the merged history.
        new RepoSync(a).pull(remote, "origin", "main");
        assertEquals(2, size(a), "A converged on the union of both commits");
    }

    @Test
    void a_repeated_pull_tracks_the_remote_as_it_advances(
            @TempDir Path aDir, @TempDir Path originDir, @TempDir Path bDir) throws IOException {
        ProllySail origin = initedSail(originDir);
        InProcessRemoteRepository remote = new InProcessRemoteRepository(origin);

        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        RepoSync aSync = new RepoSync(a);
        aSync.push(remote, "origin", "main");

        ProllySail b = initedSail(bDir);
        RepoSync bSync = new RepoSync(b);
        bSync.pull(remote, "origin", "main");
        assertEquals(1, size(b), "first pull brought A's initial triple");

        // A commits more and publishes the advance.
        commitTriple(a, "y", "p", "2");
        aSync.push(remote, "origin", "main");

        // B's second pull picks up the new commit.
        bSync.pull(remote, "origin", "main");
        assertEquals(2, size(b), "the second pull tracked A's advance");
    }
}
