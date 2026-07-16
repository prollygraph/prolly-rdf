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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;

/**
 * Phase 0 Step 2 of {@code prolly-rdf4j-test-strategy.md} — confirms the {@link RdfValueGen} /
 * {@link QuadGen} / {@link OpStreamGen} generators produce well-formed RDF4J values (the substrate
 * the later property phases consume). No Sail involved — this only validates shape, not behaviour.
 */
class RdfGenSmokeProperty {

    @Property(tries = 300)
    void irisAreNonNull(@ForAll @From("iris") IRI iri) {
        assertNotNull(iri.stringValue());
        assertTrue(iri.stringValue().startsWith("urn:test:"));
    }

    @Property(tries = 500)
    void literalsHaveDatatypeAndLabel(@ForAll @From("literals") Literal lit) {
        assertNotNull(lit.getLabel());
        assertNotNull(lit.getDatatype()); // langString ⇒ rdf:langString
        if (lit.getLanguage().isPresent()) {
            assertTrue(!lit.getLanguage().get().isEmpty());
        }
        assertTrue(lit.getLabel().length() <= 64 || lit.getDatatype() != null);
    }

    @Property(tries = 300)
    void quotedTriplesAreThreePartsDeep(@ForAll @From("triples") Triple t) {
        assertNotNull(t.getSubject());
        assertNotNull(t.getPredicate());
        assertNotNull(t.getObject());
    }

    @Property(tries = 400)
    void statementsAreComplete(@ForAll @From("statements") Statement s) {
        assertNotNull(s.getSubject());
        assertNotNull(s.getPredicate());
        assertNotNull(s.getObject());
        // context may be null (default graph) — that is valid.
    }

    @Property(tries = 200)
    void opStreamsAreNonEmptyAndShaped(@ForAll @From("opStreams") List<OpStreamGen.Op> ops) {
        assertTrue(!ops.isEmpty());
        for (OpStreamGen.Op op : ops) {
            switch (op.kind()) {
                case ADD, REMOVE -> assertNotNull(op.statement());
                case CLEAR, COMMIT, ROLLBACK -> {
                    /* statement null is fine */
                }
            }
        }
    }

    @Provide
    Arbitrary<IRI> iris() {
        return RdfValueGen.iris();
    }

    @Provide
    Arbitrary<Literal> literals() {
        return RdfValueGen.literals();
    }

    @Provide
    Arbitrary<Triple> triples() {
        return RdfValueGen.quotedTriples();
    }

    @Provide
    Arbitrary<Value> objects() {
        return RdfValueGen.objects();
    }

    @Provide
    Arbitrary<Statement> statements() {
        return QuadGen.statements();
    }

    @Provide
    Arbitrary<List<OpStreamGen.Op>> opStreams() {
        return OpStreamGen.opStreams();
    }
}
