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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.value.DictionaryTermResolver;
import java.lang.foreign.Arena;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;

/**
 * Phase 2 Step 8 of {@code prolly-rdf4j-test-strategy.md} — the <b>term-codec round-trip</b>
 * property (S-3). Drives the documented pipeline {@code
 * DictionaryTermResolver.resolve(Dictionary.encode(TermEncoder.encode(v)))} over generated RDF4J
 * values and asserts the value survives. Two fidelity classes (the distinction Steps 5–7 deferred
 * here):
 *
 * <ul>
 *   <li><b>Lexically exact</b> — IRIs, BNodes, plain strings, lang strings: the resolved value
 *       {@code .equals} the original (label + datatype + lang preserved byte-for-byte).
 *   <li><b>Value-preserving</b> — typed XSD literals: the encoder is <i>canonicalizing</i> (e.g.
 *       {@code "1.0"^^xsd:double} → {@code "1.0E0"}), so lexical {@code .equals} need not hold;
 *       instead {@code encode∘resolve} is <b>idempotent</b> — re-encoding the resolved value yields
 *       the same dictionary {@link TermId}, i.e. the same value. This characterizes the
 *       canonicalization as intended (cf. the-termid-ordering-trap doc), not a bug.
 * </ul>
 *
 * <p>Excludes RDF-star + custom datatypes — {@code TermEncoder.encode} needs a Dictionary to
 * allocate component / datatype-IRI TermIds for those (a different pipeline); they are not part of
 * this self-contained round-trip.
 */
class TermCodecRoundTripProperty {

    private static DictionaryTermResolver resolver(Dictionary d, NodeStore s, BufferPool p) {
        return new DictionaryTermResolver(d, new PrefixTable(s, p));
    }

    @Property(tries = 400)
    void iriBnodeStringRoundTripExactly(@ForAll @From("lexStable") Value v) {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        Dictionary d = new Dictionary(store, pool, HashFunctions.defaultHash());
        try (Arena a = Arena.ofConfined()) {
            TermId id = d.encode(TermEncoder.encode(v, a));
            Value back = resolver(d, store, pool).resolve(id);
            assertEquals(v, back, "IRI/BNode/string/langString must round-trip exactly: " + v);
        }
    }

    @Property(tries = 400)
    void typedLiteralsRoundTripPreserveValue(@ForAll @From("typed") Literal v) {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        Dictionary d = new Dictionary(store, pool, HashFunctions.defaultHash());
        try (Arena a = Arena.ofConfined()) {
            TermId id1 = d.encode(TermEncoder.encode(v, a));
            Value back = resolver(d, store, pool).resolve(id1);
            assertInstanceOf(Literal.class, back, "a typed literal resolves to a Literal: " + v);
            TermId id2 = d.encode(TermEncoder.encode(back, a));
            assertEquals(
                    id1,
                    id2,
                    "encode∘resolve must be idempotent (value preserved; lexical may canonicalize): "
                            + v);
        }
    }

    @Provide
    Arbitrary<Value> lexStable() {
        return Arbitraries.oneOf(
                RdfValueGen.iris().map(v -> (Value) v),
                RdfValueGen.bnodes().map(v -> v),
                RdfValueGen.plainLiterals().map(v -> v),
                RdfValueGen.langLiterals().map(v -> v));
    }

    @Provide
    Arbitrary<Literal> typed() {
        return RdfValueGen.typedLiterals();
    }
}
