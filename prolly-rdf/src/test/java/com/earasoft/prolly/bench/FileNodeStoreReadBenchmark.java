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
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
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
 * Warm random-read latency: {@link FileNodeStore} (one {@code open()/read()/close()} per chunk) vs
 * {@link RocksNodeStore} (an LSM lookup served from the block cache). This is the read half of the
 * filesystem-node-store measurement (Step 10).
 *
 * <p><b>Measurement design</b> (per the project's measure-the-real-thing discipline):
 *
 * <ul>
 *   <li><b>Variable:</b> the backend.
 *   <li><b>Target layer:</b> FileNodeStore's per-read cost lives on the <b>syscall/IO layer</b> (a
 *       fresh file descriptor + path resolution per chunk, even when the bytes are warm in the page
 *       cache); RocksDB's lives on the <b>CPU/RAM layer</b> (an in-process cache hit). This bench
 *       isolates that per-read overhead.
 *   <li><b>Regime:</b> <b>warm</b> — the store is pre-populated and read once end-to-end before
 *       measurement, so every read hits the OS page cache (File) or the block cache (Rocks). This
 *       is the regime JMH can measure cleanly in-process. The <em>cold</em> regime — where
 *       FileNodeStore must actually fault each file in from disk and its {@code open()} tax
 *       compounds with seek latency — is <b>not reliably measurable inside one JVM</b> (dropping
 *       the page cache needs {@code echo 3 > /proc/sys/vm/drop_caches}, i.e. root); it is a named
 *       gap the build-log records, not something this bench claims.
 *   <li><b>Confound controlled:</b> both backends read the <em>same</em> pre-shuffled sequence of
 *       the <em>same</em> content hashes, so the access pattern is identical; a rotating cursor
 *       makes each op a different chunk (no single-chunk cache-resident degenerate case).
 * </ul>
 *
 * <p>Payloads are {@value #CHUNK_BYTES}-byte pseudo-random blobs — an approximation of the ~4 KiB
 * BuzHash target chunk; the read-latency question depends on the size distribution, not the byte
 * content, so identical synthetic bytes across backends are a fair instrument here.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class FileNodeStoreReadBenchmark {

    /** Backend under test. */
    public enum Backend {
        FILE,
        ROCKS
    }

    private static final int N = 1000; // distinct chunks in the store.
    private static final int CHUNK_BYTES = 4096; // ~4 KiB, the BuzHash target order of magnitude.

    @Param({"FILE", "ROCKS"})
    Backend backend;

    private Path dir;
    private NodeStore store;
    private byte[][] hashes; // pre-shuffled read order.
    private int cursor;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        dir = Files.createTempDirectory("filestore-read-bench-");
        store =
                backend == Backend.FILE
                        ? new FileNodeStore(dir.resolve("store"))
                        : new RocksNodeStore(dir.resolve("rocks").toString());

        List<byte[]> written = new ArrayList<>(N);
        Random rng = new Random(42);
        store.beginWriteBatch();
        for (int i = 0; i < N; i++) {
            byte[] payload = new byte[CHUNK_BYTES];
            rng.nextBytes(payload);
            written.add(store.write(payload));
        }
        store.endWriteBatch();

        Collections.shuffle(written, new Random(7)); // fixed random read order, same for both.
        hashes = written.toArray(new byte[0][]);

        // Warm the caches: read every chunk once so measurement is steady-state warm.
        for (byte[] h : hashes) {
            store.read(h);
        }
        cursor = 0;
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (store instanceof AutoCloseable c) {
            c.close();
        }
        deleteRecursively(dir);
    }

    /** One warm random read — returns the bytes so the JIT cannot elide the load. */
    @Benchmark
    public Optional<MemorySegment> warmRandomRead() {
        byte[] hash = hashes[cursor];
        cursor = (cursor + 1) % hashes.length;
        return store.read(hash);
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
