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
import com.earasoft.prolly.storage.FileNodeStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end: every Sail commit appends a {@link CommitLog} entry whose RootMetaTree hash matches
 * the {@link RootMetaTreeStore} pointer at that moment.
 *
 * <p>Three commits → three log entries → currentCommitHash matches the latest entry. This is the
 * core invariant that the Memento response headers will rely on.
 */
class ProllySailCommitLogIntegrationTest {

    @Test
    void each_commit_appends_one_log_entry(@TempDir Path dir) throws Exception {
        NodeStore store = new InMemoryNodeStore();
        RootMetaTreeStore mts = RootMetaTreeStore.beside(dir);
        CommitLog log = CommitLog.beside(dir);
        ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts, log);

        Repository repo = new SailRepository(sail);
        repo.init();

        Instant beforeAny = Instant.now().minusSeconds(1);

        ValueFactory vf = repo.getValueFactory();
        IRI alice = vf.createIRI("urn:test:alice");
        IRI bob = vf.createIRI("urn:test:bob");
        IRI carol = vf.createIRI("urn:test:carol");
        IRI knows = vf.createIRI("urn:test:knows");

        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            conn.add(alice, knows, bob);
            conn.commit();
            conn.begin();
            conn.add(bob, knows, carol);
            conn.commit();
            conn.begin();
            conn.add(carol, knows, alice);
            conn.commit();
        }

        repo.shutDown();

        List<CommitLog.Entry> entries = log.entries();
        assertEquals(3, entries.size(), "expected one log entry per commit");
        // Entries are chronologically ordered.
        for (int i = 1; i < entries.size(); i++) {
            assertFalse(
                    entries.get(i).timestamp().isBefore(entries.get(i - 1).timestamp()),
                    "entries must be in non-decreasing time order");
        }
        // All entries occurred at or after our start sentinel.
        for (CommitLog.Entry e : entries) {
            assertFalse(e.timestamp().isBefore(beforeAny));
        }
    }

    @Test
    void currentCommitHash_matches_latest_log_entry(@TempDir Path dir) throws Exception {
        NodeStore store = new InMemoryNodeStore();
        RootMetaTreeStore mts = RootMetaTreeStore.beside(dir);
        CommitLog log = CommitLog.beside(dir);
        ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts, log);
        Repository repo = new SailRepository(sail);
        repo.init();

        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = repo.getValueFactory();
            conn.begin();
            conn.add(
                    vf.createIRI("urn:test:s"),
                    vf.createIRI("urn:test:p"),
                    vf.createIRI("urn:test:o"));
            conn.commit();
        }

        byte[] currentHash = sail.currentCommitHash();
        assertNotNull(currentHash, "currentCommitHash should be set after a commit");
        Instant currentInstant = sail.currentCommitInstant();
        assertNotNull(currentInstant, "currentCommitInstant should be set after a commit");

        CommitLog.Entry latest = log.latest().orElseThrow();
        assertArrayEquals(
                currentHash,
                latest.metaTreeHash(),
                "Sail's currentCommitHash should equal the most recent log entry's hash");
        assertEquals(
                currentInstant,
                latest.timestamp(),
                "Sail's currentCommitInstant should equal the most recent log entry's timestamp");

        repo.shutDown();
    }

    @Test
    void setNextCommitAuthor_attributes_the_next_commit(@TempDir Path dir) throws Exception {
        // Step 2 of plans/consolidate-rdf-on-rdf4j.md — the in-process author seam.
        // An in-process caller (e.g. ProllySailBomStore) sets author + message
        // before conn.commit(); the resulting CommitLog entry reports both, and
        // the one-shot clears so the *next* (un-attributed) commit has no author.
        NodeStore store = new InMemoryNodeStore();
        RootMetaTreeStore mts = RootMetaTreeStore.beside(dir);
        CommitLog log = CommitLog.beside(dir);
        ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts, log);
        Repository repo = new SailRepository(sail);
        repo.init();

        ValueFactory vf = repo.getValueFactory();
        IRI p = vf.createIRI("urn:test:p");
        try (RepositoryConnection conn = repo.getConnection()) {
            sail.setNextCommitAuthor("alice");
            sail.setNextCommitMessage("ingest sbom v1");
            conn.begin();
            conn.add(vf.createIRI("urn:test:s1"), p, vf.createIRI("urn:test:o1"));
            conn.commit();

            // Second commit sets no author — the one-shot must have cleared.
            conn.begin();
            conn.add(vf.createIRI("urn:test:s2"), p, vf.createIRI("urn:test:o2"));
            conn.commit();
        }
        repo.shutDown();

        List<CommitLog.Entry> entries = log.entries();
        assertEquals(2, entries.size());
        assertEquals("alice", entries.get(0).author(), "first commit carries the set author");
        assertEquals("ingest sbom v1", entries.get(0).message());
        assertEquals("", entries.get(1).author(), "author one-shot cleared after the first commit");
    }

    @Test
    void reopening_restores_current_commit_info(@TempDir Path dir) throws Exception {
        // A persistent chunk store so the commit chunks survive the reopen — ADR-0073: a thin
        // commit-log row reconstructs its entry from the commit chunk, so a genuine reopen needs
        // the
        // chunks on disk (an in-memory store would lose them, and the log could no longer resolve).
        Path chunks = dir.resolve("chunks");

        // Phase 1: commit then close.
        byte[] expectedHash;
        Instant expectedInstant;
        try (FileNodeStore store = new FileNodeStore(chunks)) {
            RootMetaTreeStore mts = RootMetaTreeStore.beside(dir);
            CommitLog log = CommitLog.beside(dir);
            ProllySail sail = new ProllySail(store, new HeapBufferPool(), mts, log);
            Repository repo = new SailRepository(sail);
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = repo.getValueFactory();
                conn.begin();
                conn.add(
                        vf.createIRI("urn:test:s"),
                        vf.createIRI("urn:test:p"),
                        vf.createIRI("urn:test:o"));
                conn.commit();
            }
            expectedHash = sail.currentCommitHash();
            expectedInstant = sail.currentCommitInstant();
            repo.shutDown();
        }

        // Phase 2: reopen on the SAME persistent chunk store — the meta-head pointer is recovered,
        // and the log reconstructs its latest entry (tree hash + timestamp) from the durable chunk.
        try (FileNodeStore store = new FileNodeStore(chunks)) {
            RootMetaTreeStore mts = RootMetaTreeStore.beside(dir);
            CommitLog log = CommitLog.beside(dir, store);
            assertTrue(mts.get().isPresent());
            assertArrayEquals(
                    expectedHash,
                    mts.get().get(),
                    "meta-head pointer should match the committed hash");
            CommitLog.Entry latest = log.latest().orElseThrow();
            assertArrayEquals(expectedHash, latest.metaTreeHash());
            assertEquals(expectedInstant, latest.timestamp());
        }
    }
}
