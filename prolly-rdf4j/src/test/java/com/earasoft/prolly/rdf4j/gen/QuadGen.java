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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;

/**
 * Phase 0 Step 2 of {@code prolly-rdf4j-test-strategy.md} — generated quads (statements with
 * contexts, including the default graph). Built on {@link RdfValueGen}. Contexts are drawn from a
 * <b>small fixed set</b> so many statements share a graph (making context-isolation tests, S-5,
 * non-trivial), with ~1/3 of statements landing in the default graph (no context).
 */
public final class QuadGen {

    private QuadGen() {}

    /** Context IRIs from a small pool, with the default graph injected as null. */
    public static Arbitrary<Resource> contexts() {
        return Arbitraries.of("g1", "g2", "g3")
                .map(n -> (Resource) RdfValueGen.VF.createIRI("urn:test:graph:" + n))
                .injectNull(0.34);
    }

    /**
     * A statement; null context ⇒ default graph (3-arg create). May carry an RDF-star quoted triple
     * in subject/object.
     */
    public static Arbitrary<Statement> statements() {
        return statements(RdfValueGen.subjects(), RdfValueGen.objects());
    }

    /**
     * Statements safe for the <b>differential oracle</b> (S-2): subjects are IRIs/BNodes or
     * RDF-star quoted triples over stable components, objects are lexically-stable terms
     * (IRI/BNode/plain/lang — {@link RdfValueGen#stableObjects}) or such quoted triples. RDF-star
     * joined the oracle when the round-3 write-path wiring landed (2026-08-25 — {@code
     * RdfStarIngestGapTest}'s gap pin flipped to parity); typed literals stay excluded, inside
     * quoted triples too — their lexical fidelity is S-3 / Step 8's question, not structural
     * equivalence.
     */
    public static Arbitrary<Statement> differentialStatements() {
        Arbitrary<org.eclipse.rdf4j.model.Triple> stableQuoted =
                Combinators.combine(
                                RdfValueGen.resources(),
                                RdfValueGen.iris(),
                                RdfValueGen.stableObjects())
                        .as(RdfValueGen.VF::createTriple);
        Arbitrary<Resource> subjects =
                Arbitraries.oneOf(RdfValueGen.resources(), stableQuoted.map(t -> (Resource) t));
        Arbitrary<org.eclipse.rdf4j.model.Value> objects =
                Arbitraries.oneOf(
                        RdfValueGen.stableObjects(),
                        stableQuoted.map(t -> (org.eclipse.rdf4j.model.Value) t));
        return statements(subjects, objects);
    }

    private static Arbitrary<Statement> statements(
            Arbitrary<? extends org.eclipse.rdf4j.model.Resource> subjects,
            Arbitrary<? extends org.eclipse.rdf4j.model.Value> objects) {
        return Combinators.combine(subjects, RdfValueGen.iris(), objects, contexts())
                .as(
                        (s, p, o, c) ->
                                c == null
                                        ? RdfValueGen.VF.createStatement(s, p, o)
                                        : RdfValueGen.VF.createStatement(s, p, o, c));
    }
}
