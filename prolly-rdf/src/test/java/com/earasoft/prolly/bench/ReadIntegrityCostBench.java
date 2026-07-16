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
package com.earasoft.prolly.bench;

import com.dolthub.prolly.NodeCache;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.IntegrityVerifyingNodeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

/**
 * Step 3c of {@code core-read-integrity-default.md}: re-measure read-integrity verification on the
 * <i>shipped</i> design (ADR-0064 Option B — {@link RocksNodeStore#setVerifyOnRead}, re-hash
 * <i>below</i> the {@link NodeCache}) and confirm the warm tax the <i>rejected</i> design carried
 * is gone.
 *
 * <p><b>What changed since Step 2.</b> Step 2 measured Option A — the {@link
 * IntegrityVerifyingNodeStore} decorator <i>above</i> the store, which re-hashes on <i>every</i>
 * read including cache hits (+25.7x on the warm path; that number is what made Option A untenable).
 * Option B moves the re-hash inside {@code RocksNodeStore.read}, onto the cache-<i>miss</i> branch,
 * so a cache hit returns before the hash ever runs. This bench measures both, in one run, over a
 * single shared baseline, so the contrast is apples-to-apples on this machine.
 *
 * <p><b>Design (measure-the-real-thing).</b> The variable is the re-hash. The regime where Option
 * B's short-circuit can act is a <b>cache hit</b>, so the warm arms run against a {@link
 * RocksNodeStore} with a {@link NodeCache} sized far larger than the working set, primed so every
 * measured read is a hit ({@code hits()/misses()} is printed to prove it, not assume it). All three
 * warm arms share that one primed baseline, so the lock + Caffeine-get cost cancels in the verify
 * delta:
 *
 * <ul>
 *   <li><b>warm raw</b> — {@code verifyOnRead=false}: the bare cache-hit read (the baseline).
 *   <li><b>warm Option A (rejected)</b> — the decorator over the same cache-hit read: re-hashes
 *       every hit. Its delta over raw is the warm tax we did <i>not</i> ship.
 *   <li><b>warm Option B (shipped)</b> — {@code verifyOnRead=true}: the cache hit returns before
 *       the verify branch, so the delta over raw should be ~0 — the tax is gone.
 * </ul>
 *
 * <p>The <b>cold</b> arm is a separate {@code RocksNodeStore} with no cache, so every read is a
 * RocksDB {@code Get} (block-cache-warm here) that misses the node cache and pays the hash — the
 * documented accepted cost (ADR-0064): verification fires once per first-read. The cold delta and
 * the Option-A warm delta are the <i>same</i> SHA-512 over the same node, so they should agree — an
 * internal consistency check.
 *
 * <p>Not a JMH benchmark — a careful microbench (JIT warmup + a result sink + steady-state timing)
 * sufficient for the order-of-magnitude claim "the ~25x warm tax is gone." Run: {@code
 * scripts/run-bench.sh com.earasoft.prolly.bench.ReadIntegrityCostBench}.
 */
public final class ReadIntegrityCostBench {

    private static final int N = 2048; // distinct nodes
    private static final int NODE_BYTES =
            4096; // representative node; SHA-512 cost scales ~linearly
    private static final long CACHE_BYTES =
            128L * 1024 * 1024; // >> N*NODE_BYTES (8 MiB) → all hits
    private static final int WARMUP_PASSES = 6;
    private static final int MEASURE_PASSES = 30;

    /** Static sink: every read's first byte folds in here so the JIT cannot elide the read. */
    private static long sink;

    public static void main(String[] args) throws Exception {
        byte[][] payloads = new byte[N][];
        Random rng = new Random(42);
        for (int i = 0; i < N; i++) {
            payloads[i] = new byte[NODE_BYTES];
            rng.nextBytes(payloads[i]);
        }

        Path warmDir = Files.createTempDirectory("read-integrity-warm-");
        Path coldDir = Files.createTempDirectory("read-integrity-cold-");
        try (RocksNodeStore warm = new RocksNodeStore(warmDir.toString());
                RocksNodeStore cold = new RocksNodeStore(coldDir.toString())) {

            // Warm store: a NodeCache far larger than the working set → every read after priming
            // is a cache hit (the regime where Option B's short-circuit acts).
            NodeCache cache = new NodeCache(CACHE_BYTES);
            warm.setNodeCache(cache);
            byte[][] keys = new byte[N][];
            for (int i = 0; i < N; i++) keys[i] = warm.write(payloads[i]);

            // Cold store: no cache → every read is a RocksDB Get that pays the hash when on.
            byte[][] coldKeys = new byte[N][];
            for (int i = 0; i < N; i++) coldKeys[i] = cold.write(payloads[i]);

            System.out.printf(
                    "ReadIntegrityCostBench — N=%d nodes x %d bytes, cache=%d MiB (working set %d MiB),"
                            + " %d warmup + %d measure passes%n%n",
                    N,
                    NODE_BYTES,
                    CACHE_BYTES >> 20,
                    ((long) N * NODE_BYTES) >> 20,
                    WARMUP_PASSES,
                    MEASURE_PASSES);

            // Global warmup of the SHARED cache-hit path BEFORE any timed arm, so the
            // first-measured arm (warm raw) is at the same C2 steady state as the third (Option B).
            // Without this, arm ordering inflates whichever arm runs first — a
            // hand-rolled-microbench
            // artifact that showed up as Option B looking "faster than raw" (it cannot be: on a
            // hit,
            // verifyOnRead is unreachable). 40 passes x N reads drives the path fully hot.
            warm.setVerifyOnRead(false);
            for (int g = 0; g < 40; g++) readAll(warm, keys);

            // --- warm / cache-hit: one shared primed baseline, three arms ---
            warm.setVerifyOnRead(false);
            double wRaw = benchWarm("warm  raw          (cache hit)        ", warm, keys, cache);
            double wOptA =
                    benchWarm(
                            "warm  Option A     (hit + decorator hash)",
                            new IntegrityVerifyingNodeStore(warm),
                            keys,
                            cache);
            warm.setVerifyOnRead(true);
            double wOptB = benchWarm("warm  Option B     (shipped, on)      ", warm, keys, cache);

            // --- cold / cache-miss: each read a RocksDB Get ---
            cold.setVerifyOnRead(false);
            double cRaw = bench("cold  raw          (RocksDB Get)       ", cold, coldKeys);
            cold.setVerifyOnRead(true);
            double cVer = bench("cold  verify       (Get + re-hash)     ", cold, coldKeys);

            // Lead with ABSOLUTE taxes — the robust, reproducible number. The warm *ratio* is
            // denominator-sensitive: warm raw is at the noise floor (tens-to-hundreds of ns), so a
            // tiny absolute jitter swings the ratio wildly (it is NOT a stable figure of merit).
            System.out.printf(
                    "%n--- warm / cache-hit (shared primed RocksNodeStore + NodeCache) ---%n");
            System.out.printf(
                    "Option A (rejected) tax: +%.0f ns/read  <- decorator re-hashes EVERY hit (untenable)%n",
                    wOptA - wRaw);
            System.out.printf(
                    "Option B (shipped)  tax: %+.0f ns/read  <- ~0 at the noise floor: raw and on run%n"
                            + "                                       byte-identical code on a hit"
                            + " (verifyOnRead unreachable)%n",
                    wOptB - wRaw);
            System.out.printf(
                    "%n--- cold / cache-miss (no NodeCache; each read a RocksDB Get) ---%n");
            System.out.printf(
                    "verify-on tax: +%.0f ns/read  (%.2fx)  <- the accepted per-first-read cost%n",
                    cVer - cRaw, cVer / cRaw);
            System.out.printf("%n--- the redesign in one line ---%n");
            System.out.printf(
                    "warm hot-path tax  Option A +%.0f ns  ->  Option B ~0 ns   (the full SHA-512 is GONE"
                            + " from cache hits)%n",
                    wOptA - wRaw);
            System.out.printf(
                    "the SHA-512 re-hash (~%.0f ns warm-decorator ~ %.0f ns cold, same %d-byte node)"
                            + " now fires ONLY on a cache miss%n",
                    wOptA - wRaw, cVer - cRaw, NODE_BYTES);
            System.out.printf("%n(sink=%d — ignore; defeats dead-code elimination)%n", sink);
        } finally {
            deleteRecursively(warmDir);
            deleteRecursively(coldDir);
        }
    }

    /** Warm-arm bench that also proves the measured window was all cache hits. */
    private static double benchWarm(String label, NodeStore store, byte[][] keys, NodeCache cache) {
        for (int w = 0; w < WARMUP_PASSES; w++) readAll(store, keys);
        long missesBefore = cache.misses();
        long t0 = System.nanoTime();
        for (int m = 0; m < MEASURE_PASSES; m++) readAll(store, keys);
        long elapsed = System.nanoTime() - t0;
        long missesAdded = cache.misses() - missesBefore;
        double nsPerRead = (double) elapsed / (MEASURE_PASSES * (long) keys.length);
        System.out.printf(
                "%s  %7.0f ns/read   (misses added: %d — regime check: must be 0)%n",
                label, nsPerRead, missesAdded);
        return nsPerRead;
    }

    private static double bench(String label, NodeStore store, byte[][] keys) {
        for (int w = 0; w < WARMUP_PASSES; w++) readAll(store, keys);
        long t0 = System.nanoTime();
        for (int m = 0; m < MEASURE_PASSES; m++) readAll(store, keys);
        long elapsed = System.nanoTime() - t0;
        double nsPerRead = (double) elapsed / (MEASURE_PASSES * (long) keys.length);
        System.out.printf("%s  %7.0f ns/read%n", label, nsPerRead);
        return nsPerRead;
    }

    private static void readAll(NodeStore store, byte[][] keys) {
        long s = 0;
        for (byte[] k : keys) {
            MemorySegment seg = store.read(k).orElseThrow();
            s += seg.get(ValueLayout.JAVA_BYTE, 0L); // touch the bytes → the read cannot be elided
        }
        sink += s;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                    // best-effort temp cleanup
                                }
                            });
        }
    }

    private ReadIntegrityCostBench() {}
}
