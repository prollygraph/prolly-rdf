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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.EncoderMetrics;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Counter integrity for the codec-tier metrics seam under concurrency (roadmap T19).
 *
 * <p>The property is worth stating precisely, because the obvious reading of "EncoderMetrics
 * thread-safety" is not testable: {@link EncoderMetrics} is a single-method functional interface
 * with no state of its own, and a {@code Dictionary} is per-connection and explicitly not
 * thread-safe. What IS shared, and what this pins, is the RECORDER: one sail owns one {@code
 * MeterRegistry}, and every connection's Dictionary reports collision-chain counters into it
 * through {@link ProllySail#encoderMetrics()}. So the adapter is a genuine concurrent fan-in point
 * — many independent encoders, one counter — and a lost increment there is a silently wrong
 * operational number, the kind nobody discovers because a metric that reads low looks like good
 * news.
 *
 * <p>What the test can actually catch: an adapter that resolves or caches its counter unsafely. The
 * current implementation looks up by name on every call, which is safe; the failure this guards
 * against is someone "optimising" that into a non-thread-safe cache, or resolving the name
 * per-thread so the increments land in different counters.
 */
class EncoderMetricsConcurrencyTest {

    private static final int THREADS = 8;
    private static final int INCREMENTS_PER_THREAD = 5_000;
    private static final String COUNTER = "prolly.dict.collision.chain";

    /**
     * A sail with a REAL registry attached. The metric-less overloads default to an empty {@code
     * CompositeMeterRegistry}, which Micrometer treats as a no-op sink — deliberate (the sail is
     * metric-free unless an operator wires a registry), and a trap for this test: the first version
     * used one of those overloads and read 0.0 for every counter, which looks exactly like "the
     * recorder dropped everything" and is in fact "there was nowhere to record". The distinction is
     * pinned as its own test below.
     */
    private static ProllySail sail(Path dir) {
        return new ProllySail(
                new InMemoryNodeStore(),
                new HeapBufferPool(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                RootMetaTreeStore.beside(dir),
                CommitLog.beside(dir),
                RefsStore.beside(dir),
                false);
    }

    /**
     * The fan-in: every thread reports through the SAME adapter instance, as separate connections'
     * dictionaries do. The total must be exact — not approximately right, exact, because these are
     * counters and an off-by-anything means increments were dropped.
     */
    @Test
    void aSharedRecorderLosesNoIncrementsUnderConcurrentFanIn(@TempDir Path dir) throws Exception {
        ProllySail sail = sail(dir);
        EncoderMetrics metrics = sail.encoderMetrics();
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int n = 0; n < INCREMENTS_PER_THREAD; n++) {
                                        metrics.increment(COUNTER);
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }

        assertTrue(failures.isEmpty(), "no reporter thread may fail: " + failures);
        for (Thread t : threads) {
            assertEquals(Thread.State.TERMINATED, t.getState(), "no wedge");
        }
        double total = sail.meterRegistry().counter(COUNTER).count();
        assertEquals(
                (double) THREADS * INCREMENTS_PER_THREAD,
                total,
                0.0,
                "every increment must be counted exactly once — "
                        + THREADS
                        + " threads x "
                        + INCREMENTS_PER_THREAD
                        + " increments, registry reports "
                        + total
                        + ". A shortfall means the shared recorder dropped increments under "
                        + "concurrency; a metric that reads low is the worst kind of wrong, "
                        + "because it looks like good news.");
    }

    /**
     * Distinct counter names must stay distinct under concurrency. The failure this catches is an
     * adapter that resolves the name once and reuses it, which would silently merge unrelated
     * counters — and the totals would still look plausible in aggregate.
     */
    @Test
    void concurrentIncrementsOfDifferentNamesDoNotBleedIntoEachOther(@TempDir Path dir)
            throws Exception {
        ProllySail sail = sail(dir);
        EncoderMetrics metrics = sail.encoderMetrics();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final String name = "prolly.codec.counter." + i; // one distinct name per thread
            final int reps = 100 * (i + 1); // and a distinct expected total
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int n = 0; n < reps; n++) {
                                        metrics.increment(name);
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }

        for (int i = 0; i < THREADS; i++) {
            double expected = 100 * (i + 1);
            double actual = sail.meterRegistry().counter("prolly.codec.counter." + i).count();
            assertEquals(
                    expected,
                    actual,
                    0.0,
                    "counter "
                            + i
                            + " must hold exactly its own increments — expected "
                            + expected
                            + " got "
                            + actual
                            + ". Bleeding between names means the adapter "
                            + "resolved the counter once instead of per call.");
        }
    }

    /**
     * The metric-less default is a no-op SINK, not a broken recorder — a distinction that cost me a
     * wrong diagnosis while writing this class. A sail built through the overloads that take no
     * registry gets an empty {@code CompositeMeterRegistry}, and Micrometer discards everything
     * written to one. Pinned so the next reader of a 0.0 counter checks which constructor was used
     * before concluding increments were lost.
     */
    @Test
    void aSailWithNoRegistryDiscardsCountersByDesignRatherThanLosingThem(@TempDir Path dir) {
        ProllySail metricLess =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        EncoderMetrics metrics = metricLess.encoderMetrics();
        for (int i = 0; i < 100; i++) {
            metrics.increment(COUNTER);
        }
        assertEquals(
                0.0,
                metricLess.meterRegistry().counter(COUNTER).count(),
                0.0,
                "an empty CompositeMeterRegistry is a deliberate no-op sink — a zero here means "
                        + "'no registry was attached', NOT 'the recorder dropped increments'");
    }

    /**
     * The production default must remain free: a no-op recorder counts nothing, and never throws.
     */
    @Test
    void theNoopRecorderIsSafeToShareAndRecordsNothing() throws Exception {
        EncoderMetrics noop = EncoderMetrics.noop();
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int n = 0; n < INCREMENTS_PER_THREAD; n++) {
                                        noop.increment(COUNTER);
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }
        assertTrue(
                failures.isEmpty(),
                "the default recorder is used on every encode path — it must never throw: "
                        + failures);
    }
}
