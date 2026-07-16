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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
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
 *
 *
 * <h3>Read benchmark on a real dataset — the NCIt ontology.</h3>
 *
 * <p>Phase 2 of {@code plans/ncit-comprehensive-benchmark.md}. Loads a <b>500k-statement sample</b>
 * of the real NCIt ontology (all three engines can hold it; the full 10.8M can't be loaded by
 * ProllySail — Phase 1) once in {@code @Setup}, then benchmarks the four read shapes over the same
 * real, diverse data: point lookup (a real class IRI), predicate scans ({@code rdfs:label}, full
 * scan), and a 2-pattern join (subclass → superclass label). Confirms whether the <i>read</i>
 * sweet-spots from synthetic data (ProllySail wins scans; native wins point/joins) hold on real
 * ontology data.
 *
 * <p><b>Single fork</b> (`@Fork(1)`): the {@code @Setup} load is expensive (~7–11 s/engine), so
 * per-fork re-loading is impractical; the cross-engine read <i>ratios</i> in one fork are the
 * signal. Run via {@link JmhRunner}; real-disk {@code -Djava.io.tmpdir}; {@code -Dncit.zip=…} for
 * the corpus.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 2)
@Measurement(iterations = 4, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class NcitReadBenchmark {

    @Param({"prolly", "flatsail", "rdf4j-native", "lmdb"})
    String engine;

    @Param({"500000"})
    int sampleSize;

    /** Node-cache byte budget for the prolly engine; 0 = off (read-path Step 2 A/B knob). */
    @Param({"0"})
    long nodeCacheBytes;

    /**
     * Decode-cache max entries for the prolly engine; 0 = off (read-path Step 3 A/B knob). Measure
     * its MARGINAL gain with the node cache ON in both arms (set {@code nodeCacheBytes} > 0) — the
     * decode cache only saves the dictionary tree-walk + value-wrap the node cache leaves on the
     * CPU.
     */
    @Param({"0"})
    int termCacheSize;

    /**
     * Index count for the RDF4J B-tree engines (NativeStore + LMDB); ignored by prolly/flatsail
     * (structural 4). A comma-free TOKEN (JMH splits {@code -p} values on commas, so the literal
     * {@code "spoc,posc,ospc,cspo"} spec cannot be a param value): {@code "2idx"} (default) = their
     * real 2-index default {@code spoc,posc} — the out-of-the-box comparison; {@code "4idx"} = the
     * matched {@code spoc,posc,ospc,cspo}, for an apples-to-apples read comparison (isolates
     * substrate from index count — the current read shapes only use SPOC/POSC, so matching is
     * expected to be ~inert, which is the point worth proving).
     */
    @Param({"2idx"})
    String nativeIndexes;

    private static String indexSpec(String token) {
        return "4idx".equals(token) ? "spoc,posc,ospc,cspo" : "spoc,posc";
    }

    /**
     * Bind-join inner-re-probe memo for the prolly engine (productionize-the-cache.md). Default off
     * so the historical cross-engine matrix is unchanged; set {@code true} to compare ProllySail in
     * its production config (memo + node cache) against the other engines. Ignored by non-prolly
     * engines.
     */
    @Param({"false"})
    boolean bindJoinMemo;

    private Path dir;
    private SailRepository repo;
    private IRI pointTarget; // a real class IRI present in the loaded sample

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("ncit-read");
        org.eclipse.rdf4j.sail.Sail sail =
                StreamingNcitIngest.buildSail(
                        engine, dir, nodeCacheBytes, indexSpec(nativeIndexes));
        if (sail instanceof com.earasoft.prolly.rdf4j.sail.ProllySail ps) {
            if (bindJoinMemo) ps.setBindJoinMemoEnabled(true); // productionized read config
            if (termCacheSize > 0) ps.setTermCacheSize(termCacheSize); // Step 3 A/B knob
        }
        repo = new SailRepository(sail);
        repo.init();
        StreamingNcitIngest.streamLoad(repo, sampleSize, 100_000);
        // Pick a real subject guaranteed present: the first subClassOf subject in the loaded data.
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, RDFS.SUBCLASSOF, null)) {
            pointTarget = (IRI) r.next().getSubject();
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        repo.shutDown();
        StreamingNcitIngest.deleteTree(dir);
    }

    /** Point lookup — every statement about one real class. */
    @Benchmark
    public long pointLookup() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(pointTarget, null, null)) {
            return count(r);
        }
    }

    /** Predicate scan — every rdfs:label triple. */
    @Benchmark
    public long labelScan() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, RDFS.LABEL, null)) {
            return count(r);
        }
    }

    /** Full scan — every statement in the sample. */
    @Benchmark
    public long fullScan() {
        try (RepositoryConnection conn = repo.getConnection();
                RepositoryResult<Statement> r = conn.getStatements(null, null, null)) {
            return count(r);
        }
    }

    /** A 2-pattern acyclic join: each class's superclass and that superclass's label. */
    @Benchmark
    public long subclassLabelJoin() {
        String q =
                "SELECT ?c ?l WHERE { "
                        + "?c <"
                        + RDFS.SUBCLASSOF
                        + "> ?s . ?s <"
                        + RDFS.LABEL
                        + "> ?l }";
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }

    private static long count(RepositoryResult<Statement> r) {
        long n = 0;
        while (r.hasNext()) {
            r.next();
            n++;
        }
        return n;
    }
}
