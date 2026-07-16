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
 * <h3>ProllySail ingest throughput</h3>
 *
 * <p>Measures one write transaction: {@code begin} → {@code addStatement} ×N → {@code commit},
 * against an in-memory store. This is the metric that governs how fast a dataset loads — divide
 * {@code batchSize} by the reported ms/op for triples-per-millisecond.
 *
 * <p>A fresh Sail per invocation isolates each batch from the cost of an ever-growing tree; run
 * with a {@code Level.Trial} variant if you instead want the add-to-a-large-tree cost.
 *
 * <p>Run via {@link JmhRunner} (e.g. {@code JmhRunner SailIngest}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 3)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class SailIngestBenchmark {

    /** Triples committed per measured batch. */
    @Param({"1000", "10000"})
    int batchSize;

    private ProllySail sail;

    @Setup(Level.Invocation)
    public void setUp() {
        // No-arg ctor: in-memory InMemoryNodeStore + HeapBufferPool.
        sail = new ProllySail();
        sail.init();
    }

    @TearDown(Level.Invocation)
    public void tearDown() {
        sail.shutDown();
    }

    @Benchmark
    public void ingestBatch() {
        ValueFactory vf = sail.getValueFactory();
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            for (int i = 0; i < batchSize; i++) {
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
}
