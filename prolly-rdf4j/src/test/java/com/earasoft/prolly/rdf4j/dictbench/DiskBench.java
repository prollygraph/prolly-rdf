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
package com.earasoft.prolly.rdf4j.dictbench;

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;

/**
 * The DISK regime: RocksDB-backed lookups for both designs — the engine's dictionary over its
 * production {@link RocksNodeStore}, vs the radix in two disk layouts: naive per-node entries
 * (every hop a RocksDB get of a ~200 B value — where trie depth is predicted to strike back) and
 * the page-backed layout (4 KiB content-defined pages in RocksDB, an in-memory address →
 * page/offset directory, same-page hops reusing the fetched block).
 *
 * <p><b>Named caveat</b>: without root there is no dropping the OS page cache, and direct I/O is
 * unreliable on this filesystem — so this measures the WARM disk regime (serialization + RocksDB
 * path overhead), not cold-storage latency. Memtables are flushed and the DBs reopened before
 * lookups so reads go through the SST path rather than the memtable.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class DiskBench {

    static final int N = 50_000;
    static final int LOOKUPS = 1_000;

    byte[][] corpus;
    MemorySegment[] segments;
    int[] probeOrder;
    Path dir;

    // Engine over its production RocksDB store.
    RocksNodeStore engineStore;
    Dictionary engineDict;

    // Radix disk layouts.
    RocksDB nodeDb; // naive: addr -> node bytes
    RocksDB pageDb; // paged: page index -> page bytes
    RocksDB supernodeDb; // supernode subtree pages: pageHash -> page bytes
    SupernodePager pager;
    MerkleRadixDictionary radix;
    MerkleRadixDictionary.Addr radixRoot;
    Map<MerkleRadixDictionary.Addr, long[]> pageDirectory; // addr -> {pageIdx, offset, len}

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        RocksDB.loadLibrary();
        corpus = DictionaryBench.ncitCorpus();
        segments = new MemorySegment[N];
        for (int i = 0; i < N; i++) segments[i] = MemorySegment.ofArray(corpus[i]);
        SplittableRandom rnd = new SplittableRandom(42);
        probeOrder = new int[LOOKUPS];
        for (int i = 0; i < LOOKUPS; i++) probeOrder[i] = rnd.nextInt(N);

        dir = Files.createTempDirectory("dictbench-disk");

        // ---- engine on RocksNodeStore ----
        engineStore = new RocksNodeStore(dir.resolve("engine").toString());
        Dictionary build =
                new Dictionary(engineStore, new HeapBufferPool(), HashFunctions.defaultHash());
        for (MemorySegment s : segments) build.encode(s);
        engineDict =
                new Dictionary(
                        engineStore,
                        new HeapBufferPool(),
                        HashFunctions.defaultHash(),
                        build.commit());

        // ---- radix structures ----
        radix = new MerkleRadixDictionary(64);
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < N; i++) entries.put(corpus[i], (long) i);
        radixRoot = radix.build(entries);

        try (Options opts = new Options().setCreateIfMissing(true)) {
            nodeDb = RocksDB.open(opts, dir.resolve("nodes").toString());
            for (MerkleRadixDictionary.Addr addr :
                    DictionaryPagingReportTest.canonicalOrder(radix, radixRoot)) {
                nodeDb.put(addr.bytes(), radix.node(addr).serialize());
            }
            nodeDb.flushWal(true);
        }

        List<byte[]> pages = DictionaryPagingReportTest.packPages(radix, radixRoot);
        pageDirectory = new HashMap<>();
        // Rebuild the directory by replaying the packing walk.
        int pageIdx = 0;
        int offset = 0;
        List<MerkleRadixDictionary.Addr> order =
                DictionaryPagingReportTest.canonicalOrder(radix, radixRoot);
        int cursor = 0;
        for (byte[] page : pages) {
            offset = 0;
            while (offset < page.length) {
                MerkleRadixDictionary.Addr addr = order.get(cursor++);
                int len = radix.node(addr).serialize().length;
                pageDirectory.put(addr, new long[] {pageIdx, offset, len});
                offset += len;
            }
            pageIdx++;
        }
        try (Options opts = new Options().setCreateIfMissing(true)) {
            pageDb = RocksDB.open(opts, dir.resolve("pages").toString());
            for (int i = 0; i < pages.size(); i++) {
                pageDb.put(intKey(i), pages.get(i));
            }
            pageDb.flushWal(true);
        }

        pager = SupernodePager.of(radix, radixRoot);
        try (Options opts = new Options().setCreateIfMissing(true)) {
            supernodeDb = RocksDB.open(opts, dir.resolve("supernodes").toString());
            for (Map.Entry<MerkleRadixDictionary.Addr, byte[]> e : pager.pages.entrySet()) {
                supernodeDb.put(e.getKey().bytes(), e.getValue());
            }
            supernodeDb.flushWal(true);
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        if (supernodeDb != null) supernodeDb.close();
        if (nodeDb != null) nodeDb.close();
        if (pageDb != null) pageDb.close();
        if (engineStore != null) engineStore.close();
    }

    private static byte[] intKey(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    // ------------------------------------------------------------- lookups

    @Benchmark
    public void lookupEngineRocks(Blackhole bh) {
        for (int idx : probeOrder) bh.consume(engineDict.findTermId(segments[idx]));
    }

    /** Naive layout: one RocksDB get per trie hop. */
    @Benchmark
    public void lookupRadixNodeRocks(Blackhole bh) throws Exception {
        for (int idx : probeOrder) bh.consume(getViaNodeDb(corpus[idx]));
    }

    private long getViaNodeDb(byte[] key) throws Exception {
        byte[] cur = radixRoot.bytes();
        int at = 0;
        while (true) {
            byte[] node = nodeDb.get(cur);
            long[] r = MerkleRadixDictionary.SerializedPool.step(node, key, at);
            if (r[0] == 0) return r[1]; // resolved (id or -1)
            at = (int) r[1];
            cur = Arrays.copyOfRange(node, (int) r[2], (int) r[2] + MerkleRadixDictionary.ADDR_LEN);
        }
    }

    /** Supernode layout: whole radix subtrees per ~4 KiB page — 2-3 fetches per lookup. */
    @Benchmark
    public void lookupRadixSupernodeRocks(Blackhole bh) {
        SupernodePager.PageSource src =
                addr -> {
                    try {
                        return supernodeDb.get(addr.bytes());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                };
        for (int idx : probeOrder) {
            bh.consume(SupernodePager.get(src, pager.rootPage, corpus[idx], null));
        }
    }

    /** Page layout: directory in memory, page fetch per page-crossing hop. */
    @Benchmark
    public void lookupRadixPagedRocks(Blackhole bh) throws Exception {
        for (int idx : probeOrder) bh.consume(getViaPageDb(corpus[idx]));
    }

    private long getViaPageDb(byte[] key) throws Exception {
        MerkleRadixDictionary.Addr cur = radixRoot;
        int at = 0;
        long lastPage = -1;
        byte[] pageBytes = null;
        while (true) {
            long[] loc = pageDirectory.get(cur);
            if (loc[0] != lastPage) {
                pageBytes = pageDb.get(intKey((int) loc[0]));
                lastPage = loc[0];
            }
            byte[] node = Arrays.copyOfRange(pageBytes, (int) loc[1], (int) (loc[1] + loc[2]));
            long[] r = MerkleRadixDictionary.SerializedPool.step(node, key, at);
            if (r[0] == 0) return r[1];
            at = (int) r[1];
            cur =
                    new MerkleRadixDictionary.Addr(
                            Arrays.copyOfRange(
                                    node, (int) r[2], (int) r[2] + MerkleRadixDictionary.ADDR_LEN));
        }
    }
}
