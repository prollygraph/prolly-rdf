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

import com.dolthub.prolly.RollingHashSplitter;
import java.lang.foreign.MemorySegment;
import java.util.Random;

/**
 * chunker-throughput Step 2 — measures the {@link RollingHashSplitter}'s <b>average chunk size</b>,
 * which sizes the headline lever's win: the skip-prefix lever saves {@code MIN_CHUNK_SIZE −
 * WINDOW_SIZE = 445} bytes of hashing per chunk, so the relative saving on the chunker's hashing is
 * {@code 445 / avgChunkSize}.
 *
 * <p>Feeds a large random byte stream as tuple-sized pieces, {@code reset()}-ing on each boundary
 * (the way ingest drives it), and reports the chunk-size distribution. <b>Random data is a fair
 * proxy for the avg</b>: content-defined chunking's boundary rate is set by the rolling-hash
 * pattern probability, which is ~data-independent for a well-mixing hash (BuzHash + per-level salt)
 * — so the avg on real web-Google tuples should be close. (A real-data confirm needs the full
 * ingest, which is memory-gated on a small box; deferred with the Step-2b flame.) A measurement
 * tool, not a test — run via {@code scripts/run-bench.sh com.earasoft.prolly.bench.ChunkSizeProbe}.
 */
public final class ChunkSizeProbe {

    private ChunkSizeProbe() {}

    public static void main(String[] args) {
        final int total = args.length > 0 ? Integer.parseInt(args[0]) : 64 * 1024 * 1024; // 64 MiB
        final int piece = 32; // tuple-sized feeds

        byte[] data = new byte[total];
        new Random(0xBEEFCAFEL).nextBytes(data);
        MemorySegment seg = MemorySegment.ofArray(data);

        RollingHashSplitter s = new RollingHashSplitter(0);
        long chunks = 0;
        long sumSpan = 0;
        long span = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        for (int off = 0; off < total; off += piece) {
            int len = Math.min(piece, total - off);
            s.append(seg.asSlice(off, len), MemorySegment.NULL);
            span += len;
            if (s.crossedBoundary()) {
                chunks++;
                sumSpan += span;
                if (span < min) min = span;
                if (span > max) max = span;
                span = 0;
                s.reset();
            }
        }

        double avg = chunks == 0 ? 0 : (double) sumSpan / chunks;
        System.out.printf(
                "CHUNK-SIZE-PROBE bytes=%d chunks=%d avgChunkSize=%.1f min=%d max=%d"
                        + " winRatio(445/avg)=%.4f%n",
                sumSpan, chunks, avg, chunks == 0 ? 0 : min, max, avg == 0 ? 0 : 445.0 / avg);
    }
}
