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

import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.lmdb.LmdbStore;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

/**
 *
 *
 * <h3>Triejoin on a <i>real</i> cyclic graph — closing the WCOJ gap.</h3>
 *
 * <p>The LeapfrogTriejoin (worst-case-optimal join) was wired into ProllySail's SPARQL evaluation
 * and proven correct + fast on a <b>synthetic</b> dense-core triangle ({@link
 * SparqlTriangleEngineBench}). The open question — flagged in {@code
 * test_ontologies_zips/README.md} as the corpus's highest-value gap — was whether the advantage
 * holds on a <b>real</b> cyclic graph, where degree skew and structure differ from a generator.
 * NCIt is an acyclic taxonomy, so it never exercised this path; this bench uses a real social /
 * voting network instead.
 *
 * <p><b>Data.</b> <a href="https://snap.stanford.edu/data/wiki-Vote.html">wiki-Vote</a> — Wikipedia
 * adminship voting, a real directed graph (7,115 nodes, 103,689 edges, power-law in-degree from
 * popular candidates). Each line {@code from\tto} becomes a triple {@code <v_from> :edge <v_to>}.
 * Optionally symmetrized (add the reverse edge) to raise the directed-3-cycle count.
 *
 * <p><b>Query.</b> the cyclic triangle {@code ?x :edge ?y . ?y :edge ?z . ?z :edge ?x} — the
 * canonical case where a bind-join's intermediate (2-paths through a skewed hub) blows up while a
 * WCOJ stays near the AGM bound. Times it on RDF4J NativeStore, flatsail (bind-join), ProllySail
 * bind-join, ProllySail triejoin; min-of-k indicative (not a JMH verdict). Run: {@code java …
 * -Dgraph.zip=test_ontologies_zips/wiki-vote.zip RealGraphTriangleBench [symmetrize=false]}.
 */
public final class RealGraphTriangleBench {

    static final String EDGE = "urn:bench:edge";

    public static void main(String[] args) throws Exception {
        // args: optional engine filter {native|flatsail|bind|triejoin|all} + optional "symmetrize".
        boolean symmetrize = false;
        String only = "all";
        for (String a : args) {
            if (a.equals("symmetrize")) symmetrize = true;
            else only = a;
        }
        Set<Long> edges = loadEdges(symmetrize);
        String ed = "<" + EDGE + ">";
        String q =
                "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z . ?z " + ed + " ?x }";
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));

        System.out.printf(
                "[real cyclic triangle — wiki-Vote, %,d edges%s — min-of-3 INDICATIVE, engine=%s]%n",
                edges.size(), symmetrize ? " (symmetrized)" : " (directed)", only);
        if (only.equals("all") || only.equals("native"))
            bench("rdf4j-native    ", () -> newNative(tmp), edges, q);
        if (only.equals("all") || only.equals("lmdb"))
            bench("rdf4j-lmdb      ", () -> newLmdb(tmp), edges, q);
        if (only.equals("all") || only.equals("flatsail"))
            bench("flatsail (bind) ", () -> newFlat(tmp), edges, q);
        if (only.equals("all") || only.equals("bind"))
            bench(
                    "prolly bind-join",
                    () -> {
                        ProllySail s = new ProllySail();
                        s.setTriejoinEnabled(false);
                        return s;
                    },
                    edges,
                    q);
        if (only.equals("all") || only.equals("triejoin"))
            bench(
                    "prolly triejoin ",
                    () -> {
                        ProllySail s = new ProllySail();
                        s.setTriejoinEnabled(true);
                        return s;
                    },
                    edges,
                    q);
    }

    private static Sail newFlat(Path tmp) {
        try {
            return new RocksDbFlatSail(Files.createTempDirectory(tmp, "flat-rtri"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Sail newNative(Path tmp) {
        try {
            return new NativeStore(Files.createTempDirectory(tmp, "native-rtri").toFile());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Sail newLmdb(Path tmp) {
        try {
            return new LmdbStore(
                    Files.createTempDirectory(tmp, "lmdb-rtri").toFile(),
                    new LmdbStoreConfig("spoc,posc"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void bench(String label, Supplier<Sail> sailFactory, Set<Long> edges, String q) {
        SailRepository repo = new SailRepository(sailFactory.get());
        repo.init();
        long count = 0, best = Long.MAX_VALUE;
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(EDGE);
            conn.begin();
            long c = 0;
            for (long enc : edges) {
                conn.add(vf.createIRI(vIri(enc >>> 32)), e, vf.createIRI(vIri(enc & 0xFFFFFFFFL)));
                // Batched commits bound memory — never a single-tx mega-transaction (OOMs at
                // scale).
                if (++c % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
            for (int i = 0; i < 3; i++) { // first runs warm; min picks the warmed one
                long t0 = System.nanoTime();
                count = run(conn, q);
                best = Math.min(best, System.nanoTime() - t0);
            }
        } finally {
            repo.shutDown();
        }
        System.out.printf("  %s  %,10d results   %9.2f ms%n", label, count, best / 1e6);
    }

    private static long run(RepositoryConnection conn, String q) {
        try (TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }

    static String vIri(long v) {
        return "urn:bench:v" + v;
    }

    /**
     * Parse a SNAP-style edge list ({@code from\tto} lines, {@code #} comments) from the FIRST zip
     * entry. Node ids are packed (from &lt;&lt; 32) | to into a long, so it is correct for any
     * graph with 32-bit ids (wiki-Vote / Epinions / web-Google / wiki-Talk / …), not just node ids
     * &lt; 1M.
     */
    static Set<Long> loadEdges(boolean symmetrize) throws Exception {
        Set<Long> edges = new LinkedHashSet<>();
        Path zip = graphZip();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            ZipEntry entry = zf.entries().nextElement(); // first (only) entry — name-agnostic
            try (InputStream in = zf.getInputStream(entry);
                    BufferedReader r =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int tab = line.indexOf('\t');
                    if (tab < 0) tab = line.indexOf(' ');
                    long from = Long.parseLong(line.substring(0, tab).trim());
                    long to = Long.parseLong(line.substring(tab + 1).trim());
                    edges.add((from << 32) | to);
                    if (symmetrize) edges.add((to << 32) | from);
                }
            }
        }
        return edges;
    }

    private static Path graphZip() {
        String prop = System.getProperty("graph.zip");
        if (prop != null) return Path.of(prop);
        for (String c :
                new String[] {
                    "test_ontologies_zips/wiki-vote.zip", "../test_ontologies_zips/wiki-vote.zip"
                }) {
            Path p = Path.of(c);
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("wiki-vote.zip not found; pass -Dgraph.zip=…");
    }

    private RealGraphTriangleBench() {}
}
