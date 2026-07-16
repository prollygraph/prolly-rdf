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
 * Phase 1 Step 4 of {@code plans/prepublic/sparql-baseline-cardinality-aware.md} — the W3C
 * correctness gate for cardinality-aware variable ordering.
 *
 * <p>Identical to {@link ProllySparql11QueryTriejoinComplianceTest} except the Sail <b>also</b> has
 * {@code triejoinCardinalityOrder(true)}: every routed cyclic BGP is ordered by {@code
 * SelectivityVariableOrder} instead of the provisional first-appearance order. This is exactly the
 * post-Step-4 production config (triejoin on + cardinality ordering on). It shares the <b>same
 * known-failures baseline</b> as the bind-join and triejoin-only suites — so if the cardinality
 * order makes any previously-passing W3C query diverge (or throw on a query shape {@code
 * SelectivityVariableOrder} mishandles), this test fails (the conformance ratchet). Passing proves
 * the ordering is answer-invariant across the full SPARQL 1.1 query corpus — the gate for flipping
 * the operator-property default on.
 */
public class ProllySparql11QueryTriejoinCardinalityComplianceTest
        extends SPARQL11QueryComplianceTest {

    public ProllySparql11QueryTriejoinCardinalityComplianceTest() {
        KnownFailures.load("/known-failures/sparql11-query.txt").forEach(this::addIgnoredTest);
    }

    @Override
    protected Repository newRepository() throws Exception {
        ProllySail sail = new ProllySail();
        sail.setTriejoinEnabled(true); // route cyclic BGPs through the triejoin
        sail.setTriejoinCardinalityOrder(true); // ...ordered by cardinality (the Step-4 gate)
        SailRepository repo = new SailRepository(sail);
        repo.init();
        return repo;
    }
}
