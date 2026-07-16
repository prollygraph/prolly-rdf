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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 4 Step 18 of {@code prolly-rdf4j-test-strategy.md} (S-7) — <b>four-index agreement</b>, the
 * angle the per-order {@code QuadIndexModelProperty} doesn't directly assert: the four permutation
 * indexes <b>jointly populated</b> (every quad written to all four, exactly as {@code
 * ProllySailConnection.writeQuad} does) hold the <i>same logical quad set</i> as each other.
 *
 * <p>The comparison is on what is order-<i>independent</i>: <b>cardinality</b> ({@code iter()} over
 * each order returns the same count) and <b>membership</b> ({@code contains(s,p,o,c)} agrees across
 * all four). The raw {@code scan} output is deliberately <i>not</i> compared across orders — it
 * keys on the leading bound <i>physical</i> columns (capped at 3) without post-filtering, so each
 * order returns a different superset by design (that per-order superset contract is pinned by
 * {@code QuadIndexModelProperty}; the planner's exact-match correctness by {@code
 * SailDifferentialProperty.patternsAgree}). Small {@code {1,2,3}} alphabet so quads collide and the
 * probe set straddles present/absent.
 */
class FourIndexAgreementProperty {

    private static TermId t(long v) {
        return TermId.of(v);
    }

    private static int count(Iterator<SpocKey> it) {
        int n = 0;
        while (it.hasNext()) {
            it.next();
            n++;
        }
        return n;
    }

    @Property(tries = 200)
    void the_four_jointly_populated_indexes_hold_the_same_set(
            @ForAll @From("quads") List<List<Long>> inserts,
            @ForAll @From("quads") List<List<Long>> probes) {
        EnumMap<QuadOrder, QuadIndex> idx = new EnumMap<>(QuadOrder.class);
        for (QuadOrder o : QuadOrder.values()) {
            idx.put(o, new QuadIndex(o, new InMemoryNodeStore(), new HeapBufferPool()));
        }

        // Joint population: the same (s,p,o,c) into ALL four indexes (mirrors writeQuad).
        Set<List<Long>> inserted = new HashSet<>();
        for (List<Long> q : inserts) {
            for (QuadIndex qi : idx.values()) {
                qi.insert(t(q.get(0)), t(q.get(1)), t(q.get(2)), t(q.get(3)));
            }
            inserted.add(q);
        }
        for (QuadIndex qi : idx.values()) {
            qi.commit();
        }

        // (1) Cardinality agreement: each order's full iteration counts the same set.
        for (QuadOrder o : QuadOrder.values()) {
            assertEquals(
                    inserted.size(),
                    count(idx.get(o).iter()),
                    o + ": iter() cardinality must equal the inserted set's size");
        }

        // (2) Membership agreement: contains() is identical across all four for any probe.
        for (List<Long> q : probes) {
            boolean present = inserted.contains(q);
            for (QuadOrder o : QuadOrder.values()) {
                assertEquals(
                        present,
                        idx.get(o).contains(t(q.get(0)), t(q.get(1)), t(q.get(2)), t(q.get(3))),
                        o + ": contains(" + q + ") must agree with the other indexes");
            }
        }
    }

    /** 0–12 quads over the small {1,2,3} alphabet (heavy collision). */
    @Provide
    Arbitrary<List<List<Long>>> quads() {
        Arbitrary<Long> col = Arbitraries.longs().between(1, 3);
        Arbitrary<List<Long>> quad =
                Combinators.combine(col, col, col, col).as((a, b, c, d) -> List.of(a, b, c, d));
        return quad.list().ofMinSize(0).ofMaxSize(12);
    }
}
