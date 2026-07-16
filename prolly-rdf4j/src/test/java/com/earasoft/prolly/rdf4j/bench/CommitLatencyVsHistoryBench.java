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
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDBException;

/**
 *
 *
 * <h3>Commit latency vs history depth — does a commit get slower as the log grows?</h3>
 *
 * <p>Commits {@code N} <b>distinct single-triple transactions</b> ({@code (s_n, p, o_n)}) in a loop
 * and reports the <b>distribution</b> of per-commit latency bucketed by history depth — so the
 * question "how long does a commit take with {@code n} commits already in the log?" gets a measured
 * answer rather than a guess. This is the <i>commit-count</i> scaling axis; the existing benches
 * measure <i>statement-count</i> ingest. Plan: {@code
 * prolly-rdf4j/plans/commit-latency-vs-history-benchmark.md}.
 *
 * <p><b>Retention-free by design (D-1).</b> Triples are generated on the fly and never retained;
 * the only memory that grows with {@code n} is a {@code long[]} of the latencies (~8&nbsp;MB at
 * 1M). This is deliberate — {@code NcitVersioningBenchmark} retains its statements + a per-release
 * reachability set and so out-of-memories on its own harness; this driver must not, or it would
 * measure the instrument instead of the Sail.
 *
 * <p><b>One connection per commit (required, not a style choice).</b> The per-transaction buffers
 * ({@code dictTx} / {@code indexesTx} / {@code resolverTx}) are built in the connection constructor
 * from the <i>current committed</i> roots, so a fresh connection per commit is what makes each
 * commit an independent transaction against the latest snapshot — and the connection open is an
 * {@code O(1)} snapshot fork, a constant adder, not a confound on the history axis.
 *
 * <p><b>Attribution (D-4).</b> Per window it deltas the per-phase Micrometer timers the commit path
 * already records ({@code sail.commit.tables} = the four index + dict + namespaces + stats tree
 * builds; {@code sail.commit.prefixes}); the {@code wall − tables − prefixes} residual is the
 * per-commit fixed input/output (RootMetaTree chunk + commit-log append + refs/head overwrites +
 * connection fork). It also samples RocksDB stats (estimate-num-keys, pending-compaction — the
 * tail) and the process resident set (the {@code MemSampler} technique inline: {@code
 * /proc/self/status} VmRSS + heap) to answer <i>was it bounded?</i>.
 *
 * <p><b>Output (D-5, crash-surviving).</b> Two CSVs under {@code outDir}, written incrementally and
 * flushed per window so a killed or out-of-memoried run keeps the curve up to the crash: {@code
 * commit-latency-windows.csv} (one row per history window — the distribution + attribution + memory
 * + RocksDB columns) and {@code commit-latency-samples.csv} (a downsampled {@code (n, latency_us)}
 * series for the scatter plot). Adapt {@code test-support/plot_soak.py} to render them (plan Step
 * 5).
 *
 * <p><b>Dev-box numbers are shape-only (D-7).</b> Absolute milliseconds here measure the ZFS
 * read-cache / swap regime as much as the Sail; trust the <i>trend</i> (does p99 grow? is the
 * resident set bounded?), and take the trustworthy run on a confound-free box.
 *
 * <p>Run (best detached for a large N — see the plan's Phase 1):
 *
 * <pre>
 *   java -Djava.io.tmpdir=target/benchtmp --enable-native-access=ALL-UNNAMED \
 *     -cp … com.earasoft.prolly.rdf4j.bench.CommitLatencyVsHistoryBench [N=1000000] [windows=100] [outDir]
 * </pre>
 */
public final class CommitLatencyVsHistoryBench {

    private static final String P = "urn:bench:p";

    /** Header of the per-window CSV — kept in one place so the test can pin it. */
    static final String WINDOWS_CSV_HEADER =
            "n,count,mean_us,p50_us,p90_us,p99_us,p999_us,max_us,tables_us,prefixes_us,residual_us,"
                    + "rss_mb,heap_mb,rocks_num_keys,sst_mb,pend_compact_mb,l0_files,running_compactions";

    static final String SAMPLES_CSV_HEADER = "n,latency_us";

    private CommitLatencyVsHistoryBench() {}

    public static void main(String[] args) throws IOException, RocksDBException {
        int n = args.length > 0 ? Integer.parseInt(args[0].replace("_", "")) : 1_000_000;
        int windows = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        Path outDir =
                args.length > 2 ? Path.of(args[2]) : Path.of("target", "commit-latency-results");
        Path tmp = Path.of(System.getProperty("java.io.tmpdir", "."));
        Path storeDir = Files.createTempDirectory(tmp, "commit-latency-");
        try {
            run(storeDir, outDir, n, windows, System.out);
        } finally {
            deleteTree(storeDir);
        }
    }

    /**
     * Run the bench: store under {@code storeDir} (a throwaway), CSVs under {@code outDir} (kept).
     * Public so a test can drive it with a small {@code n}.
     */
    public static void run(Path storeDir, Path outDir, int n, int windows, PrintStream out)
            throws IOException, RocksDBException {
        out.printf(
                "=== Commit latency vs history — N=%,d single-triple commits, %d windows ===%n",
                n, windows);
        out.printf(
                "store: %s (real disk); production config (RocksNodeStore + HeapBufferPool)%n",
                storeDir);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RocksNodeStore store = new RocksNodeStore(storeDir.resolve("rocks").toString());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        registry,
                        RootMetaTreeStore.beside(storeDir),
                        CommitLog.beside(storeDir),
                        RefsStore.beside(storeDir));
        SailRepository repo = new SailRepository(sail);
        repo.init();
        ValueFactory vf = repo.getValueFactory();
        IRI p = vf.createIRI(P);

        Files.createDirectories(outDir);
        Path windowsCsv = outDir.resolve("commit-latency-windows.csv");
        Path samplesCsv = outDir.resolve("commit-latency-samples.csv");

        long[] lat =
                new long[n]; // per-commit wall latency (ns); ~8 MB at 1M — the only n-sized state
        int windowSize = Math.max(1, n / windows);
        int sampleEvery = Math.max(1, n / 2000); // ~2000 scatter points regardless of N

        out.printf("csv: %s%n%n", outDir);
        out.printf(
                "  %-12s %9s %9s %9s %9s %9s | %9s %9s | %7s %9s %9s%n",
                "n",
                "mean_us",
                "p50",
                "p99",
                "p999",
                "max",
                "tables_us",
                "resid_us",
                "rss_mb",
                "rocksKeys",
                "pendCmp_mb");

        long prevTablesNs = 0;
        long prevTablesCnt = 0;
        long prevPrefixNs = 0;
        long prevPrefixCnt = 0;
        long firstRssMb = -1;
        long lastRssMb = -1;
        int windowStart = 0;

        try (BufferedWriter ww = Files.newBufferedWriter(windowsCsv);
                BufferedWriter ws = Files.newBufferedWriter(samplesCsv)) {
            ww.write(WINDOWS_CSV_HEADER);
            ww.newLine();
            ww.flush();
            ws.write(SAMPLES_CSV_HEADER);
            ws.newLine();
            ws.flush();

            for (int i = 0; i < n; i++) {
                // Distinct triple every commit (D-2): a no-op would skip the commit-log append.
                IRI s = vf.createIRI("urn:bench:s:" + i);
                IRI o = vf.createIRI("urn:bench:o:" + i);
                long t0 = System.nanoTime();
                try (RepositoryConnection conn = repo.getConnection()) {
                    conn.begin();
                    conn.add(s, p, o);
                    conn.commit();
                }
                lat[i] = System.nanoTime() - t0;

                if (i % sampleEvery == 0) {
                    ws.write((i + 1) + "," + fmt(lat[i] / 1_000.0));
                    ws.newLine();
                }

                boolean boundary = (i + 1) % windowSize == 0 || i == n - 1;
                if (!boundary) {
                    continue;
                }

                // ---- window aggregate ----
                int from = windowStart;
                int to = i + 1; // exclusive
                long[] slice = Arrays.copyOfRange(lat, from, to);
                Arrays.sort(slice);
                double meanUs = mean(slice) / 1_000.0;
                long rssMb = vmRssKib() >> 10;
                long heapMb = heapUsedMb();
                if (firstRssMb < 0) {
                    firstRssMb = rssMb;
                }
                lastRssMb = rssMb;

                long tablesNs = timerTotalNanos(registry, "sail.commit.tables");
                long tablesCnt = timerCount(registry, "sail.commit.tables");
                long prefixNs = timerTotalNanos(registry, "sail.commit.prefixes");
                long prefixCnt = timerCount(registry, "sail.commit.prefixes");
                double tablesUs = perCommitUs(tablesNs - prevTablesNs, tablesCnt - prevTablesCnt);
                double prefixUs = perCommitUs(prefixNs - prevPrefixNs, prefixCnt - prevPrefixCnt);
                double residUs = Math.max(0.0, meanUs - tablesUs - prefixUs);
                prevTablesNs = tablesNs;
                prevTablesCnt = tablesCnt;
                prevPrefixNs = prefixNs;
                prevPrefixCnt = prefixCnt;

                double p50 = pct(slice, 0.50) / 1_000.0;
                double p90 = pct(slice, 0.90) / 1_000.0;
                double p99 = pct(slice, 0.99) / 1_000.0;
                double p999 = pct(slice, 0.999) / 1_000.0;
                double max = slice[slice.length - 1] / 1_000.0;
                long numKeys = prop(store, "rocksdb.estimate-num-keys");
                long sstMb = prop(store, "rocksdb.total-sst-files-size") >> 20;
                long pendMb = prop(store, "rocksdb.estimate-pending-compaction-bytes") >> 20;
                long l0 = prop(store, "rocksdb.num-files-at-level0");
                long runCompact = prop(store, "rocksdb.num-running-compactions");

                out.printf(
                        "  %-12d %9.1f %9.1f %9.1f %9.1f %9.1f | %9.1f %9.1f | %7d %,9d %,9d%n",
                        to, meanUs, p50, p99, p999, max, tablesUs, residUs, rssMb, numKeys, pendMb);

                // Per-window CSV row — flushed immediately so a kill keeps the curve (D-5).
                ww.write(
                        String.join(
                                ",",
                                Integer.toString(to),
                                Integer.toString(slice.length),
                                fmt(meanUs),
                                fmt(p50),
                                fmt(p90),
                                fmt(p99),
                                fmt(p999),
                                fmt(max),
                                fmt(tablesUs),
                                fmt(prefixUs),
                                fmt(residUs),
                                Long.toString(rssMb),
                                Long.toString(heapMb),
                                Long.toString(numKeys),
                                Long.toString(sstMb),
                                Long.toString(pendMb),
                                Long.toString(l0),
                                Long.toString(runCompact)));
                ww.newLine();
                ww.flush();
                ws.flush();
                windowStart = to;
            }
        }

        // ---- overall distribution + the no-op-guard contract check ----
        long[] all = lat.clone();
        Arrays.sort(all);
        out.printf(
                "%n  overall: mean %.1f us | p50 %.1f | p90 %.1f | p99 %.1f | p999 %.1f | max %.1f us%n",
                mean(all) / 1_000.0,
                pct(all, 0.50) / 1_000.0,
                pct(all, 0.90) / 1_000.0,
                pct(all, 0.99) / 1_000.0,
                pct(all, 0.999) / 1_000.0,
                all[all.length - 1] / 1_000.0);

        long logEntries = sail.commitLog().map(CommitLatencyVsHistoryBench::entryCount).orElse(-1L);
        out.printf(
                "  commit-log entries: %,d (expected %,d) — %s%n",
                logEntries,
                n,
                logEntries == n
                        ? "OK (no no-op skipped the append)"
                        : "MISMATCH — a commit was a no-op!");
        out.printf(
                "  resident set: first window %,d MiB -> last window %,d MiB (%s)%n",
                firstRssMb,
                lastRssMb,
                lastRssMb <= firstRssMb + 64
                        ? "~bounded"
                        : "GREW — investigate (shape-only on dev box)");
        out.printf("  rocksdb: %s%n", store.memStatsLine());
        out.printf("  wrote %s + %s%n", windowsCsv.getFileName(), samplesCsv.getFileName());

        repo.shutDown();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Compact fixed-point format for CSV cells (no locale grouping, no scientific notation). */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static double perCommitUs(long deltaNs, long deltaCount) {
        return deltaCount > 0 ? (double) deltaNs / deltaCount / 1_000.0 : 0.0;
    }

    private static long timerTotalNanos(SimpleMeterRegistry reg, String name) {
        Timer t = reg.find(name).timer();
        return t == null ? 0L : (long) t.totalTime(TimeUnit.NANOSECONDS);
    }

    private static long timerCount(SimpleMeterRegistry reg, String name) {
        Timer t = reg.find(name).timer();
        return t == null ? 0L : t.count();
    }

    private static long heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }

    private static double mean(long[] a) {
        long sum = 0;
        for (long v : a) {
            sum += v;
        }
        return a.length == 0 ? 0 : (double) sum / a.length;
    }

    /** Percentile of an already-sorted array (nearest-rank). */
    private static long pct(long[] sorted, double q) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }

    /** A RocksDB numeric property, or {@code -1} if unavailable. Estimates — no forced flush. */
    private static long prop(RocksNodeStore store, String key) {
        try {
            return Long.parseLong(store.db().getProperty(key));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Process resident set in KiB, via {@code /proc/self/status} (the {@code MemSampler}
     * technique).
     */
    private static long vmRssKib() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.split("\\s+")[1]);
                }
            }
        } catch (Exception ignored) {
            // /proc absent (non-Linux) or transient failure — sampling is best-effort.
        }
        return 0L;
    }

    private static long entryCount(CommitLog log) {
        try {
            return log.entries().size();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            pth -> {
                                try {
                                    Files.deleteIfExists(pth);
                                } catch (IOException ignored) {
                                    // best-effort cleanup
                                }
                            });
        }
    }
}
