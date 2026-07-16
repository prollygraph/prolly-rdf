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
package com.earasoft.prolly.rdf4j.bench;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.semantic.GraphPatternEngine;
import com.earasoft.prolly.semantic.QuadPattern;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Phase 4 Step 16 of {@code multi-variable-leapfrog-triejoin.md} — <b>JMH wall-time</b> for the
 * multi-variable join, three arms over one logical query:
 *
 * <ul>
 *   <li><b>triejoin-native</b> — {@link GraphPatternEngine#executeMulti} over hand-built prolly
 *       SPOC/POSC indexes (the leapfrog triejoin);
 *   <li><b>rdf4j-memory</b> — the same query as SPARQL over an RDF4J {@link MemoryStore} (RDF4J's
 *       bind-join, in-memory storage);
 *   <li><b>prolly-sail</b> — the same SPARQL over {@link ProllySail} (RDF4J's bind-join, prolly
 *       storage).
 * </ul>
 *
 * <p><b>What each comparison isolates:</b> {@code native} vs {@code prolly-sail} is the
 * <i>algorithm</i> (triejoin vs RDF4J bind-join) over the same storage family; {@code rdf4j-memory}
 * vs {@code prolly-sail} is the <i>storage</i> under the same evaluator. The triangle is where the
 * triejoin is expected to pull ahead (it avoids the O(N²) intermediate a bind-join builds); the
 * 2-hop path and single-variable star are where RDF4J/bind-join stays competitive — and the
 * benchmark is meant to surface that honestly, including where the triejoin <i>loses</i> (small N,
 * acyclic).
 *
 * <p>Not run in the default build — JMH is manual: {@code mvn -pl prolly-rdf4j test-compile && java
 * ... org.openjdk.jmh.Main TriejoinVsRdf4jBenchmark}. The CI-stable correctness cross-check (all
 * three arms agree) lives in {@code TriejoinVsRdf4jAgreementTest}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
// 3 forks: JIT compilation-plan variance is real run-to-run noise; one fork hides it (methodology
// D-6).
@Fork(
        value = 3,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class TriejoinVsRdf4jBenchmark {

    public static final String EDGE = "urn:bench:e";
    public static final String GRAPH = "urn:bench:g";

    @Param({"triejoin-native", "rdf4j-memory", "prolly-sail"})
    String engine;

    @Param({"triangle", "path2", "star"})
    String query;

    @Param({"30", "90", "380"})
    int size; // edge budget for the dense core

    // triejoin-native arm
    private GraphPatternEngine engine_;
    private List<QuadPattern> patterns;
    private List<String> varOrder;
    // SPARQL arms
    private Repository repo;
    private String sparql;
    private Path dir;
    private DirectBufferPool pool;

    @Setup(Level.Trial)
    public void setUp() throws IOException, org.rocksdb.RocksDBException {
        Set<Edge> edges = denseCore(size);
        varOrder = List.of("?x", "?y", "?z");
        patterns = patternsFor(query);
        sparql = sparqlFor(query);

        switch (engine) {
            case "triejoin-native" -> {
                pool = new DirectBufferPool();
                engine_ = buildNativeEngine(edges, pool);
            }
            case "rdf4j-memory" -> repo = buildMemory(edges);
            case "prolly-sail" -> {
                dir = Files.createTempDirectory("triejoin-bench");
                repo = buildProllySail(edges, dir);
            }
            default -> throw new IllegalArgumentException(engine);
        }
    }

    @Benchmark
    public void run(Blackhole bh) {
        if (engine.equals("triejoin-native")) {
            bh.consume(engine_.executeMulti(patterns, varOrder).size());
        } else {
            bh.consume(countSparql(repo, sparql));
        }
    }

    // ---- shared builders (reused by the agreement test) -------------------

    public record Edge(int from, int to) {}

    /** Complete directed graph on ~√n vertices (triangle-dense; no self-loops). */
    public static Set<Edge> denseCore(int n) {
        int k = Math.max(3, (int) Math.floor((1 + Math.sqrt(1 + 4.0 * n)) / 2));
        Set<Edge> edges = new LinkedHashSet<>();
        for (int a = 0; a < k; a++)
            for (int b = 0; b < k; b++) if (a != b) edges.add(new Edge(a, b));
        return edges;
    }

    /** Bidirectional star: hub 0 + m leaves (triangle-free; 2m edges). */
    public static Set<Edge> star(int m) {
        Set<Edge> edges = new LinkedHashSet<>();
        for (int i = 1; i <= m; i++) {
            edges.add(new Edge(0, i));
            edges.add(new Edge(i, 0));
        }
        return edges;
    }

    /** n random directed edges over n vertices (deterministic given seed). */
    public static Set<Edge> sparseRandom(int n, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        Set<Edge> edges = new LinkedHashSet<>();
        int guard = 0;
        while (edges.size() < n && guard++ < n * 10) {
            int a = rng.nextInt(n), b = rng.nextInt(n);
            if (a != b) edges.add(new Edge(a, b));
        }
        return edges;
    }

    public static String vIri(int v) {
        return "urn:bench:v" + String.format("%07d", v);
    }

    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    public static GraphPatternEngine buildNativeEngine(Set<Edge> edges, DirectBufferPool pool) {
        StaticMap spoc = buildIndex(edges, pool, true);
        StaticMap posc = buildIndex(edges, pool, false);
        return new GraphPatternEngine(
                new InMemoryNodeStore(), pool, SPOC, Map.of("SPOC", spoc, "POSC", posc));
    }

    /**
     * {SPOC, POSC} indexes — lets the allocation profiler construct a LeapfrogTriejoin directly to
     * split projection (constructor) vs descent (solve) allocation.
     */
    public static StaticMap[] buildSpocPosc(Set<Edge> edges, DirectBufferPool pool) {
        return new StaticMap[] {buildIndex(edges, pool, true), buildIndex(edges, pool, false)};
    }

    /** The 4-column SPOC tuple descriptor the native indexes use. */
    public static TupleDescriptor spocDescriptor() {
        return SPOC;
    }

    private static StaticMap buildIndex(Set<Edge> edges, DirectBufferPool pool, boolean spocOrder) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
        for (Edge e : edges) {
            String s = vIri(e.from()), o = vIri(e.to());
            if (spocOrder)
                mm.put(tuple(pool, s, EDGE, o, GRAPH), java.lang.foreign.MemorySegment.NULL);
            else mm.put(tuple(pool, EDGE, o, s, GRAPH), java.lang.foreign.MemorySegment.NULL);
        }
        return mm.flush();
    }

    private static java.lang.foreign.MemorySegment tuple(
            DirectBufferPool pool, String a, String b, String c, String d) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, a.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, b.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, c.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, d.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    public static Repository buildMemory(Set<Edge> edges) {
        Repository repo = new SailRepository(new MemoryStore());
        repo.init();
        loadStatements(repo, edges);
        return repo;
    }

    public static Repository buildProllySail(Set<Edge> edges, Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        Repository repo = new SailRepository(sail);
        repo.init();
        loadStatements(repo, edges);
        return repo;
    }

    private static void loadStatements(Repository repo, Set<Edge> edges) {
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            conn.begin();
            long c = 0;
            for (Edge e : edges) {
                conn.add(
                        vf.createIRI(vIri(e.from())),
                        vf.createIRI(EDGE),
                        vf.createIRI(vIri(e.to())));
                // Batched commits bound memory — never a single-tx mega-transaction (OOMs at
                // scale).
                if (++c % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
    }

    // ---- queries ----------------------------------------------------------

    public static List<QuadPattern> patternsFor(String q) {
        return switch (q) {
            case "triangle" ->
                    List.of(
                            QuadPattern.of("?x", EDGE, "?y", GRAPH),
                            QuadPattern.of("?y", EDGE, "?z", GRAPH),
                            QuadPattern.of("?z", EDGE, "?x", GRAPH));
            case "path2" ->
                    List.of(
                            QuadPattern.of("?x", EDGE, "?y", GRAPH),
                            QuadPattern.of("?y", EDGE, "?z", GRAPH));
            case "star" ->
                    List.of(
                            QuadPattern.of("?x", EDGE, "?y", GRAPH),
                            QuadPattern.of("?x", EDGE, "?z", GRAPH));
            default -> throw new IllegalArgumentException(q);
        };
    }

    public static String sparqlFor(String q) {
        String e = "<" + EDGE + ">";
        return switch (q) {
            case "triangle" ->
                    "SELECT ?x ?y ?z WHERE { ?x "
                            + e
                            + " ?y . ?y "
                            + e
                            + " ?z . ?z "
                            + e
                            + " ?x . }";
            case "path2" -> "SELECT ?x ?y ?z WHERE { ?x " + e + " ?y . ?y " + e + " ?z . }";
            case "star" -> "SELECT ?x ?y ?z WHERE { ?x " + e + " ?y . ?x " + e + " ?z . }";
            default -> throw new IllegalArgumentException(q);
        };
    }

    // ---- result extraction (for timing + the agreement test) --------------

    public static int countSparql(Repository repo, String query) {
        int n = 0;
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (r.hasNext()) {
                r.next();
                n++;
            }
        }
        return n;
    }

    /** Result rows as {@code [x,y,z]} IRI-string lists — for cross-arm equality. */
    public static Set<List<String>> bindingsSparql(
            Repository repo, String query, List<String> vars) {
        Set<List<String>> out = new LinkedHashSet<>();
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                List<String> row = new ArrayList<>();
                for (String v : vars) row.add(b.getValue(v).stringValue());
                out.add(row);
            }
        }
        return out;
    }

    public static Set<List<String>> bindingsNative(
            GraphPatternEngine engine,
            List<QuadPattern> patterns,
            List<String> order,
            List<String> vars) {
        return new LinkedHashSet<>(rowsNative(engine, patterns, order, vars));
    }

    /** Native triejoin result rows as a <b>multiset</b> (List, dups preserved). */
    public static List<List<String>> rowsNative(
            GraphPatternEngine engine,
            List<QuadPattern> patterns,
            List<String> order,
            List<String> vars) {
        List<List<String>> out = new ArrayList<>();
        for (Map<String, byte[]> m : engine.executeMulti(patterns, order)) {
            List<String> row = new ArrayList<>();
            for (String v : vars)
                row.add(new String(m.get("?" + v), StandardCharsets.UTF_8)); // native keys are "?x"
            out.add(row);
        }
        return out;
    }

    /** SPARQL result rows as a <b>multiset</b> (List, dups preserved). */
    public static List<List<String>> rowsSparql(Repository repo, String query, List<String> vars) {
        List<List<String>> out = new ArrayList<>();
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r =
                        conn.prepareTupleQuery(QueryLanguage.SPARQL, query).evaluate()) {
            while (r.hasNext()) {
                BindingSet b = r.next();
                List<String> row = new ArrayList<>();
                for (String v : vars) row.add(b.getValue(v).stringValue());
                out.add(row);
            }
        }
        return out;
    }
}
