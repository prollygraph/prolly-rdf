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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL10QueryComplianceTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;

/**
 * The living half of the SPARQL 1.0 {@code dataset-*} baseline ruling ({@code
 * known-failures/sparql10-query.txt}): those eight DAWG tests carry their datasets in the query's
 * own FROM/FROM NAMED clauses, so passing requires engine-side document-IRI dereferencing — the
 * same engine-independent family as {@code constructwhere04} ({@link
 * FrontierEngineIndependenceTest}). This gate re-verifies the parity every build: each baselined
 * name must fail on BOTH this store and RDF4J's MemoryStore. A test that starts passing on either
 * store fails here and forces the baseline — and the ruling — to be revisited.
 */
class Sparql10DatasetEngineIndependenceTest {

    private static final class ProllySuite extends SPARQL10QueryComplianceTest {
        @Override
        protected Repository newRepository() {
            return ProllyComplianceRepository.fresh();
        }
    }

    private static final class MemorySuite extends SPARQL10QueryComplianceTest {
        @Override
        protected Repository newRepository() {
            return new SailRepository(new MemoryStore());
        }
    }

    private static List<String> nowPassing(Collection<DynamicTest> all, List<String> names) {
        List<String> passing = new ArrayList<>();
        for (String n : names) {
            for (DynamicTest dt : all) {
                if (dt.getDisplayName().endsWith(": " + n) || dt.getDisplayName().equals(n)) {
                    try {
                        dt.getExecutable().execute();
                        passing.add(n + "  [" + dt.getDisplayName() + "]");
                    } catch (Throwable stillFailing) {
                        // exactly what the ruling expects
                    }
                }
            }
        }
        return passing;
    }

    @Test
    void everyBaselinedDatasetTestFailsOnBothEngines() {
        List<String> names = KnownFailures.load("/known-failures/sparql10-query.txt");
        List<String> prolly = nowPassing(new ProllySuite().tests(), names);
        assertTrue(
                prolly.isEmpty(),
                "baselined SPARQL 1.0 tests now PASS on ProllySail — shrink the baseline"
                        + " (remove each line + lower QUERY10_MAX): "
                        + prolly);
        List<String> memory = nowPassing(new MemorySuite().tests(), names);
        assertTrue(
                memory.isEmpty(),
                "baselined SPARQL 1.0 tests now PASS on RDF4J MemoryStore — the"
                        + " engine-independence ruling no longer holds for: "
                        + memory);
    }
}
