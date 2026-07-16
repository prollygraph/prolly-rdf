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

import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL11UpdateComplianceTest;

/**
 * Runs the W3C Approved SPARQL 1.1 <em>update</em> conformance suite against a {@code ProllySail}
 * (plan 10, §10.5).
 *
 * <p>Exercises the INSERT / DELETE / DELETE-INSERT / LOAD / CLEAR / COPY / MOVE / ADD update paths
 * — the mutating side of the Sail, which the query suite does not touch. The {@code @TestFactory}
 * is inherited from {@link SPARQL11UpdateComplianceTest}; this subclass supplies the store and the
 * known-failures baseline.
 *
 * <p>Known failures live in {@code known-failures/sparql11-update.txt} and are skipped via {@code
 * addIgnoredTest}; an unbaselined test that starts failing fails the build — the conformance
 * ratchet (§10.11).
 */
public class ProllySparql11UpdateComplianceTest extends SPARQL11UpdateComplianceTest {

    public ProllySparql11UpdateComplianceTest() {
        KnownFailures.load("/known-failures/sparql11-update.txt").forEach(this::addIgnoredTest);
    }

    @Override
    protected Repository newRepository() throws Exception {
        return ProllyComplianceRepository.fresh();
    }
}
