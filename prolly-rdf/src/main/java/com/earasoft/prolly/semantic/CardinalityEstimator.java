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

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Estimates how many keys fall in a range or under a prefix of one index, without scanning them —
 * the selectivity input the join planner uses to order its work.
 *
 * <p>It exploits the prolly tree's subtree counts: every internal node records how many leaf
 * entries live beneath it, so the ordinal position of a key (how many keys precede it) is
 * computable by descending once from the root and summing the counts to its left. The size of a
 * range is then the difference of two ordinals — an answer proportional to tree height, not to the
 * number of keys in the range. A prefix estimate is the same trick applied to the half-open
 * interval from the prefix to its byte-wise increment.
 *
 * @apiNote {@link #estimateRange} bounds a key range and is the general answer; {@link
 *     #estimatePrefix} bounds the keys sharing a RAW BYTE prefix and only works on trees whose byte
 *     order is their key order — it throws otherwise rather than returning a plausible wrong
 *     number, see its own doc. Both return 0 for an empty tree and clamp a negative difference to
 *     0. The figure is exact for the counts the tree stores, not sampled — it is called an
 *     "estimate" because callers use it as a planning heuristic, not because it approximates.
 * @implNote <b>Collaborators:</b> a {@link StaticMap} (the constructor pulls its {@link NodeStore},
 *     root {@link Node}, and {@link TupleDescriptor}), {@link Cursor} (positions at a key to read
 *     its ordinal), and {@link ByteUtils} (the prefix increment). It relies on {@link
 *     Node#treeCount()} and the per-node subtree counts being correct. <b>Dependents:</b> the join
 *     planner — {@code SelectivityVariableOrder} / {@code VariableOrderHeuristic} — which orders
 *     join variables by estimated selectivity, and does so through {@link #estimateRange}. {@link
 *     #estimatePrefix} has no production caller.
 */
public class CardinalityEstimator {
    private final NodeStore store;
    // @Nullable: an empty StaticMap has a null root (the empty-tree sentinel). estimateRange /
    // estimatePrefix return 0 for it; getOrdinal is reached only past that guard.
    private final @Nullable Node root;
    private final TupleDescriptor descriptor;

    public CardinalityEstimator(StaticMap map) {
        this.store = map.store();
        this.root = map.root();
        this.descriptor = map.descriptor();
    }

    public long estimateRange(MemorySegment startKey, @Nullable MemorySegment endKey) {
        if (root == null) return 0;
        long posA = getOrdinal(startKey, false);
        long posB = (endKey == null) ? root.treeCount() : getOrdinal(endKey, false);
        return Math.max(0, posB - posA);
    }

    /**
     * Keys sharing a raw byte prefix — <b>valid only where raw byte order IS the tree's order</b>.
     *
     * <p>This descends with {@link Cursor#atRawKey}, comparing bytes, while the tree was built by
     * {@link TupleDescriptor#compare}. Those agree when the descriptor asks for binary parity (the
     * builder bit-flipped numeric fields into big-endian so their bytes sort correctly) or when
     * every column is one {@code TypeCodec.compareAt} falls through to an unsigned byte compare —
     * {@code Uint*}, {@code String}, {@code Bytes}, and the rest.
     *
     * <p>They do NOT agree for the four encodings {@code compareAt} handles numerically: {@code
     * Int64}, {@code Int32}, {@code Float64}, {@code Float32}, which it reads
     * <em>little-endian</em> and compares with {@code Long.compare} and friends. On such a tree the
     * raw descent lands in the wrong subtree and the difference of two wrong ordinals is returned
     * as a perfectly plausible count — measured at <b>0 where the answer was 4</b>. That is the
     * worst failure available: 0 is exactly what "no matches" looks like, and it cost a downstream
     * Gate run before anyone questioned it.
     *
     * <p>So this refuses. It does not attempt a typed-prefix fix, because {@link #estimateRange}
     * between two short tuples already answers the same question correctly for any descriptor — a
     * k-field tuple sorts before every longer key sharing it — and is what the only production
     * consumer uses.
     *
     * @throws UnsupportedOperationException if the descriptor's bytes do not order like its keys
     */
    public long estimatePrefix(MemorySegment prefix) {
        if (root == null) return 0;
        requireByteOrderedKeys();
        byte[] startBytes = prefix.toArray(ValueLayout.JAVA_BYTE);
        byte[] endBytes = ByteUtils.increment(startBytes);

        long posA = getOrdinal(MemorySegment.ofArray(startBytes), true);
        long posB =
                (endBytes == null)
                        ? root.treeCount()
                        : getOrdinal(MemorySegment.ofArray(endBytes), true);

        return Math.max(0, posB - posA);
    }

    /**
     * The encodings {@code TypeCodec.compareAt} compares NUMERICALLY from little-endian bytes, so
     * their byte order is not their value order. Everything else falls through to {@code
     * compareRangeUnsigned}, which is byte order by definition.
     */
    private static final Set<Encoding> NOT_BYTE_ORDERED =
            EnumSet.of(Encoding.Int64, Encoding.Int32, Encoding.Float64, Encoding.Float32);

    private void requireByteOrderedKeys() {
        if (descriptor.isBinaryParity()) return;
        for (int i = 0; i < descriptor.size(); i++) {
            Encoding enc = descriptor.typeAt(i).encoding();
            if (NOT_BYTE_ORDERED.contains(enc)) {
                throw new UnsupportedOperationException(
                        "estimatePrefix walks this tree by RAW BYTE comparison, but column "
                                + i
                                + " is "
                                + enc
                                + ", which the descriptor compares numerically from little-endian"
                                + " bytes — the descent would land in the wrong subtree and return a"
                                + " plausible wrong count rather than fail. Use estimateRange"
                                + " between two tuples built from this descriptor, or build the tree"
                                + " with a binary-parity descriptor.");
            }
        }
    }

    private long getOrdinal(MemorySegment key, boolean isRaw) {
        // root is non-null here: both callers (estimateRange / estimatePrefix) return 0 on a null
        // root before reaching this. The assertion makes that precondition explicit to NullAway,
        // which cannot carry the field narrowing across the method boundary.
        Node r = Objects.requireNonNull(root);
        Cursor cur =
                isRaw ? Cursor.atRawKey(store, r, key) : Cursor.atKey(store, r, key, descriptor);
        return CardinalityEstimator.calculateOrdinal(cur);
    }

    public static long calculateOrdinal(Cursor cur) {
        if (cur.node() == null) return 0;
        long ordinal = cur.index();
        Cursor p = cur.parent();
        while (p != null) {
            if (p.index() > 0) {
                ordinal += p.node().getSubtreeCount(p.index() - 1);
            }
            p = p.parent();
        }
        return ordinal;
    }
}
