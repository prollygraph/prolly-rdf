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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.Database;
import com.earasoft.prolly.GarbageCollector;
import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The positive twin the ADR-0074 trust class demands of {@link SailGcReachability}: a REAL
 * Rocks-backed Sail store is garbage-collected with the contributor registered (Sail quiesced — the
 * ADR's offline-collection constraint), and every surface must still read: current statements,
 * every historical commit's full chunk closure, and the commit log itself — while genuinely
 * unreferenced chunks ARE swept (the collector still collects; the contributor is a claim, not an
 * off switch).
 */
class SailGcReachabilityTest {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void collectingASailStoreWithTheContributor_preservesEverySurface_andStillSweeps(
            @TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString())) {
            CommitLog log = CommitLog.beside(dir);
            ProllySail sail =
                    new ProllySail(
                            store,
                            new com.dolthub.prolly.HeapBufferPool(),
                            RootMetaTreeStore.beside(dir),
                            log,
                            RefsStore.beside(dir),
                            false);
            SailRepository repo = new SailRepository(sail);
            repo.init();

            // Real history: 8 commits, each adding a triple.
            for (int i = 0; i < 8; i++) {
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
            List<CommitLog.Entry> entries = log.entries();
            assertEquals(8, entries.size(), "fixture: eight commits in the log");

            // Genuinely dead bytes: an unreferenced chunk — must be SWEPT despite the contributor.
            byte[] junk =
                    store.write(
                            java.lang.foreign.MemorySegment.ofArray(
                                    "dead-bytes".getBytes(StandardCharsets.UTF_8)));
            assertTrue(store.read(junk).isPresent());

            // Collect: engine Database constructed over the shared store contributes no branches;
            // the Sail's liveness rides ENTIRELY on the contributor (the point of the test).
            Database engineDb = new Database(store, "gc-shared", DESC, pool);
            new GarbageCollector(engineDb, store, List.of(new SailGcReachability(log))).collect();

            // 1. The dead chunk went — the collector still collects on a shared store.
            assertFalse(store.read(junk).isPresent(), "unreferenced bytes must still be swept");

            // 2. Current reads survive.
            try (RepositoryConnection conn = repo.getConnection()) {
                assertEquals(8, conn.size(), "every statement must survive collection");
            }

            // 3. EVERY historical commit's closure is complete — ChunkReachability.from throws
            //    on any missing chunk, so this is the strongest whole-history assertion.
            for (CommitLog.Entry e : entries) {
                assertDoesNotThrow(
                        () -> ChunkReachability.from(store, e.metaTreeHash(), ChunkSet.EMPTY),
                        "commit " + e.message() + "'s full closure must survive collection");
                assertTrue(
                        store.read(e.id()).isPresent(),
                        "the commit-object chunk (ADR-0073) must survive collection");
            }

            repo.shutDown();
        }
    }

    @Test
    void sailGarbageCollection_frontDoor_sweepsOrphans_readsFlowDuring_writesQueueBehind(
            @TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString())) {
            CommitLog log = CommitLog.beside(dir);
            ProllySail sail =
                    new ProllySail(
                            store,
                            new com.dolthub.prolly.HeapBufferPool(),
                            RootMetaTreeStore.beside(dir),
                            log,
                            RefsStore.beside(dir),
                            false);
            SailRepository repo = new SailRepository(sail);
            repo.init();
            for (int i = 0; i < 4; i++) {
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
            byte[] junk =
                    store.write(
                            java.lang.foreign.MemorySegment.ofArray(
                                    "orphan".getBytes(StandardCharsets.UTF_8)));

            // A READER opened before the collection keeps working during/after it (readers touch
            // only claimed chunks — the D-1 narrowing this endpoint design rides on).
            try (RepositoryConnection reader = repo.getConnection()) {
                com.earasoft.prolly.GcResult result = SailGarbageCollection.collect(sail);
                assertTrue(result.sweptChunks() >= 1, "the orphan is swept");
                assertTrue(result.reachableChunks() > 0, "history is claimed");
                assertEquals(4, reader.size(), "reads flow during/after a collection");
            }
            assertFalse(store.read(junk).isPresent());

            // A WRITE issued after (i.e. queued behind) a collection completes normally — the
            // writer lock is released; the store is fully writable.
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                ValueFactory vf = conn.getValueFactory();
                conn.add(vf.createIRI("urn:after"), vf.createIRI("urn:p"), vf.createIRI("urn:gc"));
                conn.commit();
            }
            try (RepositoryConnection conn = repo.getConnection()) {
                assertEquals(5, conn.size(), "post-collection write landed");
            }
            repo.shutDown();
        }
    }
}
