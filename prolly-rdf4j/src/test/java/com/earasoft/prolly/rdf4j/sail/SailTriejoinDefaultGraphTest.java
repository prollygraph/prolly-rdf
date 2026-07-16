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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TypeCodec;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.value.DictionaryTermResolver;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 enablement of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md}: the triejoin
 * answers a <b>default-graph</b> cyclic query over ProllySail's real indexes and equals ProllySail
 * SPARQL. The PoC ({@link SailTriejoinOnRealIndexesTest}) used a <i>named</i> graph; the MVP routes
 * default-graph BGPs, where ProllySail encodes the null context as the reserved {@code
 * TermId.ZERO}. This pins the matching engine change ({@code LeapfrogTriejoin}: a {@code null}
 * {@code QuadPattern} context seeks {@code ZERO} instead of dictionary-encoding an IRI).
 */
class SailTriejoinDefaultGraphTest {

    private static final String E = "urn:e";

    @Test
    void triejoinDefaultGraphEqualsSparql(@TempDir Path dir) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        ProllySail sail =
                new ProllySail(
                        store,
                        pool,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        Repository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = conn.getValueFactory();
                IRI e = vf.createIRI(E);
                conn.begin();
                // directed 3-cycle in the DEFAULT graph (no context) + a noise edge
                conn.add(vf.createIRI("urn:v0"), e, vf.createIRI("urn:v1"));
                conn.add(vf.createIRI("urn:v1"), e, vf.createIRI("urn:v2"));
                conn.add(vf.createIRI("urn:v2"), e, vf.createIRI("urn:v0"));
                conn.add(vf.createIRI("urn:v0"), e, vf.createIRI("urn:v3"));
                conn.commit();
            }

            Dictionary dict =
                    new Dictionary(
                            sail.store(),
                            sail.pool(),
                            sail.hashFn(),
                            sail.dictRoot(),
                            com.earasoft.prolly.rdf4j.term.EncoderMetrics.noop());
            DictionaryTermResolver resolver =
                    new DictionaryTermResolver(dict, new PrefixTable(store, pool));
            StaticMap spoc = sail.indexRoot(QuadOrder.SPOC);
            StaticMap posc = sail.indexRoot(QuadOrder.POSC);
            // c = null → the default graph (TermId.ZERO), the new engine path
            List<QuadPattern> triangle =
                    List.of(
                            QuadPattern.of("?x", E, "?y", null),
                            QuadPattern.of("?y", E, "?z", null),
                            QuadPattern.of("?z", E, "?x", null));

            Set<List<String>> fromTriejoin = new HashSet<>();
            try (DirectBufferPool tjPool = new DirectBufferPool()) {
                for (Map<String, byte[]> row :
                        new LeapfrogTriejoin(
                                        triangle,
                                        List.of("?x", "?y", "?z"),
                                        spoc,
                                        posc,
                                        SpocKey.DESCRIPTOR,
                                        tjPool,
                                        dict)
                                .solve()) {
                    fromTriejoin.add(
                            List.of(
                                    decode(resolver, row.get("?x")),
                                    decode(resolver, row.get("?y")),
                                    decode(resolver, row.get("?z"))));
                }
            }

            Set<List<String>> fromSparql = new HashSet<>();
            String q =
                    "SELECT ?x ?y ?z WHERE { ?x <"
                            + E
                            + "> ?y . ?y <"
                            + E
                            + "> ?z . ?z <"
                            + E
                            + "> ?x }";
            try (RepositoryConnection conn = repo.getConnection();
                    TupleQueryResult r =
                            conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
                while (r.hasNext()) {
                    BindingSet b = r.next();
                    fromSparql.add(
                            List.of(
                                    b.getValue("x").stringValue(),
                                    b.getValue("y").stringValue(),
                                    b.getValue("z").stringValue()));
                }
            }

            assertTrue(fromSparql.size() >= 3, "the 3-cycle yields its rotations");
            assertEquals(
                    fromSparql,
                    fromTriejoin,
                    "default-graph triejoin must equal ProllySail SPARQL (c=null → TermId.ZERO)");
        } finally {
            repo.shutDown();
        }
    }

    private static String decode(DictionaryTermResolver resolver, byte[] termIdBytes) {
        long v = TypeCodec.readInt64(MemorySegment.ofArray(termIdBytes));
        return resolver.resolve(new TermId(v)).stringValue();
    }
}
