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

import com.dolthub.prolly.*;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * End-to-end {@code applyMutations} throughput on an in-memory store. This is the integration
 * metric: chunker + splitter + serializer + mutator put through their paces with no RocksDB I/O.
 * Now that structural fast-forwarding is disabled (see TreeMutator Javadoc), this benchmark is the
 * canonical worst-case cost — O(existing-tree + edits) per call.
 *
 * <p>Two scenarios per N:
 *
 * <ul>
 *   <li>{@code buildFromEmpty}: applyMutations(null, N edits) — fresh tree.
 *   <li>{@code reapplyOnExisting}: applyMutations(rootN, N more edits) — measures the
 *       existing-tree-walk cost on top of the edits.
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class TreeMutatorBenchmark {

    @Param({"1000", "5000", "20000"})
    int N;

    private DirectBufferPool pool;
    private InMemoryNodeStore store;
    private TupleDescriptor desc;
    private TreeMutator mutator;
    private List<TreeMutator.Mutation> firstBatch;
    private List<TreeMutator.Mutation> secondBatch;
    private Node existingRoot;

    @Setup(Level.Trial)
    public void setup() {
        pool = new DirectBufferPool();
        store = new InMemoryNodeStore();
        desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        mutator = new TreeMutator(store, desc, pool);

        firstBatch = build(0, N);
        existingRoot = mutator.applyMutations(null, firstBatch.iterator());
        // Persist root so subsequent applyMutations on it can resolve.
        store.write(existingRoot.segment());

        // Second batch lives entirely AFTER the first — keys "next-..." sort after
        // "key-...". Avoids hitting the unrelated sort-check path.
        secondBatch = buildPrefix("next-", 0, N);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    public Node buildFromEmpty() {
        return mutator.applyMutations(null, firstBatch.iterator());
    }

    @Benchmark
    public Node reapplyOnExisting() {
        return mutator.applyMutations(existingRoot, secondBatch.iterator());
    }

    private List<TreeMutator.Mutation> build(int start, int count) {
        return buildPrefix("key-", start, count);
    }

    private List<TreeMutator.Mutation> buildPrefix(String prefix, int start, int count) {
        List<TreeMutator.Mutation> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, String.format("%s%08d", prefix, start + i).getBytes());
            out.add(
                    new TreeMutator.Mutation(
                            tb.build().segment(),
                            MemorySegment.ofArray(("v-" + (start + i)).getBytes())));
        }
        return out;
    }
}
