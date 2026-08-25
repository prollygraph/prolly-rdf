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

/**
 * SPARQL Update through the repository API — the imperative counterpart to the manifest update
 * suite.
 *
 * <p>Gap-wiring round 2026-08-25: this RDF4J contract suite existed in the dependency but was never
 * wired to the {@code ProllySail}-backed store. Same ratchet as every wired suite: a test not
 * explicitly {@code @Disabled}/baselined that starts failing fails the build.
 */
public class ProllySparqlUpdateContractTest
        extends org.eclipse.rdf4j.testsuite.query.parser.sparql.SPARQLUpdateTest {

    @Override
    protected org.eclipse.rdf4j.repository.Repository newRepository() {
        return ProllyComplianceRepository.fresh();
    }

    @Override
    @org.junit.jupiter.api.Disabled(
            "Cross-connection visibility: a connection forks a snapshot at open and does not see"
                    + " a commit that lands mid-transaction (the documented"
                    + " ProllyRepositoryConnectionContractTest CROSS_CONNECTION_VISIBILITY cluster —"
                    + " this is its SPARQL-Update face: con2, opened before con.commit, must observe"
                    + " the commit the moment it happens).")
    public void testAutoCommitHandling() {}
}
