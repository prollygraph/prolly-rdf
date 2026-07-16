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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.earasoft.prolly.rdf4j.concurrency.ConcurrencyHarness;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.junit.jupiter.api.Test;

/**
 * Concurrency contract for the {@code ProllySail} write lock (plan 11, Phases B/C). Covers the
 * single-writer guarantee (C1), thread-agnostic release (C4), and the two bugs fixed in Phase A:
 *
 * <ul>
 *   <li>the cross-thread lock leak — a {@code ReentrantLock} could not be released by a non-owning
 *       thread (e.g. {@code Sail.shutDown()});
 *   <li>the un-abortable blocked {@code begin} — an uninterruptible acquire defeated RDF4J's
 *       interrupt-to-abort connection-close protocol.
 * </ul>
 */
class ProllySailWriteLockTest {

    private static IRI iri(ValueFactory vf, String s) {
        return vf.createIRI("http://example.org/" + s);
    }

    @Test
    void writeLockFreeAfterCommit() {
        ProllySail sail = new ProllySail();
        sail.init();
        try (SailConnection c = sail.getConnection()) {
            ValueFactory vf = sail.getValueFactory();
            c.begin();
            assertEquals(0, sail.writeLockAvailablePermits(), "begin must hold the write lock");
            c.addStatement(iri(vf, "s"), iri(vf, "p"), iri(vf, "o"));
            c.commit();
            assertEquals(1, sail.writeLockAvailablePermits(), "commit must release the write lock");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void writeLockFreeAfterRollback() {
        ProllySail sail = new ProllySail();
        sail.init();
        try (SailConnection c = sail.getConnection()) {
            ValueFactory vf = sail.getValueFactory();
            c.begin();
            c.addStatement(iri(vf, "s"), iri(vf, "p"), iri(vf, "o"));
            c.rollback();
            assertEquals(
                    1, sail.writeLockAvailablePermits(), "rollback must release the write lock");
        } finally {
            sail.shutDown();
        }
    }

    /**
     * Regression — cross-thread lock leak. A connection's transaction is begun on one thread and
     * the connection closed from another. A {@code ReentrantLock} could not be unlocked by the
     * non-owning closer; the {@code Semaphore} can.
     */
    @Test
    void writeLockReleasedByCrossThreadClose() throws Exception {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            SailConnection conn = sail.getConnection();
            Thread beginner = new Thread(conn::begin, "beginner");
            beginner.start();
            beginner.join(5_000);
            assertFalse(beginner.isAlive(), "begin must not hang");
            assertEquals(0, sail.writeLockAvailablePermits(), "begin must hold the write lock");

            // Close from this thread — the begin ran on `beginner`.
            conn.close();
            assertEquals(
                    1,
                    sail.writeLockAvailablePermits(),
                    "a cross-thread close must release the write lock");

            // A fresh writer must now be able to acquire.
            try (SailConnection c2 = sail.getConnection()) {
                c2.begin();
                assertEquals(0, sail.writeLockAvailablePermits());
                c2.commit();
            }
            assertEquals(1, sail.writeLockAvailablePermits());
        } finally {
            sail.shutDown();
        }
    }

    /**
     * Regression — un-abortable blocked {@code begin}. A second writer parked on the write lock
     * must honour interruption (RDF4J aborts a blocked connection by interrupting its thread),
     * surfacing as a {@code SailException} rather than hanging forever.
     */
    @Test
    void blockedBeginIsInterruptible() throws Exception {
        ProllySail sail = new ProllySail();
        sail.init();
        SailConnection holder = sail.getConnection();
        try {
            holder.begin(); // holds the write lock
            assertEquals(0, sail.writeLockAvailablePermits());

            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread blocked =
                    new Thread(
                            () -> {
                                try (SailConnection c = sail.getConnection()) {
                                    c.begin(); // parks on acquireWriteLock
                                    c.commit();
                                } catch (Throwable t) {
                                    caught.set(t);
                                }
                            },
                            "blocked-writer");
            blocked.start();

            // Wait until it is genuinely parked on the lock.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (blocked.isAlive()
                    && blocked.getState() != Thread.State.WAITING
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(
                    Thread.State.WAITING,
                    blocked.getState(),
                    "the second begin should be parked on the write lock");

            blocked.interrupt();
            blocked.join(5_000);
            assertFalse(blocked.isAlive(), "an interrupted begin must abort, not hang");
            assertInstanceOf(
                    SailException.class,
                    caught.get(),
                    "an interrupted begin must surface as SailException");

            holder.rollback();
            assertEquals(
                    1,
                    sail.writeLockAvailablePermits(),
                    "write lock free once the holder releases it");
        } finally {
            holder.close();
            sail.shutDown();
        }
    }

    /**
     * The write lock must serialize transactions — at no instant are two connections inside the
     * begin→commit critical section (C1).
     */
    @Test
    void concurrentTransactionsAreSerialized() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = iri(vf, "p");
            IRI o = iri(vf, "o");
            ConcurrencyHarness.MutexProbe probe = new ConcurrencyHarness.MutexProbe();

            ConcurrencyHarness.runConcurrent(
                    8,
                    Duration.ofSeconds(30),
                    idx -> {
                        for (int r = 0; r < 25; r++) {
                            try (SailConnection c = sail.getConnection()) {
                                c.begin();
                                probe.enter();
                                c.addStatement(iri(vf, "s" + idx + "_" + r), p, o);
                                probe.exit();
                                c.commit();
                            }
                        }
                    });

            assertEquals(
                    1,
                    probe.maxObserved(),
                    "the write lock must never admit two writers to the critical section");
            assertEquals(
                    1,
                    sail.writeLockAvailablePermits(),
                    "write lock must be free after every transaction completes");
        } finally {
            sail.shutDown();
        }
    }

    /**
     * Release must be exactly-once. {@code commitInternal}/{@code rollbackInternal} release the
     * lock, and {@code closeInternal} releases it again defensively; the per-connection {@code
     * writeLockHeld} flag must stop the second call from raising the permit count above one (which
     * would let two writers run).
     */
    @Test
    void writeLockReleaseIsIdempotent() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            SailConnection c1 = sail.getConnection();
            c1.begin();
            c1.commit(); // releases
            c1.close(); // must NOT release again
            assertEquals(
                    1,
                    sail.writeLockAvailablePermits(),
                    "commit+close must leave exactly one permit");

            SailConnection c2 = sail.getConnection();
            c2.begin();
            c2.rollback(); // releases
            c2.close(); // must NOT release again
            assertEquals(
                    1,
                    sail.writeLockAvailablePermits(),
                    "rollback+close must leave exactly one permit");

            // Decisive: had any path double-released, the semaphore would hold
            // two permits and this begin would leave one rather than zero.
            try (SailConnection c3 = sail.getConnection()) {
                c3.begin();
                assertEquals(
                        0,
                        sail.writeLockAvailablePermits(),
                        "a single begin must take the lock to zero permits");
                c3.commit();
            }
        } finally {
            sail.shutDown();
        }
    }

    // Note: shutdown-while-a-transaction-is-in-flight is covered by RDF4J's
    // SailConcurrencyTest (wired as ProllySailConcurrencyTest, passing). A
    // focused test here is not meaningful — AbstractSail.shutDown() waits out
    // a grace period for connections whose owner has not closed them, so a
    // test that holds a connection open forever measures RDF4J's grace timer,
    // not the ProllySail write lock.

    /** A fair write lock serves queued writers in arrival (FIFO) order — no starvation. */
    @Test
    void writeLockIsFair() throws Exception {
        ProllySail sail = new ProllySail();
        sail.init();
        SailConnection holder = sail.getConnection();
        try {
            holder.begin(); // holds the lock so every waiter queues

            List<String> order = Collections.synchronizedList(new ArrayList<>());
            List<Thread> waiters = new ArrayList<>();
            for (String name : List.of("A", "B", "C", "D")) {
                Thread t =
                        new Thread(
                                () -> {
                                    try (SailConnection c = sail.getConnection()) {
                                        c.begin(); // queues on the write lock
                                        order.add(name);
                                        c.commit();
                                    } catch (RuntimeException e) {
                                        throw e;
                                    }
                                },
                                "waiter-" + name);
                waiters.add(t);
                t.start();
                // Park-gate: only start the next waiter once this one is queued,
                // so the AQS wait queue is unambiguously A,B,C,D.
                awaitParkedOnLock(t);
            }

            holder.commit(); // release — fair handoff begins

            for (Thread t : waiters) {
                t.join(10_000);
            }
            for (Thread t : waiters) {
                assertFalse(
                        t.isAlive(),
                        t.getName() + " did not finish\n" + ConcurrencyHarness.threadDump());
            }
            assertEquals(
                    List.of("A", "B", "C", "D"),
                    order,
                    "a fair write lock must serve queued writers in arrival order");
            assertEquals(1, sail.writeLockAvailablePermits());
        } finally {
            holder.close();
            sail.shutDown();
        }
    }

    /** Spin until {@code t} is parked (state WAITING) — i.e. enqueued on the write lock. */
    private static void awaitParkedOnLock(Thread t) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.isAlive()
                && t.getState() != Thread.State.WAITING
                && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(
                Thread.State.WAITING,
                t.getState(),
                t.getName() + " should be parked on the write lock");
    }
}
