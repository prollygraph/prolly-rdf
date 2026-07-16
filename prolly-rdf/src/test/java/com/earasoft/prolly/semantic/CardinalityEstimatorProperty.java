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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
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
 * Phase 2 Step 15 of prolly-rdf-test-strategy — {@link CardinalityEstimator} bounds (R-6). The
 * estimator computes {@code estimateRange = pos(end) - pos(start)} where {@code pos(key)} is the
 * key's ordinal, summed from the prolly tree's exact subtree counts. Since those counts are exact,
 * the estimate is in fact the <b>exact</b> number of keys in {@code [start,end)} — this property
 * pins that (and {@code estimatePrefix}'s group count) against an oracle over the known key set,
 * and thereby the {@code pos(end)-pos(start)} ordinal-arithmetic invariant.
 *
 * <p>Keys are two-field {@code (group,item)} tuples over small domains (mirrors the index-prefix
 * shape exercised by {@code CardinalityTest}). Drives a real {@link StaticMap}, no mocks.
 */
class CardinalityEstimatorProperty {

    private static final List<String> GROUPS = List.of("g0", "g1", "g2");
    private static final List<String> ITEMS =
            List.of("i0", "i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8", "i9");
    private static final TupleDescriptor DESC =
            new TupleDescriptor(
                    List.of(new Type(Encoding.String, false), new Type(Encoding.String, false)));

    record Key(String group, String item) {}

    @Provide
    Arbitrary<Set<Key>> keySets() {
        Arbitrary<Key> k =
                Combinators.combine(Arbitraries.of(GROUPS), Arbitraries.of(ITEMS)).as(Key::new);
        return k.set().ofMinSize(1).ofMaxSize(30);
    }

    @Property(tries = 50)
    void estimatesAreExactAgainstTheKeySet(
            @ForAll @From("keySets") Set<Key> keys,
            @ForAll("ITEMS") String lo,
            @ForAll("ITEMS") String hi,
            @ForAll("GROUPS") String gLo,
            @ForAll("GROUPS") String gHi) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, DESC), store, DESC, pool);
            List<MemorySegment> sortedKeys = new ArrayList<>();
            for (Key k : keys) {
                MemorySegment t = key2(pool, k.group(), k.item());
                mm.put(t, MemorySegment.NULL);
                sortedKeys.add(t);
            }
            StaticMap map = mm.flush();
            CardinalityEstimator est = new CardinalityEstimator(map);

            // estimatePrefix(group): exact count of keys in that group. The
            // prefix must be the RAW field-0 bytes (a byte-prefix of the 2-field
            // keys), NOT a framed 1-field tuple — extract them via the Tuple
            // decoder so the encoding matches exactly (mirrors CardinalityTest's
            // getFieldSegment(0)).
            for (String g : GROUPS) {
                long oracle = keys.stream().filter(k -> k.group().equals(g)).count();
                byte[] rawG = new Tuple(key2(pool, g, ITEMS.get(0))).getField(0);
                assertEquals(
                        oracle,
                        est.estimatePrefix(MemorySegment.ofArray(rawG)),
                        "estimatePrefix(" + g + ") must be the exact group count");
            }

            // estimateRange(a,b) with a<=b in tuple order: exact count in [a,b).
            MemorySegment a = key2(pool, gLo, lo);
            MemorySegment b = key2(pool, gHi, hi);
            if (DESC.compare(new Tuple(a), new Tuple(b)) > 0) {
                MemorySegment t = a;
                a = b;
                b = t;
            }
            final MemorySegment loKey = a, hiKey = b;
            long oracle =
                    sortedKeys.stream()
                            .filter(
                                    k ->
                                            DESC.compare(new Tuple(k), new Tuple(loKey)) >= 0
                                                    && DESC.compare(new Tuple(k), new Tuple(hiKey))
                                                            < 0)
                            .count();
            assertEquals(
                    oracle,
                    est.estimateRange(loKey, hiKey),
                    "estimateRange = pos(end)-pos(start) must equal the exact count in [a,b)");

            // estimateRange(a, null) == keys with key >= a (open-ended to treeCount).
            long oracleOpen =
                    sortedKeys.stream()
                            .filter(k -> DESC.compare(new Tuple(k), new Tuple(loKey)) >= 0)
                            .count();
            assertEquals(
                    oracleOpen,
                    est.estimateRange(loKey, null),
                    "estimateRange to null end must count keys >= start (treeCount - pos(start))");
        }
    }

    @Provide
    Arbitrary<String> ITEMS() {
        return Arbitraries.of(ITEMS);
    }

    @Provide
    Arbitrary<String> GROUPS() {
        return Arbitraries.of(GROUPS);
    }

    private static MemorySegment key2(DirectBufferPool pool, String g, String i) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, g.getBytes());
        tb.putField(1, i.getBytes());
        return tb.build().segment();
    }
}
