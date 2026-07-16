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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Step 7 of {@code prolly-rdf/plans/triejoin-streaming-results.md} — the <b>{@code LIMIT}
 * short-circuit work bound</b> (D-6): a pull cursor consumed only partially must drive the descent
 * only far enough to emit what was pulled — O(k) work, not O(all-rows).
 *
 * <p>The instance is the <b>opposite</b> of {@link TriejoinWorkBoundProperty}'s triangle-free star:
 * a <b>complete directed graph</b> on {@code n} vertices ({@code i→j} for every {@code i≠j}), on
 * which the triangle query {@code ?x :e ?y . ?y :e ?z . ?z :e ?x} has {@code n·(n-1)·(n-2)}
 * solutions — a deliberately <em>large</em> result so a small {@code k} is a tiny fraction of it
 * (the measure-the-real-thing regime: a short-circuit can only be observed when there is a long
 * tail to cut off).
 *
 * <p>The pin is {@link LeapfrogTriejoin#seekWork()} (the same canary D-7 holds fixed): pulling
 * {@code k} rows then {@code close()}-ing accumulates only the seek work done so far. The
 * load-bearing assertion is {@code seekWork(k) ≪ seekWork(full)} — <b>if the consumer ever
 * regressed to eager materialization, pulling {@code k} would compute every row first, so {@code
 * seekWork(k)} would equal {@code seekWork(full)} and this test would fail.</b> It is the
 * engine-level proof under {@code ProllyEvaluationStrategy}'s lazy {@code LookAheadIteration} +
 * RDF4J's {@code LimitIteration} (the Sail end-to-end wiring is pinned separately).
 */
class TriejoinLimitShortCircuitTest {

    private static final String EDGE = "e";
    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));
    private static final List<QuadPattern> TRIANGLE =
            List.of(
                    QuadPattern.of("?x", EDGE, "?y", G),
                    QuadPattern.of("?y", EDGE, "?z", G),
                    QuadPattern.of("?z", EDGE, "?x", G));
    private static final List<String> ORDER = List.of("?x", "?y", "?z");

    @Test
    void limitKDrivesOnlyOkWork() {
        int n = 20; // complete digraph: 380 edges, 20·19·18 = 6,840 triangle solutions
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap index = buildCompleteDigraph(pool, n);

            // Full drain: the result count + the total seek work (the O(all-rows) ceiling).
            LeapfrogTriejoin full = newTriejoin(index, pool);
            long rowsFull = full.solve().size();
            long workFull = full.seekWork();

            // Regime check (measure-the-real-thing): the join must be large, or "short-circuit"
            // measures nothing. A complete K_20 digraph has exactly n·(n-1)·(n-2) directed
            // triangles.
            assertEquals((long) n * (n - 1) * (n - 2), rowsFull, "complete digraph triangle count");
            assertTrue(rowsFull > 1000, "result must be large to expose the tail; got " + rowsFull);

            long work5 = workAfterPulling(index, pool, 5);
            long work100 = workAfterPulling(index, pool, 100);

            System.out.printf(
                    "[LIMIT short-circuit] rowsFull=%d  seekWork: k=5 -> %d, k=100 -> %d, full -> %d%n",
                    rowsFull, work5, work100, workFull);

            // Monotone: pulling more rows does more work, but the partial pulls stay strictly below
            // the full drain. (Eager materialization would make all three equal.)
            assertTrue(work5 < work100, "more rows pulled must cost more seek work");
            assertTrue(
                    work100 < workFull,
                    "a partial pull (100 of " + rowsFull + ") must cost < full");

            // The load-bearing short-circuit assertion: pulling 100 of 6,840 rows does far less
            // than
            // half the full work. Under eager materialization work100 == workFull (ratio 1.0); the
            // O(k) pull makes it a small fraction. Generous margin (0.5) → catches the regression,
            // not flaky on leapfrog init overhead.
            assertTrue(
                    work100 * 2 < workFull,
                    "LIMIT 100 must short-circuit to ≪ O(all-rows); work100="
                            + work100
                            + " workFull="
                            + workFull
                            + " (ratio "
                            + ((double) work100 / workFull)
                            + ")");
        }
    }

    /**
     * A fresh triejoin (fresh tries → fresh seek counters), pulled exactly {@code k} rows then
     * closed.
     */
    private static long workAfterPulling(StaticMap index, DirectBufferPool pool, int k) {
        LeapfrogTriejoin tj = newTriejoin(index, pool);
        LeapfrogTriejoin.BindingCursor cur = tj.cursor();
        int pulled = 0;
        while (pulled < k && cur.next()) pulled++;
        cur.close();
        assertEquals(k, pulled, "graph must have at least k=" + k + " solutions");
        return tj.seekWork();
    }

    private static LeapfrogTriejoin newTriejoin(StaticMap index, DirectBufferPool pool) {
        return new LeapfrogTriejoin(TRIANGLE, ORDER, index, null, SPOC, pool);
    }

    /** Complete directed graph: every vertex points at every other (no self-loops). */
    private static StaticMap buildCompleteDigraph(DirectBufferPool pool, int n) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                mm.put(spoc(pool, v(i), v(j)), MemorySegment.NULL);
            }
        }
        return mm.flush();
    }

    private static String v(int i) {
        return "V" + String.format("%05d", i); // zero-pad: byte order == numeric order
    }

    private static MemorySegment spoc(DirectBufferPool pool, String s, String o) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, EDGE.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, o.getBytes(StandardCharsets.UTF_8));
        tb.putField(3, G.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
