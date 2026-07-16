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
import com.dolthub.prolly.NodeCache;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Phase 1.5 Step 4a of {@code plans/prolly-bulk-load.md} — the measure-first gate for the
 * transactional write path's no-OOM guarantee (D-7). Streams NCIt into a fresh RocksDB-backed
 * ProllySail and logs, per commit batch, the <b>Java heap</b> used and the process <b>resident set
 * (RSS)</b> against statement count — so the runaway consumer is <i>attributed to a layer</i>
 * before any lever is tuned: heap climbing with {@code n} ⇒ the Java-side staging (TreeMutator /
 * dict-index pending) is the consumer (adaptive batch / parallelism cap is the lever); RSS climbing
 * while heap stays flat ⇒ off-heap/native (RocksDB {@code WriteBatchWithIndex} + memtables) is the
 * consumer (a RocksDB-budget lever). A background sampler captures the per-commit peak (the 7-way
 * build fan-out spike).
 *
 * <p>Run under a bounded heap so it fails fast / shows the slope cheaply: {@code mvn -pl
 * prolly-rdf4j test -Dtest=WritePathMemoryProbe -Dncit.zip=… -Dload.batch=100000
 * -Dload.limit=1000000 -Dload.cache=0} with {@code MAVEN_OPTS="-Xmx2g"} (or run the class directly
 * with {@code -Xmx…}). {@code load.batch} ≥ {@code load.limit} ⇒ the single-giant-transaction worst
 * case.
 */
public class WritePathMemoryProbe {

    static {
        // NCIt's RDF/XML nests deeper than JAXP's Java-25 default `jdk.xml.maxElementDepth=100`
        // (depth 101 at line ~5173), so the parse fatal-errors before any statement loads — the
        // same
        // toolchain-bump regression StreamingNcitIngest already guards. Lift the limit for this
        // BENCH
        // only (trusted local corpus, not the untrusted import path); setProperty makes every
        // launch
        // path robust (JAXP reads it when the SAXParser is created during parse()).
        System.setProperty("jdk.xml.maxElementDepth", "0");
    }

    private volatile boolean sampling = true;
    private volatile long peakHeap, peakRss;

    // Layered-attribution handles (plans/prolly-bulk-load.md throughput probe).
    private CountingNodeStore counting; // Layer C: node-store read/write count + latency + bytes
    private RocksNodeStore rocks; // Layer D: RocksDB internal props (memStatsLine)
    private NodeCache nodeCache; // Layer C: cache hit/miss (null when cache off)
    // Commit-phase node I/O, snapshotted around conn.commit() to split commit reads from encode
    // reads. Cumulative across batches; the per-batch delta is computed offline from the log.
    private long cmtReadCount, cmtReadNanos, cmtWriteCount, cmtWriteNanos;

    /**
     * Direct entry point so the probe can run under an explicit {@code -Xmx} without surefire's
     * argLine.
     */
    public static void main(String[] args) throws Exception {
        new WritePathMemoryProbe().probeWritePathMemory();
    }

    @Test
    public void probeWritePathMemory() throws Exception {
        String zip = System.getProperty("ncit.zip");
        Assumptions.assumeTrue(
                zip != null && Files.exists(Path.of(zip)), "set -Dncit.zip=/path/to/ncit.zip");
        final int batch = Integer.getInteger("load.batch", 100_000);
        final long limit = Long.getLong("load.limit", 1_000_000L);
        final long cacheBytes = Long.getLong("load.cache", 0L);

        Path dir = Files.createTempDirectory("ncit-writeprobe");
        this.rocks = new RocksNodeStore(dir.resolve("rocks").toString());
        this.nodeCache = cacheBytes > 0 ? new NodeCache(cacheBytes) : null;
        if (nodeCache != null) rocks.setNodeCache(nodeCache);
        this.counting = new CountingNodeStore(rocks); // Layer C decorator — counts every node op
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        ProllySail sail =
                new ProllySail(
                        counting,
                        new HeapBufferPool(),
                        reg,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();

        Thread sampler = new Thread(this::sampleLoop, "mem-sampler");
        sampler.setDaemon(true);
        sampler.start();

        long start = System.nanoTime();
        System.out.printf(
                "%n===== WRITE-PATH MEMORY PROBE (batch=%,d limit=%,d cache=%,d MiB, Xmx=%,d MiB) =====%n",
                batch,
                limit,
                cacheBytes / 1024 / 1024,
                Runtime.getRuntime().maxMemory() / 1024 / 1024);
        System.out.printf(
                "%12s %10s %10s %10s %10s %8s%n",
                "statements", "heapMB", "rssMB", "peakHeapMB", "peakRssMB", "elapsed");

        try (RepositoryConnection conn = repo.getConnection();
                ZipFile zf = new ZipFile(Path.of(zip).toFile())) {
            ZipEntry entry = zf.getEntry("ncit.owl");
            try (InputStream in = new BufferedInputStream(zf.getInputStream(entry), 1 << 20)) {
                RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                long[] n = {0};
                conn.begin();
                parser.setRDFHandler(
                        new AbstractRDFHandler() {
                            @Override
                            public void handleStatement(Statement st) {
                                conn.add(st);
                                if (++n[0] % batch == 0) {
                                    long r0 = counting.readCount();
                                    long rn0 = counting.readNanos();
                                    long w0 = counting.writeCount();
                                    long wn0 = counting.writeNanos();
                                    conn.commit();
                                    cmtReadCount += counting.readCount() - r0;
                                    cmtReadNanos += counting.readNanos() - rn0;
                                    cmtWriteCount += counting.writeCount() - w0;
                                    cmtWriteNanos += counting.writeNanos() - wn0;
                                    log(n[0], start, reg);
                                    if (n[0] % 1_000_000 == 0) {
                                        System.out.printf(
                                                "%n===== ROCKSDB FULL STATS @ %,d =====%n%s%n",
                                                n[0], rocks.rocksDbFullStats());
                                    }
                                    conn.begin();
                                }
                                if (limit > 0 && n[0] >= limit) throw new StopParsing();
                            }
                        });
                long parseStart = System.nanoTime();
                try {
                    parser.parse(in, "http://purl.obolibrary.org/obo/ncit.owl");
                } catch (StopParsing done) {
                    /* hit limit */
                }
                long parseEnd = System.nanoTime();
                long r0 = counting.readCount();
                long rn0 = counting.readNanos();
                long w0 = counting.writeCount();
                long wn0 = counting.writeNanos();
                conn.commit();
                cmtReadCount += counting.readCount() - r0;
                cmtReadNanos += counting.readNanos() - rn0;
                cmtWriteCount += counting.writeCount() - w0;
                cmtWriteNanos += counting.writeNanos() - wn0;
                long commitEnd = System.nanoTime();
                log(n[0], start, reg);
                System.out.printf(
                        "DONE: %,d statements in %.1fs; peak heap=%,d MB, peak rss=%,d MB%n",
                        n[0], (System.nanoTime() - start) / 1e9, peakHeap, peakRss);
                double parseS = (parseEnd - parseStart) / 1e9;
                double commitS = (commitEnd - parseEnd) / 1e9;
                System.out.printf(
                        "PHASES: parse+encode+stage=%.1fs  commit=%.1fs  (commit %.1f%% of the two)%n",
                        parseS, commitS, 100.0 * commitS / (parseS + commitS));
                reg.getMeters().stream()
                        .filter(mt -> mt instanceof io.micrometer.core.instrument.Timer)
                        .map(mt -> (io.micrometer.core.instrument.Timer) mt)
                        .filter(tm -> tm.count() > 0)
                        .sorted(
                                (a, b) ->
                                        Double.compare(
                                                b.totalTime(java.util.concurrent.TimeUnit.SECONDS),
                                                a.totalTime(java.util.concurrent.TimeUnit.SECONDS)))
                        .forEach(
                                tm ->
                                        System.out.printf(
                                                "  timer %-26s total=%6.2fs count=%d%n",
                                                tm.getId().getName(),
                                                tm.totalTime(java.util.concurrent.TimeUnit.SECONDS),
                                                tm.count()));
                System.out.printf(
                        "%n===== ROCKSDB FULL STATS @ END (%,d) =====%n%s%n",
                        n[0], rocks.rocksDbFullStats());
            }
        } finally {
            sampling = false;
            repo.shutDown();
            StreamingNcitIngest.deleteTree(dir);
        }
    }

    private void log(long n, long start, SimpleMeterRegistry reg) {
        Runtime rt = Runtime.getRuntime();
        long heap = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
        double t = (System.nanoTime() - start) / 1e9;
        // Two prefixed lines per batch (join on n=); the per-batch DELTA of each cumulative
        // localises
        // the super-linear degradation to a layer. [A] phase + per-tree timers (which of the 7
        // trees
        // degrades — the scatter test); [B] node-store read/write decomposition (read-bound vs
        // write-bound), commit-phase split, cache hit/miss, RocksDB internals, and real disk I/O.
        System.out.printf(
                "[A n=%,d t=%.1fs heap=%dMB rss=%dMB | PHASE enc=%.2f ins=%.2f cmt=%.2f"
                        + " | TREE dict=%.2f spoc=%.2f posc=%.2f ospc=%.2f cspo=%.2f ns=%.2f"
                        + " stats=%.2f]%n",
                n,
                t,
                heap,
                rssMB(),
                timerS(reg, "sail.add.encode"),
                timerS(reg, "sail.add.insert"),
                timerS(reg, "sail.commit.total"),
                timerS(reg, "sail.commit.tree.dict"),
                timerS(reg, "sail.commit.tree.spoc"),
                timerS(reg, "sail.commit.tree.posc"),
                timerS(reg, "sail.commit.tree.ospc"),
                timerS(reg, "sail.commit.tree.cspo"),
                timerS(reg, "sail.commit.tree.ns"),
                timerS(reg, "sail.commit.tree.stats"));
        long[] io = procIoBytes();
        System.out.printf(
                "[B n=%,d | NODE rd=%,d/%,dms/%,dMiB miss=%,d wr=%,d/%,dms/%,dMiB"
                        + " | CMT rd=%,d/%,dms wr=%,d/%,dms | CACHE hit=%,d miss=%,d"
                        + " | DISK rd=%,dMiB wr=%,dMiB | %s]%n",
                n,
                counting.readCount(),
                counting.readNanos() / 1_000_000,
                counting.readBytes() >> 20,
                counting.readMisses(),
                counting.writeCount(),
                counting.writeNanos() / 1_000_000,
                counting.writeBytes() >> 20,
                cmtReadCount,
                cmtReadNanos / 1_000_000,
                cmtWriteCount,
                cmtWriteNanos / 1_000_000,
                nodeCache != null ? nodeCache.hits() : 0L,
                nodeCache != null ? nodeCache.misses() : 0L,
                io[0] >> 20,
                io[1] >> 20,
                rocks.memStatsLine());
    }

    /** Cumulative seconds recorded by a named Micrometer timer; 0 if it has not fired yet. */
    private static double timerS(SimpleMeterRegistry reg, String name) {
        var t = reg.find(name).timer();
        return t == null ? 0.0 : t.totalTime(java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * {@code {read_bytes, write_bytes}} from {@code /proc/self/io} — actual block-device I/O issued
     * by this process (Layer E); {@code {0,0}} if {@code /proc} is absent or unreadable.
     */
    private static long[] procIoBytes() {
        long r = 0, w = 0;
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/io"))) {
                if (line.startsWith("read_bytes:")) r = Long.parseLong(line.split("\\s+")[1]);
                else if (line.startsWith("write_bytes:")) w = Long.parseLong(line.split("\\s+")[1]);
            }
        } catch (Exception ignored) {
            // /proc absent (non-Linux) or a transient read failure — best-effort sampling.
        }
        return new long[] {r, w};
    }

    private void sampleLoop() {
        Runtime rt = Runtime.getRuntime();
        while (sampling) {
            long heap = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024;
            long rss = rssMB();
            if (heap > peakHeap) peakHeap = heap;
            if (rss > peakRss) peakRss = rss;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Resident set size in MB from {@code /proc/self/status} (Linux); 0 if unavailable. */
    private static long rssMB() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", "")) / 1024;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static final class StopParsing extends RuntimeException {
        StopParsing() {
            super(null, null, false, false);
        }
    }
}
