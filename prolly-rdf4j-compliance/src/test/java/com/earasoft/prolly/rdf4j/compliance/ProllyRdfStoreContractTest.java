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
    // (2026-08-25 audit: testTimeZoneRoundTrip + testInvalidDateTime removed as STALE — fixed by
    // ADR-0043 in June; testAddTripleContext removed when the RDF-star write-path wiring landed.)

    @Override
    @Disabled(
            "prolly Tuple offsets are uint16 → 65535-byte term cap; literals "
                    + ">64KB need an out-of-line blob layer (TupleBuilder.java:79). Architectural.")
    public void testReallyLongLiteralRoundTrip() {}
}
