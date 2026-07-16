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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 of {@code plans/unify-rdf-encoding-on-term-codec.md} — the honest wall-time
 * RE-measurement (ADR-0035 D-10). Step 16 found the raw-IRI triejoin ~80× slower than RDF4J at
 * N=380 (variable-length keys + per-query projection). Now the triejoin rides ProllySail's
 * <b>fixed-width TermId</b> indexes; this measures whether that closes the gap, against ProllySail
 * SPARQL and RDF4J {@code MemoryStore} SPARQL on a triangle over a dense core.
 *
 * <p>Indicative timing (NOT JMH); asserts the three arms agree on the count, prints µs. Measured,
 * not asserted — the point is to SEE the numbers.
 */
class TriejoinTermIdCrossoverTest {

    private static final String E = "urn:e";
    private static final String G = "urn:g";
    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void triangleCrossoverOnTermIdIndexes(@TempDir Path dir) throws java.io.IOException {
        String sparql =
                "SELECT ?x ?y ?z WHERE { GRAPH <"
                        + G
                        + "> { "
                        + "?x <"
                        + E
                        + "> ?y . ?y <"
                        + E
                        + "> ?z . ?z <"
                        + E
                        + "> ?x } }";
        List<QuadPattern> tri =
                List.of(
                        QuadPattern.of("?x", E, "?y", G),
                        QuadPattern.of("?y", E, "?z", G),
                        QuadPattern.of("?z", E, "?x", G));
        System.out.println(
                "[TermId crossover — indicative µs, min of 4]  N(edges)  triejoin  prolly-SPARQL  rdf4j-mem");

        for (int k : new int[] {6, 10, 14, 20}) { // complete digraph on k vertices
            List<int[]> edges = denseCore(k);
            Path sub = java.nio.file.Files.createDirectories(dir.resolve("k" + k));

            InMemoryNodeStore store = new InMemoryNodeStore();
            HeapBufferPool pool = new HeapBufferPool();
            ProllySail sail =
                    new ProllySail(
                            store,
                            pool,
                            RootMetaTreeStore.beside(sub),
                            CommitLog.beside(sub),
                            RefsStore.beside(sub),
                            false);
            Repository prolly = new SailRepository(sail);
            Repository mem = new SailRepository(new MemoryStore());
            prolly.init();
            mem.init();
            try {
                load(prolly, edges);
                load(mem, edges);

                Dictionary dict =
                        new Dictionary(
                                sail.store(),
                                sail.pool(),
                                sail.hashFn(),
                                sail.dictRoot(),
                                com.earasoft.prolly.rdf4j.term.EncoderMetrics.noop());
                StaticMap spoc = sail.indexRoot(QuadOrder.SPOC),
                        posc = sail.indexRoot(QuadOrder.POSC);

                try (DirectBufferPool tjPool = new DirectBufferPool()) {
                    long cTrie =
                            new LeapfrogTriejoin(
                                            tri,
                                            ORDER,
                                            spoc,
                                            posc,
                                            SpocKey.DESCRIPTOR,
                                            tjPool,
                                            dict)
                                    .solve()
                                    .size();
                    long cProlly = sparqlCount(prolly, sparql);
                    long cMem = sparqlCount(mem, sparql);
                    assertEquals(cMem, cTrie, "triejoin count vs MemoryStore @k=" + k);
                    assertEquals(cMem, cProlly, "prolly SPARQL count vs MemoryStore @k=" + k);

                    long tTrie =
                            timeUs(
                                    () ->
                                            new LeapfrogTriejoin(
                                                            tri,
                                                            ORDER,
                                                            spoc,
                                                            posc,
                                                            SpocKey.DESCRIPTOR,
                                                            tjPool,
                                                            dict)
                                                    .solve()
                                                    .size());
                    long tProlly = timeUs(() -> sparqlCount(prolly, sparql));
                    long tMem = timeUs(() -> sparqlCount(mem, sparql));
                    System.out.printf(
                            "                                            %4d   %8d   %12d   %9d%n",
                            edges.size(), tTrie, tProlly, tMem);
                }
            } finally {
                prolly.shutDown();
                mem.shutDown();
            }
        }
    }

    private static List<int[]> denseCore(int k) {
        List<int[]> e = new ArrayList<>();
        for (int a = 0; a < k; a++) for (int b = 0; b < k; b++) if (a != b) e.add(new int[] {a, b});
        return e;
    }

    private static void load(Repository repo, List<int[]> edges) {
        try (RepositoryConnection c = repo.getConnection()) {
            ValueFactory vf = c.getValueFactory();
            IRI e = vf.createIRI(E), g = vf.createIRI(G);
            c.begin();
            for (int[] ed : edges)
                c.add(vf.createIRI("urn:v" + ed[0]), e, vf.createIRI("urn:v" + ed[1]), g);
            c.commit();
        }
    }

    private static long sparqlCount(Repository repo, String q) {
        long n = 0;
        try (RepositoryConnection c = repo.getConnection();
                TupleQueryResult r = c.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
            while (r.hasNext()) {
                r.next();
                n++;
            }
        }
        return n;
    }

    private static long timeUs(LongSupplier work) {
        for (int i = 0; i < 2; i++) work.getAsLong();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            long t0 = System.nanoTime();
            work.getAsLong();
            best = Math.min(best, (System.nanoTime() - t0) / 1_000);
        }
        return best;
    }
}
