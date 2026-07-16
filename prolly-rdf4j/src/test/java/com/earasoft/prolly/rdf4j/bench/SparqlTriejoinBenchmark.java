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
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
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
 * Step 11 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md} — end-to-end perf validation
 * of the wired-in routing: does the engine-level cyclic win survive the full SPARQL layer (parse →
 * optimize → route → triejoin eval → decode → BindingSet)? And does an acyclic query stay on the
 * bind-join with no regression?
 *
 * <ul>
 *   <li>{@code query=triangle} (cyclic): flag ON routes through the triejoin; flag OFF is the
 *       bind-join. Expect ON &lt; OFF.
 *   <li>{@code query=path2} (acyclic): not routed either way → bind-join. Expect ON ≈ OFF (no
 *       regression).
 * </ul>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 4, time = 2)
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class SparqlTriejoinBenchmark {

    @Param({"true", "false"})
    boolean triejoin;

    @Param({"triangle", "path2"})
    String query;

    @Param({"380"})
    int size;

    private SailRepository repo;
    private String sparql;

    @Setup(Level.Trial)
    public void setUp() {
        ProllySail sail = new ProllySail(); // in-memory: the join cost is CPU, not disk
        sail.setTriejoinEnabled(triejoin);
        repo = new SailRepository(sail);
        repo.init();
        ValueFactory vf = repo.getValueFactory();
        IRI e = vf.createIRI(TriejoinVsRdf4jBenchmark.EDGE);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            long c = 0;
            for (TriejoinVsRdf4jBenchmark.Edge edge : TriejoinVsRdf4jBenchmark.denseCore(size)) {
                conn.add(
                        vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.from())),
                        e,
                        vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.to())));
                // Batched commits bound memory — never a single-tx mega-transaction (OOMs at
                // scale).
                if (++c % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
        String ed = "<" + TriejoinVsRdf4jBenchmark.EDGE + ">";
        sparql =
                switch (query) {
                    case "triangle" ->
                            "SELECT ?x ?y ?z WHERE { ?x "
                                    + ed
                                    + " ?y . ?y "
                                    + ed
                                    + " ?z . ?z "
                                    + ed
                                    + " ?x }";
                    case "path2" -> "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z }";
                    default -> throw new IllegalArgumentException(query);
                };
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        repo.shutDown();
    }

    @Benchmark
    public long evaluate() {
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, sparql).evaluate()) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }
}
