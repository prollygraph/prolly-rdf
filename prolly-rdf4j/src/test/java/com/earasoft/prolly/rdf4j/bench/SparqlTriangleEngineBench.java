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

import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

/**
 * Cheap measure-first experiment ("TrieJoin on flatsail?"): does flatsail's bind-join already
 * handle the <b>cyclic triangle</b> fast — making a triejoin port to flatsail pointless? Times the
 * same default-graph triangle SPARQL over the same dense-core data on four engines: RDF4J
 * MemoryStore, flatsail (bind-join), ProllySail bind-join, ProllySail triejoin. {@code main()}
 * tool, min-of-k indicative (not a JMH verdict).
 */
public final class SparqlTriangleEngineBench {

    public static void main(String[] args) throws Exception {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 380;
        Set<TriejoinVsRdf4jBenchmark.Edge> edges = TriejoinVsRdf4jBenchmark.denseCore(size);
        String ed = "<" + TriejoinVsRdf4jBenchmark.EDGE + ">";
        String q =
                "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z . ?z " + ed + " ?x }";
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));

        System.out.printf(
                "[SPARQL cyclic triangle @N=%d edges — min-of-6 INDICATIVE]%n", edges.size());
        bench("rdf4j-memory    ", MemoryStore::new, edges, q);
        bench("flatsail (bind) ", () -> newFlat(tmp), edges, q);
        bench(
                "prolly bind-join",
                () -> {
                    ProllySail s = new ProllySail();
                    s.setTriejoinEnabled(false);
                    return s;
                },
                edges,
                q);
        bench(
                "prolly triejoin ",
                () -> {
                    ProllySail s = new ProllySail();
                    s.setTriejoinEnabled(true);
                    return s;
                },
                edges,
                q);
    }

    private static Sail newFlat(Path tmp) {
        try {
            return new RocksDbFlatSail(Files.createTempDirectory(tmp, "flat-triangle"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void bench(
            String label,
            Supplier<Sail> sailFactory,
            Set<TriejoinVsRdf4jBenchmark.Edge> edges,
            String q) {
        SailRepository repo = new SailRepository(sailFactory.get());
        repo.init();
        long count = 0, best = Long.MAX_VALUE;
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(TriejoinVsRdf4jBenchmark.EDGE);
            conn.begin();
            long c = 0;
            for (TriejoinVsRdf4jBenchmark.Edge edge : edges) {
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
            for (int i = 0; i < 8; i++) { // first runs warm; min picks the warmed one
                long t0 = System.nanoTime();
                count = run(conn, q);
                best = Math.min(best, System.nanoTime() - t0);
            }
        } finally {
            repo.shutDown();
        }
        System.out.printf("  %s  %6d results   %7.2f ms%n", label, count, best / 1e6);
    }

    private static long run(RepositoryConnection conn, String q) {
        try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }

    private SparqlTriangleEngineBench() {}
}
