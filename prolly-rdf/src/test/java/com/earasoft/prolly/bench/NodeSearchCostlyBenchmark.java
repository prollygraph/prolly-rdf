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

import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Costly-comparator companion to {@link NodeSearchBenchmark}. The cheap-compare verdict (binary
 * wins ~3×) leaves one open case: the index / leapfrog-triejoin node search, whose comparator is a
 * multi-column {@code TupleCompare}, not a single {@code Int64}. Does an expensive enough
 * comparator flip the verdict, and how expensive must it be at a realistic node fanout?
 *
 * <p><b>Cost model.</b> Each "row" is {@code k} longs; the comparator walks them column-by-column
 * with early-exit. The leading {@code k-1} columns are held <i>equal</i> across every row (and the
 * query), so every comparison walks all {@code k} columns before the discriminating last one —
 * faithfully modelling an index prefix scan where the node's keys share the bound subject/predicate
 * and the compare must step past them each time. {@code k=1} is the dictionary's single {@code
 * Int64}; {@code k≈4} is a SPOC quad; higher {@code k} models a pricier compare. Interpolation
 * interpolates on the discriminating last column (uniform 63-bit, the {@code TermId.ofNatural}
 * band).
 *
 * <p>The crossover prediction: with binary doing ~log2(N) probes and interpolation ~3, each probe
 * paying {@code k} long-compares, interpolation wins once {@code k * (per-column compare) >
 * (interpolation divide)} — i.e. only when the comparator is several long-compares dear. This
 * benchmark finds that point.
 *
 * <pre>
 *   scripts/run-bench.sh com.earasoft.prolly.bench.JmhRunner NodeSearchCostlyBenchmark
 * </pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(
        value = 1,
        jvmArgsAppend = {"--enable-native-access=ALL-UNNAMED"})
public class NodeSearchCostlyBenchmark {

    /**
     * Node fanout (realistic low-hundreds + a larger point — larger N lets binary's extra probes
     * pay for interpolation at a lower comparator cost).
     */
    @Param({"64", "256"})
    public int n;

    /**
     * Columns compared per comparison ~ comparator cost. 1 = dictionary Int64; 4 ≈ SPOC quad; 16/64
     * = progressively pricier comparators.
     */
    @Param({"1", "4", "16", "64"})
    public int k;

    private static final int QUERIES = 256;
    private static final long PREFIX = 0x0123_4567_89AB_CDEFL; // shared leading-column value

    private long[][] rows; // n rows, sorted by the discriminating last column
    private long[][] queries; // QUERIES present rows to look up

    @Setup(Level.Trial)
    public void setup() {
        long[] sortKeys = NodeSearchBenchmark.uniformSortedKeys(n, 0x5EED1234L);
        rows = new long[n][];
        for (int i = 0; i < n; i++) {
            long[] row = new long[k];
            for (int c = 0; c < k - 1; c++) row[c] = PREFIX; // equal leading columns
            row[k - 1] = sortKeys[i]; // discriminating column
            rows[i] = row;
        }
        queries = new long[QUERIES][];
        Random r = new Random(0xC0FFEEL);
        for (int i = 0; i < QUERIES; i++) queries[i] = rows[r.nextInt(n)];
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void binary(Blackhole bh) {
        for (long[] q : queries) bh.consume(binarySearch(rows, q));
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void interpolation(Blackhole bh) {
        for (long[] q : queries) bh.consume(interpolationSearch(rows, q));
    }

    /**
     * Multi-column compare with early-exit (the leading columns are equal, so this walks all k
     * columns to the discriminating one).
     */
    private static int cmp(long[] row, long[] target) {
        for (int c = 0; c < row.length; c++) {
            int d = Long.compare(row[c], target[c]);
            if (d != 0) return d;
        }
        return 0;
    }

    private static int binarySearch(long[][] a, long[] target) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = cmp(a[mid], target);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    private static int interpolationSearch(long[][] a, long[] target) {
        int last = a[0].length - 1;
        long key = target[last]; // interpolate on the discriminating column
        int lo = 0, hi = a.length - 1;
        while (lo <= hi && key >= a[lo][last] && key <= a[hi][last]) {
            long span = a[hi][last] - a[lo][last];
            if (span == 0L) return (cmp(a[lo], target) == 0) ? lo : -(lo + 1);
            int pos = lo + (int) (((double) (key - a[lo][last]) / (double) span) * (hi - lo));
            if (pos < lo) pos = lo;
            else if (pos > hi) pos = hi;
            int c = cmp(a[pos], target); // a full k-column compare per probe, as in binary
            if (c < 0) lo = pos + 1;
            else if (c > 0) hi = pos - 1;
            else return pos;
        }
        return -(lo + 1);
    }
}
