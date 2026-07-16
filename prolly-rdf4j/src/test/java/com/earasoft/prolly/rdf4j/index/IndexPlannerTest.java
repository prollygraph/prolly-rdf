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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IndexPlannerTest {

    private Map<QuadOrder, QuadIndex> allFour() {
        Map<QuadOrder, QuadIndex> map = new EnumMap<>(QuadOrder.class);
        for (QuadOrder o : QuadOrder.values()) {
            map.put(o, new QuadIndex(o, new InMemoryNodeStore(), new HeapBufferPool()));
        }
        return map;
    }

    @Test
    void s_bound_chooses_spoc() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(TermId.of(1L), null, null, null);
        assertEquals(QuadOrder.SPOC, pick.order());
    }

    @Test
    void p_bound_only_chooses_posc() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(null, TermId.of(1L), null, null);
        assertEquals(QuadOrder.POSC, pick.order());
    }

    @Test
    void o_bound_only_chooses_ospc() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(null, null, TermId.of(1L), null);
        assertEquals(QuadOrder.OSPC, pick.order());
    }

    @Test
    void c_bound_only_chooses_cspo() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(null, null, null, TermId.of(1L));
        assertEquals(QuadOrder.CSPO, pick.order());
    }

    @Test
    void sp_bound_chooses_spoc_with_prefix_2() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(TermId.of(1L), TermId.of(2L), null, null);
        assertEquals(QuadOrder.SPOC, pick.order());
        assertEquals(2, pick.leadingPrefixLength(TermId.of(1L), TermId.of(2L), null, null));
    }

    @Test
    void po_bound_chooses_posc_with_prefix_2() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(null, TermId.of(1L), TermId.of(2L), null);
        assertEquals(QuadOrder.POSC, pick.order());
        assertEquals(2, pick.leadingPrefixLength(null, TermId.of(1L), TermId.of(2L), null));
    }

    @Test
    void all_bound_chooses_three_column_prefix() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
        // Any index could match all 4; SPOC wins on enum order.
        // The leading-prefix length is capped at 3 by SpocIndex.iterPrefix.
        assertEquals(QuadOrder.SPOC, pick.order());
        assertEquals(
                3,
                pick.leadingPrefixLength(
                        TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L)));
    }

    @Test
    void no_bound_falls_back_to_spoc_full_scan() {
        IndexPlanner p = new IndexPlanner(allFour());
        QuadIndex pick = p.choose(null, null, null, null);
        assertEquals(QuadOrder.SPOC, pick.order());
        assertEquals(0, pick.leadingPrefixLength(null, null, null, null));
    }

    @Test
    void choice_recorded_in_metrics() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        IndexPlanner p = new IndexPlanner(allFour(), metrics);
        p.choose(TermId.of(1L), null, null, null); // SPOC prefix 1
        p.choose(null, TermId.of(2L), null, null); // POSC prefix 1
        p.choose(null, TermId.of(2L), TermId.of(3L), null); // POSC prefix 2
        assertEquals(
                1d, metrics.get("planner.choice").tag("choice", "SPOC.prefix1").counter().count());
        assertEquals(
                1d, metrics.get("planner.choice").tag("choice", "POSC.prefix1").counter().count());
        assertEquals(
                1d, metrics.get("planner.choice").tag("choice", "POSC.prefix2").counter().count());
    }
}
