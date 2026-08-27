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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.value.DictionaryTermEncoder;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.OWL;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The same IRI must resolve to the same {@link TermId} in every store.</b>
 *
 * <p>This is a CONTRACT test guarding a property that consumers are about to build on, not a
 * regression test for a bug that happened. A downstream ontology editor plans a class-index
 * collector that works entirely in TermId space, and its index is built over a <b>closure</b> — the
 * repo's own snapshot unioned with one opened member per pin, where each member is a separate store
 * with its own {@link Dictionary}. Unioning TermId-space scans across those stores is only sound if
 * an IRI's TermId does not depend on which store interned it.
 *
 * <p>Today it does not, and the reason is incidental rather than designed: {@code TermEncoder}
 * emits only the {@code 0x82} FULL-IRI form, so an IRI's encoded bytes — and therefore its hash —
 * are independent of the store's {@code PrefixTable}. That encoder's own javadoc says a
 * PrefixTable-aware encoder preferring the {@code 0x80} short-prefix form <b>arrives later
 * (Phase 2 Sail integration)</b>.
 *
 * <p><b>That change would break this silently.</b> A prefix-compressed IRI encodes differently
 * depending on the interning store's prefix table, so the same IRI would carry different TermIds in
 * two stores — and nothing would throw. A cross-store union would simply lose the edges whose two
 * ends were interned in different stores, producing an index that is quietly incomplete. This test
 * exists so that lands as a red test rather than as a wrong ontology months later.
 *
 * <p>If you are here because this test failed after making the encoder prefix-aware: the property
 * is genuinely gone, and every cross-store TermId consumer needs a per-store translation step.
 * Do not delete this test — change it to assert the new contract and tell the consumers.
 *
 * <p>A second, narrower hazard is <b>not</b> covered here because it is not deterministically
 * reproducible: {@code Dictionary.encode} walks a salt chain on hash collision, so a term that
 * collides in one store and not another lands in a different slot. At realistic corpus sizes that
 * is vanishingly rare (~2e-7 at ~2M terms) but it is silent when it happens.
 */
class TermIdCrossStorePortabilityTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static final TupleDescriptor SCHEMA_INT64 =
            new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));

    /** Deliberately mixed: a long shared-prefix IRI, a well-known vocabulary term, a short one, a URN. */
    private static final List<String> SHARED = List.of(
            "http://purl.obolibrary.org/obo/NCIT_C107687",
            "http://www.w3.org/2000/01/rdf-schema#label",
            "http://example.org/a",
            "urn:x#Zebra");

    @Test
    void theSameIriResolvesToTheSameTermIdInTwoDifferentStores(@TempDir Path dir) throws Exception {
        // The two stores are given deliberately different surrounding content, so their
        // dictionaries differ in size and insertion order. Store A's 200 same-namespace fillers are
        // the shape that WOULD trigger prefix compression if the encoder used it — which is exactly
        // the future condition this test is guarding against.
        long[] a = idsAfterIngest(dir.resolve("a"), 200, "http://purl.obolibrary.org/obo/");
        long[] b = idsAfterIngest(dir.resolve("b"), 5, "http://unrelated.example/ns#");

        for (int i = 0; i < SHARED.size(); i++) {
            String iri = SHARED.get(i);
            assertNotEquals(Long.MIN_VALUE, a[i], iri + " was not interned in store A");
            assertNotEquals(Long.MIN_VALUE, b[i], iri + " was not interned in store B");
            assertEquals(a[i], b[i],
                    "TermId for " + iri + " differs between stores (A=" + Long.toHexString(a[i])
                            + " B=" + Long.toHexString(b[i]) + ") — a cross-store TermId union is"
                            + " no longer sound; see this test's javadoc");
        }
    }

    private static long[] idsAfterIngest(Path dir, int filler, String fillerNs) throws Exception {
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            HeapBufferPool pool = new HeapBufferPool();
            SailRepository repo = new SailRepository(new ProllySail(store, pool,
                    RootMetaTreeStore.beside(dir), CommitLog.beside(dir), RefsStore.beside(dir)));
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                for (String s : SHARED) {
                    IRI iri = VF.createIRI(s);
                    conn.add(iri, RDF.TYPE, OWL.CLASS);
                    conn.add(iri, RDFS.LABEL, VF.createLiteral("shared"));
                }
                for (int i = 0; i < filler; i++) {
                    IRI f = VF.createIRI(fillerNs + "Filler" + i);
                    conn.add(f, RDF.TYPE, OWL.CLASS);
                    conn.add(f, RDFS.LABEL, VF.createLiteral("filler " + i));
                }
                conn.commit();
            }
            repo.shutDown();

            byte[] head = RootMetaTreeStore.beside(dir).get()
                    .orElseThrow(() -> new IllegalStateException("no committed metatree"));
            RootMetaTree mt = RootMetaTree.readFrom(store, head).orElseThrow();
            StaticMap dictMap = mt.hashOf(RootMetaTree.NAME_DICT)
                    .map(h -> new StaticMap(store,
                            Node.fromBytes(store.read(h).orElseThrow()), SCHEMA_INT64))
                    .orElseThrow(() -> new IllegalStateException("no dictionary root"));
            Dictionary dict = new Dictionary(store, pool, HashFunctions.defaultHash(), dictMap);

            long[] out = new long[SHARED.size()];
            try (Arena arena = Arena.ofConfined()) {
                for (int i = 0; i < SHARED.size(); i++) {
                    out[i] = DictionaryTermEncoder
                            .findTermId(VF.createIRI(SHARED.get(i)), dict, arena)
                            .map(TermId::value).orElse(Long.MIN_VALUE);
                }
            }
            return out;
        }
    }
}
