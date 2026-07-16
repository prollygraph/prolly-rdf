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
import java.time.Duration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * CPU flame profile of the <b>flag-ON SPARQL triangle</b> through ProllySail — to localize what
 * dilutes the triejoin's engine-level win down to ~1.12× end-to-end (Phase 4 of
 * triejoin-evaluation-wiring.md). Shows the split between the triejoin {@code solve()} (the
 * algorithm), the per-row decode + {@code BindingSet} build (the suspected shared/SPARQL-layer
 * tax), and RDF4J's parse/optimize/iterate machinery. A {@code main()} tool (JFR repository on real
 * disk; see {@link CpuFlameProfiler}).
 */
public final class SparqlTriejoinFlameProfile {

    public static void main(String[] args) throws Exception {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 380;
        ProllySail sail = new ProllySail();
        sail.setTriejoinEnabled(true);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        ValueFactory vf = repo.getValueFactory();
        IRI e = vf.createIRI(TriejoinVsRdf4jBenchmark.EDGE);
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (TriejoinVsRdf4jBenchmark.Edge edge : TriejoinVsRdf4jBenchmark.denseCore(size)) {
                conn.add(
                        vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.from())),
                        e,
                        vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.to())));
            }
            conn.commit();
        }
        String ed = "<" + TriejoinVsRdf4jBenchmark.EDGE + ">";
        String q =
                "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z . ?z " + ed + " ?x }";

        Runnable work =
                () -> {
                    try (RepositoryConnection conn = repo.getConnection();
                            TupleQueryResult r =
                                    conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
                        while (r.hasNext()) r.next();
                    }
                };

        CpuFlameProfiler.Result res =
                CpuFlameProfiler.profile(
                        "sparql-triejoin-triangle",
                        Duration.ofMillis(800),
                        Duration.ofSeconds(4),
                        work);
        System.out.printf(
                "[flag-ON SPARQL triangle @N=%d] %d samples → %s%n",
                size, res.samples(), res.svg());
        System.out.println("[top self-CPU frames]");
        res.topSelf()
                .forEach(
                        fc ->
                                System.out.printf(
                                        "  %5d self  %5d total  %s%n",
                                        fc.selfSamples(), fc.totalSamples(), fc.frame()));
        repo.shutDown();
    }

    private SparqlTriejoinFlameProfile() {}
}
