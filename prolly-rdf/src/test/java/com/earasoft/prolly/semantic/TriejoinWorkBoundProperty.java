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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Step 9 of {@code multi-variable-leapfrog-triejoin.md} — the <b>worst-case-optimal-join
 * time-bound EVIDENCE</b> for the seek-streaming triejoin (Gain 3).
 *
 * <p>The adversarial instance is a <b>bidirectional star</b>: a hub {@code c} with {@code m} leaves
 * {@code L_0..L_{m-1}}, edges {@code c→L_i} and {@code L_i→c} (so {@code N = 2m} edges). On this
 * graph the triangle query {@code ?x :e ?y . ?y :e ?z . ?z :e ?x} has <b>zero</b> triangles (a star
 * is triangle-free), yet <b>any binary-join plan is quadratic</b>: joining the first two relations
 * on the middle variable materializes, at the hub alone, every in-edge × every out-edge — {@code m
 * × m = Θ(N²)} intermediate tuples, all of which are then discarded by the third relation.
 *
 * <p>The leapfrog triejoin never builds that intermediate. We instrument its sublinear-seek work
 * ({@link LeapfrogTriejoin#seekWork()}) across growing {@code N}, fit the log-log slope by least
 * squares, and assert it grows <b>sub-quadratically</b> — far below the binary plan's slope of 2.
 * We assert a fitted slope (with margin), not a brittle constant, per D-10.
 */
class TriejoinWorkBoundProperty {

    private static final String EDGE = "e";
    private static final String G = "g";
    private static final TupleDescriptor SPOC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.IRI, false), new Type(Encoding.IRI, false),
                            new Type(Encoding.IRI, false), new Type(Encoding.String, false)));

    @Test
    void triejoinWorkIsSubQuadraticWhereBinaryJoinIsQuadratic() {
        int[] leafCounts = {8, 16, 32, 48, 64, 96};
        List<double[]> triejoin = new ArrayList<>(); // (ln N, ln seekWork)
        List<double[]> binary = new ArrayList<>(); // (ln N, ln intermediate)

        try (DirectBufferPool pool = new DirectBufferPool()) {
            for (int m : leafCounts) {
                StaticMap index = buildStar(pool, m);

                List<QuadPattern> tri =
                        List.of(
                                QuadPattern.of("?x", EDGE, "?y", G),
                                QuadPattern.of("?y", EDGE, "?z", G),
                                QuadPattern.of("?z", EDGE, "?x", G));
                LeapfrogTriejoin lftj =
                        new LeapfrogTriejoin(
                                tri, List.of("?x", "?y", "?z"), index, null, SPOC, pool);
                List<Map<String, byte[]>> rows = lftj.solve();

                // A star is triangle-free: the cyclic query returns nothing — yet the
                // triejoin proved it without ever materializing the quadratic join.
                assertEquals(0, rows.size(), "star graph (m=" + m + ") has no directed triangles");

                long work = lftj.seekWork();
                long binaryIntermediate = (long) m * m + m; // hub: m×m ; each leaf y: 1×1

                int n = 2 * m;
                triejoin.add(new double[] {Math.log(n), Math.log(work)});
                binary.add(new double[] {Math.log(n), Math.log(binaryIntermediate)});
            }
        }

        double triejoinSlope = slope(triejoin);
        double binarySlope = slope(binary);
        System.out.printf(
                "[WCOJ evidence] triejoin seek-work slope=%.3f, binary-join intermediate slope=%.3f%n",
                triejoinSlope, binarySlope);

        // Binary-join intermediate is quadratic in N.
        assertTrue(
                binarySlope > 1.9,
                "binary-join intermediate must grow ~N^2, got slope " + binarySlope);
        // Triejoin seek-work is sub-quadratic — comfortably below the WCOJ ceiling of
        // N^1.5, and far below the binary plan. (On the star it is near-linear.)
        assertTrue(
                triejoinSlope < 1.6,
                "triejoin seek-work must be sub-quadratic, got slope " + triejoinSlope);
        // And a clear separation between the two regimes.
        assertTrue(
                binarySlope - triejoinSlope > 0.5,
                "triejoin must be asymptotically cheaper than binary join; slopes triejoin="
                        + triejoinSlope
                        + " binary="
                        + binarySlope);
    }

    /** Least-squares slope of points (x, y). */
    private static double slope(List<double[]> pts) {
        int n = pts.size();
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        return (n * sxy - sx * sy) / (n * sxx - sx * sx);
    }

    /** Bidirectional star: hub "c", m leaves "L_i", edges c→L_i and L_i→c. */
    private static StaticMap buildStar(DirectBufferPool pool, int m) {
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, SPOC), store, SPOC, pool);
        String hub = "c";
        for (int i = 0; i < m; i++) {
            String leaf = "L" + String.format("%05d", i); // zero-pad: byte order == numeric order
            mm.put(spoc(pool, hub, leaf), MemorySegment.NULL);
            mm.put(spoc(pool, leaf, hub), MemorySegment.NULL);
        }
        return mm.flush();
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
