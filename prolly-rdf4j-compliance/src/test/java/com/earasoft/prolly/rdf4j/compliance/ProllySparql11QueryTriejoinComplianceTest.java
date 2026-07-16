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
package com.earasoft.prolly.rdf4j.compliance;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL11QueryComplianceTest;

/**
 * Step 9 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md} — the end-to-end correctness
 * gate: the full W3C Approved SPARQL 1.1 <em>query</em> suite with the <b>WCOJ-triejoin routing
 * flag ON</b>.
 *
 * <p>Identical to {@link ProllySparql11QueryComplianceTest} except the Sail has {@code
 * triejoinEnabled(true)}: every eligible cyclic / multi-way default-graph BGP in the W3C corpus is
 * routed through the {@code LeapfrogTriejoin}; everything else stays on RDF4J's bind-join. It
 * shares the <b>same known-failures baseline</b> — so if routing makes any previously-passing query
 * diverge, this test fails (the conformance ratchet), and D-7 eligibility must shrink until green.
 * Passing proves the flag is safe to expose: it never changes a W3C-correct result.
 */
public class ProllySparql11QueryTriejoinComplianceTest extends SPARQL11QueryComplianceTest {

    public ProllySparql11QueryTriejoinComplianceTest() {
        KnownFailures.load("/known-failures/sparql11-query.txt").forEach(this::addIgnoredTest);
    }

    @Override
    protected Repository newRepository() throws Exception {
        ProllySail sail = new ProllySail();
        sail.setTriejoinEnabled(
                true); // route cyclic BGPs through the triejoin (the gate's whole point)
        SailRepository repo = new SailRepository(sail);
        repo.init();
        return repo;
    }
}
