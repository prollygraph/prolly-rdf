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
package com.earasoft.prolly.rdf4j.term;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TermStatsTest {

    private TermStats fresh() {
        return new TermStats(new InMemoryNodeStore(), new HeapBufferPool());
    }

    @Test
    void empty_stats_returns_zero() {
        TermStats s = fresh();
        assertEquals(0L, s.frequency(TermId.of(0L)));
        assertEquals(0L, s.frequency(TermId.of(42L)));
    }

    @Test
    void increment_then_read_in_pending() {
        TermStats s = fresh();
        s.increment(TermId.of(1L), 5L);
        assertEquals(5L, s.frequency(TermId.of(1L)));
    }

    @Test
    void multiple_increments_compose_additively() {
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.increment(id, 1L);
        s.increment(id, 2L);
        s.increment(id, 3L);
        assertEquals(6L, s.frequency(id));
    }

    @Test
    void increment_default_is_one() {
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.increment(id);
        s.increment(id);
        s.increment(id);
        assertEquals(3L, s.frequency(id));
    }

    @Test
    void decrement_subtracts() {
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.increment(id, 10L);
        s.decrement(id, 4L);
        assertEquals(6L, s.frequency(id));
    }

    @Test
    void net_zero_increments_canonicalize_to_zero() {
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.increment(id, 5L);
        s.decrement(id, 5L);
        assertEquals(0L, s.frequency(id));
    }

    @Test
    void negative_frequency_allowed() {
        // Stats are signed — net-negative deltas are legal for merge semantics
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.decrement(id, 3L);
        assertEquals(-3L, s.frequency(id));
    }

    @Test
    void distinct_terms_dont_interfere() {
        TermStats s = fresh();
        s.increment(TermId.of(1L), 100L);
        s.increment(TermId.of(2L), 200L);
        s.increment(TermId.of(3L), 300L);
        assertEquals(100L, s.frequency(TermId.of(1L)));
        assertEquals(200L, s.frequency(TermId.of(2L)));
        assertEquals(300L, s.frequency(TermId.of(3L)));
    }

    @Test
    void commit_persists_pending_and_clears_buffer() {
        TermStats s = fresh();
        s.increment(TermId.of(1L), 5L);
        s.commit();
        assertEquals(5L, s.frequency(TermId.of(1L))); // still 5 after commit
        s.increment(TermId.of(1L), 3L);
        assertEquals(8L, s.frequency(TermId.of(1L))); // committed + new pending
    }

    @Test
    void commit_cycle_accumulates_correctly() {
        TermStats s = fresh();
        TermId id = TermId.of(7L);
        s.increment(id, 1L);
        s.commit();
        s.increment(id, 2L);
        s.commit();
        s.increment(id, 3L);
        s.commit();
        assertEquals(6L, s.frequency(id));
    }

    @Test
    void many_distinct_terms_accumulate_across_commits() {
        // Regression for the two-pass commit() rewrite: a commit of many
        // distinct terms must read each term's prior committed value (pass 1)
        // before writing any (pass 2). Each term's final frequency is the sum
        // of its deltas across both commits — no cross-term interference, and
        // the second commit correctly adds onto the first commit's values.
        TermStats s = fresh();
        int n = 200;
        for (int i = 0; i < n; i++) {
            s.increment(TermId.of(i + 1), i + 1); // term i: +(i+1)
        }
        s.commit();
        for (int i = 0; i < n; i++) {
            s.increment(TermId.of(i + 1), 10L); // term i: +10 more
        }
        s.commit();
        for (int i = 0; i < n; i++) {
            assertEquals(
                    (i + 1) + 10L,
                    s.frequency(TermId.of(i + 1)),
                    "term " + (i + 1) + " must equal its committed value plus the new delta");
        }
    }

    @Test
    void reopen_at_committed_root_preserves_counters() {
        NodeStore store = new InMemoryNodeStore();
        BufferPool pool = new HeapBufferPool();
        TermStats s1 = new TermStats(store, pool);
        s1.increment(TermId.of(1L), 42L);
        s1.increment(TermId.of(2L), 17L);
        StaticMap committed = s1.commit();

        TermStats s2 = new TermStats(store, pool, committed);
        assertEquals(42L, s2.frequency(TermId.of(1L)));
        assertEquals(17L, s2.frequency(TermId.of(2L)));
        assertEquals(0L, s2.frequency(TermId.of(3L)));
    }

    @Test
    void large_positive_value_round_trips() {
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.increment(id, Long.MAX_VALUE - 1);
        s.commit();
        assertEquals(Long.MAX_VALUE - 1, s.frequency(id));
    }

    @Test
    void large_negative_value_round_trips() {
        TermStats s = fresh();
        TermId id = TermId.of(1L);
        s.decrement(id, Long.MAX_VALUE);
        s.commit();
        assertEquals(-Long.MAX_VALUE, s.frequency(id));
    }

    @Test
    void extension_term_ids_work() {
        TermStats s = fresh();
        TermId ext = TermId.ofExtensionSlot(42L);
        s.increment(ext, 5L);
        s.commit();
        assertEquals(5L, s.frequency(ext));
    }

    @Test
    void commit_then_read_with_no_changes_safe() {
        TermStats s = fresh();
        s.commit(); // empty
        assertEquals(0L, s.frequency(TermId.of(1L)));
        s.increment(TermId.of(1L), 1L);
        s.commit();
        assertEquals(1L, s.frequency(TermId.of(1L)));
    }

    @Test
    void stress_random_increments_across_many_terms() {
        SplittableRandom r = new SplittableRandom(0xC0DEL);
        TermStats s = fresh();
        long[] expected = new long[100];
        TermId[] ids = new TermId[100];
        for (int i = 0; i < 100; i++) ids[i] = TermId.of((long) i);
        for (int op = 0; op < 1000; op++) {
            int i = r.nextInt(100);
            long delta = r.nextLong(-10, 11);
            s.increment(ids[i], delta);
            expected[i] += delta;
            if (op % 100 == 0) s.commit();
        }
        s.commit();
        for (int i = 0; i < 100; i++) {
            assertEquals(expected[i], s.frequency(ids[i]), "freq mismatch at i=" + i);
        }
    }
}
