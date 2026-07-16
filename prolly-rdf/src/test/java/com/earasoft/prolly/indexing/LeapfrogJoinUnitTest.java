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

import com.dolthub.prolly.*;
import com.earasoft.prolly.pool.*;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 *
 * <h3>LeapfrogJoin Algorithm Test</h3>
 *
 * <p>Pins the worst-case-optimal-join behaviour of {@link
 * com.earasoft.prolly.indexing.LeapfrogJoin}: given N sorted iterators, it emits exactly the
 * elements present in <b>all</b> N iterators, in sorted order.
 *
 * <p><b>The Gap:</b> {@code LeapfrogJoin} backs SPARQL-style joins via the BGP engine. The existing
 * {@code LeapfrogJoinTest} is shallow (53 lines, single example). This test exercises the algorithm
 * as a unit using a synthetic in-memory iterator over sorted keys, covering non-trivial overlaps,
 * the singleton-iterator passthrough, the empty- intersection case, and the empty-input case.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>3-way overlap: {A=[1,2,5,7,9], B=[2,5,8], C=[2,5,9]} → emit [2,5].
 *   <li>Disjoint: {A=[1,3,5], B=[2,4,6]} → emit nothing.
 *   <li>Singleton: {A=[1,2,3]} → emit [1,2,3] (intersection of one set is itself).
 *   <li>Empty input iterator: {A=[]} → emit nothing.
 *   <li>Identical iterators: {A=A=A=[1,2,3]} → emit [1,2,3].
 * </ol>
 */
public class LeapfrogJoinUnitTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- LeapfrogJoin Algorithm Test ---");
        try (DirectBufferPool pool = new DirectBufferPool()) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));

            // Oracle 1: 3-way overlap → [2, 5]
            String[] expected1 = {"2", "5"};
            List<MapIterator> set1 =
                    List.of(
                            iter(pool, desc, "1", "2", "5", "7", "9"),
                            iter(pool, desc, "2", "5", "8"),
                            iter(pool, desc, "2", "5", "9"));
            check("3-way overlap", expected1, drain(new LeapfrogJoin(set1, desc)));
            System.out.println("3-way overlap intersection. (1/5)");

            // Oracle 2: disjoint → []
            List<MapIterator> set2 =
                    List.of(iter(pool, desc, "1", "3", "5"), iter(pool, desc, "2", "4", "6"));
            check("disjoint", new String[0], drain(new LeapfrogJoin(set2, desc)));
            System.out.println("Disjoint sets emit nothing. (2/5)");

            // Oracle 3: singleton.
            List<MapIterator> set3 = List.of(iter(pool, desc, "1", "2", "3"));
            check("singleton", new String[] {"1", "2", "3"}, drain(new LeapfrogJoin(set3, desc)));
            System.out.println("Singleton iterator emits unchanged. (3/5)");

            // Oracle 4: empty input iterator (one of the iterators is empty).
            List<MapIterator> set4 = List.of(iter(pool, desc, "1", "2", "3"), iter(pool, desc));
            check("with-empty", new String[0], drain(new LeapfrogJoin(set4, desc)));
            System.out.println("Empty input iterator → empty intersection. (4/5)");

            // Oracle 5: identical iterators
            List<MapIterator> set5 =
                    List.of(
                            iter(pool, desc, "1", "2", "3"),
                            iter(pool, desc, "1", "2", "3"),
                            iter(pool, desc, "1", "2", "3"));
            check("identical", new String[] {"1", "2", "3"}, drain(new LeapfrogJoin(set5, desc)));
            System.out.println("Identical iterators emit the full sequence. (5/5)");

            System.out.println("--- LeapfrogJoin Algorithm Test PASSED ---");
        }
    }

    private static void check(String name, String[] expected, List<String> got) {
        if (!Arrays.equals(expected, got.toArray(new String[0]))) {
            throw new RuntimeException(
                    name + " mismatch: expected=" + Arrays.toString(expected) + " got=" + got);
        }
    }

    /** Drain a MapIterator, returning the first-field decoded values in order. */
    private static List<String> drain(MapIterator it) {
        List<String> out = new ArrayList<>();
        while (it.next()) {
            Tuple t = new Tuple(it.key());
            out.add(new String(t.getField(0), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** Builds an in-memory MapIterator over a sorted list of single-field tuples. */
    private static MapIterator iter(DirectBufferPool pool, TupleDescriptor desc, String... values) {
        List<MemorySegment> keys = new ArrayList<>();
        for (String v : values) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, v.getBytes(StandardCharsets.UTF_8));
            keys.add(tb.build().segment());
        }
        return new ListIter(keys, desc);
    }

    /** Minimal sorted-list-backed MapIterator that satisfies LeapfrogJoin's contract. */
    private static class ListIter implements MapIterator {
        private final List<MemorySegment> keys;
        private final TupleDescriptor desc;
        private int idx = -1;

        ListIter(List<MemorySegment> keys, TupleDescriptor desc) {
            this.keys = keys;
            this.desc = desc;
        }

        @Override
        public boolean next() {
            idx++;
            return idx < keys.size();
        }

        @Override
        public boolean prev() {
            return false;
        }

        @Override
        public void seek(MemorySegment target) {
            // Move idx forward to the first key >= target. LeapfrogJoin calls
            // seek(greatestKey) followed by next(); next() then advances to idx
            // (rather than past), matching standard iterator semantics. Implement
            // as: leave idx pointing at the position BEFORE the first match, so
            // the subsequent next() advances onto it.
            for (int i = 0; i < keys.size(); i++) {
                if (desc.compare(new Tuple(keys.get(i)), new Tuple(target)) >= 0) {
                    idx = i - 1;
                    return;
                }
            }
            idx = keys.size();
        }

        @Override
        public MemorySegment key() {
            return idx >= 0 && idx < keys.size() ? keys.get(idx) : null;
        }

        @Override
        public MemorySegment value() {
            return MemorySegment.NULL;
        }
    }
}
