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

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.jspecify.annotations.Nullable;

/**
 * Self-contained probe of <b>what a {@code ProllySail} read actually costs</b> — the measured
 * artifact behind the read-path cost documentation.
 *
 * <p>A {@code getStatements} scan is two costs stacked: a <b>fixed per-query floor</b> paid once
 * (open the connection, fork the committed index roots into a snapshot, encode the bound terms,
 * choose the covering index, flush, open the cursor) and a <b>marginal per-row cost</b> paid for
 * every emitted statement (advance the cursor, four null-checks in {@code filterByLogical}, decode
 * the three {@link com.earasoft.prolly.rdf4j.term.TermId}s back to values, wrap them, build the
 * {@code Statement}). This probe separates the two and then measures the one lever that acts on the
 * marginal cost — the optional term-decode cache — in the two regimes that bound its behaviour.
 *
 * <p>It prints three sections:
 *
 * <ol>
 *   <li><b>Fixed vs marginal</b> — a 1-row point lookup vs an N-row scan, decomposed into the warm
 *       per-query floor (microseconds) and the per-row marginal (nanoseconds + bytes). The lesson:
 *       a <em>small</em> query is almost all fixed floor; the per-row work is cheap.
 *   <li><b>No term repeats</b> — a paired control (cache off vs on) over an <em>all-distinct</em>
 *       full scan ({@code (s_i, p_i, o_i)}: distinct subjects, predicates, AND objects). Every
 *       decode is a fresh miss, so the cache can never hit — it is pure overhead (copy-to-heap +
 *       map insert). This is the only regime where the cache is a tax.
 *   <li><b>Terms repeat</b> — the same paired control over a scan whose predicate is shared and
 *       whose objects come from a small pool, so most decodes are cache <em>hits</em> that skip the
 *       dictionary tree-walk + wrap.
 * </ol>
 *
 * <p><b>The non-obvious finding</b> (measured, after a false start): the cache only acts when a
 * consumer actually <em>materializes</em> the terms — {@code ProllyStatement} decodes lazily, so a
 * scan that merely counts rows triggers no decode and shows the cache doing nothing (the first cut
 * of this probe made exactly that mistake). Once the terms are read, the cache is a sharp,
 * regime-specific lever: a measurable <em>tax</em> on an all-distinct scan (every decode misses,
 * paying the cache's copy-to-heap + insert for no hit) and a substantial <em>win</em> when terms
 * repeat (the wrapped value is memoised, skipping the dictionary tree-walk + wrap on every hit).
 * Because the sign of the effect flips with the workload, it ships <b>off by default</b>, gated on
 * {@code prolly.rdf4j.term-cache-size} for an operator to opt in — see {@link
 * com.earasoft.prolly.rdf4j.value.DictionaryTermResolver}.
 *
 * <p>Run from CLI (best in a <em>fresh</em> JVM):
 *
 * <pre>
 *   mvn -pl prolly-rdf4j compile exec:java \
 *     -Dexec.mainClass=com.earasoft.prolly.rdf4j.examples.ReadPathCostDemo
 * </pre>
 *
 * @implNote Times are a warmed <b>min-of-N</b> (the floor is the least-noisy estimator of the real
 *     cost); allocation is {@code com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes} —
 *     <b>indicative/directional, not a JMH verdict</b> (this is a teaching probe, not the gate).
 *     Absolute milliseconds are machine-specific; the <em>ratios</em> (off vs on) are what travel.
 *     Uses an in-memory {@code new ProllySail()} so it needs no store directory and leaves nothing
 *     behind. The cache is toggled with {@link ProllySail#setTermCacheSize(int)}, which the next
 *     opened connection reads when it builds its resolver.
 */
public final class ReadPathCostDemo {

    private static final com.sun.management.ThreadMXBean TMX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /**
     * Triples per dataset — large enough to amortise the fixed floor, small enough to stay quick.
     */
    private static final int N = 20_000;

    /**
     * Size of the small term pool in the "repeated" regime (objects drawn from this many values).
     */
    private static final int POOL = 64;

    /** Large enough to hold every distinct term without eviction churn skewing the measurement. */
    private static final int CACHE_ON = 1_000_000;

    private static final String P = "urn:bench:p";

    private ReadPathCostDemo() {
        // static main only
    }

    public static void main(String[] args) {
        run(System.out);
    }

    /**
     * Run the probe, writing to {@code out}. Public so a test can drive it with a captured stream.
     */
    public static void run(PrintStream out) {
        out.println("=== ProllySail read-path cost probe (getStatements) ===");
        out.printf("dataset: %,d triples; in-memory store; warm min-of-8%n%n", N);

        fixedVsMarginal(out);
        out.println();
        cacheRegime(
                out,
                2,
                "no term repeats — all-distinct full scan (s_i, p_i, o_i)",
                allDistinctSail());
        out.println();
        cacheRegime(
                out, 3, "terms repeat — shared predicate + " + POOL + "-object pool", pooledSail());

        out.println();
        out.println(
                "=== Takeaway: a small query is dominated by the fixed per-query floor; past it, the"
                        + " per-row term decode dominates. The decode cache is a regime-specific");
        out.println(
                "    lever — a tax when terms do not repeat (the all-distinct scan) and a win when"
                        + " they do (the repeated scan) — so it ships off by default, gated on"
                        + " prolly.rdf4j.term-cache-size to opt in per workload. ===");
    }

    // ------------------------------------------------------------------
    // Section 1 — fixed per-query floor vs marginal per-row cost
    // ------------------------------------------------------------------

    private static void fixedVsMarginal(PrintStream out) {
        out.println("[1] Fixed per-query floor vs marginal per-row cost");
        ProllySail sail = sharedPredicateSail();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI predicate = vf.createIRI(P);
            IRI midSubject = vf.createIRI("urn:bench:s:" + (N / 2));

            // 1 row: point lookup by exact subject. N rows: full predicate scan.
            long[] one = measure(() -> scan(sail, midSubject, null, null));
            long[] many = measure(() -> scan(sail, null, predicate, null));

            long rows = many[2] - one[2];
            double marginalNs = rows <= 0 ? 0 : (double) (many[0] - one[0]) / rows;
            double marginalB = rows <= 0 ? 0 : (double) (many[1] - one[1]) / rows;
            double floorUs = (one[0] - marginalNs) / 1_000.0; // subtract the 1 row's own marginal

            out.printf(
                    "    point lookup (%d row):   %8.2f us   %,10d B%n",
                    one[2], one[0] / 1_000.0, one[1]);
            out.printf(
                    "    predicate scan (%,d):  %8.2f us   %,10d B%n",
                    many[2], many[0] / 1_000.0, many[1]);
            out.printf(
                    "    => fixed per-query floor ~ %.1f us   |   marginal ~ %.0f ns/row, %.0f B/row%n",
                    floorUs, marginalNs, marginalB);
            out.printf(
                    "    (the floor is worth ~%.0f rows of marginal — a query under that size is"
                            + " almost all floor)%n",
                    marginalNs <= 0 ? 0 : floorUs * 1_000.0 / marginalNs);
        } finally {
            sail.shutDown();
        }
    }

    // ------------------------------------------------------------------
    // Sections 2/3 — the term-decode cache, paired off vs on, per regime
    // ------------------------------------------------------------------

    /**
     * Full-scan the sail with the decode cache off, then on; report time + allocation + the ratio.
     */
    private static void cacheRegime(PrintStream out, int idx, String label, ProllySail sail) {
        out.println("[" + idx + "] Decode cache — " + label);
        try {
            sail.setTermCacheSize(0);
            long[] off = measure(() -> scan(sail, null, null, null));
            sail.setTermCacheSize(CACHE_ON);
            long[] on = measure(() -> scan(sail, null, null, null));

            out.printf(
                    "    cache OFF: %7.2f ms   %,12d B   (%,d rows)%n",
                    off[0] / 1e6, off[1], off[2]);
            out.printf("    cache ON:  %7.2f ms   %,12d B%n", on[0] / 1e6, on[1]);
            out.printf(
                    "    => time %.2fx, alloc %.2fx  (>1 = cache slower/heavier; <1 = cache wins)%n",
                    safeRatio(on[0], off[0]), safeRatio(on[1], off[1]));
        } finally {
            sail.shutDown();
        }
    }

    // ------------------------------------------------------------------
    // Datasets
    // ------------------------------------------------------------------

    /** {@code (s_i, P, o_i)} — shared predicate, distinct subjects/objects (for section 1). */
    private static ProllySail sharedPredicateSail() {
        return load(
                (vf, conn) -> {
                    IRI predicate = vf.createIRI(P);
                    for (int i = 0; i < N; i++) {
                        conn.addStatement(
                                vf.createIRI("urn:bench:s:" + i),
                                predicate,
                                vf.createIRI("urn:bench:o:" + i));
                    }
                });
    }

    /**
     * {@code (s_i, p_i, o_i)} — every subject, predicate, AND object distinct: zero possible hits.
     */
    private static ProllySail allDistinctSail() {
        return load(
                (vf, conn) -> {
                    for (int i = 0; i < N; i++) {
                        conn.addStatement(
                                vf.createIRI("urn:bench:s:" + i),
                                vf.createIRI("urn:bench:p:" + i),
                                vf.createIRI("urn:bench:o:" + i));
                    }
                });
    }

    /**
     * {@code (s_i, P, o_(i mod POOL))} — shared predicate + small object pool: most decodes hit.
     */
    private static ProllySail pooledSail() {
        return load(
                (vf, conn) -> {
                    IRI predicate = vf.createIRI(P);
                    for (int i = 0; i < N; i++) {
                        conn.addStatement(
                                vf.createIRI("urn:bench:s:" + i),
                                predicate,
                                vf.createIRI("urn:bench:o:" + (i % POOL)));
                    }
                });
    }

    private interface Loader {
        void load(ValueFactory vf, SailConnection conn);
    }

    private static ProllySail load(Loader loader) {
        ProllySail sail = new ProllySail();
        sail.init();
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            loader.load(sail.getValueFactory(), conn);
            conn.commit();
        }
        return sail;
    }

    // ------------------------------------------------------------------
    // Measurement
    // ------------------------------------------------------------------

    /** Blackhole — a volatile write per scan stops the JIT eliminating the term materialization. */
    private static volatile long blackhole;

    /**
     * Drain the iteration AND materialize every statement's subject/predicate/object — the lazy
     * decode + wrap is what the term cache accelerates, so a probe that only counted rows (the
     * first cut of this code did) would never trigger a decode and would measure nothing about the
     * cache. Returns the row count; the materialized terms are folded into {@link #blackhole}.
     */
    private static long scan(ProllySail sail, @Nullable IRI s, @Nullable IRI p, @Nullable IRI o) {
        long n = 0;
        long acc = 0;
        try (SailConnection conn = sail.getConnection();
                CloseableIteration<? extends Statement> it = conn.getStatements(s, p, o, false)) {
            while (it.hasNext()) {
                Statement st = it.next();
                acc += st.getSubject().hashCode();
                acc += st.getPredicate().hashCode();
                acc += st.getObject().hashCode();
                n++;
            }
        }
        blackhole += acc;
        return n;
    }

    /** {@code {minWallNs, minHeapAllocBytes, rowCount}} over a warmed min-of-8. */
    private static long[] measure(java.util.function.LongSupplier work) {
        for (int i = 0; i < 5; i++) {
            var unused = work.getAsLong(); // warm / JIT — return intentionally discarded
        }
        long bestNs = Long.MAX_VALUE;
        long bestAlloc = Long.MAX_VALUE;
        long count = 0;
        for (int i = 0; i < 8; i++) {
            long a0 = TMX.getCurrentThreadAllocatedBytes();
            long t0 = System.nanoTime();
            count = work.getAsLong();
            bestNs = Math.min(bestNs, System.nanoTime() - t0);
            bestAlloc = Math.min(bestAlloc, TMX.getCurrentThreadAllocatedBytes() - a0);
        }
        return new long[] {bestNs, bestAlloc, count};
    }

    private static double safeRatio(long num, long den) {
        return den == 0 ? Double.NaN : (double) num / den;
    }
}
