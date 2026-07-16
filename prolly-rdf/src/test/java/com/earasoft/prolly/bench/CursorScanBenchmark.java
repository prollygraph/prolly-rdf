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
import com.dolthub.prolly.Cursor;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Read-side throughput. Two metrics:
 *
 * <ul>
 *   <li>{@code sequentialScan} — full forward iteration over a 50k-key tree via repeated {@link
 *       Cursor#advance()}. Reported per element.
 *   <li>{@code randomSeek} — 1000 calls to {@link Cursor#atKey} on random keys within the same
 *       tree. Reported per call.
 * </ul>
 *
 * Tree sits in {@link InMemoryNodeStore} so storage I/O doesn't dominate.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class CursorScanBenchmark {
    private static final int TREE_SIZE = 50_000;
    private static final int SEEK_OPS = 1_000;

    private DirectBufferPool pool;
    private InMemoryNodeStore store;
    private TupleDescriptor desc;
    private Node root;
    private MemorySegment[] seekKeys;

    @Setup(Level.Trial)
    public void setup() {
        pool = new DirectBufferPool();
        store = new InMemoryNodeStore();
        desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        TreeMutator mutator = new TreeMutator(store, desc, pool);

        List<TreeMutator.Mutation> edits = new ArrayList<>(TREE_SIZE);
        for (int i = 0; i < TREE_SIZE; i++) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, String.format("k-%08d", i).getBytes());
            edits.add(
                    new TreeMutator.Mutation(
                            tb.build().segment(), MemorySegment.ofArray(("v-" + i).getBytes())));
        }
        root = mutator.applyMutations(null, edits.iterator());
        store.write(root.segment());

        // Pre-build a random key set for seekRandom.
        Random rng = new Random(0xDEADBEEFL);
        seekKeys = new MemorySegment[SEEK_OPS];
        for (int i = 0; i < SEEK_OPS; i++) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, String.format("k-%08d", rng.nextInt(TREE_SIZE)).getBytes());
            seekKeys[i] = tb.build().segment();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        pool.close();
    }

    @Benchmark
    @OperationsPerInvocation(TREE_SIZE)
    public long sequentialScan() {
        long bytes = 0;
        Cursor cur = Cursor.atStart(store, root);
        do {
            bytes += cur.currentKey().byteSize();
        } while (cur.advance());
        return bytes;
    }

    @Benchmark
    @OperationsPerInvocation(SEEK_OPS)
    public long randomSeek() {
        long sink = 0;
        for (MemorySegment k : seekKeys) {
            Cursor cur = Cursor.atKey(store, root, k, desc);
            sink += cur.currentKey().byteSize();
        }
        return sink;
    }
}
