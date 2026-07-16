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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.LeapfrogTriejoin;
import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The <b>resident-memory</b> proof for the streaming triejoin — the OOM-relevant counterpart to the
 * allocation scorecard (`TriejoinAllocationProfileTest`). Fills the resident sweep that {@code
 * prolly-rdf/plans/triejoin-streaming-results.md} Step 9 folded forward: does the streaming cursor
 * actually bound peak *live* heap, where the materializing {@code solve()} grows it O(rows)?
 *
 * <p><b>The A/B</b> (everything else equal — same index, same query, same N): {@code solve()}
 * returns a {@code List<Map<String,byte[]>>} of <em>all</em> rows (the path the old Sail consumer
 * used — O(rows) retained), versus a count-only {@code cursor()} drain (the path the new lazy Sail
 * consumer uses). The variable is materialize-vs-stream; the regime is a large result (dense-core
 * triangle, output Θ(N^1.5)); the confounds are controlled — the O(N) index sits in the measured
 * <em>baseline</em> (so it cancels), and {@code System.gc()} runs <em>before</em> each reading so
 * we capture <em>retained</em> live set, not transient per-row churn.
 *
 * <p><b>Measured (2026-06-21) — and it corrected my own claim.</b> {@code solve()} is dead-on
 * <b>O(rows)</b> (~0.40 KB/row, flat ratio) → extrapolates to OOM at large results. The cursor is
 * <b>NOT O(depth) / flat</b> as I'd asserted: it grows <b>~linearly with N (edges)</b> (~0.57
 * KB/edge) — the engine's <b>materialized projections</b> ({@code projectScoped} builds O(N)
 * variable-tries eagerly, and the inner-class {@code BindingCursor} pins the enclosing {@code
 * LeapfrogTriejoin} + its projections for the descent). So the real win is <b>O(rows) → O(N)</b>,
 * not O(rows) → O(depth): streaming removes the O(rows) result materialization, leaving the O(N)
 * projection floor. Because the dense result is Θ(N^1.5) ≫ N, the gap still <em>widens</em> with
 * scale (15× at N=380 → 29× at N=1980) — so streaming pushes the OOM threshold from <em>result</em>
 * size out to <em>input</em> size. The O(N) projection floor is the next lever ({@code
 * triejoin-projection-streaming.md}). Asserts the divergence (cursor ≪ solve at scale); the exact
 * shape is in the build-log image.
 */
class TriejoinResidentMemoryTest {

    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void residentMemory_materializeVsStream() {
        List<QuadPattern> patterns = TriejoinVsRdf4jBenchmark.patternsFor("triangle");
        TupleDescriptor desc = TriejoinVsRdf4jBenchmark.spocDescriptor();
        System.out.println(
                "[resident memory — materialize (solve) vs stream (cursor), dense-core triangle]");
        System.out.printf(
                "  %-9s %14s %20s %20s%n",
                "N(edges)", "rows", "solve retained KB", "cursor retained KB");

        List<long[]> data =
                new ArrayList<>(); // {edges, rows, solveRetainedBytes, cursorRetainedBytes}
        for (int n : new int[] {380, 650, 1000, 1500, 2000}) {
            var edges = TriejoinVsRdf4jBenchmark.denseCore(n);
            try (DirectBufferPool pool = new DirectBufferPool()) {
                StaticMap[] idx = TriejoinVsRdf4jBenchmark.buildSpocPosc(edges, pool);
                StaticMap spoc = idx[0], posc = idx[1];

                // MATERIALIZE: solve() holds every row in a List<Map>. Baseline includes the live
                // index, so the delta is the retained result set (O(rows)).
                long base = liveHeap();
                List<Map<String, byte[]>> rows =
                        new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool).solve();
                long solveRetained = Math.max(0, liveHeap() - base);
                long rowCount = rows.size();
                rows = null; // release before the streaming arm

                // STREAM: count-only cursor drain retains one row + O(depth) cursor state + the
                // O(N)
                // materialized projections the cursor pins (the measured floor — see class doc).
                long base2 = liveHeap();
                long c = 0;
                LeapfrogTriejoin.BindingCursor cur =
                        new LeapfrogTriejoin(patterns, ORDER, spoc, posc, desc, pool).cursor();
                while (cur.next()) c++;
                long cursorRetained = Math.max(0, liveHeap() - base2);
                cur.close();

                if (c != rowCount) {
                    throw new AssertionError("cursor count " + c + " != solve count " + rowCount);
                }
                System.out.printf(
                        "  %-9d %14d %20d %20d%n",
                        edges.size(), rowCount, solveRetained / 1024, cursorRetained / 1024);
                data.add(new long[] {edges.size(), rowCount, solveRetained, cursorRetained});
            }
        }

        long[] first = data.get(0);
        long[] last = data.get(data.size() - 1);
        // solve()'s retained set grows with the result count — the O(rows) ceiling that OOMs at
        // scale.
        assertTrue(
                last[2] > first[2] * 2,
                "solve() retained must grow with result size (O(rows)); first="
                        + first[2]
                        + " last="
                        + last[2]);
        // The cursor stays bounded — far below solve() at the largest result (streaming is O(N),
        // the
        // projection floor; NOT O(rows)).
        assertTrue(
                last[3] * 4 < last[2],
                "cursor retained must be << solve retained at scale (streaming is bounded); cursor="
                        + last[3]
                        + " solve="
                        + last[2]);
    }

    /**
     * Live heap (used after a best-effort full GC) — approximate, but the deltas are tens of MB.
     */
    private static long liveHeap() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 5; i++) {
            System.gc();
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
