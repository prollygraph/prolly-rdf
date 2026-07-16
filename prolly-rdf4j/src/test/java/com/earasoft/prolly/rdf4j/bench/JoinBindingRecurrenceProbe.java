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
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of {@code plans/join-approaches-benchmark.md} — the deciding measurement. For the
 * representative join {@code ?c rdfs:subClassOf ?s . ?s rdfs:label ?l}, the bind-join evaluates the
 * inner pattern ({@code ?s rdfs:label ?l}) once per outer {@code (?c, ?s)} row. This probe
 * measures, on real NCIt data, how often the inner key {@code ?s} <b>recurs</b> — the number that
 * decides which approach the workload wants:
 *
 * <ul>
 *   <li><b>recurrence = total / distinct</b> ≫ 1 → keys recur → a bindings-result memo has a high
 *       hit rate ({@code (total−distinct)/total}); cheap, likely the win.
 *   <li>recurrence ≈ 1 → distinct keys → a memo is dead weight; the lever is sort-merge (order).
 * </ul>
 *
 * <p>No instrumentation of the join is needed — the recurrence is a property of the data, read off
 * with two SPARQL counts. Run: {@code mvn -pl prolly-rdf4j test -Dtest=JoinBindingRecurrenceProbe
 * -Dncit.zip=/path/to/ncit.zip [-Dprobe.sample=50000]}.
 */
public class JoinBindingRecurrenceProbe {

    private static final String SUBCLASS = "<http://www.w3.org/2000/01/rdf-schema#subClassOf>";
    private static final String LABEL = "<http://www.w3.org/2000/01/rdf-schema#label>";
    private static final String TYPE = "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type>";

    @Test
    public void measureInnerBindingRecurrence() throws Exception {
        String zip = System.getProperty("ncit.zip");
        Assumptions.assumeTrue(
                zip != null && Files.exists(Path.of(zip)), "set -Dncit.zip=/path/to/ncit.zip");
        int sample = Integer.getInteger("probe.sample", 50_000);

        Path dir = Files.createTempDirectory("ncit-join-probe");
        SailRepository repo = new SailRepository(StreamingNcitIngest.buildSail("prolly", dir));
        repo.init();
        StreamingNcitIngest.streamLoad(repo, sample, 100_000);

        try (RepositoryConnection conn = repo.getConnection()) {
            System.out.println(
                    "\n========= JOIN INNER-KEY RECURRENCE (sample="
                            + sample
                            + " statements) =========");

            // --- subclassLabelJoin: inner key = ?s (the superclass) ---
            long total = count(conn, "SELECT (COUNT(*) AS ?n) WHERE { ?c " + SUBCLASS + " ?s }");
            long distinct =
                    count(
                            conn,
                            "SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { ?c " + SUBCLASS + " ?s }");
            report("subClassOf join — inner key ?s (superclass)", total, distinct);

            // --- a high-fan-in contrast: ?x rdf:type ?t (inner key = ?t) ---
            long tTotal = count(conn, "SELECT (COUNT(*) AS ?n) WHERE { ?x " + TYPE + " ?t }");
            long tDistinct =
                    count(conn, "SELECT (COUNT(DISTINCT ?t) AS ?n) WHERE { ?x " + TYPE + " ?t }");
            report("rdf:type join — inner key ?t (the class)", tTotal, tDistinct);

            // --- a distinct-ish contrast: label keyed by subject ?s ---
            long lTotal = count(conn, "SELECT (COUNT(*) AS ?n) WHERE { ?s " + LABEL + " ?l }");
            long lDistinct =
                    count(conn, "SELECT (COUNT(DISTINCT ?s) AS ?n) WHERE { ?s " + LABEL + " ?l }");
            report("label lookup — inner key ?s (subject)", lTotal, lDistinct);

            System.out.println(
                    "================================================================================\n");
        } finally {
            repo.shutDown();
            StreamingNcitIngest.deleteTree(dir);
        }
    }

    private static void report(String label, long total, long distinct) {
        double recurrence = distinct == 0 ? 0 : (double) total / distinct;
        double memoHit = total == 0 ? 0 : 100.0 * (total - distinct) / total;
        System.out.printf(
                "%-44s total=%,d distinct=%,d  recurrence=%.2fx  memo-hit=%.1f%%%n",
                label, total, distinct, recurrence, memoHit);
    }

    private static long count(RepositoryConnection conn, String sparql) {
        try (TupleQueryResult r = conn.prepareTupleQuery(sparql).evaluate()) {
            return ((Literal) r.next().getValue("n")).longValue();
        }
    }
}
