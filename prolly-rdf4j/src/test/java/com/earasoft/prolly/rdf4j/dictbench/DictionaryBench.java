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
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The engine's real dictionary vs the Merkle Radix study candidate, on identical inputs.
 *
 * <p><b>What is compared</b> — each design's real end-to-end pipeline over the SAME raw term bytes
 * (NCIt-shaped: one dominant namespace with dense sequential ids, a second vocabulary, property
 * URIs, label literals):
 *
 * <ul>
 *   <li><b>current</b> ({@code prolly-codec} {@link Dictionary}): term → id is COMPUTED (salted
 *       FNV-1a 64 hash + collision probe against the tree); the tree stores id → term bytes. Ingest
 *       = {@code encode()} per term + {@code commit()}; lookup = {@code findTermId()}.
 *   <li><b>radix</b> ({@link MerkleRadixDictionary}): term → id is a trie walk; ingest hashes every
 *       node (SHA-256/20) bottom-up. Batch = canonical build (includes the required sort); stream =
 *       fold of path-copying inserts.
 * </ul>
 *
 * <p><b>Regime</b>: in-memory NodeStore / node pool — the CPU + structure regime, deliberately not
 * the disk regime; both arms hold everything in RAM. Confounds isolated: identical input bytes (no
 * TermCodec in either arm), fresh structures per invocation, 3 forks (fork = the replication unit;
 * check with {@code bench_significance.py}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class DictionaryBench {

    static final int N = 50_000;
    static final int LOOKUPS = 1_000;

    /** Terminal-bucket capacity: 1 = classic one-leaf-per-key; 64 = collapsed bottom levels. */
    @org.openjdk.jmh.annotations.Param({"1", "64"})
    public int bucketSize;

    byte[][] corpus;
    MemorySegment[] segments;

    // Pre-built structures for the lookup arms (Trial scope — read-only thereafter).
    Dictionary builtCurrent;
    MerkleRadixDictionary builtRadix;
    MerkleRadixDictionary.Addr builtRadixRoot;
    MerkleRadixDictionary.SerializedPool builtRadixSerialized;
    FlatbufferPool builtRadixFlatbuffer;
    int[] probeOrder;

    // Update workload: 5k NEW terms applied to the pre-built 50k base.
    static final int UPDATES = 5_000;
    byte[][] updateCorpus;
    com.dolthub.prolly.NodeStore baseStore;
    com.dolthub.prolly.BufferPool basePool;
    com.dolthub.prolly.StaticMap baseCommitted;

    @Setup(Level.Trial)
    public void setUp() {
        corpus = ncitCorpus();
        segments = new MemorySegment[N];
        for (int i = 0; i < N; i++) segments[i] = MemorySegment.ofArray(corpus[i]);

        baseStore = new InMemoryNodeStore();
        basePool = new HeapBufferPool();
        builtCurrent = new Dictionary(baseStore, basePool, HashFunctions.defaultHash());
        for (MemorySegment s : segments) builtCurrent.encode(s);
        baseCommitted = builtCurrent.commit();

        updateCorpus = new byte[UPDATES][];
        for (int u = 0; u < UPDATES; u++) {
            updateCorpus[u] = utf8("http://purl.obolibrary.org/obo/NCIT_C" + (900_000 + u));
        }

        builtRadix = new MerkleRadixDictionary(bucketSize);
        builtRadixRoot = builtRadix.build(sortedEntries());
        builtRadixSerialized = MerkleRadixDictionary.SerializedPool.of(builtRadix);
        builtRadixFlatbuffer = FlatbufferPool.of(builtRadix, builtRadixRoot);

        SplittableRandom rnd = new SplittableRandom(42);
        probeOrder = new int[LOOKUPS];
        for (int i = 0; i < LOOKUPS; i++) probeOrder[i] = rnd.nextInt(N);
    }

    static byte[][] ncitCorpus() {
        byte[][] out = new byte[N][];
        int i = 0;
        for (int k = 0; k < 40_000; k++) {
            out[i++] = utf8("http://purl.obolibrary.org/obo/NCIT_C" + (100_000 + k));
        }
        for (int k = 0; k < 2_000; k++) {
            out[i++] = utf8("http://purl.obolibrary.org/obo/GO_" + (7_000_000 + k));
        }
        String[] props = {
            "label",
            "comment",
            "subClassOf",
            "seeAlso",
            "isDefinedBy",
            "Class",
            "ObjectProperty",
            "DatatypeProperty",
            "equivalentClass"
        };
        for (String p : props) out[i++] = utf8("http://www.w3.org/2000/01/rdf-schema#" + p);
        while (i < N) {
            out[i] = utf8("Neoplasm of anatomic site " + i + " (morphology variant)");
            i++;
        }
        return out;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private TreeMap<byte[], Long> sortedEntries() {
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < N; i++) entries.put(corpus[i], (long) i);
        return entries;
    }

    // ------------------------------------------------------------------ ingest

    @Benchmark
    public Object ingestCurrent() {
        Dictionary dict =
                new Dictionary(
                        new InMemoryNodeStore(), new HeapBufferPool(), HashFunctions.defaultHash());
        for (int i = 0; i < N; i++) dict.encode(MemorySegment.ofArray(corpus[i]));
        return dict.commit();
    }

    @Benchmark
    public Object ingestRadixBatch() {
        MerkleRadixDictionary dict = new MerkleRadixDictionary(bucketSize);
        return dict.build(sortedEntries()); // the sort is part of the batch pipeline
    }

    @Benchmark
    public Object ingestRadixInserts() {
        MerkleRadixDictionary dict = new MerkleRadixDictionary(bucketSize);
        MerkleRadixDictionary.Addr root = dict.singleton(corpus[0], 0);
        for (int i = 1; i < N; i++) root = dict.insert(root, corpus[i], i);
        return root;
    }

    @Benchmark
    public Object ingestRadixBatchFlatbuffer() {
        // End state leveled with ingestCurrent: a fully flatbuffer-framed store.
        MerkleRadixDictionary dict = new MerkleRadixDictionary(bucketSize);
        MerkleRadixDictionary.Addr root = dict.build(sortedEntries());
        return FlatbufferPool.of(dict, root);
    }

    // ------------------------------------------------------------------ update
    // The streaming question: apply 5k NEW terms to the pre-built 50k dictionary.

    @Benchmark
    public Object updateCurrent() {
        Dictionary dict =
                new Dictionary(baseStore, basePool, HashFunctions.defaultHash(), baseCommitted);
        for (byte[] term : updateCorpus) dict.encode(MemorySegment.ofArray(term));
        return dict.commit();
    }

    @Benchmark
    public Object updateRadixBuffered() {
        MerkleRadixDictionary dict = builtRadix.fork(); // harness snapshot (pages persist IRL)
        TreeMap<byte[], Long> batch = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < UPDATES; u++) batch.put(updateCorpus[u], (long) (N + u));
        return dict.insertAll(builtRadixRoot, batch);
    }

    @Benchmark
    public Object updateRadixPerInsert() {
        MerkleRadixDictionary dict = builtRadix.fork();
        MerkleRadixDictionary.Addr root = builtRadixRoot;
        for (int u = 0; u < UPDATES; u++) {
            root = dict.insert(root, updateCorpus[u], N + u);
        }
        return root;
    }

    @Benchmark
    public Object updateRadixBufferedFlatbuffer() {
        MerkleRadixDictionary dict = builtRadix.fork();
        TreeMap<byte[], Long> batch = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < UPDATES; u++) batch.put(updateCorpus[u], (long) (N + u));
        MerkleRadixDictionary.Addr root = dict.insertAll(builtRadixRoot, batch);
        return FlatbufferPool.update(builtRadixFlatbuffer, dict, root);
    }

    // ------------------------------------------------------------------ lookup

    @Benchmark
    public void lookupCurrent(Blackhole bh) {
        for (int idx : probeOrder) {
            java.util.Optional<TermId> id = builtCurrent.findTermId(segments[idx]);
            bh.consume(id);
        }
    }

    @Benchmark
    public void lookupRadixSerialized(Blackhole bh) {
        for (int idx : probeOrder) {
            bh.consume(builtRadixSerialized.get(builtRadixRoot, corpus[idx]));
        }
    }

    @Benchmark
    public void lookupRadixFlatbuffer(Blackhole bh) {
        for (int idx : probeOrder) {
            bh.consume(builtRadixFlatbuffer.get(builtRadixRoot, corpus[idx]));
        }
    }

    @Benchmark
    public void lookupRadix(Blackhole bh) {
        for (int idx : probeOrder) {
            bh.consume(builtRadix.get(builtRadixRoot, corpus[idx]));
        }
    }
}
