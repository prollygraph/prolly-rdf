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
package com.earasoft.prolly.rdf4j.concurrency;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-support kit for concurrency / lock tests (plan 11, Phase B).
 *
 * <p>{@link #runConcurrent} barrier-starts a pool of threads so they contend simultaneously, runs a
 * job on each, and fails — <em>with a full thread dump</em> — if any job throws or the batch
 * overruns its deadline. The thread dump is the point: a CI hang must be diagnosable from the log,
 * not a silent timeout (the residual {@code SailConcurrencyTest} hang in Phase A needed a manual
 * {@code jstack}).
 */
public final class ConcurrencyHarness {

    private ConcurrencyHarness() {}

    /** A job that may throw; receives its 0-based thread index. */
    @FunctionalInterface
    public interface Job {
        void run(int threadIndex) throws Exception;
    }

    /**
     * Run {@code job} once on each of {@code threads} threads, all released together from a common
     * barrier. Rethrows the first job failure (the rest attached as suppressed); on timeout dumps
     * every live thread's stack and fails.
     */
    public static void runConcurrent(int threads, Duration timeout, Job job) {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(
                        pool.submit(
                                () -> {
                                    try {
                                        start.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
                                        job.run(idx);
                                    } catch (BrokenBarrierException ignored) {
                                        // A peer failed before reaching the barrier; that
                                        // peer's throwable is the real failure — stay quiet.
                                    } catch (Throwable t) {
                                        failures.add(t);
                                    }
                                }));
            }
            pool.shutdown();
            if (!pool.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                String dump = threadDump();
                pool.shutdownNow();
                throw new AssertionError(
                        "concurrency batch of "
                                + threads
                                + " threads did not finish within "
                                + timeout
                                + " — likely a deadlock or leaked lock.\n"
                                + dump);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency harness interrupted", e);
        } finally {
            pool.shutdownNow();
        }
        if (!failures.isEmpty()) {
            AssertionError e =
                    new AssertionError(
                            failures.size()
                                    + " concurrent job(s) failed; first: "
                                    + failures.get(0));
            for (Throwable t : failures) {
                e.addSuppressed(t);
            }
            throw e;
        }
    }

    /** Every live thread's name, state, and stack — wired into timeout failures. */
    public static String threadDump() {
        StringBuilder sb = new StringBuilder("==== THREAD DUMP ====\n");
        Thread.getAllStackTraces()
                .forEach(
                        (thread, stack) -> {
                            sb.append('"')
                                    .append(thread.getName())
                                    .append("\" ")
                                    .append(thread.getState())
                                    .append('\n');
                            for (StackTraceElement f : stack) {
                                sb.append("\tat ").append(f).append('\n');
                            }
                            sb.append('\n');
                        });
        return sb.toString();
    }

    /**
     * Mutual-exclusion probe. A party calls {@link #enter()} on entering the critical section and
     * {@link #exit()} on leaving; {@code enter()} throws the instant it observes more than one
     * party inside, so a serialization bug fails the test directly rather than corrupting data
     * silently.
     */
    public static final class MutexProbe {
        private final AtomicInteger inside = new AtomicInteger();
        private final AtomicInteger maxObserved = new AtomicInteger();

        public void enter() {
            int n = inside.incrementAndGet();
            maxObserved.accumulateAndGet(n, Math::max);
            if (n > 1) {
                inside.decrementAndGet();
                throw new IllegalStateException(
                        "mutual exclusion violated: " + n + " parties in the critical section");
            }
        }

        public void exit() {
            inside.decrementAndGet();
        }

        /** Largest concurrency ever observed inside the critical section (must be 1). */
        public int maxObserved() {
            return maxObserved.get();
        }
    }
}
