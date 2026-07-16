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
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 *
 *
 * <h3>CardinalityEstimator Test</h3>
 *
 * <p>Pins {@link com.earasoft.prolly.semantic.CardinalityEstimator}'s ordinal- based count math:
 * estimating the number of keys in a range {@code [startKey, endKey)} or under a raw byte prefix.
 *
 * <p><b>The Gap:</b> {@code CardinalityEstimator} feeds the BGP query planner. If its estimates are
 * off, the planner picks the wrong join order and queries explode in cost. Until now it had only
 * incidental coverage via end-to-end BGP tests; this test pins the count math directly.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>{@code estimateRange(k_i, k_j)} on a contiguous-integer corpus is exact: {@code j - i}.
 *   <li>{@code estimateRange(k_i, null)} returns {@code N - i} (everything from {@code i} to the
 *       end of the tree).
 *   <li>{@code estimatePrefix} on a fixed prefix returns the count of keys under that prefix.
 *   <li>An empty tree returns 0 for both range and prefix.
 * </ol>
 */
public class CardinalityEstimatorTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- CardinalityEstimator Test ---");
        Path tempDir = Files.createTempDirectory("prolly-cardinality");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));

            // Oracle 4 (start with empty so we don't accidentally skip it).
            CardinalityEstimator empty = new CardinalityEstimator(new StaticMap(store, null, desc));
            if (empty.estimateRange(buildKey(pool, "anything"), null) != 0) {
                throw new RuntimeException("empty range != 0");
            }
            if (empty.estimatePrefix(MemorySegment.ofArray("anything".getBytes())) != 0) {
                throw new RuntimeException("empty prefix != 0");
            }
            System.out.println("Empty tree returns 0. (4/4)");

            // Build a tree of N=2000 contiguous keys "key-NNNN".
            int N = 2000;
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < N; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%04d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            TreeMutator mutator = new TreeMutator(store, desc, pool);
            Node root = mutator.applyMutations(null, edits.iterator());
            store.write(root.segment());

            CardinalityEstimator est = new CardinalityEstimator(new StaticMap(store, root, desc));

            // Oracle 1: range(k_500, k_1500) == 1000.
            long got1 = est.estimateRange(buildKey(pool, "key-0500"), buildKey(pool, "key-1500"));
            if (got1 != 1000) {
                throw new RuntimeException("range(500,1500) = " + got1 + " expected 1000");
            }
            // Range starting from key-0000 (the very first key) to key-0100.
            long got2 = est.estimateRange(buildKey(pool, "key-0000"), buildKey(pool, "key-0100"));
            if (got2 != 100) {
                throw new RuntimeException("range(0,100) = " + got2 + " expected 100");
            }
            System.out.println("estimateRange exact on contiguous keys. (1/4)");

            // Oracle 2: range(k_500, null) == N - 500 == 1500.
            long got3 = est.estimateRange(buildKey(pool, "key-0500"), null);
            if (got3 != N - 500) {
                throw new RuntimeException("range(500, null) = " + got3 + " expected " + (N - 500));
            }
            System.out.println("estimateRange to end returns N - i. (2/4)");

            // Oracle 3: prefix("key-1") matches keys 1000..1999 = 1000 keys.
            // Note: our keys are "key-%04d" so the first 4 chars are "key-".
            // "key-1" is 5 bytes. Increment of "key-1" is "key-2" (last byte '1'+1='2').
            // [key-1, key-2) covers keys 1000..1999.
            long got4 =
                    est.estimatePrefix(
                            MemorySegment.ofArray("key-1".getBytes(StandardCharsets.UTF_8)));
            if (got4 != 1000) {
                throw new RuntimeException("prefix('key-1') = " + got4 + " expected 1000");
            }
            // Tighter prefix: "key-15" should match 1500..1599 = 100.
            long got5 =
                    est.estimatePrefix(
                            MemorySegment.ofArray("key-15".getBytes(StandardCharsets.UTF_8)));
            if (got5 != 100) {
                throw new RuntimeException("prefix('key-15') = " + got5 + " expected 100");
            }
            // Prefix that matches NOTHING (lex past everything).
            long got6 =
                    est.estimatePrefix(
                            MemorySegment.ofArray("zz".getBytes(StandardCharsets.UTF_8)));
            if (got6 != 0) {
                throw new RuntimeException("prefix('zz') = " + got6 + " expected 0");
            }
            System.out.println("estimatePrefix returns exact count under prefix. (3/4)");

            System.out.println("--- CardinalityEstimator Test PASSED ---");
        }
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
