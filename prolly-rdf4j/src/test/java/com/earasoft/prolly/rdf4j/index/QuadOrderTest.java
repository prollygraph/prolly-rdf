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

import com.earasoft.prolly.rdf4j.term.TermId;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link QuadOrder}. The permutation enum is what makes the multi-index
 * design work: every BGP lookup picks the index whose physical column order matches the bound
 * positions. Drift in the permutation silently shuffles data into the wrong index, and SPARQL
 * queries return spurious results without throwing.
 */
class QuadOrderTest {

    private static final TermId S = TermId.of(1L);
    private static final TermId P = TermId.of(2L);
    private static final TermId O = TermId.of(3L);
    private static final TermId C = TermId.of(4L);

    // ---- SPOC permutation: (s, p, o, c) → (col0=s, col1=p, col2=o, col3=c) ----

    @Test
    void SPOC_keyOf_identity_permutation() {
        SpocKey k = QuadOrder.SPOC.keyOf(S, P, O, C);
        assertEquals(S, k.col0(), "SPOC col0 == subject");
        assertEquals(P, k.col1(), "SPOC col1 == predicate");
        assertEquals(O, k.col2(), "SPOC col2 == object");
        assertEquals(C, k.col3(), "SPOC col3 == context");
    }

    // ---- POSC permutation: (s, p, o, c) → (col0=p, col1=o, col2=s, col3=c) ----

    @Test
    void POSC_keyOf_rotates_subject_to_col2() {
        SpocKey k = QuadOrder.POSC.keyOf(S, P, O, C);
        assertEquals(P, k.col0(), "POSC col0 == predicate");
        assertEquals(O, k.col1(), "POSC col1 == object");
        assertEquals(S, k.col2(), "POSC col2 == subject");
        assertEquals(C, k.col3(), "POSC col3 == context");
    }

    // ---- OSPC permutation: (s, p, o, c) → (col0=o, col1=s, col2=p, col3=c) ----

    @Test
    void OSPC_keyOf_puts_object_first() {
        SpocKey k = QuadOrder.OSPC.keyOf(S, P, O, C);
        assertEquals(O, k.col0(), "OSPC col0 == object");
        assertEquals(S, k.col1(), "OSPC col1 == subject");
        assertEquals(P, k.col2(), "OSPC col2 == predicate");
        assertEquals(C, k.col3(), "OSPC col3 == context");
    }

    // ---- CSPO permutation: (s, p, o, c) → (col0=c, col1=s, col2=p, col3=o) ----

    @Test
    void CSPO_keyOf_puts_context_first() {
        SpocKey k = QuadOrder.CSPO.keyOf(S, P, O, C);
        assertEquals(C, k.col0(), "CSPO col0 == context");
        assertEquals(S, k.col1(), "CSPO col1 == subject");
        assertEquals(P, k.col2(), "CSPO col2 == predicate");
        assertEquals(O, k.col3(), "CSPO col3 == object");
    }

    // ---- role() mapping ----

    @Test
    void SPOC_role_is_identity() {
        QuadRole r = QuadOrder.SPOC.role();
        assertSame(QuadRole.SPOC, r);
        assertEquals(0, r.s());
        assertEquals(1, r.p());
        assertEquals(2, r.o());
        assertEquals(3, r.c());
    }

    @Test
    void POSC_role_inverts_permutation() {
        // POSC stores (p, o, s, c); the role reverse-maps logical → column:
        // s is at col 2, p at col 0, o at col 1, c at col 3.
        QuadRole r = QuadOrder.POSC.role();
        assertSame(QuadRole.POSC, r);
        assertEquals(2, r.s());
        assertEquals(0, r.p());
        assertEquals(1, r.o());
        assertEquals(3, r.c());
    }

    @Test
    void OSPC_role_inverts_permutation() {
        QuadRole r = QuadOrder.OSPC.role();
        assertSame(QuadRole.OSPC, r);
        assertEquals(1, r.s());
        assertEquals(2, r.p());
        assertEquals(0, r.o());
        assertEquals(3, r.c());
    }

    @Test
    void CSPO_role_inverts_permutation() {
        QuadRole r = QuadOrder.CSPO.role();
        assertSame(QuadRole.CSPO, r);
        assertEquals(1, r.s());
        assertEquals(2, r.p());
        assertEquals(3, r.o());
        assertEquals(0, r.c());
    }

    // ---- enum cardinality / completeness ----

    @Test
    void enum_has_exactly_four_values() {
        // Pin the cardinality so a fifth index addition is intentional —
        // adding an index without updating planners and readers silently
        // breaks query results.
        assertEquals(
                4,
                QuadOrder.values().length,
                "QuadOrder cardinality changed — review all multi-index call sites");
    }

    @Test
    void valueOf_works_for_each_name() {
        assertEquals(QuadOrder.SPOC, QuadOrder.valueOf("SPOC"));
        assertEquals(QuadOrder.POSC, QuadOrder.valueOf("POSC"));
        assertEquals(QuadOrder.OSPC, QuadOrder.valueOf("OSPC"));
        assertEquals(QuadOrder.CSPO, QuadOrder.valueOf("CSPO"));
    }

    // ---- round-trip property ----

    @Test
    void keyOf_then_role_recovers_logical_quad() {
        // For each order, build the key, then use the role to read each
        // logical position back. Must match the original (s, p, o, c).
        TermId[] logical = {S, P, O, C};
        for (QuadOrder order : QuadOrder.values()) {
            SpocKey k = order.keyOf(S, P, O, C);
            TermId[] cols = {k.col0(), k.col1(), k.col2(), k.col3()};
            QuadRole r = order.role();
            assertEquals(logical[0], cols[r.s()], order + ": subject must be at role.s() column");
            assertEquals(logical[1], cols[r.p()], order + ": predicate must be at role.p() column");
            assertEquals(logical[2], cols[r.o()], order + ": object must be at role.o() column");
            assertEquals(logical[3], cols[r.c()], order + ": context must be at role.c() column");
        }
    }

    @Test
    void all_columns_unique_per_order() {
        // The permutation must be a bijection — no logical position can land
        // in two columns. Verify via Set semantics.
        for (QuadOrder order : QuadOrder.values()) {
            SpocKey k = order.keyOf(S, P, O, C);
            java.util.Set<TermId> seen = new java.util.HashSet<>();
            seen.add(k.col0());
            seen.add(k.col1());
            seen.add(k.col2());
            seen.add(k.col3());
            assertEquals(4, seen.size(), order + ": permutation must be bijective");
        }
    }

    // ---- pre-computed metric keys ----

    @Test
    void insert_metric_keys_follow_index_order_convention() {
        assertEquals("index.spoc.insert", QuadOrder.SPOC.insertMetricKey());
        assertEquals("index.posc.insert", QuadOrder.POSC.insertMetricKey());
        assertEquals("index.ospc.insert", QuadOrder.OSPC.insertMetricKey());
        assertEquals("index.cspo.insert", QuadOrder.CSPO.insertMetricKey());
    }

    @Test
    void delete_metric_keys_follow_index_order_convention() {
        assertEquals("index.spoc.delete", QuadOrder.SPOC.deleteMetricKey());
        assertEquals("index.posc.delete", QuadOrder.POSC.deleteMetricKey());
        assertEquals("index.ospc.delete", QuadOrder.OSPC.deleteMetricKey());
        assertEquals("index.cspo.delete", QuadOrder.CSPO.deleteMetricKey());
    }

    @Test
    void metric_keys_are_precomputed_and_stable() {
        // The ingest hot path reads these per index per triple — they must be
        // a cached constant, not a string rebuilt on every call.
        for (QuadOrder order : QuadOrder.values()) {
            assertSame(
                    order.insertMetricKey(),
                    order.insertMetricKey(),
                    order + ": insertMetricKey must return the same cached instance");
            assertSame(
                    order.deleteMetricKey(),
                    order.deleteMetricKey(),
                    order + ": deleteMetricKey must return the same cached instance");
        }
    }

    @Test
    void insert_and_delete_metric_keys_are_all_distinct() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (QuadOrder order : QuadOrder.values()) {
            assertTrue(keys.add(order.insertMetricKey()), "duplicate insert key: " + order);
            assertTrue(keys.add(order.deleteMetricKey()), "duplicate delete key: " + order);
        }
        assertEquals(8, keys.size(), "4 orders × {insert, delete} = 8 distinct metric keys");
    }

    @Test
    void metric_key_matches_legacy_concatenation_form() {
        // The pre-computed key must equal what the old inline
        // "index." + name().toLowerCase() + ".insert" produced — metrics
        // dashboards and aggregations key on these exact strings.
        for (QuadOrder order : QuadOrder.values()) {
            String lc = order.name().toLowerCase(java.util.Locale.ROOT);
            assertEquals("index." + lc + ".insert", order.insertMetricKey());
            assertEquals("index." + lc + ".delete", order.deleteMetricKey());
        }
    }
}
