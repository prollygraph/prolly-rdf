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
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
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
 * <h3>Read-path comparison — ProllySail vs RocksDbFlatSail vs NativeStore</h3>
 *
 * <p>The sibling of {@link SailComparisonBenchmark} (which measures ingest): this one loads a
 * dataset <em>once</em> ({@code @Setup(Level.Trial)}) and then times read workloads, which are
 * idempotent so the loaded store is reused:
 *
 * <ul>
 *   <li>{@code pointLookup} — {@code getStatements} for one exact subject;
 *   <li>{@code predicateScan} — {@code getStatements} for one predicate;
 *   <li>{@code fullScan} — {@code getStatements(null, null, null)};
 *   <li>{@code sparqlJoin} — a two-pattern SPARQL basic-graph-pattern join.
 * </ul>
 *
 * <p>The dataset is {@code datasetSize} subjects, each with a {@code p}-triple and a {@code
 * next}-triple (≈2× rows total). Run via {@code org.openjdk.jmh.Main "SailReadBenchmark"}.
 */
@State(Scope.Benchmark)
// SampleTime added alongside AverageTime: the read ops are latency-shaped (esp. pointLookup +
// sparqlJoin), so percentiles (p50/p99) matter, not just the mean (methodology D-6).
@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
// 3 forks: JIT compilation-plan variance is real run-to-run noise; one fork hides it (D-6).
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class SailReadBenchmark {

    private static final String P = "urn:bench:p";
    private static final String NEXT = "urn:bench:next";

    @Param({"prolly", "flatsail", "rdf4j-native"})
    String engine;

    @Param({"10000"})
    int datasetSize;

    private Path dir;
    private Repository repo;
    private IRI predicate;
    private IRI nextPredicate;
    private IRI midSubject;

    @Setup(Level.Trial)
    public void setUp() throws IOException, org.rocksdb.RocksDBException {
        dir = Files.createTempDirectory("sail-read-bench");
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
        ValueFactory vf = repo.getValueFactory();
        predicate = vf.createIRI(P);
        nextPredicate = vf.createIRI(NEXT);
        midSubject = vf.createIRI("urn:bench:s:" + (datasetSize / 2));

        // Load the dataset once — each subject gets a p-triple and a next-triple.
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (int i = 0; i < datasetSize; i++) {
                IRI s = vf.createIRI("urn:bench:s:" + i);
                conn.add(s, predicate, vf.createIRI("urn:bench:o:" + i));
                if (i + 1 < datasetSize) {
                    conn.add(s, nextPredicate, vf.createIRI("urn:bench:s:" + (i + 1)));
                }
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

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        repo.shutDown();
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

    /** Point lookup — every statement about one subject. */
    @Benchmark
    public long pointLookup() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(midSubject, null, null)) {
            return count(r);
        }
    }

    /** Range scan — every statement with one predicate. */
    @Benchmark
    public long predicateScan() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, predicate, null)) {
            return count(r);
        }
    }

    /** Full scan — every statement in the store. */
    @Benchmark
    public long fullScan() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, null, null)) {
            return count(r);
        }
    }

    /** A two-pattern SPARQL join: each subject's next-hop and that hop's object. */
    @Benchmark
    public long sparqlJoin() {
        String query = "SELECT ?o WHERE { ?s <" + NEXT + "> ?n . ?n <" + P + "> ?o }";
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }

    private static long count(RepositoryResult<Statement> result) {
        long n = 0;
        while (result.hasNext()) {
            result.next();
            n++;
        }
        return n;
    }
}
