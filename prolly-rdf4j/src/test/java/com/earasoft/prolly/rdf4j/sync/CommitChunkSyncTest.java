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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.CommitObject;
import com.earasoft.prolly.rdf4j.sail.CommitStore;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0073 Phase 3 (Step 8) — a pulled commit arrives on the receiver as a <b>content-addressed
 * chunk</b>, not only a log row. {@code PackBuilder} now includes each delta commit's own chunk in
 * the pack, so after a pull the receiver holds every commit under its id (as the authoring peer
 * does). This is the precondition that lets Phase 4 read commit content from chunks + shrink the
 * log, without a just-pulled commit's content going missing.
 */
class CommitChunkSyncTest {

    @Test
    void pulled_commits_arrive_as_chunks_on_the_receiver(@TempDir Path dirA, @TempDir Path dirB)
            throws Exception {
        NodeStore storeA = new InMemoryNodeStore();
        CommitLog logA = CommitLog.beside(dirA);
        ProllySail a = inited(storeA, logA, dirA);

        NodeStore storeB = new InMemoryNodeStore();
        CommitLog logB = CommitLog.beside(dirB);
        ProllySail b = inited(storeB, logB, dirB);

        // Peer A authors three commits (each writes its commit chunk locally, ADR-0073 Phase 1).
        for (int i = 0; i < 3; i++) {
            a.setNextCommitMessage("commit " + i);
            a.setNextCommitAuthor("author" + i);
            commitTriple(a, i);
        }

        // Peer B pulls main from A.
        new RepoSync(b).pull(new InProcessRemoteRepository(a), "origin", "main");

        CommitStore commitsB = new CommitStore(storeB);
        List<CommitLog.Entry> pulled = logB.entries();
        // The real invariant: EVERY commit on the receiver's log is also present as a chunk — no
        // matter how many there are (a genesis and/or a pull-created merge are counted too; if any
        // such commit lacked a chunk, that would be a genuine Phase-3 gap this assertion surfaces).
        assertTrue(pulled.size() >= 3, "the receiver pulled at least the three authored commits");
        for (CommitLog.Entry e : pulled) {
            CommitObject chunk =
                    commitsB.read(e.id())
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "receiver must hold the commit chunk for "
                                                            + HashUtils.toHex(e.id())
                                                            + " after pulling it"));
            assertArrayEquals(e.id(), chunk.id(), "the chunk hashes back to the pulled commit id");
            assertArrayEquals(
                    e.metaTreeHash(),
                    chunk.metaTreeHash(),
                    "the chunk's tree hash matches the pulled entry");
            assertEquals(e.message(), chunk.message(), "message survives the transfer");
            assertEquals(e.author(), chunk.author(), "author survives the transfer");
        }
    }

    private static ProllySail inited(NodeStore store, CommitLog log, Path dir) {
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        log,
                        RefsStore.beside(dir),
                        false);
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
}
