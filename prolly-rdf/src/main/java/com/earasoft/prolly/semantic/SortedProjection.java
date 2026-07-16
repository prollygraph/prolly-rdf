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

import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Re-emits a join-input iterator's keys <b>sorted + deduplicated</b> by the join descriptor — the
 * precondition {@link com.earasoft.prolly.indexing.LeapfrogJoin} requires of every input.
 *
 * <p>Why this exists: a {@link ProjectingIterator} yields the join column in the underlying index's
 * key order. That column is sorted only when it is the field <i>immediately after</i> the bound
 * prefix. When an <b>unbound position sits between the prefix and the join column</b> — e.g.
 * pattern {@code (s, ?w, ?j)} scans SPOC prefix {@code [s]} and projects the object across all
 * predicates — the projection comes out ordered by {@code (predicate, object)}, not by object
 * alone, and can repeat a value. Feeding that unsorted/duplicated stream to the leapfrog ring made
 * it silently miss matches (the bug {@code GraphPatternBgpProperty} found; see ADR-0033 /
 * the-leapfrog-join-contract).
 *
 * <p>This wrapper drains the source via {@code next()} only (so it is immune to the source's order
 * <i>and</i> to its broken gapped {@code seek()}), sorts the keys by {@code joinDesc}, drops
 * adjacent duplicates, and serves its own correct {@code next}/{@code seek}. Cost: it materializes
 * the pattern's match set (the rows are read anyway) and is O(m log m) — streaming is traded for
 * correctness, acceptable at this engine's scale; the optimal alternative is a covering index
 * permutation per access pattern (deferred — see ADR-0033).
 */
final class SortedProjection implements MapIterator {

    private final TupleDescriptor joinDesc;
    private final List<MemorySegment> keys = new ArrayList<>();
    private int idx = -1;

    SortedProjection(MapIterator src, TupleDescriptor joinDesc) {
        this.joinDesc = joinDesc;
        List<MemorySegment> raw = new ArrayList<>();
        while (src.next()) {
            // Copy onto the heap — the source may reuse its key buffer across next().
            raw.add(MemorySegment.ofArray(src.key().toArray(ValueLayout.JAVA_BYTE)));
        }
        raw.sort((a, b) -> joinDesc.compare(new Tuple(a), new Tuple(b)));
        MemorySegment prev = null;
        for (MemorySegment k : raw) {
            if (prev == null || joinDesc.compare(new Tuple(prev), new Tuple(k)) != 0) keys.add(k);
            prev = k;
        }
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

    /**
     * Position so the next {@code next()} lands on the first key >= target (the contract
     * LeapfrogJoin's seek-then-next ring relies on).
     */
    @Override
    public void seek(MemorySegment target) {
        for (int i = 0; i < keys.size(); i++) {
            if (joinDesc.compare(new Tuple(keys.get(i)), new Tuple(target)) >= 0) {
                idx = i - 1;
                return;
            }
        }
        idx = keys.size();
    }

    @Override
    public MemorySegment key() {
        return keys.get(idx);
    }

    @Override
    public MemorySegment value() {
        return MemorySegment.NULL;
    }
}
