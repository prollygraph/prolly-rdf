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
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
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
 * <h3>Ingest benchmark on a real dataset — the NCIt ontology.</h3>
 *
 * <p>Replaces the synthetic dense-core ingest ({@link SailComparisonBenchmark}) with real-world
 * RDF: a bounded <b>sample</b> of {@code ncit.owl} (NCI Thesaurus OBO Edition, RDF/XML, ~811 MB
 * full) streamed from {@code test_ontologies_zips/ncit.zip}. We parse only the first {@code
 * sampleSize} statements (stop early — not the whole 811 MB), once, then time ingesting that sample
 * into a fresh disk-backed Sail per engine (ProllySail / flatsail / RDF4J NativeStore). Real
 * ontologies have blank nodes, annotation axioms, and skewed term reuse the synthetic generator
 * lacks — so this measures the ingest path on representative data.
 *
 * <p>Run via {@link JmhRunner}; point {@code -Djava.io.tmpdir} at real disk (the stores are
 * disk-backed and the {@code /tmp} tmpfs is quota-limited). Override the zip path with {@code
 * -Dncit.zip=/path/to/ncit.zip}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 4, time = 3)
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class NcitIngestBenchmark {

    @Param({"prolly", "flatsail", "rdf4j-native"})
    String engine;

    @Param({"25000"})
    int sampleSize;

    /** Parsed once per sampleSize across all engines (the parse is not what we're measuring). */
    private static final Map<Integer, List<Statement>> SAMPLES = new ConcurrentHashMap<>();

    private List<Statement> sample;
    private Path dir;
    private SailRepository repo;

    @Setup(Level.Trial)
    public void loadSample() {
        sample = SAMPLES.computeIfAbsent(sampleSize, NcitIngestBenchmark::parseSample);
    }

    @Setup(Level.Invocation)
    public void freshStore() throws IOException, org.rocksdb.RocksDBException {
        dir = Files.createTempDirectory("ncit-ingest");
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
    public void drop() throws IOException {
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

    @Benchmark
    public long ingestNcitSample() {
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            long c = 0;
            for (Statement st : sample) {
                conn.add(st);
                // Batched commits bound memory — never a single-tx mega-transaction (OOMs at
                // scale).
                if (++c % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
        return sample.size();
    }

    // ---- parse a bounded sample of ncit.owl from the zip (stop early) ----

    private static final class StopParsing extends RuntimeException {
        StopParsing() {
            super(null, null, false, false);
        }
    }

    private static List<Statement> parseSample(int n) {
        List<Statement> out = new ArrayList<>(n);
        try (ZipFile zip = new ZipFile(ncitZip().toFile())) {
            ZipEntry entry = zip.getEntry("ncit.owl");
            if (entry == null) throw new IllegalStateException("ncit.owl not found in zip");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1 << 16)) {
                RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                parser.setRDFHandler(
                        new AbstractRDFHandler() {
                            @Override
                            public void handleStatement(Statement st) {
                                out.add(st);
                                if (out.size() >= n)
                                    throw new StopParsing(); // stop — don't read all 811 MB
                            }
                        });
                try {
                    parser.parse(in, "http://purl.obolibrary.org/obo/ncit.owl");
                } catch (StopParsing done) {
                    /* reached n */
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static Path ncitZip() {
        String prop = System.getProperty("ncit.zip");
        if (prop != null) return Path.of(prop);
        for (String c :
                new String[] {
                    "test_ontologies_zips/ncit.zip", "../test_ontologies_zips/ncit.zip"
                }) {
            Path p = Path.of(c);
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("ncit.zip not found; pass -Dncit.zip=/path/to/ncit.zip");
    }
}
