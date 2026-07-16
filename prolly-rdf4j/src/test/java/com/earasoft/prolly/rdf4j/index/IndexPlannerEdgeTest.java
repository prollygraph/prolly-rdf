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
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

/**
 * Edge-case coverage for {@link IndexPlanner}. Existing tests cover the normal-path prefix-length
 * and selectivity heuristics; this file pins the boundary contracts: empty available-map,
 * single-index fallback, and the "prefix length is clamped to 3" rule.
 */
class IndexPlannerEdgeTest {

    private static QuadIndex indexFor(QuadOrder order) {
        return new QuadIndex(order, new InMemoryNodeStore(), new HeapBufferPool());
    }

    @Test
    void empty_available_throws_no_indexes() {
        IndexPlanner planner = new IndexPlanner(new EnumMap<>(QuadOrder.class));
        IllegalStateException e =
                assertThrows(
                        IllegalStateException.class,
                        () -> planner.choose(TermId.of(1L), null, null, null));
        assertTrue(
                e.getMessage().contains("no indexes available"),
                "error must clearly identify the cause: " + e.getMessage());
    }

    @Test
    void single_available_index_always_chosen() {
        // Even if the pattern doesn't match this index's natural ordering,
        // when there's only one option, it must be returned.
        EnumMap<QuadOrder, QuadIndex> only = new EnumMap<>(QuadOrder.class);
        QuadIndex cspoOnly = indexFor(QuadOrder.CSPO);
        only.put(QuadOrder.CSPO, cspoOnly);
        IndexPlanner planner = new IndexPlanner(only);

        // S-only bound pattern would normally pick SPOC; with only CSPO, must return it.
        QuadIndex chosen = planner.choose(TermId.of(1L), null, null, null);
        assertSame(cspoOnly, chosen);
    }

    @Test
    void all_null_pattern_chooses_first_available() {
        // Unbound everywhere — every index ties at prefix=0. Enum-order
        // tie-break wins: SPOC.
        EnumMap<QuadOrder, QuadIndex> all = new EnumMap<>(QuadOrder.class);
        QuadIndex spoc = indexFor(QuadOrder.SPOC);
        QuadIndex posc = indexFor(QuadOrder.POSC);
        all.put(QuadOrder.SPOC, spoc);
        all.put(QuadOrder.POSC, posc);
        IndexPlanner planner = new IndexPlanner(all);

        QuadIndex chosen = planner.choose(null, null, null, null);
        assertSame(spoc, chosen, "all-unbound: SPOC wins via enum-declaration tie-break");
    }

    @Test
    void available_map_is_defensively_copied() {
        EnumMap<QuadOrder, QuadIndex> mutable = new EnumMap<>(QuadOrder.class);
        QuadIndex spoc = indexFor(QuadOrder.SPOC);
        mutable.put(QuadOrder.SPOC, spoc);
        IndexPlanner planner = new IndexPlanner(mutable);

        // Mutate the source map.
        mutable.clear();
        // Planner must still find SPOC.
        QuadIndex chosen = planner.choose(TermId.of(1L), null, null, null);
        assertSame(spoc, chosen, "IndexPlanner must defensively copy the available map");
    }

    @Test
    void prefix_length_clamped_to_three() {
        // The planner accepts a 4-bound pattern but SpocIndex.iterPrefix only
        // handles 1..3 columns. QuadIndex.leadingPrefixLength clamps to 3.
        // Pin this so the planner still picks the index but the prefix isn't 4.
        EnumMap<QuadOrder, QuadIndex> all = new EnumMap<>(QuadOrder.class);
        QuadIndex spoc = indexFor(QuadOrder.SPOC);
        all.put(QuadOrder.SPOC, spoc);
        IndexPlanner planner = new IndexPlanner(all);

        int prefix =
                spoc.leadingPrefixLength(
                        TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        assertEquals(
                3,
                prefix,
                "all-four-bound pattern must clamp prefix to 3 — SpocIndex.iterPrefix limit");
    }

    @Test
    void two_arg_constructor_with_metrics_works() {
        // Pin the two-arg overload (no stats) works alongside the one-arg.
        EnumMap<QuadOrder, QuadIndex> all = new EnumMap<>(QuadOrder.class);
        all.put(QuadOrder.SPOC, indexFor(QuadOrder.SPOC));
        IndexPlanner planner =
                new IndexPlanner(
                        all, new io.micrometer.core.instrument.composite.CompositeMeterRegistry());
        assertDoesNotThrow(() -> planner.choose(TermId.of(1L), null, null, null));
    }

    @Test
    void leadingPrefixLength_zero_for_no_bindings() {
        // Edge: pattern with no bindings at all. Each index returns 0.
        QuadIndex spoc = indexFor(QuadOrder.SPOC);
        assertEquals(0, spoc.leadingPrefixLength(null, null, null, null));
    }

    @Test
    void leadingPrefixLength_stops_at_first_null() {
        // SPOC pattern: (s, null, o, c). Only s is leading-bound, then a gap.
        QuadIndex spoc = indexFor(QuadOrder.SPOC);
        int prefix = spoc.leadingPrefixLength(TermId.of(1L), null, TermId.of(3L), TermId.of(4L));
        assertEquals(1, prefix, "prefix scan stops at the first null logical position");
    }

    @Test
    void posc_prefix_with_only_subject_bound_is_zero() {
        // POSC stores (p, o, s, c). If only s is bound (col 2), POSC's leading
        // prefix is zero because col 0 (p) is null.
        QuadIndex posc = indexFor(QuadOrder.POSC);
        int prefix = posc.leadingPrefixLength(TermId.of(1L), null, null, null);
        assertEquals(
                0, prefix, "POSC with only s bound has prefix=0 — pin the inverse permutation");
    }

    @Test
    void cspo_prefix_with_only_context_bound_is_one() {
        // CSPO stores (c, s, p, o). With only c bound, prefix=1.
        QuadIndex cspo = indexFor(QuadOrder.CSPO);
        int prefix = cspo.leadingPrefixLength(null, null, null, TermId.of(99L));
        assertEquals(1, prefix);
    }
}
