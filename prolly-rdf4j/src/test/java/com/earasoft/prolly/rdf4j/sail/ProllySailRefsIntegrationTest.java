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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each successful Sail commit advances {@code refs/main}. After N commits, {@code refs/main} should
 * point at the same id as {@link ProllySail#currentCommitId()} (refs hold commit ids, ADR-0071).
 */
class ProllySailRefsIntegrationTest {

    @Test
    void commit_advances_refs_main(@TempDir Path dir) throws Exception {
        NodeStore store = new InMemoryNodeStore();
        RootMetaTreeStore mts = RootMetaTreeStore.beside(dir);
        CommitLog log = CommitLog.beside(dir);
        RefsStore refs = RefsStore.beside(dir);
        ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts, log, refs);

        Repository repo = new SailRepository(sail);
        repo.init();

        // Before any commit: no refs/main file.
        assertFalse(refs.exists(RefsStore.DEFAULT_BRANCH));

        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = repo.getValueFactory();
            IRI alice = vf.createIRI("urn:test:alice");
            IRI knows = vf.createIRI("urn:test:knows");
            IRI bob = vf.createIRI("urn:test:bob");
            conn.begin();
            conn.add(alice, knows, bob);
            conn.commit();
        }
        byte[] firstHead = refs.get(RefsStore.DEFAULT_BRANCH).orElseThrow();
        assertArrayEquals(
                sail.currentCommitId(),
                firstHead,
                "after first commit, refs/main must match the current commit id");

        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = repo.getValueFactory();
            conn.begin();
            conn.add(
                    vf.createIRI("urn:test:c"),
                    vf.createIRI("urn:test:p"),
                    vf.createIRI("urn:test:d"));
            conn.commit();
        }
        byte[] secondHead = refs.get(RefsStore.DEFAULT_BRANCH).orElseThrow();
        assertArrayEquals(sail.currentCommitId(), secondHead);
        assertFalse(
                java.util.Arrays.equals(firstHead, secondHead),
                "second commit must produce a different head hash");

        repo.shutDown();
    }

    @Test
    void currentBranch_is_main(@TempDir Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        assertEquals("main", sail.currentBranch());
    }
}
