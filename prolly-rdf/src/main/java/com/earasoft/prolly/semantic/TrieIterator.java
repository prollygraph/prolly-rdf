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

import com.dolthub.prolly.Cursor;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.TypeCodec;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A hierarchical <b>trie</b> view over a sorted index {@link StaticMap} (Phases 0 + 2 of {@code
 * multi-variable-leapfrog-triejoin.md}). The index columns are trie levels; at the current level it
 * iterates the distinct values of the current column under the bound prefix.
 *
 * <ul>
 *   <li>{@code key()} / {@code next()} / {@code seek()} / {@code atEnd()} — operate on the current
 *       level;
 *   <li>{@code open()} — descend: fix the current value, move to the next column;
 *   <li>{@code up()} — ascend: pop the last bound value.
 * </ul>
 *
 * <p><b>Step 9 — seek-streaming.</b> This rides the prolly-tree {@link Cursor} with
 * <b>sublinear</b> operations: {@code seek}/{@code next} are {@code atKey} tree-seeks (binary
 * search per node), and {@code next()} skips the current value's whole subtree by seeking to {@code
 * [prefix, value + 0x00]} — a tuple that sorts after every {@code [prefix, value, …]}
 * (longer-with-equal-prefix sorts later, and IRI/String compare unsigned-bytewise), landing on the
 * next distinct value without scanning the subtree. {@code open}/{@code up} snapshot and restore
 * the cursor via {@link Cursor#clone()} (the restore hazard that the earlier materializing version
 * sidestepped — now handled directly). A {@code seek} counter is exposed for the WCOJ work-bound
 * evidence.
 */
public final class TrieIterator {

    private final StaticMap map;
    private final TupleDescriptor desc;
    private final DirectBufferPool pool;
    private final int arity;

    private final List<byte[]> prefix = new ArrayList<>();
    // @Nullable: null for an empty tree (ctor) or once next() runs off the column max; positioned
    // within the current prefix otherwise. atEnd() == (cur == null || !valid || prefix mismatch);
    // key()/open() assert cur != null (their callers gate on !atEnd()).
    private @Nullable Cursor cur;
    private final Deque<Cursor> stack = new ArrayDeque<>(); // saved cursor clones per open()
    private long seeks = 0; // sublinear-seek work counter (WCOJ evidence)

    /**
     * Little-endian uint16 — Tuple offset/count framing (mirrors {@link Tuple}/{@link
     * TupleBuilder}).
     */
    private static final ValueLayout.OfShort LE_U16 =
            ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public TrieIterator(StaticMap map, TupleDescriptor desc, DirectBufferPool pool) {
        this.map = map;
        this.desc = desc;
        this.pool = pool;
        this.arity = desc.size();
        this.cur = (map.root() == null) ? null : Cursor.atStart(map.store(), map.root());
    }

    public int depth() {
        return prefix.size();
    }

    public int arity() {
        return arity;
    }

    public int currentColumn() {
        return prefix.size();
    }

    public long seekCount() {
        return seeks;
    }

    public boolean atEnd() {
        return cur == null || !cur.isValid() || !prefixMatches(new Tuple(cur.currentKey()));
    }

    /** The current column value at this level (raw field bytes). */
    public byte[] key() {
        Cursor c = Objects.requireNonNull(cur, "key() with no current position (atEnd)");
        // An index key has a value at every column, so the level field is never null-encoded.
        return Objects.requireNonNull(
                new Tuple(c.currentKey()).getField(prefix.size()),
                "index key column is never null");
    }

    /** Advance to the next distinct value at this level (sublinear subtree-skip). */
    public boolean next() {
        if (atEnd()) return false;
        byte[] succ = successor(key(), desc.typeAt(prefix.size()).encoding());
        if (succ == null) {
            cur = null;
            return false;
        } // value was the column max → no next
        seekToValue(succ);
        return !atEnd();
    }

    /**
     * Position at the first value {@code >= target} in this column's order (absolute, sublinear).
     */
    public void seek(byte[] target) {
        seekToValue(target);
    }

    /** Descend: fix the current value, begin enumerating the next column. */
    public void open() {
        if (atEnd()) throw new IllegalStateException("open() with no current key");
        // !atEnd() implies cur != null; assert it (the atEnd() narrowing doesn't cross the call).
        stack.push(Objects.requireNonNull(cur).clone());
        prefix.add(key());
        // cur already points at the first key under [prefix]; its field[prefix.size()]
        // is the first value of the new level.
    }

    /** Ascend: pop the last bound value, restore the parent cursor. */
    public void up() {
        if (stack.isEmpty()) throw new IllegalStateException("up() at root");
        prefix.remove(prefix.size() - 1);
        cur = stack.pop();
    }

    /**
     * A {@link MapIterator} over the current level's distinct values, on an <i>independent</i>
     * cursor anchored at {@code [prefix]} — so the triejoin driver can leapfrog this level while
     * the <b>same</b> trie's main cursor is separately driven through {@code seek}/{@code
     * open}/{@code up} descents. Single-IRI keys; seek-streaming (no materialization). Its seeks
     * count into this iterator's {@link #seekCount()}.
     */
    public MapIterator levelIterator() {
        return new LevelIterator(new ArrayList<>(prefix));
    }

    /**
     * Seek-streaming view of one trie level with {@link
     * com.earasoft.prolly.indexing.LeapfrogJoin}'s contract: starts before the first value; {@code
     * seek(t)} positions so the next {@code next()} lands on the first value {@code >= t}; {@code
     * key()} is read only after a {@code next()}. Owns its cursor (independent of the enclosing
     * trie's main cursor).
     */
    private final class LevelIterator implements MapIterator {
        private final List<byte[]> base;
        // @Nullable: null until the first next() anchors it, and again once the column is
        // exhausted.
        // valid() short-circuits on c == null; key()/curValue() assert it (read-after-next).
        private @Nullable Cursor c;
        private boolean started; // false until the first next()
        private boolean primed; // a seek positioned us; next() should consume, not advance

        LevelIterator(List<byte[]> base) {
            this.base = base;
        }

        @Override
        public boolean next() {
            if (!started) {
                started = true;
                anchor(null);
                return valid();
            }
            if (primed) {
                primed = false;
                return valid();
            }
            byte[] succ = successor(curValue(), desc.typeAt(base.size()).encoding());
            if (succ == null) {
                c = null;
                return false;
            } // column max → no next
            anchor(succ); // sublinear subtree-skip to next distinct value
            return valid();
        }

        @Override
        public void seek(MemorySegment target) {
            anchor(new Tuple(target).getField(0));
            started = true;
            primed = true; // next() consumes this position (first >= target)
        }

        @Override
        public boolean prev() {
            return false;
        }

        // Reused single-field key buffer. Built in place from the cursor's current field BYTE RANGE
        // — via
        // Tuple.fieldRange (a packed start/end long), NOT getFieldSegment, so no asSlice → no
        // HeapMemorySegmentImpl.dup (the #1 self-CPU frame in the CPU flame graph at ~15%; see
        // test_ontologies_zips/wiki-vote.md). We copy straight from the parent segment at the field
        // offset.
        // No getField byte[] copy, no per-call TupleBuilder/ArrayList, no MemorySegment.ofArray, no
        // pool
        // borrow. Safe to reuse across key() calls: LeapfrogJoin holds two keys at once only from
        // DIFFERENT
        // level iterators (each has its own buffer), and retains a match only until the driver's
        // immediate
        // getField copy.
        // @Nullable: lazily allocated on the first key() call and re-sized in lockstep (both set
        // together inside the size guard), so a non-null keyBuf implies a non-null keySeg.
        private byte @Nullable [] keyBuf;
        private @Nullable MemorySegment keySeg;

        @Override
        public MemorySegment key() {
            Cursor cc = Objects.requireNonNull(c, "key() read before a positioned next()");
            MemorySegment ck = cc.currentKey();
            long r = new Tuple(ck).fieldRange(base.size());
            int fStart = (int) (r >>> 32), fEnd = (int) (r & 0xFFFFFFFFL);
            int len = fEnd - fStart; // start == end → null-encoded field → len 0
            int total = len + 4; // [field][offset:u16][count:u16]
            if (keyBuf == null || keyBuf.length != total) {
                keyBuf = new byte[total];
                keySeg = MemorySegment.ofArray(keyBuf);
            }
            // keySeg is set in lockstep with keyBuf above, so it is non-null past the guard.
            MemorySegment ks = Objects.requireNonNull(keySeg);
            if (len > 0) MemorySegment.copy(ck, fStart, ks, 0, len);
            ks.set(LE_U16, len, (short) len); // offset[0] = end of field 0
            ks.set(LE_U16, len + 2, (short) 1); // count = 1
            return ks;
        }

        @Override
        public MemorySegment value() {
            return MemorySegment.NULL;
        }

        private byte[] curValue() {
            Cursor cc = Objects.requireNonNull(c, "curValue() with no current position");
            // An index key has a value at every column — the level field is never null-encoded.
            return Objects.requireNonNull(
                    new Tuple(cc.currentKey()).getField(base.size()),
                    "index key column is never null");
        }

        private boolean valid() {
            if (c == null || !c.isValid()) return false;
            Tuple k = new Tuple(c.currentKey());
            for (int i = 0; i < base.size(); i++) {
                if (!k.fieldEquals(i, base.get(i)))
                    return false; // in-place, no getField byte[] copy
            }
            return true;
        }

        /**
         * Position at the first key {@code >= [base, valueAtLevel]}; {@code null} value anchors at
         * the start of {@code [base]}.
         *
         * <p>Positioned forward-seek (Phase 4): leapfrog seeks are monotonic forward, so when the
         * cursor is already positioned within {@code base} we advance it forward toward the target
         * (allocation-free — no new {@code Cursor}, no key {@code TupleBuilder}, no node re-parse)
         * instead of an {@code atKey} re-descent from root. A bounded budget caps the linear
         * advance; far jumps fall back to {@code atKey} (preserving the sublinear seek). The
         * advance lands exactly where {@code atKey} would: every key under the cursor shares prefix
         * {@code base} (checked by {@link #valid()}), so comparing the level field ≡ comparing the
         * full tuple.
         */
        private void anchor(byte @Nullable [] valueAtLevel) {
            if (valueAtLevel != null
                    && c != null
                    && c.isValid()
                    && valid()
                    && forwardAdvanceTo(valueAtLevel)) {
                return; // positioned by forward-advance (or determined "none in base"): no
                // re-descent
            }
            seeks++;
            if (map.root() == null) {
                c = null;
                return;
            }
            if (valueAtLevel == null && base.isEmpty()) {
                c = Cursor.atStart(map.store(), map.root());
                return;
            }
            TupleBuilder tb = new TupleBuilder(pool);
            for (int i = 0; i < base.size(); i++) tb.putField(i, base.get(i));
            if (valueAtLevel != null) tb.putField(base.size(), valueAtLevel);
            c = Cursor.atKey(map.store(), map.root(), tb.build().segment(), desc);
        }

        /**
         * Advance the (within-base) cursor forward to the first key whose level field {@code >=
         * target}. Returns {@code true} when positioned (reached, or advanced out of {@code
         * base}/to end → the correct "no key ≥ target in base"); {@code false} when the budget is
         * exhausted before reaching, so the caller re-descends with {@code atKey}.
         */
        private boolean forwardAdvanceTo(byte[] target) {
            // Called only with c != null (anchor's guard) and c is not reassigned here, so the
            // local
            // aliases the field; advance() mutates the shared cursor in place.
            Cursor cc = Objects.requireNonNull(c);
            MemorySegment t = MemorySegment.ofArray(target); // wrap target once, not per step
            int budget = FORWARD_ADVANCE_BUDGET;
            while (budget-- > 0) {
                if (compareFieldAt(base.size(), cc.currentKey(), t) >= 0)
                    return true; // c at first ≥ target
                if (!cc.advance()) return true; // end of map → none ≥ target
                if (!valid()) return true; // left base → none ≥ target in base
            }
            return false; // far jump → fall back to atKey
        }
    }

    /**
     * Bounded forward-advance steps before falling back to an {@code atKey} re-descent (Phase 4).
     */
    private static final int FORWARD_ADVANCE_BUDGET = 32;

    // ---- internals -------------------------------------------------------

    private void seekToValue(byte[] valueAtLevel) {
        // Positioned forward-seek (Phase 4): advance the within-prefix main cursor forward toward
        // the
        // target instead of an atKey re-descent — but ONLY when the cursor is strictly before the
        // target.
        // Unlike the independent LevelIterator (fresh per level, consumed monotonically forward),
        // the
        // main descent cursor is REUSED, and a pattern that skips a global variable (e.g. T(x,z)
        // skipping
        // y in the triangle) is never up()'d across the skipped level — so its seek can be
        // BACKWARD.
        // forwardAdvanceMain detects current >= target and bails to atKey (which re-descends
        // correctly);
        // this guard is what the first (reverted) attempt was missing — it over-counted on the
        // backward case.
        if (cur != null
                && cur.isValid()
                && prefixMatches(new Tuple(cur.currentKey()))
                && forwardAdvanceMain(valueAtLevel)) {
            return;
        }
        seeks++;
        if (map.root() == null) {
            cur = null;
            return;
        }
        TupleBuilder tb = new TupleBuilder(pool);
        for (int i = 0; i < prefix.size(); i++) tb.putField(i, prefix.get(i));
        tb.putField(prefix.size(), valueAtLevel);
        cur = Cursor.atKey(map.store(), map.root(), tb.build().segment(), desc);
    }

    /**
     * Forward-advance the main cursor to the first key whose level field {@code >= target}. Safe
     * ONLY when the cursor is strictly before the target ({@code current < target}); then every key
     * at/before the cursor is {@code < target}, so "first {@code >= target} from here" == "first
     * {@code >= target} under prefix". Returns {@code false} on a non-forward seek ({@code current
     * >= target}) or budget exhaustion → caller re-descends with {@code atKey}.
     */
    private boolean forwardAdvanceMain(byte[] target) {
        // Called only with cur != null (seekToValue's guard) and cur is not reassigned here, so the
        // local aliases the field; advance() mutates the shared cursor in place.
        Cursor c = Objects.requireNonNull(cur);
        MemorySegment t = MemorySegment.ofArray(target); // wrap target once, not per step
        if (compareFieldAt(prefix.size(), c.currentKey(), t) >= 0)
            return false; // not strictly before → atKey
        int budget = FORWARD_ADVANCE_BUDGET;
        while (budget-- > 0) {
            if (!c.advance()) return true; // end of map → none ≥ target
            if (!(c.isValid() && prefixMatches(new Tuple(c.currentKey()))))
                return true; // left prefix
            if (compareFieldAt(prefix.size(), c.currentKey(), t) >= 0)
                return true; // first ≥ target
        }
        return false; // far jump → atKey
    }

    /**
     * Compare {@code cursorKey}'s field at {@code col} to the target segment {@code t} (its whole
     * range), <b>in place</b> via {@link Tuple#fieldRange} + {@link TypeCodec#compareAt} — no
     * {@code getFieldSegment} slice, and so no {@code asSlice}/{@code dup} (the top forward-advance
     * CPU frame in the flame graph) nor its allocation. Mirrors {@link TupleDescriptor#compare}'s
     * order; binary-parity / out-of-schema columns force unsigned range compare. The convergent
     * alloc+CPU lever (a) — prolly-rdf/plans/triejoin-performance.md, Phase 3.
     */
    private int compareFieldAt(int col, MemorySegment cursorKey, MemorySegment t) {
        long r = new Tuple(cursorKey).fieldRange(col);
        int fStart = (int) (r >>> 32), fEnd = (int) (r & 0xFFFFFFFFL);
        long tEnd = t.byteSize();
        if (desc.isBinaryParity() || col >= desc.size()) {
            return TypeCodec.compareRangeUnsigned(cursorKey, fStart, fEnd, t, 0, tEnd);
        }
        return TypeCodec.compareAt(
                desc.typeAt(col).encoding(), cursorKey, fStart, fEnd, t, 0, tEnd);
    }

    private boolean prefixMatches(Tuple k) {
        for (int i = 0; i < prefix.size(); i++) {
            if (!k.fieldEquals(i, prefix.get(i))) return false; // in-place, no getField byte[] copy
        }
        return true;
    }

    /**
     * The smallest key value strictly greater than {@code value} in {@code enc}'s sort order — so
     * seeking to {@code [prefix, successor(value)]} lands on the next distinct value (the sublinear
     * subtree-skip). {@code null} ⇒ {@code value} is already the column maximum (no successor).
     *
     * <p><b>Variable-length, unsigned-byte-compared</b> (IRI/String/Bytes): append a trailing
     * {@code 0x00} — it sorts after {@code value} and after every {@code [value, …]} extension.
     * <b>Fixed-width {@code Int64}</b> (e.g. {@code TermId}): append-0x00 would be <i>ignored</i>
     * by the comparator ({@code readInt64} reads exactly 8 little-endian bytes), so the successor
     * is the numeric {@code value+1} re-encoded little-endian — matching {@code
     * TypeCodec.compare}'s {@code Long.compare}.
     */
    private static byte @Nullable [] successor(byte[] value, Encoding enc) {
        if (enc == Encoding.Int64) {
            long x = TypeCodec.readInt64(MemorySegment.ofArray(value));
            if (x == Long.MAX_VALUE) return null;
            byte[] out = new byte[8];
            MemorySegment.ofArray(out)
                    .set(
                            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN),
                            0,
                            x + 1);
            return out;
        }
        if (enc == Encoding.IRI || enc == Encoding.String || enc == Encoding.Bytes) {
            return Arrays.copyOf(value, value.length + 1);
        }
        throw new UnsupportedOperationException(
                "TrieIterator successor unsupported for encoding "
                        + enc
                        + " (supported: IRI/String/Bytes/Int64)");
    }
}
