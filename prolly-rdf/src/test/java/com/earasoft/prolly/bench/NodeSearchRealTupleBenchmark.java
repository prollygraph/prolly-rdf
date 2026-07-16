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

import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Layouts;
import java.lang.foreign.MemorySegment;
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
 * Real-comparator companion to {@link NodeSearchCostlyBenchmark}. That one modelled the comparator
 * as K plain {@code Long.compare}s; this one uses the <b>actual</b> index comparator — {@code
 * SpocKey.DESCRIPTOR.compare}, which dispatches through {@code TypeCodec} per column — over real
 * 4-column SPOC {@link Tuple}s. It settles the one honest unknown left open: does the per-column
 * <i>type dispatch</i> push the real index search past the K≈4 wash into an interpolation win?
 *
 * <p><b>Prefix-scan model via {@code varyCol}.</b> All four columns are held equal (a shared bound
 * prefix) except the one named by {@code varyCol}, which carries the uniform 63-bit sort key.
 * {@code varyCol=0} (vary the subject) makes the comparator differ at column 0 → ~1 dispatch per
 * compare (≈ the dictionary's cost, but through the real tuple machinery). {@code varyCol=3} (vary
 * the context, s/p/o equal) makes it walk all four columns → 4 dispatches — the deepest, priciest
 * realistic prefix scan. Interpolation interpolates on the discriminating column's Int64.
 *
 * <p>The tuples are built byte-identically to {@link SpocKey#toTupleSegment} (same 42-byte layout,
 * same offsets) on heap segments, and compared with the same shared {@link SpocKey#DESCRIPTOR} the
 * production indexes use — so the comparator path is the real one.
 *
 * <pre>
 *   scripts/run-bench.sh com.earasoft.prolly.bench.JmhRunner NodeSearchRealTupleBenchmark
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
public class NodeSearchRealTupleBenchmark {

    @Param({"64", "256"})
    public int n;

    /**
     * Which column discriminates (the rest are held equal): 0 = vary subject (~1 dispatch/compare);
     * 3 = vary context with s/p/o equal (4 dispatches).
     */
    @Param({"0", "3"})
    public int varyCol;

    private static final int QUERIES = 256;
    private static final long PREFIX = 0x0123_4567_89AB_CDEFL; // shared leading-column value
    private static final TupleDescriptor DESC = SpocKey.DESCRIPTOR;

    private Tuple[] rows; // n SPOC tuples, sorted by the discriminating column
    private Tuple[] queries; // QUERIES present tuples
    private int offset; // byte offset of the discriminating column = varyCol * 8

    @Setup(Level.Trial)
    public void setup() {
        offset = varyCol * 8;
        long[] sortKeys = NodeSearchBenchmark.uniformSortedKeys(n, 0x5EED1234L);
        rows = new Tuple[n];
        for (int i = 0; i < n; i++) {
            long[] cols = {PREFIX, PREFIX, PREFIX, PREFIX};
            cols[varyCol] = sortKeys[i]; // only this column varies → tuples already sorted
            rows[i] = tuple(cols[0], cols[1], cols[2], cols[3]);
        }
        queries = new Tuple[QUERIES];
        Random r = new Random(0xC0FFEEL);
        for (int i = 0; i < QUERIES; i++) queries[i] = rows[r.nextInt(n)];
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void binary(Blackhole bh) {
        for (Tuple q : queries) bh.consume(binarySearch(rows, q));
    }

    @Benchmark
    @OperationsPerInvocation(QUERIES)
    public void interpolation(Blackhole bh) {
        for (Tuple q : queries) bh.consume(interpolationSearch(rows, q, offset));
    }

    private static int binarySearch(Tuple[] a, Tuple target) {
        int lo = 0, hi = a.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = DESC.compare(a[mid], target);
            if (c < 0) lo = mid + 1;
            else if (c > 0) hi = mid - 1;
            else return mid;
        }
        return -(lo + 1);
    }

    private static int interpolationSearch(Tuple[] a, Tuple target, int off) {
        long key = col(target, off);
        int lo = 0, hi = a.length - 1;
        while (lo <= hi && key >= col(a[lo], off) && key <= col(a[hi], off)) {
            long span = col(a[hi], off) - col(a[lo], off);
            if (span == 0L) return (DESC.compare(a[lo], target) == 0) ? lo : -(lo + 1);
            int pos = lo + (int) (((double) (key - col(a[lo], off)) / (double) span) * (hi - lo));
            if (pos < lo) pos = lo;
            else if (pos > hi) pos = hi;
            int c = DESC.compare(a[pos], target); // the real per-column-dispatch compare
            if (c < 0) lo = pos + 1;
            else if (c > 0) hi = pos - 1;
            else return pos;
        }
        return -(lo + 1);
    }

    private static long col(Tuple t, int off) {
        return t.segment().get(Layouts.LE64_U, off);
    }

    /** Byte-identical to {@link SpocKey#toTupleSegment} (heap-backed, no pool). */
    private static Tuple tuple(long c0, long c1, long c2, long c3) {
        MemorySegment seg = MemorySegment.ofArray(new byte[42]);
        seg.set(Layouts.LE64_U, 0, c0);
        seg.set(Layouts.LE64_U, 8, c1);
        seg.set(Layouts.LE64_U, 16, c2);
        seg.set(Layouts.LE64_U, 24, c3);
        seg.set(Layouts.LE16_U, 32, (short) 8);
        seg.set(Layouts.LE16_U, 34, (short) 16);
        seg.set(Layouts.LE16_U, 36, (short) 24);
        seg.set(Layouts.LE16_U, 38, (short) 32);
        seg.set(Layouts.LE16_U, 40, (short) 4);
        return new Tuple(seg);
    }
}
