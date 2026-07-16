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

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
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
 * <h3>ProllySail full-scan read throughput</h3>
 *
 * <p>Pre-loads {@code datasetSize} triples once, then measures an unfiltered {@code
 * getStatements(null, null, null)} cursor scan over the whole branch. This is the read-side
 * counterpart to {@link SailIngestBenchmark}.
 *
 * <p>Run via {@link JmhRunner} (e.g. {@code JmhRunner SailScan}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 3)
// 3 forks: JIT compilation-plan variance is real run-to-run noise; one fork hides it (methodology
// D-6).
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class SailScanBenchmark {

    /** Number of triples pre-loaded into the branch before scanning. */
    @Param({"10000", "100000"})
    int datasetSize;

    private ProllySail sail;

    @Setup(Level.Trial)
    public void setUp() {
        sail = new ProllySail();
        sail.init();
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            for (int i = 0; i < datasetSize; i++) {
                conn.addStatement(
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

    @TearDown(Level.Trial)
    public void tearDown() {
        sail.shutDown();
    }

    @Benchmark
    public long fullScan() {
        long n = 0;
        try (SailConnection conn = sail.getConnection();
                CloseableIteration<? extends Statement> it =
                        conn.getStatements(null, null, null, false)) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }
}
