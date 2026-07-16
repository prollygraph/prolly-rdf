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
package com.earasoft.prolly.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 2 Step 12 of prolly-rdf-test-strategy — {@link LeapfrogJoin} == set intersection (R-6,
 * worst-case-optimal join correctness). {@code LeapfrogJoinUnitTest} pins the deterministic edges
 * (disjoint / singleton / with-empty / identical); this adds the generative property: for N
 * iterators over real {@link StaticMap}s, the join yields exactly the intersection of their key
 * sets (oracle = {@code retainAll}). The N range (1..4) and a small value pool make empty-iterator
 * and single-iterator cases arise naturally.
 *
 * <p>Drives <b>real</b> {@code StaticMap.iter()} iterators (built via {@code MutableMap.put} +
 * {@code flush}), not a hand-rolled double — so a failure is a real {@code LeapfrogJoin} finding,
 * and the iterators are sorted by the actual tree order (LeapfrogJoin's precondition) by
 * construction.
 */
class LeapfrogJoinProperty {

    private static final TupleDescriptor DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    /**
     * 1..4 groups of values from a small pool, so intersections are frequently non-empty and
     * empty/singleton groups occur.
     */
    @Provide
    Arbitrary<List<List<String>>> valueGroups() {
        Arbitrary<String> v = Arbitraries.of("0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
        Arbitrary<List<String>> group = v.list().ofMaxSize(10);
        return group.list().ofMinSize(1).ofMaxSize(4);
    }

    @Property(tries = 100)
    void leapfrogJoinEqualsIntersection(@ForAll @From("valueGroups") List<List<String>> groups) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();

            // Oracle: intersection of the N value sets.
            Set<String> oracle = new HashSet<>(new LinkedHashSet<>(groups.get(0)));
            for (int i = 1; i < groups.size(); i++) oracle.retainAll(new HashSet<>(groups.get(i)));

            // Build N real sorted iterators over StaticMaps (key-only entries).
            List<MapIterator> iters = new ArrayList<>();
            for (List<String> g : groups) {
                MutableMap mm = new MutableMap(new StaticMap(store, null, DESC), store, DESC, pool);
                for (String v : new LinkedHashSet<>(g)) {
                    mm.put(tuple(pool, v), MemorySegment.NULL);
                }
                iters.add(mm.flush().iter());
            }

            Set<String> joined = new HashSet<>(drain(new LeapfrogJoin(iters, DESC)));
            assertEquals(
                    oracle,
                    joined,
                    "leapfrog join of N sorted iterators must equal their set intersection");
        }
    }

    private static MemorySegment tuple(DirectBufferPool pool, String v) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, v.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }

    private static List<String> drain(MapIterator it) {
        List<String> out = new ArrayList<>();
        while (it.next())
            out.add(new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8));
        return out;
    }
}
