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
package com.earasoft.prolly.bench;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.QuadPattern;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Phase 4 Step 14 of {@code multi-variable-leapfrog-triejoin.md} — the <b>benchmark graph
 * generators + query set + prolly-index loader</b>. Steps 15 (deterministic work/space counters)
 * and 16 (JMH wall-time vs RDF4J) measure on these.
 *
 * <p>Four deterministic, size-parameterised graph families, each over a single edge predicate
 * {@code e} in the default graph {@code g}:
 *
 * <ul>
 *   <li><b>denseCore</b> — a complete directed graph on {@code ~sqrt(N)} vertices ({@code ~N}
 *       edges). Triangle-dense, and the worst case for binary joins (the middle join blows up
 *       before the third relation prunes).
 *   <li><b>sparseRandom</b> — {@code N} random edges over {@code N} vertices (average degree ~1,
 *       few triangles).
 *   <li><b>starPlusPath</b> — a bidirectional star (hub + leaves) plus a simple path over the
 *       remaining budget.
 *   <li><b>scaleFree</b> — a <b>power-law</b> degree distribution via Barabási–Albert preferential
 *       attachment (a few high-degree hubs, a long low-degree tail). This is the realistic-RDF
 *       family: the uniform {@code denseCore} / near-uniform {@code sparseRandom} families miss the
 *       degree skew that real ontologies have (Step 7's NCIt ingest showed a uniform synthetic
 *       vocabulary mis-ranked the engines), and the hub adjacency lists stress the leapfrog
 *       intersection in a way uniform graphs cannot.
 * </ul>
 *
 * <p>Query set: {@link #triangle()} (cyclic), {@link #path2()} (acyclic 2-hop), {@link #star()}
 * (single shared variable). The RDF4J-{@code MemoryStore} side of the comparison lives in
 * prolly-rdf4j (Step 16) — prolly-rdf has no RDF4J on its classpath, so the cross-store harness
 * spans the module boundary by design.
 */
public final class BenchGraphs {

    public static final String EDGE = "e";
    public static final String GRAPH = "g";
    public static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    private BenchGraphs() {}

    /** A directed edge between integer-named vertices. */
    public record Edge(int from, int to) {}

    public enum Family {
        DENSE_CORE,
        SPARSE_RANDOM,
        STAR_PATH,
        SCALE_FREE
    }

    /** Generate the requested family with a roughly-{@code n}-edge budget. */
    public static Set<Edge> generate(Family family, int n, long seed) {
        return switch (family) {
            case DENSE_CORE -> denseCore(n);
            case SPARSE_RANDOM -> sparseRandom(n, seed);
            case STAR_PATH -> starPlusPath(n);
            case SCALE_FREE -> scaleFree(n, seed);
        };
    }

    /** Complete directed graph on ~sqrt(n) vertices (no self-loops). */
    public static Set<Edge> denseCore(int n) {
        int k = Math.max(3, (int) Math.floor((1 + Math.sqrt(1 + 4.0 * n)) / 2)); // k(k-1) ~ n
        Set<Edge> edges = new LinkedHashSet<>();
        for (int a = 0; a < k; a++)
            for (int b = 0; b < k; b++) if (a != b) edges.add(new Edge(a, b));
        return edges;
    }

    /** n random directed edges over n vertices (deterministic given seed). */
    public static Set<Edge> sparseRandom(int n, long seed) {
        Random rng = new Random(seed);
        Set<Edge> edges = new LinkedHashSet<>();
        int guard = 0;
        while (edges.size() < n && guard++ < n * 10) {
            int a = rng.nextInt(n), b = rng.nextInt(n);
            if (a != b) edges.add(new Edge(a, b));
        }
        return edges;
    }

    /**
     * Bidirectional star (hub 0 + leaves) over ~half the budget, plus a simple path over the rest.
     */
    public static Set<Edge> starPlusPath(int n) {
        Set<Edge> edges = new LinkedHashSet<>();
        int leaves = Math.max(1, n / 4);
        for (int i = 1; i <= leaves; i++) { // 2 edges per leaf (in + out)
            edges.add(new Edge(0, i));
            edges.add(new Edge(i, 0));
        }
        int pathStart = leaves + 1;
        int pathLen = Math.max(0, n - edges.size());
        for (int i = 0; i < pathLen; i++) edges.add(new Edge(pathStart + i, pathStart + i + 1));
        return edges;
    }

    /** Attachments per new vertex in {@link #scaleFree} — ≥ 2 so triangles can form among hubs. */
    private static final int SCALE_FREE_M = 3;

    /**
     * Scale-free (power-law degree) graph via Barabási–Albert preferential attachment — ~{@code n}
     * directed edges, deterministic given {@code seed}. Each new vertex attaches to {@link
     * #SCALE_FREE_M} existing vertices chosen with probability proportional to their current degree
     * (the {@code repeated}-endpoints list makes a uniform draw a degree-weighted draw), yielding a
     * {@code P(k) ~ k^-3} degree distribution: a handful of high-degree hubs over a long low-degree
     * tail, like a real RDF relation.
     *
     * <p><b>Edges are symmetric</b> (each attachment adds both directions). A pure new→old
     * attachment is a directed acyclic graph with <em>zero</em> directed triangles — a
     * false-negative workload for the headline triangle query (measure-the-real-thing: a benchmark
     * graph on which the query under test returns nothing measures nothing). Symmetry keeps the
     * power-law degree distribution while guaranteeing the triangle query a non-degenerate result,
     * modelling a hub-heavy symmetric predicate (e.g. a {@code knows}-like relation). The budget
     * contract holds for {@code n} ≫ {@code SCALE_FREE_M}; tiny {@code n} returns the seed clique.
     */
    public static Set<Edge> scaleFree(int n, long seed) {
        int m = SCALE_FREE_M;
        Random rng = new Random(seed);
        Set<Edge> edges = new LinkedHashSet<>();
        List<Integer> repeated = new ArrayList<>(); // each vertex appears once per incident edge

        // Seed: a small symmetric clique on m+1 vertices so the first draws have something to hit.
        int seedCount = m + 1;
        for (int a = 0; a < seedCount; a++) {
            for (int b = a + 1; b < seedCount; b++) {
                addBoth(edges, a, b);
                repeated.add(a);
                repeated.add(b);
            }
        }

        int next = seedCount;
        while (edges.size() < n) {
            int v = next++;
            Set<Integer> chosen = new LinkedHashSet<>();
            int guard = 0;
            while (chosen.size() < m && guard++ < m * 50) {
                chosen.add(repeated.get(rng.nextInt(repeated.size())));
            }
            for (int t : chosen) {
                addBoth(edges, v, t);
                repeated.add(v);
                repeated.add(t);
            }
        }
        return edges;
    }

    private static void addBoth(Set<Edge> edges, int a, int b) {
        edges.add(new Edge(a, b));
        edges.add(new Edge(b, a));
    }

    // ---- prolly index loaders --------------------------------------------

    /** Build the SPOC index {@code (s,p,o,c)} from an edge set. */
    public static StaticMap buildSpoc(Set<Edge> edges, DirectBufferPool pool) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
        for (Edge e : edges)
            mm.put(tuple(pool, iri(e.from()), EDGE, iri(e.to()), GRAPH), MemorySegment.NULL);
        return mm.flush();
    }

    /** Build the POSC index {@code (p,o,s,c)} from an edge set. */
    public static StaticMap buildPosc(Set<Edge> edges, DirectBufferPool pool) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
        for (Edge e : edges)
            mm.put(tuple(pool, EDGE, iri(e.to()), iri(e.from()), GRAPH), MemorySegment.NULL);
        return mm.flush();
    }

    // ---- query set --------------------------------------------------------

    /** Cyclic triangle: {@code ?x e ?y . ?y e ?z . ?z e ?x}. */
    public static List<QuadPattern> triangle() {
        return List.of(
                QuadPattern.of("?x", EDGE, "?y", GRAPH),
                QuadPattern.of("?y", EDGE, "?z", GRAPH),
                QuadPattern.of("?z", EDGE, "?x", GRAPH));
    }

    /** Acyclic 2-hop path: {@code ?x e ?y . ?y e ?z}. */
    public static List<QuadPattern> path2() {
        return List.of(
                QuadPattern.of("?x", EDGE, "?y", GRAPH), QuadPattern.of("?y", EDGE, "?z", GRAPH));
    }

    /** Single shared variable (out-star): {@code ?x e ?y . ?x e ?z}. */
    public static List<QuadPattern> star() {
        return List.of(
                QuadPattern.of("?x", EDGE, "?y", GRAPH), QuadPattern.of("?x", EDGE, "?z", GRAPH));
    }

    // ---- helpers ----------------------------------------------------------

    /** Zero-padded so byte (unsigned) order == numeric order. */
    public static String iri(int v) {
        return "v" + String.format("%07d", v);
    }

    private static MemorySegment tuple(
            DirectBufferPool pool, String a, String b, String c, String d) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, a.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, b.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, c.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, d.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    /** Brute-force directed-triangle count — the oracle for the harness smoke test. */
    public static long bruteForceTriangles(Set<Edge> edges) {
        Set<Long> set = new java.util.HashSet<>();
        int max = 0;
        for (Edge e : edges) {
            set.add(key(e.from(), e.to()));
            max = Math.max(max, Math.max(e.from(), e.to()));
        }
        long count = 0;
        List<Integer> vs = new ArrayList<>();
        for (int i = 0; i <= max; i++) vs.add(i);
        for (int x = 0; x <= max; x++)
            for (int y = 0; y <= max; y++)
                if (set.contains(key(x, y)))
                    for (int z = 0; z <= max; z++)
                        if (set.contains(key(y, z)) && set.contains(key(z, x))) count++;
        return count;
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }
}
