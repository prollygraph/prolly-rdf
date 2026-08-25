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
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Collection;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL11QueryComplianceTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;

/**
 * Pins the <b>engine-independence ruling</b> behind the {@code constructwhere04} baseline entry:
 * the test's manifest declares NO dataset, the harness therefore loads nothing, and passing would
 * require the ENGINE to dereference the query's own {@code FROM <data.ttl>} document IRI — which no
 * RDF4J store does ({@code FROM} dereferencing is implementation-defined per SPARQL 1.1, and silent
 * IRI fetching inside a store is an SSRF-shaped non-feature by deliberate ruling, 2026-08-25
 * conformance round).
 *
 * <p>The ruling stands only as long as the reference implementation agrees, so this test runs the
 * W3C test against BOTH stores and asserts they fail <em>together</em>: if an RDF4J upgrade ever
 * makes {@code MemoryStore} pass (upstream implements dataset-document resolution), this fails and
 * forces the ruling — and the baseline entry — to be revisited. The prolly half doubles as the
 * must-shrink audit for the entry.
 */
class FrontierEngineIndependenceTest {

    private static final String TEST_NAME = ": constructwhere04 - CONSTRUCT WHERE";

    private static final class ProllySuite extends SPARQL11QueryComplianceTest {
        @Override
        protected Repository newRepository() {
            return ProllyComplianceRepository.fresh();
        }
    }

    private static final class MemorySuite extends SPARQL11QueryComplianceTest {
        @Override
        protected Repository newRepository() {
            return new SailRepository(new MemoryStore());
        }
    }

    private static boolean fails(Collection<DynamicTest> tests) {
        for (DynamicTest dt : tests) {
            if (dt.getDisplayName().endsWith(TEST_NAME)) {
                try {
                    dt.getExecutable().execute();
                    return false;
                } catch (Throwable expected) {
                    return true;
                }
            }
        }
        return fail("constructwhere04 not found in the approved manifest — upstream drift");
    }

    @Test
    void constructwhere04FailsOnTheReferenceImplementationToo() {
        assertTrue(
                fails(new ProllySuite().tests()),
                "constructwhere04 PASSES on ProllySail — remove its baseline entry and this"
                        + " ruling");
        assertTrue(
                fails(new MemorySuite().tests()),
                "constructwhere04 PASSES on RDF4J MemoryStore — upstream now resolves"
                        + " FROM-document IRIs; the engine-independence ruling no longer holds."
                        + " Revisit the baseline entry and docs/conformance-frontier.md");
    }
}
