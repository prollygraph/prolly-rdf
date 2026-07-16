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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.Iterator;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A 4-column index over a logical RDF quad, parameterized by a {@link QuadOrder}.
 *
 * <p>Same underlying tree shape as {@link SpocIndex}; the {@link QuadOrder} tells callers how to
 * translate between (s, p, o, c) and (col0..col3).
 *
 * <p>Public API speaks in logical (s, p, o, c) form. Callers don't need to know which permutation
 * the index uses — that's an internal detail that affects which read patterns are efficient.
 *
 * @apiNote One {@code QuadIndex} wraps one permutation. The full quad store keeps four of them
 *     (SPOC, POSC, OSPC, CSPO) so that any triple pattern has an index whose key prefix matches its
 *     bound columns — the planner picks the permutation, this class hides it. {@code insert} /
 *     {@code delete} / {@code contains} all take logical (s, p, o, c) and route through the {@link
 *     QuadOrder} to the physical column order.
 * @implNote <b>Collaborators:</b> {@link SpocIndex} (the key-only prolly map it delegates to),
 *     {@link QuadOrder} (the logical-to-physical column mapping), {@link TermId} (the per-column
 *     identifiers). <b>Dependents:</b> the Sail's per-transaction index set ({@code
 *     ProllySailConnection}) and the query planner's index selection.
 */
public final class QuadIndex {

    private final QuadOrder order;
    private final SpocIndex index;

    public QuadIndex(QuadOrder order, NodeStore store, BufferPool pool) {
        this(order, store, pool, com.dolthub.prolly.BoundarySplitter.ROLLING_HASH);
    }

    /** Seam constructor (SPOC boundary-function-adoption D-1): inject the boundary function. */
    public QuadIndex(
            QuadOrder order,
            NodeStore store,
            BufferPool pool,
            com.dolthub.prolly.BoundarySplitter.Factory splitterFactory) {
        this.order = order;
        this.index = new SpocIndex(store, pool, splitterFactory);
    }

    public QuadIndex(QuadOrder order, NodeStore store, BufferPool pool, StaticMap committed) {
        this(order, store, pool, committed, com.dolthub.prolly.BoundarySplitter.ROLLING_HASH);
    }

    /** Seam constructor (SPOC boundary-function-adoption D-1): inject the boundary function. */
    public QuadIndex(
            QuadOrder order,
            NodeStore store,
            BufferPool pool,
            StaticMap committed,
            com.dolthub.prolly.BoundarySplitter.Factory splitterFactory) {
        this.order = order;
        this.index = new SpocIndex(store, pool, committed, splitterFactory);
    }

    public QuadOrder order() {
        return order;
    }

    public void insert(TermId s, TermId p, TermId o, TermId c) {
        index.insert(order.keyOf(s, p, o, c));
    }

    public void delete(TermId s, TermId p, TermId o, TermId c) {
        index.delete(order.keyOf(s, p, o, c));
    }

    public boolean contains(TermId s, TermId p, TermId o, TermId c) {
        return index.contains(order.keyOf(s, p, o, c));
    }

    public StaticMap commit() {
        return index.commit();
    }

    /**
     * Open a cursor over rows that match the supplied bound positions, where {@code null} means
     * "any".
     *
     * <p>This index is efficient ONLY when the bound positions form a leading prefix in {@link
     * #order}. Other patterns trigger a full scan + filter at the caller's discretion (the planner
     * uses {@link #leadingPrefixLength} to choose the right index).
     */
    public Iterator<SpocKey> scan(
            @Nullable TermId s, @Nullable TermId p, @Nullable TermId o, @Nullable TermId c) {
        TermId @Nullable [] logical = {s, p, o, c};
        // Build the prefix array from the leading columns of this index's order
        int prefixLen = leadingPrefixLength(s, p, o, c);
        if (prefixLen == 0) {
            return index.iter();
        }
        TermId[] prefix = new TermId[prefixLen];
        for (int col = 0; col < prefixLen; col++) {
            int logicalIndex = physicalToLogical(col);
            // col < prefixLen ⟹ this leading physical column's logical position is bound
            // (non-null).
            prefix[col] = Objects.requireNonNull(logical[logicalIndex]);
        }
        return index.iterPrefix(prefix);
    }

    /**
     * @return the number of leading physical columns whose logical position is bound (non-null).
     *     E.g., for SPOC with (s, p, ?, ?), this returns 2.
     */
    public int leadingPrefixLength(
            @Nullable TermId s, @Nullable TermId p, @Nullable TermId o, @Nullable TermId c) {
        TermId @Nullable [] logical = {s, p, o, c};
        int n = 0;
        for (int col = 0; col < 4; col++) {
            if (logical[physicalToLogical(col)] != null) n++;
            else break;
        }
        // SpocIndex.iterPrefix only supports 1..3 columns
        return Math.min(n, 3);
    }

    private int physicalToLogical(int col) {
        // Inverse of the permutation QuadOrder.keyOf applies.
        return switch (order) {
            case SPOC -> col; // (0,1,2,3) → (0,1,2,3)
            case POSC -> col == 0 ? 1 : col == 1 ? 2 : col == 2 ? 0 : 3; // (1,2,0,3)
            case OSPC -> col == 0 ? 2 : col == 1 ? 0 : col == 2 ? 1 : 3; // (2,0,1,3)
            case CSPO -> col == 0 ? 3 : col == 1 ? 0 : col == 2 ? 1 : 2; // (3,0,1,2)
        };
    }

    /** Full scan, when no prefix can be inferred. */
    public Iterator<SpocKey> iter() {
        return index.iter();
    }
}
