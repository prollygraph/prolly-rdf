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
import com.earasoft.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Single-variable worst-case-optimal join over several sorted {@link MapIterator}s — the leapfrog
 * intersection at the heart of the triejoin.
 *
 * <p>Given N iterators, each positioned at the start of a sorted, deduplicated key stream, this
 * finds the keys present in <em>every</em> one of them. It keeps the iterators in a ring ordered by
 * their current key and repeatedly advances the one holding the smallest key to at least the
 * largest — "leapfrogging" — so a key that cannot be in the intersection is skipped in a single
 * seek rather than one step at a time. That is what makes the join worst-case optimal: its cost is
 * bounded by the size of the true output, not by the product of the input sizes.
 *
 * @apiNote Construct it with the input iterators and the {@link TupleDescriptor} that orders their
 *     keys; it is itself a {@link MapIterator} over the intersection. <b>The inputs must be sorted
 *     and deduplicated by the join key.</b> A raw projection that emits the join column in index
 *     order is not necessarily sorted (an unbound position between the bound prefix and the join
 *     column breaks it), so callers wrap each input in {@code SortedProjection}; without that
 *     wrapper the join silently misses matches (see ADR-0033). The constructor also sorts the
 *     iterators by their head key once up front — omitting that initial sort made the ring's
 *     smallest-versus-largest comparison meaningless and returned false intersection members (a
 *     fixed bug, now pinned by a property-based test).
 * @implNote <b>Collaborators:</b> the input {@link MapIterator}s (the sorted streams it intersects)
 *     and {@link TupleDescriptor} (the key comparison order). <b>Dependents:</b> {@code
 *     GraphPatternEngine.execute} (the single-variable star join) and {@code LeapfrogTriejoin}
 *     (which reuses one of these per variable level to bind multiple variables).
 */
public class LeapfrogJoin implements MapIterator {
    private final List<MapIterator> iterators;
    private final TupleDescriptor descriptor;
    private int p = 0;
    // @Nullable: null before the first successful next() and after the stream is exhausted; set to
    // the matched key on each next()==true. key() asserts the read-after-positioned-next()
    // contract.
    private @Nullable MemorySegment lastMatch = null;

    public LeapfrogJoin(List<MapIterator> iterators, TupleDescriptor descriptor) {
        this.iterators = new ArrayList<>(iterators);
        this.descriptor = descriptor;
        for (var it : this.iterators) {
            if (!it.next()) {
                this.iterators.clear();
                return;
            }
        }
        // Leapfrog-triejoin REQUIRES the iterators to start sorted by their
        // current (head) key: the ring then advances the smallest past the
        // largest, and that rotation preserves the sorted order. Without this
        // initial sort the ring's "least vs greatest" comparison is bogus — e.g.
        // heads [0, 1, 0] compare iter[0]=0 against ring-neighbour iter[2]=0,
        // declare a match, and skip iter[1]=1 entirely (a false intersection
        // member). Found by LeapfrogJoinProperty, 2026-05-29.
        this.iterators.sort((a, b) -> descriptor.compare(new Tuple(a.key()), new Tuple(b.key())));
    }

    @Override
    public boolean next() {
        if (iterators.isEmpty()) return false;

        if (lastMatch != null) {
            if (!iterators.get(p).next()) return false;
            p = (p + 1) % iterators.size();
        }

        while (true) {
            MemorySegment leastKey = iterators.get(p).key();
            int prev = (p == 0) ? iterators.size() - 1 : p - 1;
            MemorySegment greatestKey = iterators.get(prev).key();

            int cmp = descriptor.compare(new Tuple(leastKey), new Tuple(greatestKey));
            if (cmp == 0) {
                lastMatch = leastKey;
                return true;
            }

            iterators.get(p).seek(greatestKey);
            if (!iterators.get(p).next()) {
                return false;
            }

            p = (p + 1) % iterators.size();
        }
    }

    @Override
    public boolean prev() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void seek(MemorySegment key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MemorySegment key() {
        return Objects.requireNonNull(lastMatch, "key() read before a successful next()");
    }

    @Override
    public MemorySegment value() {
        return java.lang.foreign.MemorySegment.NULL;
    }
}
