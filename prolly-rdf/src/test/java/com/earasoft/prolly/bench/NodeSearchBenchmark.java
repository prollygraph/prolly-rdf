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

import java.util.Arrays;
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
 * In-node search microbenchmark: <b>binary vs interpolation</b> over a sorted array of uniform
 * 64-bit (63-bit positive) hash keys — the shape of a ProllySail dictionary node, whose key is
 * {@code TermId = ofNatural(hash(term))} and whose in-node search today is {@code
 * Cursor.searchInNode} (a binary search).
 *
 * <p>It exists to prove or disprove the dictionary table in {@code
 * _research_performance/rocksdb-interpolation-search.md}, whose two separable claims map to the two
 * outputs here:
 *
 * <ol>
 *   <li><b>Accuracy</b> — interpolation lands in ~1 probe on uniform keys (vs binary's ~log2 N).
 *       Measured by {@link #main} (deterministic probe counts).
 *   <li><b>Net speedup</b> — small/wash at real node fanout because each saved probe is only a
 *       cheap {@code long} compare while interpolation pays a divide. Measured by the
 *       {@code @Benchmark} wall-time methods across {@code @Param} n.
 * </ol>
 *
 * <p>The comparison modelled here is a primitive {@code long} compare — the cheapest case, matching
 * the dictionary's single-{@code Int64} key. If interpolation cannot beat binary on cheap compares
 * it will not on the dictionary; the index path (an expensive multi-column tuple compare) is a
 * separate question.
 *
 * <pre>
 *   Wall-time:  scripts/run-bench.sh com.earasoft.prolly.bench.JmhRunner NodeSearchBenchmark
 *   Probes:     scripts/run-bench.sh com.earasoft.prolly.bench.NodeSearchBenchmark
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
public class NodeSearchBenchmark {

    /**
     * Node fanout. Real prolly-tree nodes sit in the low tens-to-hundreds; the high end shows where
     * (and whether) interpolation starts winning on time.
     */
    @Param({"16", "32", "64", "128", "256", "512", "1024"})
    public int n;

    private static final int QUERIES = 256;
    private long[] keys; // sorted, distinct, uniform 63-bit (the node's keys)
    private long[] queries; // QUERIES present keys to look up per invocation

    @Setup(Level.Trial)
    public void setup() {
        keys = uniformSortedKeys(n, 0x5EED1234L);
        queries = new long[QUERIES];
        Random r = new Random(0xC0FFEEL);
        for (int i = 0; i < QUERIES; i++) queries[i] = keys[r.nextInt(n)];
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void binary(Blackhole bh) {
        for (long q : queries) bh.consume(binarySearch(keys, q));
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void interpolation(Blackhole bh) {
        for (long q : queries) bh.consume(interpolationSearch(keys, q));
    }

    // ---- the two searches (binary mirrors Cursor.searchInNode) ----

    static int binarySearch(long[] a, long key) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long v = a[mid];
            if (v < key) lo = mid + 1;
            else if (v > key) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    static int interpolationSearch(long[] a, long key) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi && key >= a[lo] && key <= a[hi]) {
            long span = a[hi] - a[lo];
            if (span == 0L) return (a[lo] == key) ? lo : -(lo + 1);
            // double estimate avoids 64-bit overflow in (key - a[lo]) * (hi - lo)
            int pos = lo + (int) (((double) (key - a[lo]) / (double) span) * (hi - lo));
            if (pos < lo) pos = lo;
            else if (pos > hi) pos = hi;
            long v = a[pos];
            if (v < key) lo = pos + 1;
            else if (v > key) hi = pos - 1;
            else return pos;
        }
        return -(lo + 1);
    }

    // ---- probe-counting variants (one probe == one compare against a[mid|pos]) ----

    static int binaryProbes(long[] a, long key) {
        int lo = 0, hi = a.length - 1, probes = 0;
        while (lo <= hi) {
            probes++;
            int mid = (lo + hi) >>> 1;
            long v = a[mid];
            if (v < key) lo = mid + 1;
            else if (v > key) hi = mid - 1;
            else return probes;
        }
        return probes;
    }

    static int interpolationProbes(long[] a, long key) {
        int lo = 0, hi = a.length - 1, probes = 0;
        while (lo <= hi && key >= a[lo] && key <= a[hi]) {
            probes++;
            long span = a[hi] - a[lo];
            if (span == 0L) return probes;
            int pos = lo + (int) (((double) (key - a[lo]) / (double) span) * (hi - lo));
            if (pos < lo) pos = lo;
            else if (pos > hi) pos = hi;
            long v = a[pos];
            if (v < key) lo = pos + 1;
            else if (v > key) hi = pos - 1;
            else return probes;
        }
        return probes;
    }

    /** Uniform 63-bit positive keys — exactly the band {@code TermId.ofNatural} occupies. */
    static long[] uniformSortedKeys(int n, long seed) {
        Random r = new Random(seed);
        long[] a = new long[n];
        for (int i = 0; i < n; i++) a[i] = r.nextLong() & 0x7FFF_FFFF_FFFF_FFFFL;
        Arrays.sort(a);
        return a; // dedup unneeded: collision odds at n<=1024 over 2^63 are ~1e-13
    }

    /** Accuracy harness: average probe count per successful lookup, per N. */
    public static void main(String[] args) {
        int[] ns = {16, 32, 64, 128, 256, 512, 1024};
        System.out.println("avg probes per successful lookup (uniform 63-bit keys)");
        System.out.printf("%6s  %9s  %16s  %16s%n", "N", "log2(N)", "binary", "interpolation");
        for (int n : ns) {
            long[] a = uniformSortedKeys(n, 0x5EED1234L);
            long bsum = 0, isum = 0;
            for (long k : a) {
                bsum += binaryProbes(a, k);
                isum += interpolationProbes(a, k);
            }
            System.out.printf(
                    "%6d  %9.1f  %16.2f  %16.2f%n",
                    n, Math.log(n) / Math.log(2.0), (double) bsum / n, (double) isum / n);
        }
    }
}
