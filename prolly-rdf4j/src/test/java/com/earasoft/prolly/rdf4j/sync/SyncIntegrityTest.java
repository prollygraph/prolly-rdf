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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncCommitEntry;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end integrity & anti-tamper coverage for {@link RepoSync#fetch} (plan Step 22). The
 * unit-level metaTreeHash check lives in {@code CommitLogSyncTest}; this test pins the property at
 * the fetch boundary — a malicious remote returning a pack with a forged {@link CommitLog.Entry}
 * that points at a metaTreeHash the pack never delivered must be rejected, leaving local state
 * unmoved.
 */
class SyncIntegrityTest {

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
    void fetch_rejects_a_pack_that_smuggles_a_phantom_commit_entry(
            @TempDir Path remoteDir, @TempDir Path localDir) throws IOException {
        ProllySail remote = initedSail(remoteDir);
        commitTriple(remote, "a", "p", "b");
        byte[] head = remote.currentCommitHash();

        // A malicious RemoteRepository — wraps the real one, but injects an
        // extra CommitLog entry whose metaTreeHash is *not* in the pack's
        // chunks. Without the Step 22 check, this would leave a phantom
        // commit in the local log pointing at unreadable data.
        InProcessRemoteRepository real = new InProcessRemoteRepository(remote);
        byte[] phantomHash = new byte[20];
        phantomHash[0] = (byte) 0xfe;
        RemoteRepository tampered =
                new RemoteRepository() {
                    @Override
                    public Map<String, byte[]> advertiseRefs() throws IOException {
                        return real.advertiseRefs();
                    }

                    @Override
                    public SyncPack fetchPack(byte[] want, Collection<byte[]> have)
                            throws IOException {
                        SyncPack base = real.fetchPack(want, have);
                        List<SyncCommitEntry> withPhantom = new ArrayList<>(base.commits());
                        // Insert the phantom *before* the genuine entries so the
                        // genuine ones (which name it as their parent in the worst
                        // case) still have a sensible topology — and even on its own
                        // the metaTreeHash points at nothing in the pack.
                        withPhantom.add(
                                0,
                                new SyncCommitEntry(
                                        Instant.now(),
                                        phantomHash,
                                        phantomHash,
                                        List.of(),
                                        "forged-by-evil-remote",
                                        ""));
                        return new SyncPack(base.chunks(), withPhantom);
                    }

                    @Override
                    public void receivePack(SyncPack pack) throws IOException {
                        real.receivePack(pack);
                    }

                    @Override
                    public boolean compareAndSetRef(String b, byte[] e, byte[] d)
                            throws IOException {
                        return real.compareAndSetRef(b, e, d);
                    }
                };

        ProllySail local = initedSail(localDir);
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new RepoSync(local).fetch(tampered, "origin", "main"));
        assertTrue(
                ex.getMessage().contains("metaTreeHash"),
                "the rejection message must name the integrity violation — got: "
                        + ex.getMessage());

        // Local state stays clean — no phantom commit landed, no tracking ref
        // points at the partial fetch.
        assertTrue(
                local.commitLog().orElseThrow().entries().isEmpty(),
                "a rejected fetch leaves the local commit log untouched");
        assertTrue(
                local.refsStore().orElseThrow().get("remotes/origin/main").isEmpty(),
                "and the tracking ref is not advanced to a partially-applied state");
        // The head chunk is also not declared "ready" since fetch threw
        // before reaching the ref update. Note we don't assert chunk absence —
        // the legitimate chunks were already streamed into the store before
        // mergeInto ran; that's acceptable (they're harmless orphans), it's
        // the *ref* and *commit log* that must stay clean.
        assertArrayEquals(
                head,
                remote.currentCommitHash(),
                "and the remote is unchanged — this is a one-sided client check");
    }
}
