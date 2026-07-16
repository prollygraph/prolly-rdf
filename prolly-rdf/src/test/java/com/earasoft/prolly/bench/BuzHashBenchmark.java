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
import com.dolthub.prolly.BuzHash;
import com.dolthub.prolly.RollingHashSplitter;
import java.lang.foreign.MemorySegment;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Per-byte rolling-hash throughput. Measures both the raw {@link BuzHash} (window only, no boundary
 * check) and {@link RollingHashSplitter} (BuzHash + pattern mask + min/max chunk size). Reported as
 * ops/sec; an op is one byte fed through the hash. Compare to Go's BuzHash byte-rate to establish
 * JIT parity.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        // --enable-preview removed 2026-06-17: the FFM API finalized in Java 22 and the project is
        // on
        // Java 25 LTS with no preview features (CLAUDE.md). The flag was a stale Java-25-migration
        // straggler (harmless — permissive — but inconsistent). Other bench @Fork configs still
        // carry
        // it; sweeping them is a separate cleanup (noted in chunker-throughput Step 1 wrap-up).
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class BuzHashBenchmark {
    private static final int N = 16 * 1024; // 16 KiB per invocation
    private static final int PIECE =
            32; // tuple-sized feed, so boundaries end chunks between pieces

    private byte[] data;
    private BuzHash bz;
    private RollingHashSplitter splitter;
    private MemorySegment dataSeg;
    private MemorySegment[] pieces; // dataSeg pre-sliced into PIECE-sized views (no in-loop alloc)

    @Setup(Level.Trial)
    public void setup() {
        data = new byte[N];
        new Random(0xBEEFCAFEL).nextBytes(data);
        bz = new BuzHash(67); // matches RollingHashSplitter.WINDOW_SIZE
        splitter = new RollingHashSplitter(0);
        dataSeg = MemorySegment.ofArray(data);
        int count = (N + PIECE - 1) / PIECE;
        pieces = new MemorySegment[count];
        for (int i = 0, off = 0; i < count; i++, off += PIECE) {
            pieces[i] = dataSeg.asSlice(off, Math.min(PIECE, N - off));
        }
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public int rawBuzHashByte() {
        bz.reset();
        int sink = 0;
        for (int i = 0; i < N; i++) sink += bz.hashByte(data[i]);
        return sink;
    }

    @Benchmark
    @OperationsPerInvocation(N)
    public int splitterFullPath() {
        // NOTE (chunker-throughput Step 1): this hashes only until the FIRST boundary — `hashByte`
        // early-returns once `crossedBoundary`, so the rest of the N bytes are cheap no-ops while
        // @OperationsPerInvocation(N) still counts them. Its ops/s is therefore INFLATED (it
        // measures
        // ~one chunk amortized over N) — kept as a per-chunk reference, not a per-byte throughput.
        RollingHashSplitter s = new RollingHashSplitter(0);
        s.append(dataSeg, MemorySegment.NULL);
        return s.offset();
    }

    /**
     * The honest per-byte chunker throughput (chunker-throughput Step 1's control arm): feed the
     * whole stream as tuple-sized pieces, {@code reset()}-ing on each boundary so every chunk's
     * bytes — including its {@code [0, MIN_CHUNK_SIZE)} prefix — are really hashed, the way ingest
     * drives it. Unlike {@link #splitterFullPath} this does not short-circuit after one boundary,
     * so @OperationsPerInvocation(N) is accurate and the headline lever (skip the wasted min-chunk
     * prefix) will show up here as a real ns/byte drop across many chunks.
     */
    @Benchmark
    @OperationsPerInvocation(N)
    public int splitterChunkedStream() {
        splitter.reset();
        for (MemorySegment p : pieces) {
            splitter.append(p, MemorySegment.NULL);
            if (splitter.crossedBoundary()) {
                splitter.reset(); // boundary → end this chunk, start the next (fresh rolling hash)
            }
        }
        return splitter.offset();
    }
}
