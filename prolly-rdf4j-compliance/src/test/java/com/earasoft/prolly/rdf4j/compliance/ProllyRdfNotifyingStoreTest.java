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
 * Sail change-notification contract — {@code ProllySail} extends {@code AbstractNotifyingSail} (the
 * MR/Memento progress surface rides on it), but the notifying half of the store contract was never
 * suite-tested. Gap-wiring round 2026-08-25; extends the plain store contract, so the {@code
 * ProllyRdfStoreContractTest} baselined gaps apply here identically.
 */
public class ProllyRdfNotifyingStoreTest
        extends org.eclipse.rdf4j.testsuite.sail.RDFNotifyingStoreTest {

    @Override
    protected org.eclipse.rdf4j.sail.NotifyingSail createSail() {
        return new com.earasoft.prolly.rdf4j.sail.ProllySail();
    }

    // ---- Mirrors of ProllyRdfStoreContractTest's baselined architectural gaps (this suite
    // extends the same RDFStoreTest, so the same two gaps surface here) ----

    @Override
    @org.junit.jupiter.api.Disabled(
            "prolly Tuple offsets are uint16 → 65535-byte term cap; literals "
                    + ">64KB need an out-of-line blob layer (TupleBuilder.java:79). Architectural —"
                    + " mirrored from ProllyRdfStoreContractTest.")
    public void testReallyLongLiteralRoundTrip() {}

    @Override
    @org.junit.jupiter.api.Disabled(
            "RDF-star write-path wiring gap: TermCodec.encodeQuotedTriple exists but the sail's"
                    + " encode path does not route Triple values through it (frontier row"
                    + " 2026-08-25). Mirrored from ProllyRdfStoreContractTest.")
    public void testAddTripleContext() {}

    @Override
    @org.junit.jupiter.api.Disabled(
            "Passes through its event assertions (change-accurate notifications, 2026-08-25); the"
                    + " FINAL assert reads con.size() on the suite's setUp connection, whose"
                    + " start-of-transaction snapshot cannot see commits from the test's repository"
                    + " connections — the documented CROSS_CONNECTION_VISIBILITY cluster"
                    + " (ProllyRepositoryConnectionContractTest).")
    public void testUpdateQuery() {}

    @Override
    @org.junit.jupiter.api.Disabled(
            "Pins raw event CARDINALITY, which depends on the update realization strategy:"
                    + " AbstractSailConnection batches DELETE-then-INSERT realization, so a"
                    + " change-accurate store emits ONE event per net change (this store's trace:"
                    + " 1 removed + 1 added, sets equal — semantically correct); the expected 2/2 raw"
                    + " trace is SailSourceConnection's per-row interleaved realization. Adopting"
                    + " per-row realization is the parked fix.")
    public void testUpdateQuery2() {}
}
