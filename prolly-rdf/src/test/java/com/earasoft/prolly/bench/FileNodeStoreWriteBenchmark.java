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

import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.storage.FileNodeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Write throughput per durability mode: one commit-sized batch of {@value #K} distinct ~4 KiB
 * chunks written to a {@link FileNodeStore} under each {@link FileNodeStore.Durability} mode,
 * against a {@link RocksNodeStore} reference. The write half of the filesystem-node-store
 * measurement (Step 10) — it turns the fsync dial and measures what each click costs.
 *
 * <p><b>Measurement design</b> (per measure-the-real-thing):
 *
 * <ul>
 *   <li><b>Variable:</b> the durability mode — {@code NONE} (never fsync), {@code BATCH} (one
 *       fsync-group at {@code endWriteBatch}), {@code EACH} (fsync every chunk) — plus RocksDB as a
 *       reference point.
 *   <li><b>Target layer:</b> the <b>fsync syscall</b>. A batch of writes is dominated by (a) temp
 *       create + {@code ATOMIC_MOVE} rename per chunk and (b) the fsync policy; the mode only moves
 *       (b), so the arms isolate the durability tax.
 *   <li><b>Regime:</b> the whole batch is written under one {@code begin/endWriteBatch} span — the
 *       only regime in which {@code BATCH}'s amortization can act (a <em>lone</em> {@code BATCH}
 *       write with no active batch fsyncs immediately, identical to {@code EACH}, so a per-write
 *       bench would measure "no effect" — a false negative this design avoids).
 *   <li><b>Confound controlled:</b> every measured chunk is <b>unique</b> (a per-op counter is
 *       baked into the payload), so content-addressed dedup never turns a write into a no-op; the
 *       store is recreated each iteration so growth stays bounded and dedup starts fresh.
 * </ul>
 *
 * <p>Caveat recorded in the build-log: fsync durability is only as real as the underlying
 * filesystem/device honoring it (a lying disk cache, or a {@code tmpfs} mount, makes {@code EACH}
 * look free); the run names the mount it measured on.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 2, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class FileNodeStoreWriteBenchmark {

    /** The write arm: a FileNodeStore durability mode, or the RocksDB reference. */
    public enum Arm {
        FILE_NONE,
        FILE_BATCH,
        FILE_EACH,
        ROCKS
    }

    private static final int K = 256; // chunks in one commit-sized batch.
    private static final int CHUNK_BYTES = 4096; // ~4 KiB, the BuzHash target order of magnitude.

    @Param({"FILE_NONE", "FILE_BATCH", "FILE_EACH", "ROCKS"})
    Arm arm;

    private Path dir;
    private NodeStore store;
    private long counter; // guarantees each written chunk is unique -> never a dedup no-op.
    private final byte[] payload = new byte[CHUNK_BYTES];

    @Setup(Level.Iteration)
    public void setup() throws Exception {
        dir = Files.createTempDirectory("filestore-write-bench-");
        store =
                switch (arm) {
                    case FILE_NONE ->
                            new FileNodeStore(dir.resolve("s"), FileNodeStore.Durability.NONE);
                    case FILE_BATCH ->
                            new FileNodeStore(dir.resolve("s"), FileNodeStore.Durability.BATCH);
                    case FILE_EACH ->
                            new FileNodeStore(dir.resolve("s"), FileNodeStore.Durability.EACH);
                    case ROCKS -> new RocksNodeStore(dir.resolve("rocks").toString());
                };
    }

    @TearDown(Level.Iteration)
    public void tearDown() throws Exception {
        if (store instanceof AutoCloseable c) {
            c.close();
        }
        deleteRecursively(dir);
    }

    /** Write one commit-sized batch of {@value #K} distinct chunks under the arm's durability. */
    @Benchmark
    public void writeCommitBatch() {
        store.beginWriteBatch();
        for (int i = 0; i < K; i++) {
            // Bake a monotonically increasing id into each payload so every chunk is unique.
            long id = counter++;
            for (int b = 0; b < 8; b++) {
                payload[b] = (byte) (id >>> (8 * b));
            }
            store.write(payload);
        }
        store.endWriteBatch();
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                    // best-effort cleanup
                                }
                            });
        }
    }
}
