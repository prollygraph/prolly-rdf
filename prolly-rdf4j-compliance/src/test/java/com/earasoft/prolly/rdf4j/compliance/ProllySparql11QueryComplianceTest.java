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
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL11QueryComplianceTest;

/**
 * Runs the W3C Approved SPARQL 1.1 <em>query</em> conformance suite against a {@code ProllySail}
 * (plan 10, §10.4).
 *
 * <p>The {@code @TestFactory} that drives the W3C manifest is inherited from {@link
 * SPARQL11QueryComplianceTest}; this subclass only supplies the store under test and the
 * known-failures baseline.
 *
 * <p>Known failures (categories B/C of plan §10.6 — unimplemented SPARQL features and encoding
 * gaps) live in {@code known-failures/sparql11-query.txt} and are skipped via {@code
 * addIgnoredTest}. A test that is not baselined and starts failing fails the build — the
 * conformance ratchet (§10.11).
 */
public class ProllySparql11QueryComplianceTest extends SPARQL11QueryComplianceTest {

    public ProllySparql11QueryComplianceTest() {
        KnownFailures.load("/known-failures/sparql11-query.txt").forEach(this::addIgnoredTest);
    }

    @Override
    protected Repository newRepository() throws Exception {
        return ProllyComplianceRepository.fresh();
    }
}
