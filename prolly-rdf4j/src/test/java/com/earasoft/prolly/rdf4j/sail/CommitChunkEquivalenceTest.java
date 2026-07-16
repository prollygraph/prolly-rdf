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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.storage.FileNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0073 Phase 2 foundation — a full {@link CommitLog.Entry} can be reconstructed from a commit
 * <b>chunk plus a timestamp</b>, byte-identical to the fat log row. This is the primitive a thin
 * {@code <datetime> <id>} commit log will lean on (Phase 4): the chunk supplies the content
 * (metaTreeHash, parents, author, message), the {@code <datetime>} supplies the wall-clock time
 * (deliberately excluded from the id, so it can only live in the sidecar).
 *
 * <p>Commits a linear history with distinct authors + messages (covering a 0-parent genesis and
 * 1-parent ordinary commits), then asserts {@code CommitStore.readEntry(id, ts)} equals the
 * log-parsed {@link CommitLog.Entry} field-for-field, over the in-memory reference and the
 * git-loose-objects {@link FileNodeStore}. Proving this equivalence is what lets Phase 4 drop the
 * fat log fields without losing any commit content.
 */
class CommitChunkEquivalenceTest {

    @TestFactory
    Stream<DynamicTest> chunk_plus_timestamp_reconstructs_the_log_entry(@TempDir Path tmp) {
        return Stream.of("inmemory", "file")
                .map(
                        backend ->
                                DynamicTest.dynamicTest(
                                        "backend: " + backend,
                                        () -> assertEquivalent(backend, tmp.resolve(backend))));
    }

    private static void assertEquivalent(String backend, Path base) throws Exception {
        Path sidecar = base.resolve("sidecar");
        Files.createDirectories(sidecar);
        NodeStore store =
                "file".equals(backend)
                        ? new FileNodeStore(base.resolve("chunks"))
                        : new InMemoryNodeStore();
        try {
            CommitLog log = CommitLog.beside(sidecar);
            ProllySail sail =
                    new ProllySail(
                            store,
                            new HeapBufferPool(),
                            RootMetaTreeStore.beside(sidecar),
                            log,
                            RefsStore.beside(sidecar),
                            false);
            SailRepository repo = new SailRepository(sail);
            repo.init();

            String[] authors = {"alice", "bob", "carol"};
            String[] messages = {"first commit", "second commit", "third commit"};
            for (int i = 0; i < 3; i++) {
                sail.setNextCommitMessage(messages[i]);
                sail.setNextCommitAuthor(authors[i]);
                try (RepositoryConnection conn = repo.getConnection()) {
                    conn.begin();
                    ValueFactory vf = conn.getValueFactory();
                    conn.add(
                            vf.createIRI("urn:s" + i),
                            vf.createIRI("urn:p"),
                            vf.createIRI("urn:o" + i));
                    conn.commit();
                }
            }

            CommitStore commits = new CommitStore(store);
            List<CommitLog.Entry> entries = log.entries();
            assertEquals(3, entries.size(), backend + ": three commits must be recorded");
            for (CommitLog.Entry e : entries) {
                CommitLog.Entry reconstructed =
                        commits.readEntry(e.id(), e.timestamp())
                                .orElseThrow(
                                        () ->
                                                new AssertionError(
                                                        backend
                                                                + ": no commit chunk stored for id "
                                                                + HashUtils.toHex(e.id())));
                assertArrayEquals(e.id(), reconstructed.id(), backend + ": id");
                assertArrayEquals(
                        e.metaTreeHash(), reconstructed.metaTreeHash(), backend + ": metaTreeHash");
                assertEquals(
                        hex(e.parents()),
                        hex(reconstructed.parents()),
                        backend + ": parents (order + values)");
                assertEquals(e.message(), reconstructed.message(), backend + ": message");
                assertEquals(e.author(), reconstructed.author(), backend + ": author");
                assertEquals(e.timestamp(), reconstructed.timestamp(), backend + ": timestamp");
            }
        } finally {
            if (store instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static List<String> hex(List<byte[]> hashes) {
        return hashes.stream().map(HashUtils::toHex).toList();
    }
}
