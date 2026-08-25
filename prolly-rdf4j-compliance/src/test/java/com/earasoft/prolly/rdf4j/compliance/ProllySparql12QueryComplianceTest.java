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
 * SPARQL 1.2 / RDF-star query evaluation — wired to MEASURE the star-support frontier, not to
 * assert it closed. NOTE: rdf4j-sparql-testsuite 5.1.4's 1.2 manifest currently yields ZERO
 * generated tests through the upstream factory (run 2026-08-25), so this suite asserts nothing yet
 * — it is wired so the coverage ARRIVES automatically with the dependency upgrade that populates
 * the manifest, rather than being forgotten. The RDF-star frontier is measured today by
 * ProllyRdfStarSupportTest instead.
 *
 * <p>Gap-wiring round 2026-08-25: this RDF4J contract suite existed in the dependency but was never
 * wired to the {@code ProllySail}-backed store. Same ratchet as every wired suite: a test not
 * explicitly {@code @Disabled}/baselined that starts failing fails the build.
 */
public class ProllySparql12QueryComplianceTest
        extends org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest
                .SPARQL12QueryComplianceTest {

    @Override
    protected org.eclipse.rdf4j.repository.Repository newRepository() throws Exception {
        return ProllyComplianceRepository.fresh();
    }
}
