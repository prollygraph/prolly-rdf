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
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link ProllySail}'s provenance surface — {@code lookupProvenance} (the {@code
 * /sparql/provenance} query path) and {@code rebuildProvenance} (the F.7 backfill that walks
 * history from genesis).
 *
 * <p>Provenance is opt-in via the 7-arg constructor; the bulk of the Sail tests run with it off,
 * leaving these methods — and the {@code diffAndRecord} / {@code tripleSpocsAt} helpers they pull
 * in — untested. This file drives both the disabled-fast-path and the populated happy path.
 */
class ProllySailProvenanceTest {

    /** A provenance-enabled Sail with file-backed sidecars under {@code dir}. */
    private static ProllySail provenanceSail(Path dir) {
        return new ProllySail(
                new InMemoryNodeStore(),
                new HeapBufferPool(),
                RootMetaTreeStore.beside(dir),
                CommitLog.beside(dir),
                RefsStore.beside(dir),
                /* provenanceEnabled */ true);
    }

    private static void add(RepositoryConnection c, String s, String p, String o) {
        ValueFactory vf = c.getValueFactory();
        c.add(vf.createIRI("urn:t:" + s), vf.createIRI("urn:t:" + p), vf.createIRI("urn:t:" + o));
    }

    // ---- provenance disabled (fast path) -------------------------------

    @Test
    void lookupProvenance_is_empty_when_provenance_is_disabled() {
        ProllySail sail = new ProllySail(); // no-arg ctor → provenance off
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            Optional<ProllySail.ProvenanceLookup> got =
                    sail.lookupProvenance(
                            vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            assertTrue(got.isEmpty(), "a provenance-disabled Sail short-circuits lookup to empty");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void rebuildProvenance_throws_when_provenance_is_disabled() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, sail::rebuildProvenance);
            assertTrue(
                    ex.getMessage().toLowerCase().contains("provenance"),
                    "the failure must name provenance so the operator knows the fix");
        } finally {
            sail.shutDown();
        }
    }

    // ---- provenance enabled (populated path) ---------------------------

    @Test
    void lookupProvenance_finds_a_committed_triple(@TempDir Path dir) {
        ProllySail sail = provenanceSail(dir);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "alice", "knows", "bob");
                c.commit();
            }
            ValueFactory vf = sail.getValueFactory();
            Optional<ProllySail.ProvenanceLookup> got =
                    sail.lookupProvenance(
                            vf.createIRI("urn:t:alice"),
                            vf.createIRI("urn:t:knows"),
                            vf.createIRI("urn:t:bob"));
            assertTrue(got.isPresent(), "a committed triple carries a provenance record");
            assertNotNull(
                    got.get().firstSeenAt(),
                    "the lookup names the commit that introduced the triple");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void lookupProvenance_is_empty_for_a_never_stored_triple(@TempDir Path dir) {
        ProllySail sail = provenanceSail(dir);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "alice", "knows", "bob");
                c.commit();
            }
            ValueFactory vf = sail.getValueFactory();
            // "urn:t:nobody" was never committed → its term isn't in the
            // dictionary, so the lookup resolves to empty.
            Optional<ProllySail.ProvenanceLookup> got =
                    sail.lookupProvenance(
                            vf.createIRI("urn:t:nobody"),
                            vf.createIRI("urn:t:knows"),
                            vf.createIRI("urn:t:bob"));
            assertTrue(got.isEmpty(), "a triple with an unknown term has no provenance");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void rebuildProvenance_walks_history_and_records_entries(@TempDir Path dir) {
        ProllySail sail = provenanceSail(dir);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "b");
                c.commit();
            }
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "c");
                c.commit();
            }
            ProllySail.RebuildProvenanceResult result = sail.rebuildProvenance();
            assertTrue(
                    result.commitsProcessed() >= 2,
                    "the rebuild must walk every commit from genesis");
            assertTrue(
                    result.entriesAdded() >= 1,
                    "the rebuild must record the first-seen triples it diffs out");
            assertNotNull(
                    result.newCommit(),
                    "the rebuild appends an auditable commit and returns its hash");
            assertFalse(result.newCommit().isBlank());
        } finally {
            repo.shutDown();
        }
    }
}
