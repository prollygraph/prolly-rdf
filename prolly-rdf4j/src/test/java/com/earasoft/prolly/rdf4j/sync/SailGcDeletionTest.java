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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.GcResult;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GC's DELETION pass, asserted at the store rather than through the collector's own arithmetic
 * (roadmap T15).
 *
 * <p>{@code SailGcReachabilityTest} already proves a specific orphan stops reading and that {@code
 * GcResult.sweptChunks()} is at least one. Neither of those catches the failure this class exists
 * for: a sweep that reports plausible numbers while leaving the bytes on disk, or one that deletes
 * MORE than it claimed. Both look identical from inside the collector — the reported count comes
 * from the same loop that does the deleting, so it cannot disagree with itself. The only
 * independent check is to count the store before and after and see whether the difference matches
 * what was reported.
 *
 * <p>Counting is hand-rolled because there is no store-wide chunk-count API: {@code NodeStore}
 * exposes only read/write, {@code RocksNodeStore} exposes only {@code db()}, and {@code
 * totalSstBytes()} is not a valid proxy — SST bytes do not shrink on delete until compaction runs,
 * so a test asserting on it would fail against a correct sweep. The 20-byte key filter mirrors the
 * collector's own eligibility rule, which is what keeps manifest rows and format markers out of the
 * count.
 *
 * <p>Owned-mode store only, deliberately: the sweep iterates and deletes on the default column
 * family while {@code RocksNodeStore.read}/{@code write} use the instance's {@code cf}. Those
 * coincide only when the store owns its database. A shared-CF store would have the sweep scanning
 * the wrong family — parked as a finding rather than exercised here.
 */
class SailGcDeletionTest {

    /**
     * Chunks currently in the store: keys of exactly 20 bytes, the collector's own eligibility
     * rule. Mirrors {@code GarbageCollector.sweep}'s iteration so the two agree about what counts.
     */
    private static int chunkCount(RocksNodeStore store) {
        int n = 0;
        try (org.rocksdb.RocksIterator it = store.db().newIterator()) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                if (it.key().length == 20) {
                    n++;
                }
            }
        }
        return n;
    }

    /** A sail over an owned Rocks store, with {@code commits} single-triple commits on it. */
    private static ProllySail sailWithHistory(Path dir, RocksNodeStore store, int commits) {
        ProllySail sail =
                new ProllySail(
                        store,
                        new com.dolthub.prolly.HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        for (int i = 0; i < commits; i++) {
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
        return sail;
    }

    /**
     * The load-bearing assertion: the store's chunk count must fall by EXACTLY what the collector
     * said it swept. A sweep that reported without deleting leaves the count unchanged; one that
     * over-deleted drops it further — and the second is the dangerous direction, because the
     * missing chunks are reachable data nobody notices until a read fails much later.
     */
    @Test
    void theStoreShrinksByExactlyTheNumberOfChunksTheCollectorReportedSweeping(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString())) {
            ProllySail sail = sailWithHistory(dir, store, 6);

            // Plant several orphans, so the arithmetic has something to be wrong about — one
            // orphan cannot distinguish "swept exactly the garbage" from "swept whatever".
            List<byte[]> orphans = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                orphans.add(
                        store.write(
                                MemorySegment.ofArray(
                                        ("orphan-" + i).getBytes(StandardCharsets.UTF_8))));
            }

            int before = chunkCount(store);
            GcResult result = SailGarbageCollection.collect(sail);
            int after = chunkCount(store);

            assertTrue(
                    result.sweptChunks() >= orphans.size(),
                    "every planted orphan must be swept; planted "
                            + orphans.size()
                            + ", collector reported "
                            + result.sweptChunks());
            assertEquals(
                    before - result.sweptChunks(),
                    after,
                    "the store must physically shrink by exactly the reported sweep count — "
                            + "before="
                            + before
                            + " after="
                            + after
                            + " reported="
                            + result.sweptChunks()
                            + ". An equal count means the sweep reported "
                            + "deletions it never performed; a smaller one means it deleted more "
                            + "than it claimed.");
            assertTrue(after < before, "a collection with real garbage must shrink the store");

            for (byte[] orphan : orphans) {
                assertFalse(store.read(orphan).isPresent(), "a planted orphan survived the sweep");
            }
        }
    }

    /**
     * The other half, and the one that matters more: everything reachable must still be there. A
     * sweep is only trustworthy if the count that fell was entirely garbage, so this reads the
     * whole history back after collection and re-collects to prove the store has reached a fixed
     * point — a second pass with nothing left to sweep.
     */
    @Test
    void reachableContentSurvivesAndASecondCollectionFindsNothingLeft(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString())) {
            ProllySail sail = sailWithHistory(dir, store, 6);
            store.write(MemorySegment.ofArray("garbage".getBytes(StandardCharsets.UTF_8)));

            SailGarbageCollection.collect(sail);

            SailRepository repo = new SailRepository(sail);
            try (RepositoryConnection conn = repo.getConnection()) {
                assertEquals(6, conn.size(), "every committed statement must survive collection");
                for (int i = 0; i < 6; i++) {
                    ValueFactory vf = conn.getValueFactory();
                    assertTrue(
                            conn.hasStatement(
                                    vf.createIRI("urn:s" + i),
                                    vf.createIRI("urn:p"),
                                    vf.createIRI("urn:o" + i),
                                    false),
                            "statement " + i + " was collected as garbage");
                }
            }

            // Fixed point: with the garbage gone, a second pass has nothing to take. If this
            // sweeps anything, the first pass left reachable-but-unclaimed chunks behind and the
            // mark phase is unstable — the shape that eventually eats live data.
            int beforeSecond = chunkCount(store);
            GcResult second = SailGarbageCollection.collect(sail);
            assertEquals(
                    0,
                    second.sweptChunks(),
                    "a second collection over an already-collected store must sweep nothing, "
                            + "swept "
                            + second.sweptChunks());
            assertEquals(beforeSecond, chunkCount(store), "and must not change the store");
        }
    }

    /**
     * GC versus a concurrent writer. The seam available at the sail layer is the reachability
     * contributor, not the collector's internal mark/sweep gap — {@code collectExclusive}, which
     * {@code ProllySail.collectGarbage} uses, never fires {@code betweenMarkAndSweep}, and that
     * field is package-private in the engine's package anyway. Parking the GC inside the
     * contributor holds the sail's write lock, so what this pins is the property that actually
     * matters operationally: <b>a commit racing a collection is never lost and never collected</b>
     * — it queues behind and lands intact.
     */
    @Test
    void aCommitRacingACollectionIsNeitherLostNorCollected(@TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.resolve("db").toString())) {
            ProllySail sail = sailWithHistory(dir, store, 3);
            SailRepository repo = new SailRepository(sail);

            CountDownLatch gcIsHoldingTheLock = new CountDownLatch(1);
            CountDownLatch releaseGc = new CountDownLatch(1);
            AtomicReference<Throwable> gcFailure = new AtomicReference<>();
            // The SAIL's own commit log, not a fresh CommitLog.beside(dir): a file-backed log
            // needs its NodeStore attached to reconstruct commit content from chunks (ADR-0073),
            // and only the sail's instance has been through that wiring.
            CommitLog log =
                    sail.commitLog()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "the sail must have a commit log for GC"));

            Thread gcThread =
                    new Thread(
                            () -> {
                                try {
                                    sail.collectGarbage(
                                            s -> {
                                                gcIsHoldingTheLock.countDown();
                                                try {
                                                    releaseGc.await();
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                                return new SailGcReachability(log).reachable(s);
                                            });
                                } catch (Throwable t) {
                                    gcFailure.set(t);
                                }
                            },
                            "gc");
            gcThread.start();
            assertTrue(
                    gcIsHoldingTheLock.await(30, java.util.concurrent.TimeUnit.SECONDS),
                    "the collection must reach its contributor");

            AtomicReference<Throwable> writerFailure = new AtomicReference<>();
            Thread writer =
                    new Thread(
                            () -> {
                                try (RepositoryConnection conn = repo.getConnection()) {
                                    conn.begin();
                                    ValueFactory vf = conn.getValueFactory();
                                    conn.add(
                                            vf.createIRI("urn:raced"),
                                            vf.createIRI("urn:p"),
                                            vf.createIRI("urn:during-gc"));
                                    conn.commit();
                                } catch (Throwable t) {
                                    writerFailure.set(t);
                                }
                            },
                            "writer");
            writer.start();

            // Give the writer a chance to reach the lock and queue behind the collection.
            Thread.sleep(200);
            releaseGc.countDown();

            gcThread.join(60_000);
            writer.join(60_000);
            assertTrue(gcFailure.get() == null, "the collection failed: " + gcFailure.get());
            assertTrue(
                    writerFailure.get() == null,
                    "the racing writer failed: " + writerFailure.get());

            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = conn.getValueFactory();
                assertTrue(
                        conn.hasStatement(
                                vf.createIRI("urn:raced"),
                                vf.createIRI("urn:p"),
                                vf.createIRI("urn:during-gc"),
                                false),
                        "the commit that raced the collection must survive it — it was written "
                                + "after the mark phase computed its reachable set, which is "
                                + "exactly the window where a sweep could eat it");
                assertEquals(4, conn.size(), "the original 3 statements plus the raced one");
            }
        }
    }
}
