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
package com.earasoft.prolly.rdf4j.gen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op;
import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op.Kind;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 0 Step 3 of {@code prolly-rdf4j-test-strategy.md} — smoke for the {@link
 * SailDifferentialHarness}: a hand-coded op stream applied to both Sails proves they accept the
 * same ops and that the comparators run. (The generated property assertions are Phase 1, Steps
 * 5–7.)
 */
class SailDifferentialHarnessSmokeTest {

    private static final ValueFactory VF = RdfValueGen.VF;
    private static final IRI A = VF.createIRI("urn:test:a");
    private static final IRI P = VF.createIRI("urn:test:p");
    private static final IRI B = VF.createIRI("urn:test:b");
    private static final IRI G1 = VF.createIRI("urn:test:graph:g1");

    @Test
    void bothSailsStayInLockstepOnAHandCodedStream(@TempDir Path dir) {
        Statement s1 = VF.createStatement(A, P, B); // default graph
        Statement s2 = VF.createStatement(A, P, B, G1); // named graph
        Statement s3 = VF.createStatement(B, P, A);

        List<Op> ops =
                List.of(
                        new Op(Kind.ADD, s1, null),
                        new Op(Kind.ADD, s2, null),
                        new Op(Kind.ADD, s3, null),
                        new Op(Kind.COMMIT, null, null),
                        new Op(Kind.REMOVE, s3, null),
                        new Op(Kind.COMMIT, null, null));

        try (SailDifferentialHarness h = new SailDifferentialHarness(dir)) {
            h.applyAll(ops);
            assertTrue(h.statementsAgree(), "statement sets must agree across both Sails");
            assertTrue(h.sizeAgrees(), "size() must agree");
            assertTrue(h.contextsAgree(), "getContextIDs() must agree");
            assertTrue(
                    h.bindingsAgree("SELECT ?s ?p ?o WHERE { ?s ?p ?o }"),
                    "SPARQL binding multiset must agree");
            assertTrue(h.prollySize() == 2, "two statements remain (s1, s2) after removing s3");
        }
    }

    @Test
    void rollbackDiscardsOnBothSails(@TempDir Path dir) {
        try (SailDifferentialHarness h = new SailDifferentialHarness(dir)) {
            h.applyAll(
                    List.of(
                            new Op(Kind.ADD, VF.createStatement(A, P, B), null),
                            new Op(Kind.ROLLBACK, null, null)));
            assertTrue(h.sizeAgrees() && h.prollySize() == 0, "rollback leaves both empty");
        }
    }
}
