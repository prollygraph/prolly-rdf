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

import com.dolthub.prolly.ByteUtils;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Default {@link VariableOrderHeuristic} — orders variables <b>most-constrained first</b> using the
 * {@link CardinalityEstimator}.
 *
 * <p>Each variable is scored by the <i>smallest</i> estimated match-count among the patterns it
 * appears in: a variable anchored by a highly selective pattern (few matching rows) is bound first,
 * shrinking the outer loop. The per-pattern estimate uses the same index choice as the seek-scoped
 * projection (ADR-0034): a bound subject estimates on SPOC, a bound predicate on POSC, and an
 * otherwise unconstrained pattern is treated as the whole store. Ties break by descending degree (a
 * variable in more patterns has more join constraints, so bind it earlier) then by name
 * (determinism).
 *
 * <p>The estimate uses rank-based {@code estimateRange} over the leading constant field —
 * sublinear, and approximate by design: it only needs to <i>rank</i> variables, not count exactly.
 */
public final class SelectivityVariableOrder implements VariableOrderHeuristic {

    private final CardinalityEstimator spocEst;
    // @Nullable: POSC is an optional index — when absent, predicate-bound patterns fall back to the
    // whole-store estimate (estimate() guards poscEst != null). Mirrors LeapfrogTriejoin.posc.
    private final @Nullable CardinalityEstimator poscEst;
    private final long totalRows;
    private final DirectBufferPool pool;

    public SelectivityVariableOrder(
            StaticMap spoc, @Nullable StaticMap posc, DirectBufferPool pool) {
        this.spocEst = new CardinalityEstimator(spoc);
        this.poscEst = (posc == null) ? null : new CardinalityEstimator(posc);
        this.totalRows = (spoc.root() == null) ? 0 : spoc.root().treeCount();
        this.pool = pool;
    }

    @Override
    public List<String> order(List<QuadPattern> patterns) {
        // Per-pattern selectivity estimate.
        long[] patternCard = new long[patterns.size()];
        for (int i = 0; i < patterns.size(); i++) patternCard[i] = estimate(patterns.get(i));

        // Per-variable: smallest anchoring-pattern estimate + degree, in
        // first-appearance order for deterministic tie-breaking.
        Map<String, long[]> stats = new LinkedHashMap<>(); // var -> {minCard, degree}
        for (int i = 0; i < patterns.size(); i++) {
            QuadPattern q = patterns.get(i);
            for (String v : vars(q)) {
                long[] s = stats.computeIfAbsent(v, k -> new long[] {Long.MAX_VALUE, 0});
                s[0] = Math.min(s[0], patternCard[i]);
                s[1] += 1;
            }
        }

        List<String> ordered = new ArrayList<>(stats.keySet());
        ordered.sort(
                (a, b) -> {
                    // a and b come from stats.keySet(), so both lookups are present.
                    long[] sa = Objects.requireNonNull(stats.get(a));
                    long[] sb = Objects.requireNonNull(stats.get(b));
                    if (sa[0] != sb[0]) return Long.compare(sa[0], sb[0]); // ascending cardinality
                    if (sa[1] != sb[1]) return Long.compare(sb[1], sa[1]); // descending degree
                    return a.compareTo(b); // name (determinism)
                });
        return ordered;
    }

    private long estimate(QuadPattern q) {
        if (!q.s().isVar()) { // subject bound -> SPOC
            return rangeOf(spocEst, q.s().value());
        } else if (!q.p().isVar() && poscEst != null) { // predicate bound -> POSC
            return rangeOf(poscEst, q.p().value());
        }
        return totalRows; // unconstrained
    }

    /** Estimated rows whose leading column equals {@code value}, via rank. */
    private long rangeOf(CardinalityEstimator est, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] next = ByteUtils.increment(bytes);
        MemorySegment start = oneField(bytes);
        MemorySegment end = (next == null) ? null : oneField(next);
        return est.estimateRange(start, end);
    }

    private MemorySegment oneField(byte[] value) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, value);
        return tb.build().segment();
    }

    private static List<String> vars(QuadPattern q) {
        List<String> vs = new ArrayList<>(3);
        if (q.s().isVar()) vs.add(q.s().value());
        if (q.p().isVar()) vs.add(q.p().value());
        if (q.o().isVar()) vs.add(q.o().value());
        return vs;
    }
}
