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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.term.TermStats;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The 4 canonical index orderings (SPOC/POSC/OSPC/CSPO) are orthogonal: for any partial bind
 * pattern, the prefix-length heuristic alone picks a unique winner. The stats tie-break is only
 * exercised when <b>all 4 positions are bound</b> — every index reaches prefix-3 (the {@code
 * SpocIndex.iterPrefix} cap) and stats decide. These tests verify that case.
 */
class IndexPlannerStatsTest {

    private Map<QuadOrder, QuadIndex> allFour() {
        Map<QuadOrder, QuadIndex> map = new EnumMap<>(QuadOrder.class);
        for (QuadOrder o : QuadOrder.values()) {
            map.put(o, new QuadIndex(o, new InMemoryNodeStore(), new HeapBufferPool()));
        }
        return map;
    }

    @Test
    void all_four_bound_stats_picks_rarest_leading_term_spoc() {
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        TermId s = TermId.of(1L);
        TermId p = TermId.of(2L);
        TermId o = TermId.of(3L);
        TermId c = TermId.of(4L);
        stats.increment(s, 1L); // SPOC's leading = rarest
        stats.increment(p, 100L);
        stats.increment(o, 100L);
        stats.increment(c, 1_000_000L);
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), stats);
        QuadIndex pick = planner.choose(s, p, o, c);
        assertEquals(QuadOrder.SPOC, pick.order());
    }

    @Test
    void all_four_bound_stats_picks_rarest_leading_term_posc() {
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        TermId s = TermId.of(1L);
        TermId p = TermId.of(2L);
        TermId o = TermId.of(3L);
        TermId c = TermId.of(4L);
        stats.increment(s, 100L);
        stats.increment(p, 1L); // POSC's leading = rarest
        stats.increment(o, 100L);
        stats.increment(c, 1_000_000L);
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), stats);
        QuadIndex pick = planner.choose(s, p, o, c);
        assertEquals(QuadOrder.POSC, pick.order());
    }

    @Test
    void all_four_bound_stats_picks_rarest_leading_term_ospc() {
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        TermId s = TermId.of(1L);
        TermId p = TermId.of(2L);
        TermId o = TermId.of(3L);
        TermId c = TermId.of(4L);
        stats.increment(s, 100L);
        stats.increment(p, 100L);
        stats.increment(o, 1L); // OSPC's leading = rarest
        stats.increment(c, 1_000_000L);
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), stats);
        QuadIndex pick = planner.choose(s, p, o, c);
        assertEquals(QuadOrder.OSPC, pick.order());
    }

    @Test
    void all_four_bound_stats_picks_rarest_leading_term_cspo() {
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        TermId s = TermId.of(1L);
        TermId p = TermId.of(2L);
        TermId o = TermId.of(3L);
        TermId c = TermId.of(4L);
        stats.increment(s, 100L);
        stats.increment(p, 100L);
        stats.increment(o, 100L);
        stats.increment(c, 1L); // CSPO's leading = rarest
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), stats);
        QuadIndex pick = planner.choose(s, p, o, c);
        assertEquals(QuadOrder.CSPO, pick.order());
    }

    @Test
    void no_stats_falls_back_to_enum_order() {
        // All 4 bound, no stats — SPOC wins by declaration order.
        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), null);
        QuadIndex pick = planner.choose(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        assertEquals(QuadOrder.SPOC, pick.order());
    }

    @Test
    void prefix_length_dominates_over_selectivity() {
        // For pattern (s, p, ?, c): the longest prefix wins, not the rarest term.
        //   SPOC (s,p,o,c): s+p bound, o null → prefix-2
        //   POSC (p,o,s,c): p bound, o null → prefix-1
        //   OSPC (o,s,p,c): o null → prefix-0
        //   CSPO (c,s,p,o): c+s+p bound, o null → prefix-3
        // CSPO wins on prefix even if c is very common (high freq).
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        TermId s = TermId.of(1L);
        TermId p = TermId.of(2L);
        TermId c = TermId.of(99L);
        stats.increment(s, 1L);
        stats.increment(p, 1L);
        stats.increment(c, 1_000_000L); // c common, but CSPO still wins on prefix
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), stats);
        QuadIndex pick = planner.choose(s, p, null, c);
        assertEquals(QuadOrder.CSPO, pick.order());
    }

    @Test
    void metrics_record_stats_aware_pick() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        stats.increment(TermId.of(1L), 1L);
        stats.increment(TermId.of(2L), 1_000L);
        stats.increment(TermId.of(3L), 1_000L);
        stats.increment(TermId.of(4L), 1_000L);
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), metrics, stats);
        planner.choose(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        // SPOC wins (rarest leading term); recorded as prefix3 because all-4-bound.
        assertEquals(
                1d, metrics.get("planner.choice").tag("choice", "SPOC.prefix3").counter().count());
    }

    @Test
    void partial_bind_planner_picks_by_prefix_length_only() {
        // Demonstrating orthogonality: with only s+o bound, OSPC prefix-2 beats SPOC prefix-1.
        // Stats are irrelevant to this decision.
        TermStats stats = new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
        stats.increment(TermId.of(1L), 1L); // make S rare to confirm stats don't override prefix
        stats.increment(TermId.of(2L), 1_000_000L); // O is common
        stats.commit();

        IndexPlanner planner = new IndexPlanner(allFour(), new CompositeMeterRegistry(), stats);
        QuadIndex pick = planner.choose(TermId.of(1L), null, TermId.of(2L), null);
        // OSPC layout = (o, s, p, c); with s+o bound and p null, prefix = 2.
        // SPOC layout = (s, p, o, c); with p null, prefix = 1.
        assertEquals(QuadOrder.OSPC, pick.order());
    }
}
