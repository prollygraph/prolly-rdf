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
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Phase 7 Step 25 of {@code prolly-rdf4j-test-strategy.md} (S-9), sub-property 2 of 3 — an
 * <b>interrupted transfer is atomic and a re-sync converges</b>. Generalizes the example-based
 * {@code InterruptedTransferTest} (one whole-{@code receivePack} failure) to a property over a
 * generated commit stream and a generated <b>crash point</b>: a push whose {@code receivePack}
 * writes the first <i>K</i> chunks to the remote store and <i>then</i> throws must (1) leave the
 * remote ref + commit log <b>unmoved</b> (chunks-first / ref-last atomicity holds for any K — the K
 * orphan chunks advance nothing), and (2) a subsequent retry must <b>converge</b> the remote ref
 * onto the local head (the orphan chunks are content-addressed, so re-sending them is an idempotent
 * no-op, not corruption).
 *
 * <p>The crash is faithful, not modelled: {@code RepoSync.push} calls {@code receivePack} (chunks)
 * then {@code compareAndSetRef} (ref); {@code InProcessRemoteRepository.receivePack} writes chunks
 * one at a time, so a wrapper that writes K of them and throws is exactly "K chunks landed, then
 * the transfer died". {@code K} ranges past the pack size (clamped), covering "died before any
 * chunk" through "died after every chunk but before the ref CAS" — all of which must leave the ref
 * where it was.
 */
class InterruptedTransferProperty {

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

    private static void commitTriple(ProllySail sail, int n) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s" + n), vf.createIRI("urn:p"), vf.createIRI("urn:o" + n));
            conn.commit();
        }
    }

    @Property(tries = 20)
    void a_partial_push_is_atomic_and_a_retry_converges(
            @ForAll @IntRange(min = 1, max = 6) int numCommits,
            @ForAll @IntRange(min = 0, max = 24) int crashAfterChunks)
            throws IOException {
        Path localDir = Files.createTempDirectory("prolly-itp-local-");
        Path remoteDir = Files.createTempDirectory("prolly-itp-remote-");
        ProllySail local = null;
        ProllySail remote = null;
        try {
            local = initedSail(localDir);
            for (int i = 0; i < numCommits; i++) {
                commitTriple(local, i);
            }
            byte[] localHead = local.currentCommitId(); // refs hold commit ids (ADR-0071)

            remote = initedSail(remoteDir); // empty — the push would create main
            InProcessRemoteRepository real = new InProcessRemoteRepository(remote);

            // A receivePack that lands the first K chunks in the remote store, then dies — before
            // the
            // commit-log append and before the ref CAS. compareAndSetRef must therefore never run.
            RemoteRepository diesAfterK =
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
                            NodeStore store = real.sail().store();
                            int written = 0;
                            for (byte[] chunk : pack.chunks()) {
                                if (written >= crashAfterChunks) {
                                    break;
                                }
                                store.write(chunk); // content-addressed, idempotent
                                written++;
                            }
                            throw new UncheckedIOException(
                                    new IOException(
                                            "simulated transfer death after "
                                                    + written
                                                    + " chunks"));
                        }

                        @Override
                        public boolean compareAndSetRef(String b, byte[] e, byte[] d) {
                            throw new AssertionError(
                                    "compareAndSetRef must not run after receivePack threw "
                                            + "(K="
                                            + crashAfterChunks
                                            + ") — chunks-first/ref-last atomicity is broken");
                        }
                    };

            // (1) Atomicity: the partial push throws, and the remote is exactly as it was.
            ProllySail finalLocal = local;
            assertThrows(
                    UncheckedIOException.class,
                    () -> new RepoSync(finalLocal).push(diesAfterK, "origin", "main"));
            assertTrue(
                    remote.refsStore().orElseThrow().get("main").isEmpty(),
                    "K="
                            + crashAfterChunks
                            + ": the remote ref must be unmoved after a partial receivePack");
            assertTrue(
                    remote.commitLog().orElseThrow().entries().isEmpty(),
                    "K="
                            + crashAfterChunks
                            + ": the remote commit log must be unchanged after a partial push");
            assertArrayEquals(
                    localHead,
                    local.currentCommitId(),
                    "the local head must be untouched by a failed push");

            // (2) Convergence: a retry (the real remote) lands main on the local head — the K
            // orphan
            //     chunks from the failed attempt are dedup'd, not corruption.
            new RepoSync(local).push(real, "origin", "main");
            byte[] remoteMain = remote.refsStore().orElseThrow().get("main").orElseThrow();
            assertArrayEquals(
                    localHead,
                    remoteMain,
                    "after a retry, the remote ref must converge on the local head");
        } finally {
            shutDownQuietly(local);
            shutDownQuietly(remote);
            deleteRecursively(localDir);
            deleteRecursively(remoteDir);
        }
    }

    private static void shutDownQuietly(ProllySail sail) {
        if (sail != null) {
            try {
                sail.shutDown();
            } catch (Exception ignored) {
                // best-effort teardown
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            pth -> {
                                try {
                                    Files.deleteIfExists(pth);
                                } catch (IOException ignored) {
                                    // best-effort temp cleanup
                                }
                            });
        }
    }
}
