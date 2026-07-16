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
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The WORKLOAD-shape corrections (real data ≠ real workload): every earlier lookup was uniform and
 * every ingest used all-distinct terms. Real RDF workloads are neither.
 *
 * <ul>
 *   <li><b>Skewed lookups</b>: term popularity is Zipfian (rdf:type / rdfs:label dominance). {@code
 *       skew=zipf} draws probes with exponent 1.0 over a fixed rank permutation; {@code
 *       skew=uniform} is the old regime, kept as the control arm.
 *   <li><b>Occurrence-stream encode</b>: real ingest calls the dictionary once per term OCCURRENCE
 *       (~4 per quad, heavy repetition: subject pool N/10, 64 hot predicates, unique objects), an
 *       evolving hit/miss mix from empty — not a distinct-term bulk load. The radix pipeline is
 *       measured as its real shape: per-occurrence lookup with a pending overlay, batched {@code
 *       insertAll} every {@code TXN} occurrences (a transaction).
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class WorkloadBench {

    static final int N = 50_000;
    static final int LOOKUPS = 1_000;
    static final int QUADS = 50_000;
    static final int TXN = 1_000;

    @Param({"uniform", "zipf"})
    public String skew;

    byte[][] corpus;
    MemorySegment[] segments;
    Dictionary builtCurrent;
    MerkleRadixDictionary builtRadix;
    MerkleRadixDictionary.Addr builtRadixRoot;
    MerkleRadixDictionary.SerializedPool builtRadixSerialized;
    int[] probeOrder;
    int[] occurrenceStream; // term index per occurrence, quad-shaped

    @Setup(Level.Trial)
    public void setUp() {
        corpus = DictionaryBench.ncitCorpus();
        segments = new MemorySegment[N];
        for (int i = 0; i < N; i++) segments[i] = MemorySegment.ofArray(corpus[i]);

        builtCurrent =
                new Dictionary(
                        new InMemoryNodeStore(), new HeapBufferPool(), HashFunctions.defaultHash());
        for (MemorySegment s : segments) builtCurrent.encode(s);
        builtCurrent.commit();
        builtRadix = new MerkleRadixDictionary(64);
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < N; i++) entries.put(corpus[i], (long) i);
        builtRadixRoot = builtRadix.build(entries);
        builtRadixSerialized = MerkleRadixDictionary.SerializedPool.of(builtRadix);

        SplittableRandom rnd = new SplittableRandom(42);
        probeOrder = new int[LOOKUPS];
        if (skew.equals("uniform")) {
            for (int i = 0; i < LOOKUPS; i++) probeOrder[i] = rnd.nextInt(N);
        } else {
            // Zipf(1.0) over a fixed rank permutation of the term space.
            int[] rankToTerm = new int[N];
            for (int i = 0; i < N; i++) rankToTerm[i] = i;
            for (int i = N - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int tmp = rankToTerm[i];
                rankToTerm[i] = rankToTerm[j];
                rankToTerm[j] = tmp;
            }
            double[] cdf = new double[N];
            double sum = 0;
            for (int r = 1; r <= N; r++) {
                sum += 1.0 / r;
                cdf[r - 1] = sum;
            }
            for (int i = 0; i < LOOKUPS; i++) {
                double u = rnd.nextDouble() * sum;
                int lo = 0;
                int hi = N - 1;
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (cdf[mid] < u) lo = mid + 1;
                    else hi = mid;
                }
                probeOrder[i] = rankToTerm[lo];
            }
        }

        // Quad-shaped occurrence stream: subject pool N/10 (drawn Zipf-lite by reuse),
        // 64 hot predicates, unique objects — 3 term occurrences per quad from the corpus
        // index space (subjects from [0, N/10), predicates from [N/10, N/10+64), objects
        // walk the remaining space uniquely, wrapping).
        occurrenceStream = new int[QUADS * 3];
        int objBase = N / 10 + 64;
        for (int q = 0; q < QUADS; q++) {
            occurrenceStream[q * 3] = rnd.nextInt(N / 10);
            occurrenceStream[q * 3 + 1] = N / 10 + rnd.nextInt(64);
            occurrenceStream[q * 3 + 2] = objBase + (q % (N - objBase));
        }
    }

    // ------------------------------------------------------------- lookups

    @Benchmark
    public void lookupCurrent(Blackhole bh) {
        for (int idx : probeOrder) bh.consume(builtCurrent.findTermId(segments[idx]));
    }

    @Benchmark
    public void lookupRadixSerialized(Blackhole bh) {
        for (int idx : probeOrder) {
            bh.consume(builtRadixSerialized.get(builtRadixRoot, corpus[idx]));
        }
    }

    // --------------------------------------------- occurrence-stream encode

    @Benchmark
    public Object occurrencesCurrent() {
        Dictionary dict =
                new Dictionary(
                        new InMemoryNodeStore(), new HeapBufferPool(), HashFunctions.defaultHash());
        for (int idx : occurrenceStream) dict.encode(MemorySegment.ofArray(corpus[idx]));
        return dict.commit();
    }

    @Benchmark
    public Object occurrencesRadixBatched() {
        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr root = null;
        Map<Integer, Long> overlay = new HashMap<>();
        TreeMap<byte[], Long> pending = new TreeMap<>(Arrays::compareUnsigned);
        long nextId = 0;
        int sinceTxn = 0;
        for (int idx : occurrenceStream) {
            long id = root == null ? -1 : dict.get(root, corpus[idx]);
            if (id == -1) {
                Long pendingId = overlay.get(idx);
                if (pendingId == null) {
                    overlay.put(idx, nextId);
                    pending.put(corpus[idx], nextId++);
                }
            }
            if (++sinceTxn >= TXN) {
                if (!pending.isEmpty()) {
                    root = root == null ? dict.build(pending) : dict.insertAll(root, pending);
                    pending = new TreeMap<>(Arrays::compareUnsigned);
                    overlay.clear();
                }
                sinceTxn = 0;
            }
        }
        if (!pending.isEmpty()) {
            root = root == null ? dict.build(pending) : dict.insertAll(root, pending);
        }
        return root;
    }
}
