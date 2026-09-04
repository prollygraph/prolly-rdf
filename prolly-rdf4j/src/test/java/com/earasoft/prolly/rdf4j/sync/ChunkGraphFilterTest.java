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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.gc.ChunkSet;
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

/** Phase 0 Step 2 of plans/auth-graph-syncpack-filter.md. */
class ChunkGraphFilterTest {

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

    @Test
    void empty_excluded_set_equals_chunk_reachability(@TempDir Path dir) throws IOException {
        // The class javadoc + plan's D-3 promise: empty excluded set is
        // equivalent to ChunkReachability.from(...). Pins this so a
        // future refactor that adds extra work for "trivial" callers
        // is caught.
        ProllySail sail = initedSail(dir);
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
        }
        NodeStore store = sail.store();
        byte[] head = sail.currentCommitHash();
        Set<String> full = ChunkReachability.from(store, head, ChunkSet.EMPTY).toHexSet();
        Set<String> filtered =
                ChunkGraphFilter.chunksReachableExcludingGraphs(store, head, Set.of()).toHexSet();
        assertEquals(full, filtered, "empty excluded-graph set MUST equal the full reachability");
    }

    @Test
    void null_excluded_set_treated_as_empty(@TempDir Path dir) throws IOException {
        // Defensive: null and empty MUST be equivalent — both = full set.
        ProllySail sail = initedSail(dir);
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
        }
        Set<String> full =
                ChunkReachability.from(sail.store(), sail.currentCommitHash(), ChunkSet.EMPTY)
                        .toHexSet();
        Set<String> filtered =
                ChunkGraphFilter.chunksReachableExcludingGraphs(
                                sail.store(), sail.currentCommitHash(), null)
                        .toHexSet();
        assertEquals(full, filtered, "null excluded-set MUST behave as empty");
    }

    @Test
    void unknown_excluded_termids_drop_nothing(@TempDir Path dir) throws IOException {
        // TermIds that exist in NO row of the CSPO leaf can't match any
        // context column → the filter has no leaves to drop → result is
        // the full reachability. This is the "subscriber gave us a graph
        // IRI the dict never minted" case.
        ProllySail sail = initedSail(dir);
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
        }
        Set<String> full =
                ChunkReachability.from(sail.store(), sail.currentCommitHash(), ChunkSet.EMPTY)
                        .toHexSet();
        Set<String> filtered =
                ChunkGraphFilter.chunksReachableExcludingGraphs(
                                sail.store(),
                                sail.currentCommitHash(),
                                Set.of(0xDEADBEEFL, 0xCAFEBABEL))
                        .toHexSet();
        assertEquals(full, filtered, "TermIds matching no rows MUST drop nothing");
    }

    @Test
    void missing_commit_yields_empty_set(@TempDir Path dir) {
        // A commit hash absent from the store: the filter must
        // return an empty result, not crash. Same defensive contract
        // as ChunkReachability.from for an absent commit.
        ProllySail sail = initedSail(dir);
        byte[] bogusCommit = new byte[20]; // all zeros — definitely not in store
        Set<String> filtered =
                ChunkGraphFilter.chunksReachableExcludingGraphs(
                                sail.store(), bogusCommit, Set.of(1L, 2L))
                        .toHexSet();
        assertTrue(filtered.isEmpty(), "absent commit yields empty set");
    }
}
