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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL11UpdateComplianceTest;
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL12QueryComplianceTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * Governance completion for the conformance ratchets (roadmap T3) — the pieces {@link
 * MustShrinkBaselineTest} (query-suite only, per its own javadoc) left implicit.
 *
 * <ul>
 *   <li><b>Update-suite must-shrink twin:</b> audits every entry of {@code
 *       known-failures/sparql11-update.txt} un-skipped, exactly like the query twin. Vacuous while
 *       the baseline is empty (90/90) — but the moment a line appears, a fixed-but-listed entry
 *       fails THIS gate instead of lingering.
 *   <li><b>SPARQL-1.2 upgrade tripwire:</b> rdf4j-sparql-testsuite 5.1.4 generates ZERO 1.2 tests
 *       (the manifest ships unapproved), so {@code ProllySparql12QueryComplianceTest} asserts
 *       nothing today. This pin makes the upgrade that populates it a LOUD event: the count
 *       changes, this fails, and the new tests get triaged + baselined deliberately.
 * </ul>
 */
class BaselineGovernanceTest {

    private static final class UnbaselinedUpdateSuite extends SPARQL11UpdateComplianceTest {
        @Override
        protected Repository newRepository() {
            return ProllyComplianceRepository.fresh();
        }
    }

    private static final class Sparql12Suite extends SPARQL12QueryComplianceTest {
        @Override
        protected Repository newRepository() {
            return ProllyComplianceRepository.fresh();
        }
    }

    @Test
    void every_baselined_update_test_still_genuinely_fails() {
        List<String> baseline = KnownFailures.load("/known-failures/sparql11-update.txt");
        if (baseline.isEmpty()) {
            return; // 90/90 — the audit activates with the first baselined entry
        }
        Collection<DynamicTest> all = new UnbaselinedUpdateSuite().getTestData();
        List<String> stale = new ArrayList<>();
        for (String name : baseline) {
            for (DynamicTest dt : all) {
                if (dt.getDisplayName().equals(name) || dt.getDisplayName().endsWith(": " + name)) {
                    try {
                        dt.getExecutable().execute();
                        stale.add(name);
                    } catch (TestAbortedException aborted) {
                        stale.add(name + " [upstream-ignored: unverifiable]");
                    } catch (Throwable stillFailing) {
                        // exactly what a known failure should do
                    }
                }
            }
        }
        assertTrue(
                stale.isEmpty(),
                "STALE update-baseline entries now PASS — shrink the baseline: " + stale);
    }

    @Test
    void sparql12SuiteStillGeneratesZeroTests() {
        assertEquals(
                0,
                new Sparql12Suite().tests().size(),
                "the RDF4J dependency now ships SPARQL 1.2 manifest tests — triage them: run the"
                        + " suite, baseline genuine gaps in a governed known-failures file with a"
                        + " cap, and update this pin to the new approved count. This tripwire"
                        + " exists so 1.2 coverage arrives as a deliberate event, not silence.");
    }
}
