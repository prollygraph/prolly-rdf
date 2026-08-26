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

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 26 of {@code prolly-rdf4j-test-strategy.md} (invariant S-11) — the <b>shrink-only gate</b>
 * on the W3C known-failures baseline. The conformance frontier is governed, not hidden: it may
 * shrink freely (fix a failure, delete its line) but may never <b>grow silently</b>.
 *
 * <p>Each baseline is capped at a pinned maximum equal to its current size. The cap going down is
 * the visible record that the frontier shrank; the cap can only go <i>up</i> by a deliberate,
 * reviewed edit here — which is exactly "a new accepted conformance gap is never silent". This
 * complements the existing ratchet (a non-baselined test that starts failing is not skipped, so it
 * fails the build — new failures can't appear without being fixed or deliberately baselined) and
 * the categorization table in {@code docs/conformance-frontier.md} (every baselined entry has a
 * category + roadmap/ruling).
 *
 * <p>Lives in the (gated) compliance module, so it runs with {@code -Dprolly.compliance.skip=false}
 * alongside the W3C suites — but unlike them it only reads the baseline resource, so it is instant.
 * Its twin — <b>must-shrink</b> detection (forcing a <i>fixed</i> failure <i>out</i> of the
 * baseline by running the baselined tests un-skipped and asserting each still fails) — is wired
 * separately in {@link MustShrinkBaselineTest} (follow-ons plan Step 2, 2026-06-11). Together they
 * make the frontier neither grow silently (here) nor stop shrinking silently (there).
 */
class KnownFailuresBaselineTest {

    /**
     * Pinned caps — equal to each baseline's current size. Lower when a failure is fixed; raising
     * one is a deliberate act that must add a categorization row to {@code
     * docs/conformance-frontier.md}.
     */
    private static final int QUERY_MAX =
            1; // shrunk 5→2 on 2026-06-12 (ADR-0043 fixed TZ()/TIMEZONE() + tsv03);

    // shrunk 2→1 on 2026-08-25 (graph-scoped path evaluation fixed pp35)

    private static final int UPDATE_MAX = 0;

    /**
     * SPARQL 1.0 (DAWG) baseline cap — eight dataset-* tests whose datasets live in the query's
     * FROM/FROM NAMED clauses (engine-independent: the MemoryStore parity probe fails all eight
     * identically; see the baseline file's header). Shrinks like QUERY_MAX.
     */
    private static final int QUERY10_MAX = 8;

    /** SPARQL 1.1 SYNTAX baseline cap — the suite is 160/160; the file exists for governance. */
    private static final int SYNTAX_MAX = 0;

    @Test
    void query_baseline_may_only_shrink() {
        List<String> baseline = KnownFailures.load("/known-failures/sparql11-query.txt");
        assertTrue(
                baseline.size() <= QUERY_MAX,
                "the SPARQL-query known-failures baseline may only SHRINK: it has "
                        + baseline.size()
                        + " entries but the pinned cap is "
                        + QUERY_MAX
                        + ". A larger baseline means a NEW accepted "
                        + "conformance gap — raise QUERY_MAX deliberately (and add a row to "
                        + "docs/conformance-frontier.md), never silently. Entries: "
                        + baseline);
    }

    @Test
    void update_baseline_may_only_shrink() {
        List<String> baseline = KnownFailures.load("/known-failures/sparql11-update.txt");
        assertTrue(
                baseline.size() <= UPDATE_MAX,
                "the SPARQL-update known-failures baseline may only SHRINK: it has "
                        + baseline.size()
                        + " entries but the pinned cap is "
                        + UPDATE_MAX
                        + " (the update suite is 90/90 clean). "
                        + "Entries: "
                        + baseline);
    }

    @org.junit.jupiter.api.Test
    void sparql10BaselineWithinCap() {
        java.util.List<String> baseline = KnownFailures.load("/known-failures/sparql10-query.txt");
        org.junit.jupiter.api.Assertions.assertTrue(
                baseline.size() <= QUERY10_MAX,
                "sparql10-query baseline has "
                        + baseline.size()
                        + " entries, cap is "
                        + QUERY10_MAX
                        + " — a NEW SPARQL 1.0 conformance gap; raise QUERY10_MAX deliberately"
                        + " (and update known-failures/sparql10-query.txt's header) or fix the"
                        + " regression.");
    }

    @org.junit.jupiter.api.Test
    void syntaxBaselineWithinCap() {
        java.util.List<String> baseline = KnownFailures.load("/known-failures/sparql11-syntax.txt");
        org.junit.jupiter.api.Assertions.assertTrue(
                baseline.size() <= SYNTAX_MAX,
                "sparql11-syntax baseline has "
                        + baseline.size()
                        + " entries, cap is "
                        + SYNTAX_MAX
                        + " — a parser conformance regression; fix it or raise SYNTAX_MAX"
                        + " deliberately.");
    }
}
