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

import org.rocksdb.PerfContext;
import org.rocksdb.PerfLevel;
import org.rocksdb.RocksDB;

/**
 *
 *
 * <h3>Per-query RocksDB instrumentation — the L3 (storage) rung.</h3>
 *
 * <p>Phase 0 of {@code plans/rocksdb-perf-instrumentation.md}. The JFR/`ThreadMXBean` profilers are
 * blind to what RocksDB actually does under a Sail; this captures it. Wraps a {@link Runnable} (a
 * query drain) with RocksDB's thread-local {@code PerfContext} at {@link PerfLevel#ENABLE_COUNT}
 * and returns the per-operation counter deltas — block reads, block-cache hits, bloom-SST
 * hits/misses, seeks, user-key comparisons, internal-key skips, block-read bytes.
 *
 * <p><b>Counts, not the instrumented wall-time, are the signal</b> (methodology D-6): {@code
 * ENABLE_COUNT} perturbs timing, but the per-op counts are exactly the attribution we want (e.g.
 * "10k seeks, 9k bloom misses"). {@code PerfContext} is <b>thread-local</b> — call {@code measure}
 * on the same thread the query runs on (RDF4J's bind-join is single-threaded), and run one Sail's
 * query at a time. The perf level is always restored to {@link PerfLevel#DISABLE} afterward.
 */
public final class RocksPerfProbe {

    public record Counters(
            long blockReads,
            long blockReadBytes,
            long blockCacheHits,
            long bloomSstHits,
            long bloomSstMisses,
            long seekChildCount,
            long userKeyComparisons,
            long internalKeySkips) {}

    /**
     * Run {@code work} with PerfContext counting enabled on {@code db}'s thread-local context;
     * return the delta.
     */
    public static Counters measure(RocksDB db, Runnable work) {
        db.setPerfLevel(PerfLevel.ENABLE_COUNT);
        try {
            PerfContext pc = db.getPerfContext();
            pc.reset();
            work.run();
            return new Counters(
                    pc.getBlockReadCount(),
                    pc.getBlockReadByte(),
                    pc.getBlockCacheHitCount(),
                    pc.getBloomSstHitCount(),
                    pc.getBloomSstMissCount(),
                    pc.getSeekChildSeekCount(),
                    pc.getUserKeyComparisonCount(),
                    pc.getInternalKeySkippedCount());
        } finally {
            db.setPerfLevel(PerfLevel.DISABLE); // never leave counting on (thread-local global)
        }
    }

    public static void print(String label, Counters c) {
        System.out.printf(
                "  %-18s blockReads=%-7d cacheHits=%-7d bloomHit/Miss=%d/%d seeks=%-6d keyCmp=%-9d ikeySkip=%-7d rdBytes=%d%n",
                label,
                c.blockReads(),
                c.blockCacheHits(),
                c.bloomSstHits(),
                c.bloomSstMisses(),
                c.seekChildCount(),
                c.userKeyComparisons(),
                c.internalKeySkips(),
                c.blockReadBytes());
    }

    private RocksPerfProbe() {}
}
