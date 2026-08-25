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
 * RDF-star support contract — wired to MEASURE the star frontier (the fixed-width SpocKey/TermId
 * codec has no triple-term slot today), not to assert it closed. Failures here are triaged into the
 * conformance frontier as a category, exactly like the SPARQL 1.2 suite. Gap-wiring round
 * 2026-08-25.
 */
public class ProllyRdfStarSupportTest
        extends org.eclipse.rdf4j.testsuite.repository.RDFStarSupportTest {

    @Override
    protected org.eclipse.rdf4j.repository.Repository createRepository() {
        return ProllyComplianceRepository.fresh();
    }
}
