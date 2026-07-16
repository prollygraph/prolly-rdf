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

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;
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
 *
 *
 * <h3>prolly-rdf4j vs the flat Sail vs the stock RDF4J NativeStore — ingest throughput</h3>
 *
 * <p>Runs the identical {@code begin → add ×N → commit} workload against three disk-backed Sails so
 * the comparison is apples-to-apples:
 *
 * <ul>
 *   <li>{@code prolly} — {@link ProllySail} over a {@code RocksNodeStore} (the versioned,
 *       content-addressed prolly-tree engine).
 *   <li>{@code flatsail} — {@link RocksDbFlatSail} (the unversioned RocksDB Sail: plain sorted
 *       keys, no Merkle tree).
 *   <li>{@code rdf4j-native} — RDF4J's own {@code NativeStore} (the stock non-versioned B-tree
 *       store).
 * </ul>
 *
 * <p>NativeStore is the baseline: it has no versioning/branching/merge cost. The flat Sail measures
 * what dropping the prolly tree (but keeping the dictionary + four indexes) costs or saves against
 * both.
 *
 * <p>Run via {@link JmhRunner} (e.g. {@code JmhRunner SailComparison}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
// 3 forks: JIT compilation-plan variance is real run-to-run noise; one fork hides it (methodology
// D-6).
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class SailComparisonBenchmark {

    @Param({"prolly", "flatsail", "rdf4j-native"})
    String engine;

    @Param({"5000"})
    int batchSize;

    private Path dir;
    private Repository repo;

    @Setup(Level.Invocation)
    public void setUp() throws IOException, org.rocksdb.RocksDBException {
        dir = Files.createTempDirectory("sail-cmp-bench");
        Sail sail =
                switch (engine) {
                    case "prolly" -> {
                        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
                        yield new ProllySail(
                                store,
                                new HeapBufferPool(),
                                RootMetaTreeStore.beside(dir),
                                CommitLog.beside(dir),
                                RefsStore.beside(dir));
                    }
                    case "flatsail" ->
                            new RocksDbFlatSail(Files.createDirectories(dir.resolve("flatsail")));
                    case "rdf4j-native" -> new NativeStore(dir.resolve("native").toFile());
                    default -> throw new IllegalArgumentException("unknown engine: " + engine);
                };
        repo = new SailRepository(sail);
        repo.init();
    }

    @TearDown(Level.Invocation)
    public void tearDown() throws IOException {
        repo.shutDown();
        // Disk-backed stores leave a tree behind — delete it so /tmp stays bounded.
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        }
    }

    @Benchmark
    public void ingestBatch() {
        ValueFactory vf = repo.getValueFactory();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (int i = 0; i < batchSize; i++) {
                conn.add(
                        vf.createIRI("urn:bench:s:" + i),
                        vf.createIRI("urn:bench:p"),
                        vf.createIRI("urn:bench:o:" + i));
                // Batched commits bound memory — never a single-tx mega-transaction (OOMs at
                // scale).
                if ((i + 1) % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
    }
}
