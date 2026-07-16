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
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code rdf:langString} language-tag canonicalization (commit 80288bde) explicitly — the
 * coverage that {@code RdfValueGen.langLiterals()} used to provide before it was made to generate
 * canonical (lower-case) tags so the store-<i>parity</i> differential tests compare on agreeing
 * inputs.
 *
 * <p>RDF 1.1 §3.3: the value space of language-tagged strings holds the tag in lower case, so
 * {@code @en-US} and {@code @en-us} are the <b>same value</b>. The codec must (a) resolve the tag
 * canonicalized to lower case, and (b) — the load-bearing property — assign value-equal langStrings
 * the <b>same</b> {@link TermId} (one content address), so a merge cannot over- or under-count
 * them.
 */
class TermCodecCanonicalizationTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    @Test
    void mixedCaseLanguageTag_canonicalizesToLowerCase_andSharesOneContentAddress() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        Dictionary d = new Dictionary(store, pool, HashFunctions.defaultHash());
        DictionaryTermResolver resolver =
                new DictionaryTermResolver(d, new PrefixTable(store, pool));

        try (Arena a = Arena.ofConfined()) {
            TermId upper = d.encode(TermEncoder.encode(VF.createLiteral("hello", "en-US"), a));
            TermId lower = d.encode(TermEncoder.encode(VF.createLiteral("hello", "en-us"), a));

            // (b) value equality -> ONE content address (the real point: no over/under-merge).
            assertEquals(
                    lower,
                    upper,
                    "en-US and en-us are the same RDF value, so they must encode to the same TermId");

            // (a) the tag resolves canonicalized to lower case.
            Literal back = (Literal) resolver.resolve(upper);
            assertEquals("hello", back.getLabel());
            assertEquals(
                    "en-us",
                    back.getLanguage().orElseThrow(),
                    "the language tag resolves in lower case (RDF 1.1 §3.3 value space)");
        }
    }
}
