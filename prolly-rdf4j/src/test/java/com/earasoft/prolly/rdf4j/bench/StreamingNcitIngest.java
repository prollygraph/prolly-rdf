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

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

/**
 *
 *
 * <h3>Whole-file (streaming) real-dataset ingest — the NCIt ontology.</h3>
 *
 * <p>Loads the <b>entire</b> {@code ncit.owl} (NCI Thesaurus OBO, RDF/XML, ~811 MB) — or a {@code
 * limit}-bounded prefix — into a fresh disk-backed Sail per engine, by <b>streaming</b> the parse
 * straight into the connection (parse → {@code conn.add}; commit every {@code commitEvery}
 * statements). Reports total time, statements/sec, peak heap, and final on-disk store size — the
 * realistic bulk-load profile a synthetic generator can't produce. This is a <b>one-shot load tool,
 * not JMH</b> (re-loading 811 MB per iteration is absurd) — the single-shot throughput + peak-mem +
 * disk-size are the signal.
 *
 * <p>Run: {@code java … -Djava.io.tmpdir=$REALDISK StreamingNcitIngest [limit=0]
 * [commitEvery=100000] [engines=prolly,flatsail,rdf4j-native]}. {@code limit=0} = whole file.
 * Batched commits bound memory (a single mega-transaction OOMs ProllySail's in-tx buffer). {@code
 * -Dncit.zip=…} overrides the path.
 */
public final class StreamingNcitIngest {

    static {
        // NCIt's RDF/XML nests rdf:Description/rdf:type deeper than 100 elements (the deep OWL
        // class
        // hierarchy). The Java 25 bump (2026-06-05) enforces JAXP's `jdk.xml.maxElementDepth`
        // default of 100, so the parse fatal-errors at line ~5173 ("depth 101 exceeds limit 100") —
        // a toolchain-bump regression in this bench's runnability (it ran on Java 21 before). Lift
        // the limit (0 = unlimited) for this BENCH only: the corpus is a trusted, local file, not
        // the
        // untrusted-input production import path — so disabling the depth DoS-guard here is safe
        // and
        // scoped. `setProperty` (not just a -D flag) makes every launch path robust (flame-bench,
        // plain mvn, IDE), since JAXP reads the property when the SAXParser is created during
        // parse().
        System.setProperty("jdk.xml.maxElementDepth", "0");
    }

    /**
     * Diagnostic handle to the live prolly chunk store, so the {@link MemSampler} can read its
     * RocksDB native-memory breakdown (table-readers / memtables / block-cache) during ingest. Set
     * by {@link #buildSail} for the prolly engine; {@code null} otherwise. Volatile — the sampler
     * runs on its own thread. Not used by any production path.
     */
    private static volatile RocksNodeStore prollyStoreForSampling;

    public static void main(String[] args) throws Exception {
        long limit = args.length > 0 ? Long.parseLong(args[0]) : 0; // 0 = whole file
        int commitEvery = args.length > 1 ? Integer.parseInt(args[1]) : 100_000;
        String[] engines =
                args.length > 2
                        ? args[2].split(",")
                        : new String[] {"prolly", "flatsail", "rdf4j-native"};
        // arg[3] = RDF4J B-tree index spec (NativeStore + LMDB); "spoc,posc" is their default,
        // "spoc,posc,ospc,cspo" MATCHES ProllySail/FlatSail's structural 4 (apples-to-apples
        // ingest).
        String indexes = args.length > 3 ? args[3] : "spoc,posc";
        // Optional churn phase (after the base load): churn.releases commits, each deleting
        // ~churn.size/2 random EXISTING triples + adding ~churn.size/2 new ones — the "bulk load,
        // then change random triples over N commits" workload (plans/prolly-bulk-load.md 4e/4f).
        // 0 = off (the existing pure-ingest behavior).
        long churnReleases = Long.getLong("churn.releases", 0L);
        int churnSize = Integer.getInteger("churn.size", 20_000);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));

        System.out.printf(
                "[NCIt whole-file streaming ingest — limit=%s, commitEvery=%,d]%n",
                limit == 0 ? "ALL" : String.format("%,d", limit), commitEvery);
        for (String engine : engines) {
            prollyStoreForSampling = null;
            Path dir = Files.createTempDirectory(tmp, "ncit-" + engine + "-");
            SailRepository repo = new SailRepository(buildSail(engine, dir, 0L, indexes));
            repo.init();
            PeakHeap peak = PeakHeap.start();
            MemSampler sampler = MemSampler.start(engine);
            long t0 = System.nanoTime();
            long n =
                    churnReleases > 0
                            ? streamLoadAndChurn(
                                    repo, limit, commitEvery, (int) churnReleases, churnSize)
                            : streamLoad(repo, limit, commitEvery);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            sampler.stop();
            peak.stop();
            repo.shutDown();
            long diskBytes = dirSize(dir);
            System.out.printf(
                    "  %-14s %,11d stmts  %,8d ms  %,9.0f stmts/s  peakHeap=%,5d MB  onDisk=%,6d MB%n",
                    engine, n, ms, n / (ms / 1000.0 + 1e-9), peak.peakMb(), diskBytes / (1L << 20));
            deleteTree(dir);
        }
    }

    static long streamLoad(SailRepository repo, long limit, int commitEvery) {
        try (RepositoryConnection conn = repo.getConnection();
                ZipFile zip = new ZipFile(ncitZip().toFile())) {
            ZipEntry entry = zip.getEntry("ncit.owl");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1 << 20)) {
                RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                long[] n = {0};
                conn.begin();
                parser.setRDFHandler(
                        new AbstractRDFHandler() {
                            @Override
                            public void handleStatement(Statement st) {
                                conn.add(st);
                                if (++n[0] % commitEvery == 0) {
                                    conn.commit();
                                    conn.begin();
                                } // bound memory
                                if (limit > 0 && n[0] >= limit) throw new StopParsing();
                            }
                        });
                try {
                    parser.parse(in, "http://purl.obolibrary.org/obo/ncit.owl");
                } catch (StopParsing done) {
                    /* hit limit */
                }
                conn.commit();
                return n[0];
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Base streaming load that <b>reservoir-samples</b> existing triples, then {@code
     * churnReleases} scattered-churn commits — each deletes ~{@code churnSize/2} random existing
     * triples (from the reservoir) and adds ~{@code churnSize/2} new ones: the "bulk load, then
     * change random triples over N commits" workload (plans/prolly-bulk-load.md 4e/4f). Per release
     * it prints the commit wall-time; the running {@link MemSampler} shows RSS + RocksDB {@code
     * numKeys} alongside. The question it answers: after a bulk load, do N random-triple commits
     * complete <b>without OOM</b>, and does each commit stay <b>O(churn)</b> (flat wall-time + flat
     * numKeys delta) or grow <b>O(touched-tree)</b> as the store fills? Deterministic ({@code
     * Random(42)}); bounded heap (the reservoir is capped at the total deletes + one release).
     */
    static long streamLoadAndChurn(
            SailRepository repo, long limit, int commitEvery, int churnReleases, int churnSize) {
        int half = churnSize / 2;
        int reservoirCap = churnReleases * half + half + 1024; // enough existing triples to delete
        var reservoir = new java.util.ArrayList<Statement>(Math.min(reservoirCap, 1 << 20));
        var rng = new java.util.Random(42);
        long[] n = {0};
        try (RepositoryConnection conn = repo.getConnection();
                ZipFile zip = new ZipFile(ncitZip().toFile())) {
            ZipEntry entry = zip.getEntry("ncit.owl");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1 << 20)) {
                RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                long bt0 = System.nanoTime();
                conn.begin();
                parser.setRDFHandler(
                        new AbstractRDFHandler() {
                            @Override
                            public void handleStatement(Statement st) {
                                conn.add(st);
                                // Reservoir sample (Vitter R): a uniform random `reservoirCap` of
                                // the
                                // stream in bounded heap — the existing triples the churn deletes.
                                long i = n[0];
                                if (reservoir.size() < reservoirCap) {
                                    reservoir.add(st);
                                } else {
                                    long j = (rng.nextLong() & Long.MAX_VALUE) % (i + 1);
                                    if (j < reservoirCap) reservoir.set((int) j, st);
                                }
                                if (++n[0] % commitEvery == 0) {
                                    conn.commit();
                                    conn.begin();
                                }
                                if (limit > 0 && n[0] >= limit) throw new StopParsing();
                            }
                        });
                try {
                    parser.parse(in, "http://purl.obolibrary.org/obo/ncit.owl");
                } catch (StopParsing done) {
                    /* hit limit */
                }
                conn.commit();
                System.out.printf(
                        "  [base] %,d stmts in %,d ms; reservoir=%,d — now %d churn releases%n",
                        n[0],
                        (System.nanoTime() - bt0) / 1_000_000,
                        reservoir.size(),
                        churnReleases);

                var vf = conn.getValueFactory();
                var pred = vf.createIRI("urn:churn:p");
                System.out.printf("  release  deleted  added  commit_ms%n");
                for (int k = 1; k <= churnReleases; k++) {
                    long ct0 = System.nanoTime();
                    conn.begin();
                    int del = 0;
                    for (int d = 0; d < half && !reservoir.isEmpty(); d++) {
                        int idx = rng.nextInt(reservoir.size());
                        int last = reservoir.size() - 1;
                        Statement s = reservoir.get(idx);
                        reservoir.set(idx, reservoir.get(last));
                        reservoir.remove(
                                last); // O(1) swap-remove; a deleted triple is not re-deleted
                        conn.remove(s);
                        del++;
                    }
                    for (int a = 0; a < half; a++) {
                        conn.add(
                                vf.createIRI("urn:churn:" + k + ":" + a),
                                pred,
                                vf.createLiteral(rng.nextInt()));
                    }
                    conn.commit();
                    System.out.printf(
                            "  %7d  %7d  %5d  %,9d%n",
                            k, del, half, (System.nanoTime() - ct0) / 1_000_000);
                }
                return n[0];
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Sail buildSail(String engine, Path dir) throws Exception {
        return buildSail(engine, dir, 0L, "spoc,posc");
    }

    static Sail buildSail(String engine, Path dir, long nodeCacheBytes) throws Exception {
        return buildSail(engine, dir, nodeCacheBytes, "spoc,posc");
    }

    /**
     * {@code nodeCacheBytes > 0} attaches a byte-budgeted {@link com.dolthub.prolly.NodeCache} to
     * the prolly engine's store (read-path Step 2 A/B knob); 0 = off / other engines unaffected.
     *
     * <p>{@code indexes} is the triple-index spec for the RDF4J B-tree stores (NativeStore + LMDB):
     * {@code "spoc,posc"} is their real default (2 indexes), so it is the **out-of-the-box**
     * comparison; pass {@code "spoc,posc,ospc,cspo"} to MATCH ProllySail's / FlatSail's structural
     * **4** for an **apples-to-apples** read comparison (index count is otherwise a confound — only
     * SPOC/POSC are exercised by the current read shapes, but matching removes the doubt). Ignored
     * by prolly/flatsail, which always build all four permutations structurally.
     */
    static Sail buildSail(String engine, Path dir, long nodeCacheBytes, String indexes)
            throws Exception {
        return switch (engine) {
            case "prolly" -> {
                RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
                if (nodeCacheBytes > 0)
                    store.setNodeCache(new com.dolthub.prolly.NodeCache(nodeCacheBytes));
                prollyStoreForSampling = store; // diagnostic: let MemSampler read RocksDB mem stats
                yield new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
            }
            case "flatsail" ->
                    new RocksDbFlatSail(Files.createDirectories(dir.resolve("flatsail")));
            case "rdf4j-native" -> new NativeStore(dir.resolve("native").toFile(), indexes);
            case "lmdb" ->
                    new org.eclipse.rdf4j.sail.lmdb.LmdbStore(
                            dir.resolve("lmdb").toFile(),
                            new org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig(indexes));
            default -> throw new IllegalArgumentException("unknown engine: " + engine);
        };
    }

    /**
     * Prolly-engine sail with a caller-supplied {@link com.dolthub.prolly.NodeCache} attached (or
     * none if {@code cache} is null) — lets an experiment inject a recording / instrumented cache
     * instance rather than only a byte budget. Used by {@code NodeCacheHitRateExperiment}.
     */
    static Sail buildSail(String engine, Path dir, com.dolthub.prolly.NodeCache cache)
            throws Exception {
        if (!"prolly".equals(engine))
            throw new IllegalArgumentException("cache injection only for prolly: " + engine);
        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
        if (cache != null) store.setNodeCache(cache);
        return new ProllySail(
                store,
                new HeapBufferPool(),
                RootMetaTreeStore.beside(dir),
                CommitLog.beside(dir),
                RefsStore.beside(dir));
    }

    /** Background daemon sampling max heap-in-use every 100 ms. */
    private static final class PeakHeap {
        private volatile boolean running = true;
        private volatile long peak;

        static PeakHeap start() {
            PeakHeap p = new PeakHeap();
            Thread t =
                    new Thread(
                            () -> {
                                Runtime rt = Runtime.getRuntime();
                                while (p.running) {
                                    p.peak = Math.max(p.peak, rt.totalMemory() - rt.freeMemory());
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                            });
            t.setDaemon(true);
            t.start();
            return p;
        }

        void stop() {
            running = false;
        }

        long peakMb() {
            return peak / (1L << 20);
        }
    }

    /**
     * Native-memory attribution sampler — prints, every 2s to {@code stderr}, the process resident
     * set (and its peak) from {@code /proc/self/status} alongside the Java heap and (prolly only)
     * the RocksDB native breakdown. The point: an abrupt {@code std::bad_alloc} leaves no clean
     * exit to summarise at, so the sample TRAIL up to the crash is the evidence. Reading RSS, heap,
     * and RocksDB-internal separately disambiguates the consumer at the wall — {@code native_other
     * ≈ RSS − heapCommitted − rocksdbTotal} isolates an off-heap (Panama arena) leak from a RocksDB
     * one (plans/prolly-bulk-load.md Step 4e/4g attribution). Diagnostic-only; not a production
     * path.
     */
    // Package-private so sibling benches (e.g. GraphIngestBench) can reuse the RSS/heap sampler.
    static final class MemSampler {
        private volatile boolean running = true;

        static MemSampler start(String engine) {
            MemSampler s = new MemSampler();
            long t0 = System.nanoTime();
            Thread t =
                    new Thread(
                            () -> {
                                Runtime rt = Runtime.getRuntime();
                                while (s.running) {
                                    long sec = (System.nanoTime() - t0) / 1_000_000_000;
                                    long heapUsed = (rt.totalMemory() - rt.freeMemory()) >> 20;
                                    long heapCommitted = rt.totalMemory() >> 20;
                                    RocksNodeStore store = prollyStoreForSampling;
                                    String rdb =
                                            store != null ? store.memStatsLine() : "rocksdb[n/a]";
                                    System.err.printf(
                                            "[mem %-12s t=%4ds rssMiB=%,7d hwmMiB=%,7d heapUsedMiB=%,6d heapCommitMiB=%,6d] %s%n",
                                            engine,
                                            sec,
                                            procStatusKb("VmRSS") >> 10,
                                            procStatusKb("VmHWM") >> 10,
                                            heapUsed,
                                            heapCommitted,
                                            rdb);
                                    try {
                                        Thread.sleep(2000);
                                    } catch (InterruptedException e) {
                                        return;
                                    }
                                }
                            });
            t.setDaemon(true);
            t.start();
            return s;
        }

        void stop() {
            running = false;
        }

        /**
         * A {@code /proc/self/status} size field in KiB (e.g. {@code VmRSS}); {@code 0} if absent.
         */
        private static long procStatusKb(String field) {
            try {
                for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                    if (line.startsWith(field + ":")) {
                        String[] parts = line.split("\\s+");
                        return Long.parseLong(parts[1]); // value is in kB
                    }
                }
            } catch (Exception ignored) {
                // /proc absent (non-Linux) or transient read failure — sampling is best-effort.
            }
            return 0L;
        }
    }

    private static final class StopParsing extends RuntimeException {
        StopParsing() {
            super(null, null, false, false);
        }
    }

    private static long dirSize(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile)
                    .mapToLong(
                            p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                    .sum();
        }
    }

    static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        }
    }

    private static Path ncitZip() {
        String prop = System.getProperty("ncit.zip");
        if (prop != null) return Path.of(prop);
        for (String c :
                new String[] {
                    "test_ontologies_zips/ncit.zip", "../test_ontologies_zips/ncit.zip"
                }) {
            Path p = Path.of(c);
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("ncit.zip not found; pass -Dncit.zip=…");
    }

    private StreamingNcitIngest() {}
}
