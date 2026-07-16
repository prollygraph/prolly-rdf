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
package com.earasoft.prolly.flatsail;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Step 13 — single-writer / concurrent-reader concurrency coverage.
 *
 * <p>The load-bearing test is {@link #concurrent_writers_keep_every_statement_queryable}: many
 * writers add statements under a <em>shared</em> predicate. Without the Sail's single-writer gate,
 * two writers interning that predicate at once would assign it two TermIds, and a predicate query
 * would then miss half the data. The gate makes interning race-free.
 */
class RocksDbFlatSailConcurrencyTest {
    static {
        RocksDB.loadLibrary();
    }

    private RocksDbFlatSail sail;
    private ValueFactory vf;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        sail = new RocksDbFlatSail(dir);
        sail.init();
        vf = sail.getValueFactory();
    }

    @AfterEach
    void tearDown() {
        if (sail != null) {
            sail.shutDown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread t : threads) {
            t.join();
        }
    }

    @Test
    void concurrent_writers_keep_every_statement_queryable() throws Exception {
        int writers = 6;
        int perWriter = 40;
        IRI sharedPredicate = vf.createIRI("urn:sharedPredicate");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writerId = w;
            Thread thread =
                    new Thread(
                            () -> {
                                await(start);
                                try (SailConnection conn = sail.getConnection()) {
                                    conn.begin();
                                    for (int i = 0; i < perWriter; i++) {
                                        conn.addStatement(
                                                vf.createIRI("urn:w" + writerId + "-s" + i),
                                                sharedPredicate,
                                                vf.createIRI("urn:w" + writerId + "-o" + i));
                                    }
                                    conn.commit();
                                } catch (Throwable e) {
                                    failure.compareAndSet(null, e);
                                }
                            });
            thread.start();
            threads.add(thread);
        }
        start.countDown(); // release all writers at once — maximize contention
        joinAll(threads);
        if (failure.get() != null) {
            fail("a concurrent writer failed", failure.get());
        }

        int expected = writers * perWriter;
        try (SailConnection conn = sail.getConnection()) {
            assertEquals((long) expected, conn.size(), "every committed statement must be present");
            // The shared predicate must have a single TermId so this query finds them all.
            try (CloseableIteration<? extends Statement> it =
                    conn.getStatements(null, sharedPredicate, null, false)) {
                int found = 0;
                while (it.hasNext()) {
                    it.next();
                    found++;
                }
                assertEquals(
                        expected,
                        found,
                        "the shared predicate must resolve to one TermId — no interning race");
            }
        }
    }

    @Test
    void concurrent_readers_all_see_the_full_dataset() throws Exception {
        int seeded = 50;
        try (SailConnection writer = sail.getConnection()) {
            writer.begin();
            for (int i = 0; i < seeded; i++) {
                writer.addStatement(
                        vf.createIRI("urn:s" + i),
                        vf.createIRI("urn:p"),
                        vf.createIRI("urn:o" + i));
            }
            writer.commit();
        }

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            Thread reader =
                    new Thread(
                            () -> {
                                await(start);
                                try (SailConnection conn = sail.getConnection()) {
                                    for (int rep = 0; rep < 25; rep++) {
                                        assertEquals((long) seeded, conn.size());
                                    }
                                } catch (Throwable e) {
                                    failure.compareAndSet(null, e);
                                }
                            });
            reader.start();
            readers.add(reader);
        }
        start.countDown();
        joinAll(readers);
        if (failure.get() != null) {
            fail("a concurrent reader failed", failure.get());
        }
    }

    @Test
    void readers_running_against_a_live_writer_stay_consistent() throws Exception {
        int batches = 20;
        int perBatch = 10;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean writing = new AtomicBoolean(true);

        Thread writer =
                new Thread(
                        () -> {
                            try {
                                for (int b = 0; b < batches; b++) {
                                    try (SailConnection conn = sail.getConnection()) {
                                        conn.begin();
                                        for (int i = 0; i < perBatch; i++) {
                                            conn.addStatement(
                                                    vf.createIRI("urn:b" + b + "-s" + i),
                                                    vf.createIRI("urn:p"),
                                                    vf.createIRI("urn:o" + i));
                                        }
                                        conn.commit();
                                    }
                                }
                            } catch (Throwable e) {
                                failure.compareAndSet(null, e);
                            } finally {
                                writing.set(false);
                            }
                        });

        List<Thread> readers = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            Thread reader =
                    new Thread(
                            () -> {
                                try {
                                    long previous = 0;
                                    while (writing.get()) {
                                        try (SailConnection conn = sail.getConnection()) {
                                            long size = conn.size();
                                            // Only commits happen, and each is atomic — a reader
                                            // must never see a count go backwards or a partial
                                            // batch.
                                            assertTrue(
                                                    size >= previous,
                                                    "size went backwards: "
                                                            + size
                                                            + " < "
                                                            + previous);
                                            assertEquals(
                                                    0,
                                                    size % perBatch,
                                                    "a partial batch was visible: " + size);
                                            previous = size;
                                        }
                                    }
                                } catch (Throwable e) {
                                    failure.compareAndSet(null, e);
                                }
                            });
            reader.start();
            readers.add(reader);
        }

        writer.start();
        writer.join();
        joinAll(readers);
        if (failure.get() != null) {
            fail("a concurrent read/write interaction failed", failure.get());
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals((long) batches * perBatch, conn.size());
        }
    }
}
