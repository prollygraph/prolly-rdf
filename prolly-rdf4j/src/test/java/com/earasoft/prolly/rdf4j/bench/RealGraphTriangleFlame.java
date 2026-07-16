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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

/**
 *
 *
 * <h3>CPU flame graph — where each engine spends time on the real cyclic triangle.</h3>
 *
 * <p>{@link RealGraphTriangleBench} established <i>that</i> the WCOJ triejoin wins on wiki-Vote
 * (3.2 s vs native 10.4 s vs bind-join 36.8 s vs flatsail 46.4 s). This tool answers <i>why</i>: it
 * runs the same triangle query under JFR {@code jdk.ExecutionSample} per engine and emits a flame
 * graph + ranked self-CPU, via {@link CpuFlameProfiler}.
 *
 * <p><b>Read the flame graphs with the JFR blind spot in mind.</b> {@code ExecutionSample} samples
 * Java stacks at safepoints — it is <b>blind to native / JNI time</b>. The triejoin and ProllySail
 * bind-join do their join work in pure Java (RocksDB only loads chunks), so their flames are fully
 * informative. flatsail and NativeStore spend much of their time <i>inside</i> RocksDB's JNI, which
 * JFR cannot see — their flames show only the Java-side distribution and where it enters native.
 * For a native-inclusive picture run async-profiler (perf-based, no safepoint bias): {@code
 * JmhRunner -prof async:output=flamegraph}.
 *
 * <p><b>A tool, not a surefire test</b> (run via {@code main}, like {@link SailCpuFlameSuite}).
 * Needs JFR's repository + RocksDB's native lib + temp dirs on real disk from JVM startup:
 *
 * <pre>
 *   java --enable-preview --enable-native-access=ALL-UNNAMED -Xmx6g \
 *        -Djava.io.tmpdir=$T -XX:FlightRecorderOptions=repository=$T/jfr \
 *        -Dgraph.zip=test_ontologies_zips/wiki-vote.zip RealGraphTriangleFlame [symmetrize] [engine]
 * </pre>
 *
 * Writes per-engine {@code .svg} + {@code target/flames/index.html} and prints a per-engine
 * top-frame table.
 */
public final class RealGraphTriangleFlame {

    public static void main(String[] args) throws Exception {
        boolean symmetrize = false;
        String only = "all";
        for (String a : args) {
            if (a.equals("symmetrize")) symmetrize = true;
            else only = a;
        }
        Set<Long> edges = RealGraphTriangleBench.loadEdges(symmetrize);
        String ed = "<" + RealGraphTriangleBench.EDGE + ">";
        String q =
                "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z . ?z " + ed + " ?x }";
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Duration warm = Duration.ZERO; // skip warmup — the slow engines' single query is plenty
        Duration measure =
                Duration.ofSeconds(
                        6); // one slow query overruns this (loop re-checks after it returns)

        System.out.printf(
                "[flame: real cyclic triangle — wiki-Vote, %,d edges%s]%n",
                edges.size(), symmetrize ? " (symmetrized)" : " (directed)");
        Map<String, CpuFlameProfiler.Result> results = new LinkedHashMap<>();
        if (only.equals("all") || only.equals("native"))
            profile(results, "native", () -> newNative(tmp), edges, q, warm, measure);
        if (only.equals("all") || only.equals("flatsail"))
            profile(results, "flatsail", () -> newFlat(tmp), edges, q, warm, measure);
        if (only.equals("all") || only.equals("bind"))
            profile(
                    results,
                    "bind",
                    () -> {
                        ProllySail s = new ProllySail();
                        s.setTriejoinEnabled(false);
                        return s;
                    },
                    edges,
                    q,
                    warm,
                    measure);
        if (only.equals("all") || only.equals("triejoin"))
            profile(
                    results,
                    "triejoin",
                    () -> {
                        ProllySail s = new ProllySail();
                        s.setTriejoinEnabled(true);
                        return s;
                    },
                    edges,
                    q,
                    warm,
                    measure);

        System.out.printf("%n=== top self-CPU frames per engine ===%n");
        for (Map.Entry<String, CpuFlameProfiler.Result> e : results.entrySet()) {
            CpuFlameProfiler.Result r = e.getValue();
            System.out.printf(
                    "%n[%s] %,d samples → %s%n", e.getKey(), r.samples(), r.svg().getFileName());
            int n = 0;
            for (CpuFlameProfiler.FrameCost fc : r.topSelf()) {
                if (n++ >= 12) break;
                System.out.printf(
                        "  %5.1f%%  %s%n",
                        100.0 * fc.selfSamples() / Math.max(1, r.samples()), fc.frame());
            }
        }
        Path index = writeIndex(results);
        System.out.printf("%nindex: %s%n", index);
    }

    private static void profile(
            Map<String, CpuFlameProfiler.Result> out,
            String name,
            Supplier<Sail> factory,
            Set<Long> edges,
            String q,
            Duration warm,
            Duration measure)
            throws Exception {
        SailRepository repo = new SailRepository(factory.get());
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(RealGraphTriangleBench.EDGE);
            conn.begin();
            for (long enc : edges) {
                conn.add(
                        vf.createIRI(RealGraphTriangleBench.vIri(enc >>> 32)),
                        e,
                        vf.createIRI(RealGraphTriangleBench.vIri(enc & 0xFFFFFFFFL)));
            }
            conn.commit();
            Runnable work =
                    () -> {
                        try (TupleQueryResult r =
                                conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
                            while (r.hasNext()) r.next();
                        }
                    };
            out.put(name, CpuFlameProfiler.profile("rtri-" + name, warm, measure, work));
        } finally {
            repo.shutDown();
        }
    }

    private static Sail newFlat(Path tmp) {
        try {
            return new RocksDbFlatSail(Files.createTempDirectory(tmp, "flat-rtriF"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Sail newNative(Path tmp) {
        try {
            return new NativeStore(Files.createTempDirectory(tmp, "native-rtriF").toFile());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path writeIndex(Map<String, CpuFlameProfiler.Result> results) throws Exception {
        StringBuilder h =
                new StringBuilder(
                        "<!doctype html><meta charset=utf-8>"
                                + "<title>real cyclic triangle — CPU flames</title>"
                                + "<h2>wiki-Vote cyclic triangle — CPU flame graphs per engine</h2>"
                                + "<p>JFR <code>jdk.ExecutionSample</code> is blind to native/JNI: triejoin + bind flames are "
                                + "complete; native + flatsail show only the Java side (much of their time is inside RocksDB JNI). "
                                + "For native-inclusive, run async-profiler.</p><ul>");
        for (Map.Entry<String, CpuFlameProfiler.Result> e : results.entrySet()) {
            h.append("<li><a href='")
                    .append(e.getValue().svg().getFileName())
                    .append("'><b>")
                    .append(e.getKey())
                    .append("</b></a> — ")
                    .append(e.getValue().samples())
                    .append(" samples</li>");
        }
        h.append("</ul>");
        Path index = Path.of(System.getProperty("user.dir"), "target", "flames", "index.html");
        Files.createDirectories(index.getParent());
        Files.writeString(index, h.toString());
        return index;
    }

    private RealGraphTriangleFlame() {}
}
