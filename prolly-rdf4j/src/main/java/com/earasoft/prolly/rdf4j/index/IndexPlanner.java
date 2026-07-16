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
package com.earasoft.prolly.rdf4j.index;

import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.term.TermStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Picks the best {@link QuadOrder} for a partially-bound quad pattern.
 *
 * <p>Cost model (iter 20, simple prefix-length heuristic):
 *
 * <ul>
 *   <li>Score each available order by the number of leading bound columns — longer prefix = tighter
 *       scan = better.
 *   <li>Break ties by enum declaration order (SPOC, POSC, OSPC, CSPO).
 * </ul>
 *
 * <p>Real cost-based selection (using {@link com.earasoft.prolly.rdf4j.term.TermStats} to weight by
 * selectivity) lands in iter 22.
 */
public final class IndexPlanner {

    private final Map<QuadOrder, QuadIndex> available;
    private final MeterRegistry registry;
    private final @Nullable TermStats stats;

    public IndexPlanner(Map<QuadOrder, QuadIndex> available) {
        this(available, new CompositeMeterRegistry(), null);
    }

    public IndexPlanner(Map<QuadOrder, QuadIndex> available, MeterRegistry registry) {
        this(available, registry, null);
    }

    public IndexPlanner(
            Map<QuadOrder, QuadIndex> available,
            MeterRegistry registry,
            @Nullable TermStats stats) {
        this.available = new EnumMap<>(available);
        this.registry = registry;
        this.stats = stats;
    }

    /**
     * Pick the best available index for the supplied pattern. Cost order:
     *
     * <ol>
     *   <li><b>Prefix length</b>: longer leading-bound prefix = tighter scan.
     *   <li><b>Selectivity</b> (when {@link TermStats} is wired): for equal prefix length, prefer
     *       the index whose leading bound column has the rarer term — lower frequency = fewer rows
     *       to walk through the prefix.
     *   <li><b>Enum declaration order</b>: stable tie-break for determinism.
     * </ol>
     */
    public QuadIndex choose(
            @Nullable TermId s, @Nullable TermId p, @Nullable TermId o, @Nullable TermId c) {
        QuadIndex best = null;
        int bestPrefix = -1;
        long bestSelectivity = Long.MAX_VALUE; // lower = more selective
        for (QuadIndex idx : available.values()) {
            int prefix = idx.leadingPrefixLength(s, p, o, c);
            if (prefix < bestPrefix) continue;
            long selectivity = estimateSelectivity(idx, s, p, o, c);
            if (prefix > bestPrefix || (prefix == bestPrefix && selectivity < bestSelectivity)) {
                bestPrefix = prefix;
                bestSelectivity = selectivity;
                best = idx;
            }
        }
        if (best == null) {
            throw new IllegalStateException("no indexes available for planning");
        }
        registry.counter("planner.choice", "choice", best.order() + ".prefix" + bestPrefix)
                .increment();
        return best;
    }

    /**
     * Estimate scan size: frequency of the leading bound term. When stats are unavailable, returns
     * 0 so the original prefix-length-only ordering applies. The first bound logical position in
     * the index's order is the one that ends up at col 0 — i.e., the column the seek lands at.
     */
    private long estimateSelectivity(
            QuadIndex idx,
            @Nullable TermId s,
            @Nullable TermId p,
            @Nullable TermId o,
            @Nullable TermId c) {
        if (stats == null) return 0L;
        TermId leadingBound = leadingBoundTerm(idx.order(), s, p, o, c);
        if (leadingBound == null) return Long.MAX_VALUE; // unbound → full scan
        long freq = stats.frequency(leadingBound);
        return freq < 0 ? 0L : freq; // guard against rollback transients
    }

    private @Nullable TermId leadingBoundTerm(
            QuadOrder order,
            @Nullable TermId s,
            @Nullable TermId p,
            @Nullable TermId o,
            @Nullable TermId c) {
        return switch (order) {
            case SPOC -> s;
            case POSC -> p;
            case OSPC -> o;
            case CSPO -> c;
        };
    }

    /** Test/diagnostic accessor. */
    public Map<QuadOrder, QuadIndex> available() {
        return new EnumMap<>(available);
    }
}
