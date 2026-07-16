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
import org.eclipse.rdf4j.testsuite.query.parser.sparql.manifest.SPARQL11QueryComplianceTest;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * The <b>must-shrink half</b> of the W3C conformance ratchet (follow-ons plan Step 2, invariant
 * S-11) — the audit that a <em>fixed</em> failure cannot quietly linger in the known-failures
 * baseline.
 *
 * <p>{@link KnownFailuresBaselineTest} pins the <em>may-only-shrink</em> direction (the baseline
 * size may never silently grow). This is its missing twin: it runs every baselined W3C query test
 * <b>un-skipped</b> and asserts each <b>still genuinely fails</b>. A baselined test that now
 * <i>passes</i> is a <b>stale entry</b> — the bug was fixed but the skip was left behind — and
 * fails this gate, forcing the line out of {@code known-failures/sparql11-query.txt} (and its
 * {@code docs/conformance-frontier.md} row, and a {@code QUERY_MAX} drop). Together the two halves
 * make the frontier <em>governed</em>: it can neither grow silently nor stop shrinking silently.
 *
 * <p><b>Why this exists (the refutation that motivated it).</b> On 2026-06-11 the
 * conformance-baseline refresh (follow-ons Step 1) found that {@code tsv03} had been
 * <i>documented</i> as failing for a reason that was no longer true — its real blocker was a custom
 * datatype, not the long-since-fixed {@code xsd:negativeInteger}. The entry was still genuinely
 * failing, so it was not removed; but the episode showed that the baseline's <i>rationale</i> can
 * drift from reality undetected, because the existing ratchet only watches membership, never
 * re-checks whether a listed failure is <i>still</i> a failure. This gate closes that gap
 * mechanically: it would have caught a {@code tsv03} that had actually been fixed, on the next run,
 * without anyone remembering to re-check by hand.
 *
 * <p><b>How it runs only the handful (D-2).</b> {@code SPARQLQueryComplianceTest.getTestData}
 * builds one {@code DynamicTest} per <i>approved</i> manifest entry, each a lazy executable that
 * runs the real W3C test and <b>throws</b> on failure / completes on pass. We build that collection
 * from a {@link SPARQL11QueryComplianceTest} subclass that — unlike the production {@link
 * ProllySparql11QueryComplianceTest} — does <b>not</b> apply the project baseline, so the baselined
 * tests are present and runnable. We then execute <i>only</i> the baselined names (matched against
 * the {@code "<dir>: <name>"} display names) and inspect each outcome. Building the collection
 * parses the manifest once; only the ~5 baselined executables actually run.
 *
 * <p><b>Collaborators.</b> {@link KnownFailures} (loads the baseline resource the production suite
 * skips on); {@link ProllyComplianceRepository#fresh()} (the {@code ProllySail}-backed store under
 * test, reused verbatim from the production suite); RDF4J's {@code SPARQL11QueryComplianceTest}
 * manifest machinery. It does <b>not</b> touch {@link ComplianceFailureListener}: the baselined
 * tests fail <i>inside</i> our {@code try/catch}, so their throws never surface as JUnit results
 * and never pollute {@code target/compliance-failures.txt} — this gate's own pass/fail is the only
 * signal it emits.
 *
 * <p><b>apiNote</b> — lives in the gated compliance module, so it only runs under {@code
 * -Dprolly.compliance.skip=false}, alongside (but independent of) the green suite, which still
 * <i>skips</i> the baselined tests and stays green. The SPARQL-<i>update</i> baseline is empty (and
 * capped at {@code UPDATE_MAX=0}), so must-shrink there is vacuous — not wired until an update
 * entry ever exists.
 */
class MustShrinkBaselineTest {

    /**
     * A W3C SPARQL-1.1 <em>query</em> suite with <b>no project baseline applied</b> — identical to
     * {@link ProllySparql11QueryComplianceTest} except it does not call {@code addIgnoredTest}, so
     * every approved manifest test (including the ones the production suite skips) is generated and
     * runnable. (RDF4J's own three {@code defaultIgnoredTests} — the two RDF-1.1-incompatible
     * type-error cases and {@code sq03} — remain ignored upstream; none of this project's baseline
     * entries is among them.)
     */
    private static final class UnbaselinedW3cQuerySuite extends SPARQL11QueryComplianceTest {
        @Override
        protected Repository newRepository() throws Exception {
            return ProllyComplianceRepository.fresh();
        }
    }

    private enum Outcome {
        FAILED,
        PASSED,
        ABORTED
    }

    /**
     * Run one manifest test directly and classify: threw ⇒ still failing (good); completed ⇒ now
     * passes (stale); upstream-aborted ⇒ unverifiable.
     */
    private static Outcome run(DynamicTest dt) {
        try {
            dt.getExecutable().execute();
            return Outcome.PASSED;
        } catch (TestAbortedException aborted) {
            return Outcome.ABORTED;
        } catch (Throwable failed) {
            return Outcome.FAILED;
        }
    }

    @Test
    void every_baselined_query_test_still_genuinely_fails() {
        List<String> baseline = KnownFailures.load("/known-failures/sparql11-query.txt");
        Collection<DynamicTest> all = new UnbaselinedW3cQuerySuite().tests();

        List<String> stale = new ArrayList<>(); // baselined, but the test now PASSES
        List<String> drifted =
                new ArrayList<>(); // baselined, but no approved manifest test matches the name
        List<String> aborted =
                new ArrayList<>(); // baselined, but upstream-ignored so we cannot audit it

        for (String name : baseline) {
            List<DynamicTest> matches = new ArrayList<>();
            for (DynamicTest dt : all) {
                String dn = dt.getDisplayName();
                if (dn.equals(name) || dn.endsWith(": " + name)) {
                    matches.add(dt);
                }
            }
            if (matches.isEmpty()) {
                drifted.add(name);
                continue;
            }
            for (DynamicTest dt : matches) {
                switch (run(dt)) {
                    case PASSED -> stale.add(name + "  [" + dt.getDisplayName() + "]");
                    case ABORTED -> aborted.add(name + "  [" + dt.getDisplayName() + "]");
                    case FAILED -> {
                        /* still failing — exactly what a known-failure should do */
                    }
                }
            }
        }

        StringBuilder msg = new StringBuilder();
        if (!stale.isEmpty()) {
            msg.append(
                            "\nSTALE baseline entries — these W3C tests now PASS but are still listed as known\n")
                    .append("failures. The baseline must SHRINK: remove each line from\n")
                    .append(
                            "known-failures/sparql11-query.txt, delete its docs/conformance-frontier.md row, and\n")
                    .append("lower KnownFailuresBaselineTest.QUERY_MAX to match:\n");
            stale.forEach(s -> msg.append("  - ").append(s).append('\n'));
        }
        if (!drifted.isEmpty()) {
            msg.append(
                            "\nDRIFTED baseline entries — these names match NO approved manifest test (renamed or\n")
                    .append(
                            "removed upstream?). Correct or remove them in known-failures/sparql11-query.txt:\n");
            drifted.forEach(s -> msg.append("  - ").append(s).append('\n'));
        }
        if (!aborted.isEmpty()) {
            msg.append(
                            "\nUNVERIFIABLE baseline entries — upstream-ignored, so must-shrink cannot audit them.\n")
                    .append("Re-check by hand whether they still fail:\n");
            aborted.forEach(s -> msg.append("  - ").append(s).append('\n'));
        }

        assertTrue(stale.isEmpty() && drifted.isEmpty() && aborted.isEmpty(), msg.toString());
    }
}
