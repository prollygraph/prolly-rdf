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
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.earasoft.prolly.rdf4j.term.Layouts;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A Prolly Map keyed on {@link SpocKey} — the canonical storage for one of the four mandatory quad
 * indexes (SPOC, POSC, OSPC, CSPO). Values are empty (key-only index).
 *
 * <p>Provides insert / delete / contains / full-scan / prefix-scan with commit-on-flush semantics.
 * Mutations are buffered in a {@link MutableMap}; {@link #commit()} flushes them to a new {@link
 * StaticMap} root.
 *
 * <p>Iteration scope: reads the <em>committed</em> state via the underlying base StaticMap.
 * Uncommitted buffered writes are visible to {@link #contains}, but NOT to {@link #iter} / {@link
 * #iterPrefix}. The Sail layer commits before each read on top of this, so the same-transaction
 * read-your-writes semantic is preserved at the Sail level.
 *
 * <p>Not thread-safe.
 *
 * @implNote <b>Collaborators:</b> {@link MutableMap} (buffers pending writes), {@link StaticMap}
 *     (the committed base and the new root produced by {@link #commit()}), {@link NodeStore} +
 *     {@link BufferPool} (chunk storage / scratch buffers), {@link SpocKey} / {@link Layouts} (the
 *     fixed four-by-Int64 key layout and its non-allocating comparator). <b>Dependents:</b> {@link
 *     QuadIndex} (wraps one of these per permutation), and through it the Sail's quad storage.
 */
public final class SpocIndex {

    /** Singleton empty-bytes marker for "key present, no value". */
    private static final MemorySegment EMPTY_VALUE = MemorySegment.ofArray(new byte[0]);

    /**
     * Non-allocating key comparator for the fixed 4×Int64 SpocKey tuple.
     *
     * <p>Order-identical to {@code SpocKey.DESCRIPTOR.compare}: that path does a signed {@code
     * Long.compare} per column, and so does this — but it reads each column's long directly at its
     * fixed offset (0/8/16/24) instead of calling {@code Tuple.getFieldSegment}, which allocates a
     * {@code MemorySegment} slice per field. The generic comparator slices eight segments per call;
     * on the ingest hot path (a TreeMap insert is ~log₂(n) comparisons) that is millions of
     * throwaway slices. Every key in this buffer is a 42-byte {@link SpocKey#toTupleSegment} tuple,
     * so the fixed-offset read is always valid.
     */
    static final Comparator<Tuple> FAST_KEY_COMPARATOR =
            (a, b) -> {
                MemorySegment sa = a.segment();
                MemorySegment sb = b.segment();
                for (int off = 0; off <= 24; off += 8) {
                    int c = Long.compare(sa.get(Layouts.LE64_U, off), sb.get(Layouts.LE64_U, off));
                    if (c != 0) return c;
                }
                return 0;
            };

    private final NodeStore store;
    private final BufferPool pool;
    private MutableMap buffer;

    /** The boundary-function seam (SPOC boundary-function-adoption D-1). */
    private final com.dolthub.prolly.BoundarySplitter.Factory splitterFactory;

    public SpocIndex(NodeStore store, BufferPool pool) {
        this(store, pool, com.dolthub.prolly.BoundarySplitter.ROLLING_HASH);
    }

    /** Seam constructor (SPOC boundary-function-adoption D-1): inject the boundary function. */
    public SpocIndex(
            NodeStore store,
            BufferPool pool,
            com.dolthub.prolly.BoundarySplitter.Factory splitterFactory) {
        this.splitterFactory = splitterFactory;
        this.store = store;
        this.pool = pool;
        StaticMap empty = new StaticMap(store, null, SpocKey.DESCRIPTOR);
        this.buffer =
                new MutableMap(
                        empty,
                        store,
                        SpocKey.DESCRIPTOR,
                        pool,
                        FAST_KEY_COMPARATOR,
                        splitterFactory);
    }

    public SpocIndex(NodeStore store, BufferPool pool, StaticMap committed) {
        this(store, pool, committed, com.dolthub.prolly.BoundarySplitter.ROLLING_HASH);
    }

    /** Seam constructor (SPOC boundary-function-adoption D-1): inject the boundary function. */
    public SpocIndex(
            NodeStore store,
            BufferPool pool,
            StaticMap committed,
            com.dolthub.prolly.BoundarySplitter.Factory splitterFactory) {
        this.splitterFactory = splitterFactory;
        this.store = store;
        this.pool = pool;
        this.buffer =
                new MutableMap(
                        committed,
                        store,
                        SpocKey.DESCRIPTOR,
                        pool,
                        FAST_KEY_COMPARATOR,
                        splitterFactory);
    }

    /** Insert a key. Idempotent: re-inserting the same key is a no-op semantically. */
    public void insert(SpocKey key) {
        buffer.put(key.toTupleSegment(pool), EMPTY_VALUE);
    }

    /** Delete a key. Idempotent: deleting an absent key is a no-op. */
    public void delete(SpocKey key) {
        buffer.delete(key.toTupleSegment(pool));
    }

    /**
     * True if the key is present (buffered insert OR committed entry that isn't buffer-deleted).
     */
    public boolean contains(SpocKey key) {
        // Transient lookup key (ADR-0062 D-3/D-4): build it into a block we own, probe, then
        // recycle the
        // block — MutableMap.get wraps the key in a *local* Tuple and returns the value, so it
        // never
        // retains the key segment (unlike insert/delete, which store it). Release the borrowed
        // block, not
        // the asSlice view (release buckets by byteSize). A no-op under HeapBufferPool.
        MemorySegment block = pool.borrow(SpocKey.TUPLE_SIZE);
        Optional<MemorySegment> v = buffer.get(key.writeInto(block));
        // v.isPresent && v != null → present. If buffered delete, MutableMap.get returns
        // Optional.ofNullable(null) = empty.
        boolean present = v.isPresent();
        pool.release(block);
        return present;
    }

    /** Flush buffered writes to a new committed root; reset the buffer. */
    public StaticMap commit() {
        StaticMap next = buffer.flush();
        this.buffer =
                new MutableMap(
                        next,
                        store,
                        SpocKey.DESCRIPTOR,
                        pool,
                        FAST_KEY_COMPARATOR,
                        splitterFactory);
        return next;
    }

    /** Full scan over the committed state, returning each key in tree order. */
    public Iterator<SpocKey> iter() {
        return new TupleKeyIterator(buffer.base().iter(), 0, (TermId[]) null);
    }

    /**
     * Prefix scan: yield rows whose first {@code prefix.length} columns equal the supplied {@link
     * TermId}s, in tree order. Length must be 1, 2, or 3 (a 4-column prefix is a point lookup — use
     * {@link #contains}).
     */
    public Iterator<SpocKey> iterPrefix(TermId... prefix) {
        if (prefix.length < 1 || prefix.length > 3) {
            throw new IllegalArgumentException(
                    "prefix length must be in [1,3]; got " + prefix.length);
        }
        MemorySegment prefixSeg = buildPrefixTuple(prefix);
        MapIterator it = buffer.base().iterRange(prefixSeg);
        return new TupleKeyIterator(it, prefix.length, prefix);
    }

    private MemorySegment buildPrefixTuple(TermId[] prefix) {
        TupleBuilder tb = new TupleBuilder(pool, SpocKey.DESCRIPTOR);
        for (int i = 0; i < prefix.length; i++) {
            tb.putInt64(i, prefix[i].value());
        }
        return tb.build().segment();
    }

    /**
     * Iterator wrapping a {@link MapIterator}, decoding each row to a {@link SpocKey} and stopping
     * when the leading {@code prefixLen} columns no longer match {@code prefix}.
     */
    private static final class TupleKeyIterator implements Iterator<SpocKey> {
        private final MapIterator inner;
        private final int prefixLen;
        // @Nullable: null iff prefixLen == 0 (full scan); a length-prefixLen array otherwise.
        private final TermId @Nullable [] prefix;
        // @Nullable lazy lookahead: null when not yet computed or the scan is exhausted.
        private @Nullable SpocKey next;
        private boolean exhausted;

        TupleKeyIterator(MapIterator inner, int prefixLen, TermId @Nullable [] prefix) {
            this.inner = inner;
            this.prefixLen = prefixLen;
            this.prefix = prefix;
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            if (exhausted) return false;
            while (inner.next()) {
                Tuple t = new Tuple(inner.key());
                SpocKey k = SpocKey.fromTuple(t);
                if (!matchesPrefix(k)) {
                    exhausted = true;
                    return false;
                }
                next = k;
                return true;
            }
            exhausted = true;
            return false;
        }

        @Override
        public SpocKey next() {
            if (!hasNext()) throw new NoSuchElementException();
            SpocKey r = Objects.requireNonNull(next); // hasNext() == true guarantees it is set
            next = null;
            return r;
        }

        private boolean matchesPrefix(SpocKey k) {
            if (prefixLen == 0) return true;
            // prefixLen > 0 ⟺ a prefix was supplied (the iter()/iterPrefix() construction
            // invariant).
            TermId[] p = Objects.requireNonNull(prefix);
            for (int i = 0; i < prefixLen; i++) {
                TermId expected = p[i];
                TermId actual = column(k, i);
                if (!expected.equals(actual)) return false;
            }
            return true;
        }

        private static TermId column(SpocKey k, int i) {
            return switch (i) {
                case 0 -> k.col0();
                case 1 -> k.col1();
                case 2 -> k.col2();
                case 3 -> k.col3();
                default -> throw new IllegalStateException();
            };
        }
    }

    /** Suppress unused warnings while {@code Layouts} import stays for future helpers. */
    @SuppressWarnings("unused")
    private static final Class<?> _layoutsKeepalive = Layouts.class;
}
