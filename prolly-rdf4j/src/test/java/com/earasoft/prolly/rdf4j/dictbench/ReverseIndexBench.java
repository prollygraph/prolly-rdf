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
import java.util.SplittableRandom;
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
 * The id → term REVERSE index: three chunking strategies for an append-only ordinal log ({@link
 * ReverseStores}) vs the engine's real reverse path ({@code Dictionary.decode} through its Int64
 * prolly tree). Same 50k NCIt-shaped corpus as {@link DictionaryBench}; lookups probe 1,000
 * uniformly sampled ids per op.
 *
 * <p>Fairness notes, named: the engine's single tree serves BOTH directions (its reverse lookup is
 * its forward storage); the ordinal-log stores serve only the reverse direction and assume dense
 * append-only id assignment (which the radix design controls, and the engine's hashed TermIds
 * forbid). Build arms end at content-addressed chunks either way.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
public class ReverseIndexBench {

    static final int N = 50_000;
    static final int LOOKUPS = 1_000;

    byte[][] corpus;
    ReverseStores.Store fixed;
    ReverseStores.Store budget;
    ReverseStores.Store cdc;
    Dictionary engine;
    TermId[] engineIds;
    int[] probeOrder;

    @Setup(Level.Trial)
    public void setUp() {
        corpus = DictionaryBench.ncitCorpus();
        fixed = ReverseStores.fixedCount(corpus, 64);
        budget = ReverseStores.byteBudget(corpus, 4096);
        cdc = ReverseStores.contentDefined(corpus);

        engine =
                new Dictionary(
                        new InMemoryNodeStore(), new HeapBufferPool(), HashFunctions.defaultHash());
        engineIds = new TermId[N];
        for (int i = 0; i < N; i++) engineIds[i] = engine.encode(MemorySegment.ofArray(corpus[i]));
        engine.commit();

        SplittableRandom rnd = new SplittableRandom(42);
        probeOrder = new int[LOOKUPS];
        for (int i = 0; i < LOOKUPS; i++) probeOrder[i] = rnd.nextInt(N);
    }

    // ------------------------------------------------------------------- build

    @Benchmark
    public Object buildFixedCount() {
        return ReverseStores.fixedCount(corpus, 64);
    }

    @Benchmark
    public Object buildByteBudget() {
        return ReverseStores.byteBudget(corpus, 4096);
    }

    @Benchmark
    public Object buildContentDefined() {
        return ReverseStores.contentDefined(corpus);
    }

    // ------------------------------------------------------------------ lookup

    @Benchmark
    public void lookupFixedCount(Blackhole bh) {
        for (int id : probeOrder) bh.consume(fixed.get(id));
    }

    @Benchmark
    public void lookupByteBudget(Blackhole bh) {
        for (int id : probeOrder) bh.consume(budget.get(id));
    }

    @Benchmark
    public void lookupContentDefined(Blackhole bh) {
        for (int id : probeOrder) bh.consume(cdc.get(id));
    }

    @Benchmark
    public void lookupEngineDecode(Blackhole bh) {
        for (int id : probeOrder) bh.consume(engine.decode(engineIds[id]));
    }
}
