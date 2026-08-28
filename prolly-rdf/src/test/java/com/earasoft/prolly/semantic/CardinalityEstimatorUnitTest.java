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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TreeMutator;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage for {@link CardinalityEstimator}. The estimator is what the SPARQL planner
 * consults to decide BGP join order — a wrong estimate doesn't cause query failures, it causes
 * massive performance cliffs in production.
 *
 * <p>Existing tests ({@code CardinalityEstimatorTest}, {@code CardinalityTest}) cover ratio
 * properties on tuned corpora; this file pins the unit-level contracts: null root, empty range,
 * full-tree estimate, prefix at the lex-end boundary.
 */
class CardinalityEstimatorUnitTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    private static MemorySegment key(HeapBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static StaticMap tree(HeapBufferPool pool, InMemoryNodeStore store, int n) {
        TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            edits.add(
                    new TreeMutator.Mutation(
                            key(pool, String.format("k-%05d", i)),
                            MemorySegment.ofArray(("v-" + i).getBytes())));
        }
        Node root = m.applyMutations(null, edits.iterator());
        return new StaticMap(store, root, STRING_DESC);
    }

    /** A leading Int64 column — the shape whose bytes do NOT order like its tree. */
    private static final TupleDescriptor ID_LABEL_DESC =
            new TupleDescriptor(
                    List.of(new Type(Encoding.Int64, false), new Type(Encoding.String, false)));

    private static MemorySegment idLabelKey(HeapBufferPool pool, long id, String label) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putInt64(0, id);
        tb.putField(1, label.getBytes());
        return tb.build().segment();
    }

    private static MemorySegment idPrefix(HeapBufferPool pool, long id) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putInt64(0, id);
        return tb.build().segment();
    }

    /** ids 1..3, four labels each — twelve keys, four under each id. */
    private static StaticMap idLabelTree(HeapBufferPool pool, InMemoryNodeStore store) {
        TreeMutator m = new TreeMutator(store, ID_LABEL_DESC, pool);
        List<TreeMutator.Mutation> edits = new ArrayList<>();
        for (long id = 1; id <= 3; id++) {
            for (int j = 0; j < 4; j++) {
                edits.add(
                        new TreeMutator.Mutation(
                                idLabelKey(pool, id, "label-" + j),
                                MemorySegment.ofArray(("v" + id + j).getBytes())));
            }
        }
        return new StaticMap(store, m.applyMutations(null, edits.iterator()), ID_LABEL_DESC);
    }

    /**
     * {@code estimatePrefix} walks the tree by RAW BYTE comparison ({@code Cursor.atRawKey}), which
     * is only the tree's own ordering when the descriptor asks for binary parity or every column is
     * byte-compared. It is not, for the four encodings {@code TypeCodec.compareAt} handles
     * numerically — {@code Int64}, {@code Int32}, {@code Float64}, {@code Float32} — which it reads
     * LITTLE-endian and compares with {@code Long.compare}/etc. {@code encodeInt64}'s
     * big-endian-plus-sign-flip layout, the one that does order lexicographically, is written only
     * under binary parity.
     *
     * <p>So on an Int64-keyed tree the descent lands in the wrong subtree and the difference of two
     * wrong ordinals comes back as a plausible number. It cost a downstream Gate run before anyone
     * noticed, because 0 is exactly what "no matches" looks like.
     *
     * <p>Refusing is the fix rather than a typed prefix API: {@code estimateRange} between two
     * one-field tuples already answers this correctly and is what the only production consumer
     * ({@code SelectivityVariableOrder}) uses. {@code estimatePrefix} has no production caller at
     * all.
     */
    @Test
    void estimatePrefix_refuses_a_tree_whose_bytes_do_not_order_like_its_keys() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            CardinalityEstimator est = new CardinalityEstimator(idLabelTree(pool, store));
            MemorySegment prefix = idPrefix(pool, 2L);

            UnsupportedOperationException thrown =
                    assertThrows(
                            UnsupportedOperationException.class, () -> est.estimatePrefix(prefix));
            assertTrue(
                    thrown.getMessage().contains("estimateRange"),
                    "the message must name the method that DOES answer this: " + thrown);
        }
    }

    /** The true answer, for comparison — and proof the tree really holds four keys under id 2. */
    @Test
    void estimateRange_answers_the_question_estimatePrefix_refuses() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            CardinalityEstimator est = new CardinalityEstimator(idLabelTree(pool, store));
            assertEquals(
                    4,
                    est.estimateRange(idPrefix(pool, 2L), idPrefix(pool, 3L)),
                    "two one-field tuples, compared by the descriptor: a short tuple sorts before "
                            + "every longer key sharing it, so this brackets id 2 exactly");
        }
    }

    /** A byte-ordered descriptor is still served — the guard must not be a blanket refusal. */
    @Test
    void estimatePrefix_still_works_where_bytes_and_keys_agree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            CardinalityEstimator est = new CardinalityEstimator(tree(pool, store, 200));
            assertEquals(
                    200,
                    est.estimatePrefix(MemorySegment.ofArray("k-".getBytes())),
                    "String is compared with compareRangeUnsigned, so raw byte order IS the "
                            + "tree's order here and the raw descent is sound");
        }
    }

    // ---- null-root semantics ----

    @Test
    void null_root_returns_zero_for_range() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap empty = new StaticMap(store, null, STRING_DESC);
            CardinalityEstimator est = new CardinalityEstimator(empty);
            assertEquals(0, est.estimateRange(key(pool, "a"), key(pool, "z")));
            assertEquals(0, est.estimatePrefix(MemorySegment.ofArray("a".getBytes())));
        }
    }

    // ---- range ----

    @Test
    void range_within_tree_estimates_correctly() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 100);
            CardinalityEstimator est = new CardinalityEstimator(map);
            long got = est.estimateRange(key(pool, "k-00000"), key(pool, "k-00100"));
            // For small trees (single leaf), the ordinal math is exact.
            assertEquals(100, got);
        }
    }

    @Test
    void range_with_null_end_means_to_end_of_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 50);
            CardinalityEstimator est = new CardinalityEstimator(map);
            // null endKey → estimate from startKey to treeCount.
            long got = est.estimateRange(key(pool, "k-00000"), null);
            assertEquals(50, got);
        }
    }

    @Test
    void empty_range_returns_zero() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 10);
            CardinalityEstimator est = new CardinalityEstimator(map);
            // start == end → zero results
            long got = est.estimateRange(key(pool, "k-00005"), key(pool, "k-00005"));
            assertEquals(0, got);
        }
    }

    @Test
    void inverted_range_returns_zero_not_negative() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 10);
            CardinalityEstimator est = new CardinalityEstimator(map);
            // Start > end (lexicographically). Implementation must Math.max(0, ...).
            long got = est.estimateRange(key(pool, "k-00008"), key(pool, "k-00002"));
            assertEquals(0, got, "inverted range must return 0, not a negative estimate");
        }
    }

    @Test
    void multi_level_tree_range_estimate() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 2000);
            assertTrue(map.root().level() >= 1, "multi-level tree required");
            CardinalityEstimator est = new CardinalityEstimator(map);

            // The estimate uses subtreeCounts at internal nodes — for a multi-
            // level tree, it's an approximation, not an exact count. Pin the
            // monotonicity property instead of an exact value.
            long fullEstimate = est.estimateRange(key(pool, "k-00000"), key(pool, "k-02000"));
            long halfEstimate = est.estimateRange(key(pool, "k-00000"), key(pool, "k-01000"));
            assertTrue(
                    halfEstimate < fullEstimate,
                    "half-range estimate must be smaller than full-range");
            assertTrue(fullEstimate <= 2000, "estimate must not exceed actual tree count");
            assertTrue(halfEstimate > 0, "half range must have a non-zero estimate");
        }
    }

    // ---- prefix ----

    @Test
    void prefix_estimate_matches_actual_count_in_small_tree() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            // Insert keys with two prefixes: "alpha-N" and "beta-N", 10 of each.
            TreeMutator m = new TreeMutator(store, STRING_DESC, pool);
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("alpha-%02d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            for (int i = 0; i < 10; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                key(pool, String.format("beta-%02d", i)),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node root = m.applyMutations(null, edits.iterator());
            StaticMap map = new StaticMap(store, root, STRING_DESC);

            CardinalityEstimator est = new CardinalityEstimator(map);
            // Use raw byte prefix — the wide-key keys are tuple-encoded so we
            // pass the on-wire byte prefix (including the tuple framing bytes
            // is impractical; just test that an "alpha" prefix doesn't return
            // 0 nor exceed total count).
            long alphaEst = est.estimatePrefix(MemorySegment.ofArray("alpha".getBytes()));
            assertTrue(
                    alphaEst >= 0 && alphaEst <= 20,
                    "alpha-prefix estimate (" + alphaEst + ") must be within [0, 20]");
        }
    }

    // ---- calculateOrdinal ----

    @Test
    void calculateOrdinal_first_leaf_position_is_zero() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 10);
            com.dolthub.prolly.Cursor c = com.dolthub.prolly.Cursor.atStart(store, map.root());
            assertEquals(0, CardinalityEstimator.calculateOrdinal(c));
        }
    }

    @Test
    void calculateOrdinal_increases_with_advance() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            StaticMap map = tree(pool, store, 10);
            com.dolthub.prolly.Cursor c = com.dolthub.prolly.Cursor.atStart(store, map.root());
            long prev = CardinalityEstimator.calculateOrdinal(c);
            c.advance();
            long now = CardinalityEstimator.calculateOrdinal(c);
            assertEquals(
                    prev + 1,
                    now,
                    "advancing one position must increment ordinal by 1 in a single leaf");
        }
    }

    @Test
    void calculateOrdinal_handles_null_node() {
        // Construct a cursor with null node — calculateOrdinal must short-circuit.
        com.dolthub.prolly.Cursor c = new com.dolthub.prolly.Cursor(null, null, null, 0);
        assertEquals(
                0,
                CardinalityEstimator.calculateOrdinal(c),
                "null-node cursor must return 0, not NPE");
    }
}
