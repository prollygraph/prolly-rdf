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

import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Early-warning benchmark for the {@link CommitLog#cache()} memoization (D-8 / Step 15 of
 * plans/commits-pagination.md). Not a CI gate — a regression instrument: if {@code warmRead} ever
 * drifts toward {@code coldRead}, the memoization has been undone.
 *
 * <p>Three probes over a 10k-entry file-backed log (the ADR-0073 thin-row + commit-chunk format):
 *
 * <ul>
 *   <li>{@code coldRead} — a FRESH CommitLog instance reads everything: 10k thin rows + 10k chunk
 *       fetches. The pre-cache per-request cost of {@code /sparql/commits}.
 *   <li>{@code warmRead} — the memoized view on a long-lived instance. Target: {@code < 1 ms} (in
 *       practice it is a volatile field read — nanoseconds).
 *   <li>{@code appendThenWarmRead} — one append (invalidates) + the rebuild. Bounds the worst-case
 *       first page load after a commit. Caveat: each invocation grows the log, so this probe's mean
 *       drifts upward across a run (~+2k entries over a default run); read it as an
 *       order-of-magnitude bound, not a precise scaling point.
 * </ul>
 *
 * <p>Run: {@code JmhRunner CommitLogCacheBench} (defaults: 3 forks — the fork is the unit of
 * replication; single-fork numbers are smoke, not claims).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(value = 3)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 4, time = 2)
public class CommitLogCacheBench {

    private static final int ENTRIES = 10_000;

    Path dir;
    NodeStore store;
    CommitLog warmLog;
    Instant appendClock;

    @Setup(Level.Trial)
    public void setUp() throws IOException {
        String benchTmp =
                System.getProperty("benchtmp", System.getProperty("user.dir") + "/target/benchtmp");
        Files.createDirectories(Path.of(benchTmp));
        dir = Files.createTempDirectory(Path.of(benchTmp), "commitlog-cache-bench");
        store = new InMemoryNodeStore();
        warmLog = CommitLog.beside(dir, store);
        byte[] tree = new byte[20];
        for (int i = 0; i < ENTRIES; i++) {
            tree[0] = (byte) i;
            tree[1] = (byte) (i >> 8);
            warmLog.append(Instant.ofEpochSecond(1_720_000_000L + i), tree);
        }
        appendClock = Instant.ofEpochSecond(1_720_000_000L + ENTRIES);
        warmLog.cache(); // prime the memo so warmRead measures the hit path
    }

    @Benchmark
    public int coldRead() throws IOException {
        // A fresh instance = the pre-cache world: full file read + 10k chunk fetches.
        return CommitLog.beside(dir, store).entries().size();
    }

    @Benchmark
    public int warmRead() throws IOException {
        return warmLog.cache().size();
    }

    @Benchmark
    public int appendThenWarmRead() throws IOException {
        appendClock = appendClock.plusSeconds(1);
        byte[] tree = new byte[20];
        tree[2] = 1;
        warmLog.append(appendClock, tree);
        return warmLog.cache().size();
    }
}
