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
package com.earasoft.prolly.rdf4j.bench;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Cooperative JVM-shutdown coordination for a bench {@code main} that owns a RocksDB-backed store.
 *
 * <h3>The bug this exists to prevent</h3>
 *
 * <p>A plain bench {@code main} that closes its store only on <em>normal completion</em> (e.g.
 * {@code repo.shutDown()} after the ingest loop) crashes the JVM when it is killed mid-run. The
 * benches run under {@code timeout}, which sends {@code SIGTERM}; a {@code finally} / post-loop
 * {@code shutDown()} <b>does not run on SIGTERM</b>, so RocksDB's native background-compaction
 * threads are still running when the process tears down → {@code SIGSEGV} in {@code
 * rocksdb::BlockBasedTable::Open} on a {@code CompactionJob::Run} background thread. This is the
 * exact crash the web-Google bulk-load verification hit; it is <b>our wrong JNI-lifecycle
 * pattern</b> (the process must close RocksDB before it exits), not a RocksDB defect. Reproduced +
 * signature-matched 2026-06-14: a control {@code main} with no hook crashed 3/3 under {@code
 * timeout --signal=TERM}; with this cooperative hook, 0/4 crashed and {@code close()} drained in
 * 1–2 ms. (Production is unaffected — Spring Boot's default shutdown hook already closes the sails
 * via {@code destroyMethod}; only the plain-{@code main} benches lacked it. See {@code
 * prolly-storage/plans/rocksdb-graceful-shutdown.md}.)
 *
 * <h3>Why <em>cooperative</em>, not "hook closes the store"</h3>
 *
 * <p>The naïve fix — a shutdown-hook thread that calls {@code store.close()} — trades the
 * compaction-teardown crash for a <b>write-vs-close</b> crash: the hook would free the DB while the
 * main thread is still inside a {@code put}/{@code commit} on another thread. So instead the hook
 * only <b>signals</b> ({@link #stopRequested()} flips true) and then <b>waits</b> ({@link
 * #complete}); the <b>writer thread stays the sole DB toucher</b> — it observes the flag, stops,
 * closes the store itself, then calls {@link #done()}. {@code db.close()} drains background
 * compactions (joins the native threads) before any DB-referenced handle is freed, so process
 * teardown finds no live compaction thread.
 *
 * @apiNote Construct once at the top of {@code main} (after opening the store). In the work loop,
 *     {@code if (shutdown.stopRequested()) break;}. After the store is closed (in a {@code
 *     finally}), call {@link #done()} exactly once. Thread-safe; {@link #done()} is idempotent via
 *     the latch.
 * @implNote <b>Collaborators:</b> a {@link Runtime#addShutdownHook(Thread) JVM shutdown hook} (set
 *     in the constructor) + a {@link CountDownLatch} the hook awaits. <b>Dependents:</b> the bench
 *     mains that own a RocksDB store ({@link GraphIngestBench}; adopt in sibling ingest benches).
 *     The {@code awaitSeconds} bound caps how long the hook blocks the JVM waiting for the writer
 *     to finish closing — generous enough for a final commit + drain, finite so a wedged writer
 *     cannot hang shutdown forever.
 */
final class BenchGracefulShutdown {
    private volatile boolean stop = false;
    private final CountDownLatch complete = new CountDownLatch(1);

    BenchGracefulShutdown(String hookName, long awaitSeconds) {
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    stop = true; // ask the writer to stop + close itself
                                    try {
                                        complete.await(awaitSeconds, TimeUnit.SECONDS);
                                    } catch (InterruptedException ignore) {
                                        Thread.currentThread().interrupt();
                                    }
                                },
                                hookName));
    }

    /** True once a JVM shutdown (e.g. SIGTERM) has begun — the work loop should break and close. */
    boolean stopRequested() {
        return stop;
    }

    /**
     * Signal that the writer has finished closing the store; releases the shutdown hook.
     * Idempotent.
     */
    void done() {
        complete.countDown();
    }
}
