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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.storage.FileNodeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0073 Phase 1 — a commit made through a live {@link ProllySail} is <b>also</b> persisted as a
 * content-addressed chunk in the {@code NodeStore} under its own id (additive: the {@code
 * commits.log} sidecar is still authoritative). After committing a triple, {@code
 * store.read(currentCommitId())} must return the commit object, its tree hash must equal the
 * commit's RootMetaTree hash, and the chunk must hash back to exactly the commit id.
 *
 * <p>Runs over every {@link NodeStore} backend — the production {@link RocksNodeStore}, the
 * in-memory reference, and the git-loose-objects {@link FileNodeStore} — to prove commit
 * persistence is backend-independent (content-addressing makes it so).
 */
class CommitChunkPersistenceTest {

    private enum Backend {
        INMEMORY,
        FILE,
        ROCKS
    }

    @TestFactory
    Stream<DynamicTest> a_committed_commit_is_stored_as_a_chunk_under_its_id(@TempDir Path tmp) {
        return Stream.of(Backend.values())
                .map(
                        backend ->
                                DynamicTest.dynamicTest(
                                        "backend: " + backend,
                                        () -> assertCommitPersistedAsChunk(backend, tmp)));
    }

    private static void assertCommitPersistedAsChunk(Backend backend, Path tmp) throws Exception {
        Path base = tmp.resolve(backend.name());
        Path sidecar = base.resolve("sidecar");
        Files.createDirectories(sidecar);
        NodeStore store = open(backend, base);
        try {
            ProllySail sail =
                    new ProllySail(
                            store,
                            new HeapBufferPool(),
                            RootMetaTreeStore.beside(sidecar),
                            CommitLog.beside(sidecar),
                            RefsStore.beside(sidecar),
                            false);
            SailRepository repo = new SailRepository(sail);
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                ValueFactory vf = conn.getValueFactory();
                conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
                conn.commit();
            }

            byte[] id = sail.currentCommitId();
            assertNotNull(id, backend + ": a commit id must exist after committing");

            Optional<CommitObject> read = new CommitStore(store).read(id);
            assertTrue(
                    read.isPresent(),
                    backend + ": the committed commit must be stored as a chunk under its id");
            CommitObject commit = read.orElseThrow();
            assertArrayEquals(
                    id, commit.id(), backend + ": the commit chunk must hash back to its id");
            assertArrayEquals(
                    sail.currentCommitHash(),
                    commit.metaTreeHash(),
                    backend + ": the chunk's tree hash must be the commit's RootMetaTree hash");
            assertTrue(
                    commit.parents().isEmpty(),
                    backend + ": the first commit is a genesis (no parents)");
        } finally {
            if (store instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static NodeStore open(Backend backend, Path base) throws Exception {
        return switch (backend) {
            case INMEMORY -> new InMemoryNodeStore();
            case FILE -> new FileNodeStore(base.resolve("chunks"));
            case ROCKS -> new RocksNodeStore(base.resolve("rocks").toString());
        };
    }
}
