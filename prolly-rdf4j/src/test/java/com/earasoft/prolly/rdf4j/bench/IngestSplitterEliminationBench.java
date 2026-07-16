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
package com.earasoft.prolly.rdf4j.bench;

import com.dolthub.prolly.BoundarySplitter;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
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
 * The SPOC boundary-function-adoption GATE (plan Step 2, D-2): what share of WHOLE ingest is the
 * boundary function? Three arms through a REAL RocksDB-backed {@link ProllySail} via the D-1 seam:
 *
 * <ul>
 *   <li><b>incumbent</b> — the production {@code RollingHashSplitter};
 *   <li><b>fixed4k</b> — the ELIMINATION arm: a near-zero-cost byte counter that cuts every 4 KiB.
 *       Its delta vs incumbent IS the whole chunker budget (elimination quantifies — geometry
 *       differs, but node count per tree stays comparable at the same mean);
 *   <li><b>directMask</b> — the study's A′ (lane-XOR + spread multiply over key lanes, two-mask):
 *       what adoption would actually recover.
 * </ul>
 *
 * <p>One op = ingest {@code TRIPLES} synthetic quads (commit per 5k) into a FRESH store — the
 * heavyweight-but-honest shape: no cross-invocation warm state, every arm pays identical
 * dictionary/tree/RocksDB costs except the splitter.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Fork(3)
public class IngestSplitterEliminationBench {

    private static final int TRIPLES = 50_000;
    private static final int BATCH = 5_000;

    @Param({"incumbent", "fixed4k", "directMask"})
    String arm;

    private Path dir;
    private RocksNodeStore store;
    private SailRepository repo;

    @Setup(Level.Invocation)
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("splitter-elim-");
        store = new RocksNodeStore(dir.resolve("rocks").toString());
        ProllySail sail = new ProllySail(store, new com.dolthub.prolly.HeapBufferPool());
        sail.setBoundarySplitterFactory(
                switch (arm) {
                    case "incumbent" -> BoundarySplitter.ROLLING_HASH;
                    case "fixed4k" -> FixedSizeSplitter::new;
                    case "directMask" -> DirectMaskSplitter::new;
                    default -> throw new IllegalArgumentException(arm);
                });
        repo = new SailRepository(sail);
        repo.init();
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws Exception {
        repo.shutDown();
        store.close();
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (java.io.IOException ignored) {
                                }
                            });
        }
    }

    @Benchmark
    public void ingest() {
        int i = 0;
        while (i < TRIPLES) {
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                ValueFactory vf = conn.getValueFactory();
                int end = Math.min(i + BATCH, TRIPLES);
                for (; i < end; i++) {
                    conn.add(
                            vf.createIRI("urn:s" + (i / 10)),
                            vf.createIRI("urn:p" + (i % 64)),
                            vf.createIRI("urn:o" + i));
                }
                conn.commit();
            }
        }
    }

    /** The elimination arm: count bytes, cut every 4 KiB — no hashing at all. */
    static final class FixedSizeSplitter implements BoundarySplitter {
        private int size;
        private boolean crossed;

        FixedSizeSplitter(int level) {}

        @Override
        public void append(MemorySegment key, MemorySegment value) {
            size += (int) key.byteSize() + (value == null ? 0 : (int) value.byteSize());
            if (size >= 4096) {
                crossed = true;
            }
        }

        @Override
        public boolean crossedBoundary() {
            return crossed;
        }

        @Override
        public int offset() {
            return size;
        }

        @Override
        public void reset() {
            size = 0;
            crossed = false;
        }
    }

    /** The study's A′: lane-XOR over the KEY + spread multiply, two-mask, MIN/MAX clamped. */
    static final class DirectMaskSplitter implements BoundarySplitter {
        private static final int MIN = 512;
        private static final int MAX = 16 * 1024;
        private static final int TARGET = 4 * 1024;

        private final long salt;
        private int size;
        private boolean crossed;

        DirectMaskSplitter(int level) {
            this.salt = new SplittableRandom(0x5EED_CAFE_F00DL ^ level).nextLong();
        }

        @Override
        public void append(MemorySegment key, MemorySegment value) {
            size += (int) key.byteSize() + (value == null ? 0 : (int) value.byteSize());
            if (crossed || size < MIN) {
                return;
            }
            if (size >= MAX) {
                crossed = true;
                return;
            }
            long mix = salt;
            int len = (int) key.byteSize();
            for (int w = 0; w + 8 <= len; w += 8) {
                mix ^= key.get(ValueLayout.JAVA_LONG_UNALIGNED, w);
            }
            mix *= 0x9E3779B97F4A7C15L;
            // Per-key rate over ~42-byte keys → target/42 ≈ 97 keys/chunk → 6-bit normal mask;
            // two-mask normalization: strict 8 bits below target, loose 4 above.
            long mask = size < TARGET ? 0xFFL : 0xFL;
            if ((mix & mask) == 0) {
                crossed = true;
            }
        }

        @Override
        public boolean crossedBoundary() {
            return crossed;
        }

        @Override
        public int offset() {
            return size;
        }

        @Override
        public void reset() {
            size = 0;
            crossed = false;
        }
    }
}
