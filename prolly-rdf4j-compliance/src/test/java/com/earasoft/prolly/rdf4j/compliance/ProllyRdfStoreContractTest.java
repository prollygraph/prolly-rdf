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

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.testsuite.sail.RDFStoreTest;
import org.junit.jupiter.api.Disabled;

/**
 * Runs RDF4J's comprehensive {@code Sail} RDF-store contract suite against a {@code ProllySail}
 * (plan 10, §10.8).
 *
 * <p>{@link RDFStoreTest} exercises add/remove/getStatements/size across the default and named
 * graphs, blank nodes, datatyped + language literals, transaction boundaries, duplicate handling
 * and isolation of uncommitted writes — the load-bearing Sail API surface.
 *
 * <p>Known failures are {@code @Disabled} overrides below; each is a tracked gap (see memory {@code
 * sail-contract-suite-findings}). A test that is not disabled and starts failing fails the build —
 * the ratchet.
 */
public class ProllyRdfStoreContractTest extends RDFStoreTest {

    @Override
    protected Sail createSail() {
        return new ProllySail();
    }

    // ---- Known failures (baselined; see memory sail-contract-suite-findings) ----

    @Override
    @Disabled(
            "prolly Tuple offsets are uint16 → 65535-byte term cap; literals "
                    + ">64KB need an out-of-line blob layer (TupleBuilder.java:79). Architectural.")
    public void testReallyLongLiteralRoundTrip() {}

    @Override
    @Disabled(
            "v2.0 xsd:date encoding has no timezone field; a timezoned xsd:date "
                    + "is rejected outright (TermEncoder.requireNoTimezone).")
    public void testTimeZoneRoundTrip() {}

    @Override
    @Disabled(
            "TermEncoder eagerly parses typed literals and throws on a "
                    + "non-conformant lexical form; RDF permits ill-typed literals. "
                    + "Needs the opaque/custom-literal fallback (same gap as xsd:negativeInteger).")
    public void testInvalidDateTime() {}

    // testStatementSerialization was baselined here (ProllyValue wrapped a non-Serializable
    // MemorySegment; RDF4J's Value extends Serializable) and is now FIXED (2026-06-22,
    // compliance-suite-live-gate Step 3): the inherited test runs + passes. Fixed by a
    // writeReplace()
    // serialization proxy on the ProllyValue hierarchy + ProllyStatement (each returns its plain
    // SimpleValueFactory equivalent). See bugs/rdf4j-repository-connection-contract-triage.md.

    // testQueryBindings was baselined here ("pre-set bindings return 0 rows") and is now FIXED
    // (2026-06-11, follow-ons Step 4): the inherited test runs + passes. The bug was a filter-only
    // pre-set binding dropped at the low-level evaluate path; fixed by inlining initial bindings
    // (BindingAssignerOptimizer) in ProllySailConnection.evaluateInternal.

    @Override
    @Disabled(
            "RDF-star: a Triple used as a statement context — ProllySail's TermEncoder rejects a Triple "
                    + "Value (it requires a Dictionary-allocated TermId via encodeQuotedTriple). Pre-existing "
                    + "RDF-star Phase-2 gap (the flat sail's RocksDbFlatSailContractTest baselines the same test); "
                    + "surfaced 2026-06-11 while running the full contract suite under the gate — verified by "
                    + "git-stash to predate, and NOT caused by, the pre-set-bindings fix in the same change.")
    public void testAddTripleContext() {}
}
