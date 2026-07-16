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

import com.dolthub.prolly.NodeCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
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
 * Real-Sail regression for the production {@link NodeCache} (Caffeine-backed, ADR-0040) — Zipfian-
 * popularity point lookups and a scan-pollution workload over a real corpus, in the eviction regime
 * (read-path plan Step 2). It descends from the experiment #3 A/B that decided ADR-0040 (the
 * LRU-vs-Caffeine arms were removed once Caffeine was adopted; the A/B findings live in the {@code
 * blog/build-log-*} cache series).
 *
 * <p><b>Regime (deliberate):</b> the default budget is ~21 MiB ≈ 1/8 of the ~174 MiB working set,
 * so eviction fires (the cache's policy + concurrency behavior can act) — NOT a budget where the
 * corpus fits. Run {@code -t 4} to exercise concurrent reads. The {@code [hitrate]} line printed at
 * teardown shows the achieved hit rate, which {@code scanThenLookups} drives down (scan pollution)
 * and the cache's frequency admission resists.
 *
 * <p>Run: {@code JmhRunner -f0 -t 1 NodeCacheRealWorldBench} / {@code -t 4}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 4, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class NodeCacheRealWorldBench {

    @Param("prolly")
    String engine;

    @Param("500000")
    int sampleSize;

    /**
     * ~21 MiB ≈ 1/8 of the ~174 MiB working set → eviction regime (override for a fits-control).
     */
    @Param("22020096")
    long nodeCacheBytes;

    private Path dir;
    private SailRepository repo;
    private NodeCache cache;
    private IRI[] classes;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("ncit-realworld");
        cache = new NodeCache(nodeCacheBytes);
        repo = new SailRepository(StreamingNcitIngest.buildSail(engine, dir, cache));
        repo.init();
        StreamingNcitIngest.streamLoad(repo, sampleSize, 100_000);
        List<IRI> cs = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, RDFS.SUBCLASSOF, null)) {
            while (r.hasNext() && cs.size() < 500) {
                if (r.next().getSubject() instanceof IRI iri) cs.add(iri);
            }
        }
        classes = cs.toArray(new IRI[0]);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        long h = cache.hits(), m = cache.misses();
        System.out.printf(
                "[hitrate] budget=%dMiB hits=%d misses=%d rate=%.1f%%%n",
                nodeCacheBytes / 1048576, h, m, 100.0 * h / Math.max(1, h + m));
        repo.shutDown();
        StreamingNcitIngest.deleteTree(dir);
    }

    /**
     * One Zipfian-popularity point lookup (a few hot classes, long cold tail) — the realistic read.
     */
    @Benchmark
    public long zipfianPointLookup() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r =
                        conn.getStatements(classes[zipfIdx()], null, null)) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }

    /**
     * The SCAN-POLLUTION regime — one full scan (floods the cache with cold leaves) then 200 hot
     * Zipfian lookups. This is where the offline hit-rate gap lived: under LRU the scan evicts the
     * hot set so the 200 lookups miss; under W-TinyLFU frequency-admission rejects the scan's
     * one-touch leaves so the hot set survives. The LRU−Caffeine *delta* (and the teardown
     * [hitrate]) isolate the scan-resistance value; the scan cost itself is identical in both arms.
     */
    @Benchmark
    public long scanThenLookups() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, null, null)) {
            while (r.hasNext()) r.next();
        }
        long n = 0;
        for (int i = 0; i < 200; i++) {
            try (RepositoryConnection conn = repo.getConnection();
                    RepositoryResult<Statement> r =
                            conn.getStatements(classes[zipfIdx()], null, null)) {
                while (r.hasNext()) {
                    r.next();
                    n++;
                }
            }
        }
        return n;
    }

    private int zipfIdx() {
        return Math.min(
                classes.length - 1,
                (int) (classes.length * Math.pow(ThreadLocalRandom.current().nextDouble(), 2.0)));
    }
}
