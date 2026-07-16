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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.semantic.CardinalityEstimator;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 *
 *
 * <h3>Scale Reliability Test</h3>
 *
 * <p>Validates Diff and Cardinality Estimator on a 100,000+ item tree. Confirms that these engines
 * maintain logarithmic performance even at scale.
 */
public class ScaleReliabilityTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Scale Reliability Test ---");
        Path tempDir = Files.createTempDirectory("prolly-scale-test");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // 1. Create 100,000 items
            int scale = 100000;
            System.out.print("Building " + scale + " item tree... ");
            List<TreeMutator.Mutation> edits = new ArrayList<>(scale);
            for (int i = 0; i < scale; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%08d", i).getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(), MemorySegment.ofArray("val".getBytes())));
            }
            Node root = mutator.applyMutations(null, edits.iterator());
            System.out.println("Done. Height: " + root.level() + ", Count: " + root.treeCount());

            if (root.treeCount() != scale) {
                throw new RuntimeException(
                        "Initial build count mismatch: expected "
                                + scale
                                + ", got "
                                + root.treeCount());
            }

            // 2. Scale Cardinality Test
            System.out.print("Testing Cardinality at Scale... ");
            CardinalityEstimator estimator =
                    new CardinalityEstimator(new StaticMap(store, root, desc));

            long count =
                    estimator.estimateRange(
                            buildKey(pool, "key-00012345"), buildKey(pool, "key-00078901"));

            if (count != (78901 - 12345))
                throw new RuntimeException("Scale cardinality failed: " + count);
            System.out.println("Passed.");

            // 3. Scale Diff Test
            System.out.print("Testing Diff Engine at Scale... ");
            List<TreeMutator.Mutation> oneEdit =
                    List.of(
                            new TreeMutator.Mutation(
                                    buildKey(pool, "key-00050000"),
                                    MemorySegment.ofArray("new".getBytes())));
            Node root2 = mutator.applyMutations(root, oneEdit.iterator());

            if (root2.treeCount() != scale) {
                throw new RuntimeException(
                        "Update count mismatch: expected " + scale + ", got " + root2.treeCount());
            }

            DiffEngine diffEngine = new DiffEngine(store, desc);
            List<DiffEngine.DiffEntry> diffs = new ArrayList<>();
            diffEngine.diff(
                    root,
                    root2,
                    entry -> {
                        diffs.add(entry);
                        return true;
                    });

            if (diffs.size() != 1)
                throw new RuntimeException("Diff failed: expected 1, got " + diffs.size());
            System.out.println("Passed.");
        }
        System.out.println("--- Scale Reliability Test PASSED ---");
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }
}
