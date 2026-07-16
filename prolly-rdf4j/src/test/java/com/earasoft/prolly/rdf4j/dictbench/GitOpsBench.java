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

import com.dolthub.prolly.DiffEngine;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
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
 * The git operations, timed: version diff (5k added terms into a 50k base) and identical-roots
 * detection, radix ({@link MerkleRadixDictionary#diff}) vs the engine's real {@link DiffEngine}
 * over two dictionary commits. Correctness of both is pinned in {@link GitOpsCorrectnessTest} —
 * including that they report the identical term set for the same logical edit.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class GitOpsBench {

    static final int N = 50_000;
    static final int UPDATES = 5_000;

    MerkleRadixDictionary radix;
    MerkleRadixDictionary.Addr radixRootA;
    MerkleRadixDictionary.Addr radixRootB;
    MerkleRadixDictionary.Addr radixTheirs;
    StaticMap commitTheirs;
    com.dolthub.prolly.MergeEngine engineMerge;

    NodeStore engineStore;
    StaticMap commitA;
    StaticMap commitB;
    DiffEngine engineDiff;

    @Setup(Level.Trial)
    public void setUp() {
        byte[][] corpus = DictionaryBench.ncitCorpus();
        byte[][] added = new byte[UPDATES][];
        for (int u = 0; u < UPDATES; u++) {
            added[u] =
                    ("http://purl.obolibrary.org/obo/NCIT_C" + (900_000 + u))
                            .getBytes(StandardCharsets.UTF_8);
        }

        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);
        radix = new MerkleRadixDictionary(64);
        radixRootA = radix.build(entries);
        TreeMap<byte[], Long> batch = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < UPDATES; u++) batch.put(added[u], (long) (N + u));
        radixRootB = radix.insertAll(radixRootA, batch);

        engineStore = new InMemoryNodeStore();
        Dictionary dict =
                new Dictionary(engineStore, new HeapBufferPool(), HashFunctions.defaultHash());
        for (byte[] term : corpus) dict.encode(MemorySegment.ofArray(term));
        commitA = dict.commit();
        Dictionary dict2 =
                new Dictionary(
                        engineStore, new HeapBufferPool(), HashFunctions.defaultHash(), commitA);
        for (byte[] term : added) dict2.encode(MemorySegment.ofArray(term));
        commitB = dict2.commit();
        engineDiff = new DiffEngine(engineStore, commitA.descriptor());

        // Merge scenario: theirs adds a disjoint 2.5k GO range on top of the same base.
        byte[][] theirsAdd = new byte[2_500][];
        for (int u = 0; u < theirsAdd.length; u++) {
            theirsAdd[u] =
                    ("http://purl.obolibrary.org/obo/GO_" + (8_000_000 + u))
                            .getBytes(StandardCharsets.UTF_8);
        }
        TreeMap<byte[], Long> tb = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < theirsAdd.length; u++) tb.put(theirsAdd[u], (long) (N + 10_000 + u));
        radixTheirs = radix.insertAll(radixRootA, tb);
        Dictionary dict3 =
                new Dictionary(
                        engineStore, new HeapBufferPool(), HashFunctions.defaultHash(), commitA);
        for (byte[] term : theirsAdd) dict3.encode(MemorySegment.ofArray(term));
        commitTheirs = dict3.commit();
        engineMerge =
                new com.dolthub.prolly.MergeEngine(
                        engineStore, commitA.descriptor(), new HeapBufferPool());
    }

    @Benchmark
    public Object diffRadix5k() {
        return radix.diff(radixRootA, radixRootB);
    }

    @Benchmark
    public Object diffRadixIdentical() {
        return radix.diff(radixRootA, radixRootA);
    }

    @Benchmark
    public Object mergeRadix() {
        return radix.merge(radixRootA, radixRootB, radixTheirs);
    }

    @Benchmark
    public Object mergeEngine() {
        return engineMerge.merge(commitA.root(), commitB.root(), commitTheirs.root());
    }

    @Benchmark
    public void diffEngine5k(Blackhole bh) {
        Iterator<DiffEngine.DiffEntry> it = engineDiff.diffIterator(commitA.root(), commitB.root());
        while (it.hasNext()) bh.consume(it.next());
    }

    @Benchmark
    public void diffEngineIdentical(Blackhole bh) {
        Iterator<DiffEngine.DiffEntry> it = engineDiff.diffIterator(commitA.root(), commitA.root());
        while (it.hasNext()) bh.consume(it.next());
    }
}
