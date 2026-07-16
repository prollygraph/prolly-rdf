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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.earasoft.prolly.rdf4j.term.TermId;
import org.junit.jupiter.api.Test;

/**
 * Mutation + behaviour coverage for {@link QuadOrder} / {@link QuadRole}, the logical-(s,p,o,c) ↔
 * physical-column permutation. These classes live in prolly-codec but were previously exercised
 * only by {@code QuadIndexModelProperty} in prolly-rdf4j — which cannot mutate them — so a
 * codec-resident test is the only way they get mutation coverage.
 */
class QuadOrderTest {

    private static final TermId S = TermId.of(1);
    private static final TermId P = TermId.of(2);
    private static final TermId O = TermId.of(3);
    private static final TermId C = TermId.of(4);

    private static void assertCols(SpocKey k, TermId c0, TermId c1, TermId c2, TermId c3) {
        assertEquals(c0, k.col0());
        assertEquals(c1, k.col1());
        assertEquals(c2, k.col2());
        assertEquals(c3, k.col3());
    }

    @Test
    void keyOf_permutes_columns_per_order() {
        assertCols(QuadOrder.SPOC.keyOf(S, P, O, C), S, P, O, C);
        assertCols(QuadOrder.POSC.keyOf(S, P, O, C), P, O, S, C);
        assertCols(QuadOrder.OSPC.keyOf(S, P, O, C), O, S, P, C);
        assertCols(QuadOrder.CSPO.keyOf(S, P, O, C), C, S, P, O);
    }

    @Test
    void role_maps_to_the_matching_quadrole() {
        assertEquals(QuadRole.SPOC, QuadOrder.SPOC.role());
        assertEquals(QuadRole.POSC, QuadOrder.POSC.role());
        assertEquals(QuadRole.OSPC, QuadOrder.OSPC.role());
        assertEquals(QuadRole.CSPO, QuadOrder.CSPO.role());
    }

    @Test
    void role_col_round_trips_every_logical_position_for_every_order() {
        // role().col(keyOf(s,p,o,c), L) must return the L-th LOGICAL term, whatever the
        // physical layout — the inverse of keyOf. Pins both permutation directions at once.
        TermId[] logical = {S, P, O, C};
        for (QuadOrder order : QuadOrder.values()) {
            SpocKey key = order.keyOf(S, P, O, C);
            QuadRole role = order.role();
            for (int l = 0; l < 4; l++) {
                assertEquals(logical[l], role.col(key, l), order + " logical position " + l);
            }
        }
    }

    @Test
    void role_col_rejects_out_of_range_position() {
        SpocKey key = QuadOrder.SPOC.keyOf(S, P, O, C);
        assertThrows(IllegalArgumentException.class, () -> QuadRole.SPOC.col(key, 4));
        assertThrows(IllegalArgumentException.class, () -> QuadRole.SPOC.col(key, -1));
    }

    @Test
    void metric_keys_are_order_named() {
        assertEquals("index.spoc.insert", QuadOrder.SPOC.insertMetricKey());
        assertEquals("index.spoc.delete", QuadOrder.SPOC.deleteMetricKey());
        assertEquals("index.posc.insert", QuadOrder.POSC.insertMetricKey());
        assertEquals("index.cspo.delete", QuadOrder.CSPO.deleteMetricKey());
    }
}
