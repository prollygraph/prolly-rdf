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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-term frequency counter table.
 *
 * <p>Maps {@link TermId} to a signed 64-bit frequency. Used by the query planner to estimate
 * selectivity when choosing an index for a triple pattern (Phase 3 work).
 *
 * <p>Increments are accumulated in a per-instance heap delta map and applied to the persisted
 * counter at {@link #commit()}. Reads check the committed value plus the pending delta. Multiple
 * writers commit independent delta sets; the Sail-level compare-and-set rebase merges them
 * additively (counters are commutative).
 *
 * <p>Schema: 1-column {@code Int64} key (TermId), {@code Int64} value (frequency, little-endian
 * native to match other Int64 columns).
 *
 * <p>Not thread-safe.
 *
 * @implNote <b>Collaborators:</b> {@link NodeStore} + {@link BufferPool} (chunk storage / scratch),
 *     {@link MutableMap} / {@link StaticMap} (the committed counter tree and the new root from
 *     {@link #commit()}), {@link com.dolthub.prolly.TupleBuilder} (encode the {@link TermId} key).
 *     <b>Dependents:</b> the query planner / {@code CardinalityEstimator} that reads per-term
 *     frequencies to estimate selectivity, and {@code ProllySailConnection} (holds one per
 *     transaction).
 */
public final class TermStats {

    private final NodeStore store;
    private final BufferPool pool;
    private final TupleDescriptor keySchema;
    private MutableMap base;

    /** Pending deltas not yet committed. */
    private final Map<TermId, Long> pending = new HashMap<>();

    public TermStats(NodeStore store, BufferPool pool) {
        this.store = store;
        this.pool = pool;
        this.keySchema = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));
        StaticMap empty = new StaticMap(store, null, keySchema);
        this.base = new MutableMap(empty, store, keySchema, pool, Int64Key.COMPARATOR);
    }

    public TermStats(NodeStore store, BufferPool pool, StaticMap committed) {
        this.store = store;
        this.pool = pool;
        this.keySchema = committed.descriptor();
        this.base = new MutableMap(committed, store, keySchema, pool, Int64Key.COMPARATOR);
    }

    /** Increment the frequency for {@code id} by {@code delta} (can be negative). */
    public void increment(TermId id, long delta) {
        pending.merge(id, delta, Long::sum);
    }

    /** Convenience: increment by 1. */
    public void increment(TermId id) {
        increment(id, 1L);
    }

    /** Decrement by {@code delta}. */
    public void decrement(TermId id, long delta) {
        increment(id, -delta);
    }

    /**
     * @return committed frequency + pending delta.
     */
    public long frequency(TermId id) {
        long committed = readCommitted(id);
        long delta = pending.getOrDefault(id, 0L);
        return committed + delta;
    }

    /** Flush pending deltas to a new committed root; reset deltas. */
    public StaticMap commit() {
        // Two passes: read every committed value first, then write. Each
        // pending TermId is a distinct key, so a write never affects a later
        // read — but interleaving get/put against the same MutableMap made
        // each get see an ever-larger pending-edit set, turning a commit of
        // n terms into O(n^2). Reading first keeps every get against the
        // clean committed base.
        Map<TermId, Long> updated = new HashMap<>(pending.size() * 2);
        for (Map.Entry<TermId, Long> e : pending.entrySet()) {
            updated.put(e.getKey(), readCommitted(e.getKey()) + e.getValue());
        }
        for (Map.Entry<TermId, Long> e : updated.entrySet()) {
            writeValue(e.getKey(), e.getValue());
        }
        pending.clear();
        StaticMap next = base.flush();
        this.base = new MutableMap(next, store, keySchema, pool, Int64Key.COMPARATOR);
        return next;
    }

    private long readCommitted(TermId id) {
        // Transient lookup key (ADR-0062 D-3/D-4): probe into a block we own, recycle it; the value
        // is read
        // after, independent of the key block. writeValue's put-site keeps using toTupleSegment
        // (retained).
        MemorySegment keyBlock = pool.borrow(Int64Key.TUPLE_SIZE);
        Optional<MemorySegment> v = base.get(Int64Key.writeInto(keyBlock, id.value()));
        pool.release(keyBlock);
        if (v.isEmpty()) return 0L;
        return v.get().get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0);
    }

    private void writeValue(TermId id, long value) {
        byte[] buf = new byte[8];
        MemorySegment seg = MemorySegment.ofArray(buf);
        seg.set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, value);
        base.put(buildKeyTuple(id), seg);
    }

    private MemorySegment buildKeyTuple(TermId id) {
        return Int64Key.toTupleSegment(pool, id.value());
    }
}
