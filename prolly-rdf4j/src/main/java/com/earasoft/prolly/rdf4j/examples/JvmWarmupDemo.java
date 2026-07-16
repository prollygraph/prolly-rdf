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
package com.earasoft.prolly.rdf4j.examples;

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.rdf4j.sail.SparqlWarmup;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 * Self-contained demo of <b>preloading the SPARQL engine</b> so the first real query is fast.
 *
 * <p>The first SPARQL query on a fresh JVM is ~100&nbsp;ms while later ones are ~1–3&nbsp;ms on the
 * same handful of triples — because the first {@code prepareTupleQuery().evaluate()} class-loads
 * RDF4J's parser + query algebra + evaluation pipeline (and the prolly Sail's eval path): ~285
 * classes, loaded + linked + verified on the calling thread. The data was never the cost.
 *
 * <p>The fix is to pay that cost <b>on purpose at startup</b>: call {@link
 * SparqlWarmup#warmUp(org.eclipse.rdf4j.repository.Repository)} once, right after the Sail is
 * initialised, so the SPARQL engine is class-loaded + JIT-warmed before the first user request. The
 * server does exactly this from {@code SparqlWarmupRunner} (a boot {@code ApplicationRunner} gated
 * by {@code prolly.rdf4j.warmup-on-start}). This demo:
 *
 * <ol>
 *   <li>runs {@link #warmUp} under an in-process <b>Java Flight Recorder</b> recording of {@code
 *       jdk.ClassLoad}, then prints how many classes — and <b>which</b> — the preload loaded;
 *   <li>times the first <em>real</em> query afterwards — now ~1–3&nbsp;ms with <b>zero</b> new
 *       classes — showing the warm-up moved off the first query's critical path.
 * </ol>
 *
 * <p>Run from CLI (best in a <em>fresh</em> JVM — that is the point):
 *
 * <pre>
 *   mvn -pl prolly-rdf4j exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.JvmWarmupDemo
 * </pre>
 *
 * <p>With no argument a temp directory is used and removed on normal exit.
 *
 * @implNote Class names are captured with the {@code jdk.jfr} API (a programmatic {@link Recording}
 *     of {@code jdk.ClassLoad}), so the demo needs no {@code -verbose:class} flag and no agent. In
 *     an already-warm JVM (e.g. a shared test fork) the preload loads nothing new — honest, and the
 *     demo says so.
 */
public final class JvmWarmupDemo {

    private static final String READ_QUERY = "SELECT ?s ?p ?o WHERE { ?s ?p ?o }";

    private JvmWarmupDemo() {
        // static main only
    }

    public static void main(String[] args) throws IOException, RocksDBException {
        Path dir;
        boolean ephemeral;
        if (args.length > 0) {
            dir = Path.of(args[0]);
            Files.createDirectories(dir);
            ephemeral = false;
        } else {
            dir = Files.createTempDirectory("prolly-warmup-demo-");
            ephemeral = true;
        }

        run(dir, System.out);

        if (ephemeral) {
            try (var stream = Files.walk(dir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }

    /**
     * Preload the SPARQL engine before the first real query — delegates to the production {@link
     * SparqlWarmup#warmUp(org.eclipse.rdf4j.repository.Repository)} (the same utility the server's
     * {@code SparqlWarmupRunner} calls at boot). Kept here so the demo narrative reads {@code
     * warmUp(repo)}.
     */
    public static void warmUp(SailRepository repo) {
        SparqlWarmup.warmUp(repo);
    }

    /** Run the demo against {@code dir}. Public so a test can drive it with a captured stream. */
    public static void run(Path dir, PrintStream out) throws IOException, RocksDBException {
        out.println("=== ProllySail SPARQL preload demo (warm up before the first query) ===");
        out.println("Store dir: " + dir);
        out.println();

        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            ProllySail sail =
                    new ProllySail(
                            store,
                            new HeapBufferPool(),
                            RootMetaTreeStore.beside(dir),
                            CommitLog.beside(dir),
                            RefsStore.beside(dir));
            SailRepository repo = new SailRepository(sail);
            repo.init();
            ValueFactory vf = repo.getValueFactory();
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(vf.createIRI("urn:b:1"), vf.createIRI("urn:title"), vf.createLiteral("X"));
                conn.add(vf.createIRI("urn:b:2"), vf.createIRI("urn:title"), vf.createLiteral("Y"));
                conn.commit();
            }

            // [1] Preload via warmUp(), recording the classes it loads.
            out.println(
                    "[1] Preload — warmUp() class-loads the SPARQL engine (recording"
                            + " jdk.ClassLoad):");
            TreeSet<String> loaded = new TreeSet<>();
            double preloadMs = warmUpUnderJfr(repo, loaded);
            long rdf4j = loaded.stream().filter(c -> c.startsWith("org.eclipse.rdf4j")).count();
            long prolly =
                    loaded.stream()
                            .filter(
                                    c ->
                                            c.startsWith("com.earasoft.prolly")
                                                    || c.startsWith("com.dolthub.prolly"))
                            .count();
            out.printf("    preload: %.2f ms — %d classes loaded%n", preloadMs, loaded.size());
            out.printf(
                    "    of those: %d RDF4J, %d prolly, %d JDK/other%n",
                    rdf4j, prolly, loaded.size() - rdf4j - prolly);

            if (loaded.isEmpty()) {
                out.println(
                        "    (this JVM was already warm — the SPARQL engine was loaded before this"
                                + " demo ran; run it standalone via exec:java to see the real set)");
            } else {
                out.println();
                out.println("[2] Classes preloaded, by package (top groups):");
                Map<String, Integer> byPackage = countByPackage(loaded);
                byPackage.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                        .limit(12)
                        .forEach(e -> out.printf("    %-44s %d%n", e.getKey(), e.getValue()));

                out.println();
                out.println("[3] The RDF4J + prolly classes preloaded (the SPARQL eval pipeline):");
                for (String c : loaded) {
                    if (c.startsWith("org.eclipse.rdf4j")
                            || c.startsWith("com.earasoft.prolly")
                            || c.startsWith("com.dolthub.prolly")) {
                        out.println("    " + c);
                    }
                }
            }

            // [4] First REAL query after preload — warm, zero new classes.
            out.println();
            out.println(
                    "[4] First real query AFTER preload (+ two more) — all warm, no new"
                            + " classes:");
            ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
            for (int i = 1; i <= 3; i++) {
                long c0 = cl.getTotalLoadedClassCount();
                long t0 = System.nanoTime();
                runReadQuery(repo);
                out.printf(
                        "    query %d: %.2f ms, +%d classes%n",
                        i,
                        (System.nanoTime() - t0) / 1_000_000.0,
                        cl.getTotalLoadedClassCount() - c0);
            }

            repo.shutDown();
        }

        out.println();
        out.println(
                "=== Done. The server's SparqlWarmupRunner calls SparqlWarmup.warmUp() at boot, so"
                        + " no user query pays the warm-up. ===");
    }

    /**
     * Run {@link #warmUp} under a {@code jdk.ClassLoad} JFR recording; fill {@code loaded} with the
     * class names captured and return the preload's elapsed milliseconds.
     */
    private static double warmUpUnderJfr(SailRepository repo, TreeSet<String> loaded)
            throws IOException {
        Path jfr = Files.createTempFile("prolly-warmup-", ".jfr");
        try (Recording rec = new Recording()) {
            rec.enable("jdk.ClassLoad");
            rec.start();
            long t0 = System.nanoTime();
            warmUp(repo);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            rec.stop();
            rec.dump(jfr);
            try (RecordingFile rf = new RecordingFile(jfr)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent e = rf.readEvent();
                    if ("jdk.ClassLoad".equals(e.getEventType().getName())) {
                        RecordedClass c = e.getValue("loadedClass");
                        if (c != null) {
                            loaded.add(c.getName());
                        }
                    }
                }
            }
            return ms;
        } finally {
            Files.deleteIfExists(jfr);
        }
    }

    /** Group class names by their first four package segments → count. */
    private static Map<String, Integer> countByPackage(TreeSet<String> classes) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String c : classes) {
            String[] parts = c.split("\\.");
            int n = Math.min(parts.length - 1, 4); // drop the simple name; cap at 4 segments
            String key = n <= 0 ? "(default)" : String.join(".", java.util.Arrays.copyOf(parts, n));
            counts.merge(key, 1, Integer::sum);
        }
        return new LinkedHashMap<>(counts);
    }

    private static void evalSelect(RepositoryConnection conn, String query) {
        try (TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
            while (r.hasNext()) {
                r.next();
            }
        }
    }

    private static void runReadQuery(SailRepository repo) {
        try (RepositoryConnection conn = repo.getConnection()) {
            evalSelect(conn, READ_QUERY);
        }
    }
}
