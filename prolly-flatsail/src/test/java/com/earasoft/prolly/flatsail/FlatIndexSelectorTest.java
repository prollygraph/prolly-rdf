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
package com.earasoft.prolly.flatsail;

import static org.junit.jupiter.api.Assertions.*;

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.term.TermId;
import org.junit.jupiter.api.Test;

/** Coverage for {@link FlatIndexSelector} — quad-pattern → index choice. */
class FlatIndexSelectorTest {

    private static final TermId S = TermId.of(10);
    private static final TermId P = TermId.of(20);
    private static final TermId O = TermId.of(30);
    private static final TermId C = TermId.of(40);

    @Test
    void subject_only_scans_spoc() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(S, null, null, null);
        assertEquals(QuadOrder.SPOC, c.order());
        assertArrayEquals(new TermId[] {S}, c.prefixTerms());
    }

    @Test
    void predicate_only_scans_posc() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(null, P, null, null);
        assertEquals(QuadOrder.POSC, c.order());
        assertArrayEquals(new TermId[] {P}, c.prefixTerms());
    }

    @Test
    void object_only_scans_ospc() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(null, null, O, null);
        assertEquals(QuadOrder.OSPC, c.order());
        assertArrayEquals(new TermId[] {O}, c.prefixTerms());
    }

    @Test
    void context_only_scans_cspo() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(null, null, null, C);
        assertEquals(QuadOrder.CSPO, c.order());
        assertArrayEquals(new TermId[] {C}, c.prefixTerms());
    }

    @Test
    void subject_and_predicate_give_a_two_column_spoc_prefix() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(S, P, null, null);
        assertEquals(QuadOrder.SPOC, c.order());
        assertArrayEquals(new TermId[] {S, P}, c.prefixTerms());
    }

    @Test
    void subject_and_object_pick_ospc_so_both_lead() {
        // Only OSPC (object, subject, ...) pins both as leading columns.
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(S, null, O, null);
        assertEquals(QuadOrder.OSPC, c.order());
        assertArrayEquals(new TermId[] {O, S}, c.prefixTerms());
    }

    @Test
    void predicate_and_object_give_a_two_column_posc_prefix() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(null, P, O, null);
        assertEquals(QuadOrder.POSC, c.order());
        assertArrayEquals(new TermId[] {P, O}, c.prefixTerms());
    }

    @Test
    void nothing_bound_is_a_full_scan() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(null, null, null, null);
        assertEquals(0, c.prefixTerms().length, "empty prefix -> full-CF scan");
    }

    @Test
    void all_four_bound_is_an_exact_key_lookup() {
        FlatIndexSelector.Choice c = FlatIndexSelector.choose(S, P, O, C);
        assertEquals(4, c.prefixTerms().length, "four columns -> exact 32-byte key");
    }
}
