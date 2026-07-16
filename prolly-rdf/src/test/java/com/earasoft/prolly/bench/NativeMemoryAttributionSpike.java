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

import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Step 0 of {@code core-resource-bounds-and-metrics.md} (the measurement-led spike): attribute
 * "native OOM under sustained load" to a mechanism <i>before</i> choosing a bound. The production
 * RDF4J write pool is {@code HeapBufferPool} (on-heap, {@code -Xmx}-bounded), so a <i>native</i>
 * OOM is not the prolly buffer pool — the one big native consumer is RocksDB. This spike measures
 * where the resident set actually goes under sustained writes, and whether each native term is
 * bounded.
 *
 * <p><b>The attribution rule</b> (from {@code WritePathMemoryProbe}): RSS climbing while post-GC
 * heap-live stays flat ⇒ the growth is <i>off-heap / native</i>, and {@link
 * RocksNodeStore#memStatsLine} then names which RocksDB consumer (table-readers / memtables / block
 * cache) is responsible. {@code estimate-table-readers-mem} is the prime suspect: with the default
 * {@code max_open_files=-1}, every sorted-string-table file's index + bloom stays resident, so it
 * grows with file count.
 *
 * <p><b>Design (measure-the-real-thing).</b> Two arms, one shared synthetic sustained-write
 * workload (no external corpus), each a fresh RocksDB on a temp dir:
 *
 * <ul>
 *   <li><b>ARM 1 — production default</b> ({@code prolly.rocksdb.block-cache.mb=0}): no app-level
 *       block cache, {@code max_open_files=-1}. Table-readers grow with the file count — the
 *       hypothesised native-OOM vector.
 *   <li><b>ARM 2 — bounded cache</b> ({@code prolly.rocksdb.block-cache.mb=N}): a bounded LRU with
 *       {@code cacheIndexAndFilterBlocks(true)}, so index/filter live <i>inside</i> the cap. The
 *       proposed fix direction; table-readers should be bounded.
 * </ul>
 *
 * <p><b>Instrument hygiene</b> (the harness must be cleaner than the system): one reused payload
 * buffer (mutated per node so each content-address is distinct) ⇒ the harness allocates ~nothing,
 * so heap stays flat and any RSS growth is attributable to RocksDB native; no {@code NodeCache} is
 * attached (it is an on-heap term that would only muddy the native attribution). Run: {@code
 * scripts/run-bench.sh com.earasoft.prolly.bench.NativeMemoryAttributionSpike} (tune {@code
 * -Dspike.nodes=…}, {@code -Dspike.node-bytes=…}, {@code -Dspike.cache-mb=…}; runs under the bench
 * launcher's heap).
 */
public final class NativeMemoryAttributionSpike {

    public static void main(String[] args) throws Exception {
        long nodeCount = Long.getLong("spike.nodes", 1_000_000L);
        int nodeBytes = Integer.getInteger("spike.node-bytes", 512);
        long sampleEvery = Long.getLong("spike.sample-every", 100_000L);
        int cacheMbArm2 = Integer.getInteger("spike.cache-mb", 64);

        System.out.printf(
                "NativeMemoryAttributionSpike — nodes=%,d x %dB (~%,dMiB raw), Xmx=%,dMiB%n",
                nodeCount,
                nodeBytes,
                (nodeCount * nodeBytes) >> 20,
                Runtime.getRuntime().maxMemory() >> 20);
        System.out.println(
                "attribution rule: RSS climbs + heap-live flat => native; memStatsLine names which"
                        + " RocksDB term.");

        System.setProperty("prolly.rocksdb.block-cache.mb", "0");
        runArm(
                "ARM 1: DEFAULT config (no app block cache, max_open_files=-1)",
                nodeCount,
                nodeBytes,
                sampleEvery);

        System.setProperty("prolly.rocksdb.block-cache.mb", Integer.toString(cacheMbArm2));
        runArm(
                "ARM 2: BOUNDED block cache=" + cacheMbArm2 + "MiB (index/filter in-cache)",
                nodeCount,
                nodeBytes,
                sampleEvery);

        System.out.printf(
                "%nread: compare FINAL rocksdb[tableReaders=…] between arms. ARM1 grows with file"
                        + " count (the native-OOM vector); ARM2 should bound it (moved into the"
                        + " capped cache).%n");
    }

    private static void runArm(String label, long nodeCount, int nodeBytes, long sampleEvery)
            throws Exception {
        Path dir = Files.createTempDirectory("native-attr-spike-");
        System.out.printf("%n===== %s =====%n", label);
        long armBaselineRss = rssBytes();
        try (RocksNodeStore rocks = new RocksNodeStore(dir.toString())) {
            byte[] buf = new byte[nodeBytes];
            for (int i = 0; i < nodeBytes; i++) buf[i] = (byte) (i * 31 + 7); // some entropy
            MemorySegment seg = MemorySegment.ofArray(buf); // one segment, reused

            System.gc(); // clean heap baseline before the loop
            System.out.printf(
                    "baseline: rss=%,dMiB heapLive=%,dMiB%n", rssBytes() >> 20, heapLive() >> 20);
            System.out.printf("%14s %8s %8s  %s%n", "nodes", "rssMiB", "heapMiB", "rocksdb-native");

            for (long n = 1; n <= nodeCount; n++) {
                long x =
                        n; // mutate the first 8 bytes → distinct content → distinct content-address
                for (int b = 0; b < 8; b++) {
                    buf[b] = (byte) (x & 0xFF);
                    x >>= 8;
                }
                rocks.write(seg);
                if (n % sampleEvery == 0) {
                    System.out.printf(
                            "%,14d %,8d %,8d  %s%n",
                            n, rssBytes() >> 20, heapUsed() >> 20, rocks.memStatsLine());
                }
            }

            // The native floor: force GC so heap-used collapses to heap-live; then RSS - heapLive
            // is
            // the off-heap/native resident (RocksDB + JVM baseline).
            System.gc();
            Thread.sleep(250);
            System.gc();
            long rss = rssBytes();
            long live = heapLive();
            System.out.printf(
                    "FINAL (post-GC): rss=%,dMiB heapLive=%,dMiB  rss-heapLive(=native+baseline)=%,dMiB"
                            + "  rssDelta-since-arm-start=%,dMiB%n",
                    rss >> 20, live >> 20, (rss - live) >> 20, (rss - armBaselineRss) >> 20);
            System.out.printf("FINAL rocksdb: %s%n", rocks.memStatsLine());
            System.out.printf("FINAL totalSst=%,dMiB%n", rocks.totalSstBytes() >> 20);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static long heapUsed() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** Post-GC heap-live: force a collection, then read used (≈ live). */
    private static long heapLive() {
        System.gc();
        return heapUsed();
    }

    /** Resident set size in bytes from {@code /proc/self/status} (Linux); 0 if unavailable. */
    private static long rssBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024; // kB → bytes
                }
            }
        } catch (Exception ignored) {
            // /proc absent (non-Linux) or a transient read failure — best-effort.
        }
        return 0;
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

    private NativeMemoryAttributionSpike() {}
}
