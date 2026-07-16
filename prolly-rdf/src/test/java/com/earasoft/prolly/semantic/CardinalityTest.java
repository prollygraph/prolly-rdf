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
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class CardinalityTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Cardinality Estimator Test ---");
        Path tempDir = Files.createTempDirectory("prolly-cardinality");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            // 1. Create a tree with skewed prefix distribution
            // Prefix "A" -> 100 items
            // Prefix "B" -> 400 items
            int numItems = 500;
            System.out.print("Creating skewed tree (100 'A' keys, 400 'B' keys)... ");
            List<TreeMutator.Mutation> edits = new ArrayList<>(numItems);
            for (int i = 0; i < 100; i++) {
                addMutation(edits, pool, String.format("A-%05d", i));
            }
            for (int i = 0; i < 400; i++) {
                addMutation(edits, pool, String.format("B-%05d", i));
            }
            Node root = mutator.applyMutations(null, edits.iterator());
            StaticMap map = new StaticMap(store, root, desc);
            CardinalityEstimator estimator = new CardinalityEstimator(map);
            System.out.println("Done. Height: " + root.level());

            // 2. Test Prefix Estimation "A"
            System.out.print("Testing Prefix Estimation ('A')... ");
            TupleBuilder tbA = new TupleBuilder(pool, desc);
            tbA.putField(0, "A".getBytes());
            MemorySegment prefixA = tbA.build().getFieldSegment(0);

            long countA = estimator.estimatePrefix(prefixA);
            if (countA != 100)
                throw new RuntimeException("Prefix A estimate failed: expected 100, got " + countA);
            System.out.println("Passed.");

            // 3. Test Prefix Estimation "B"
            System.out.print("Testing Prefix Estimation ('B')... ");
            TupleBuilder tbB = new TupleBuilder(pool, desc);
            tbB.putField(0, "B".getBytes());
            MemorySegment prefixB = tbB.build().getFieldSegment(0);
            long countB = estimator.estimatePrefix(prefixB);
            if (countB != 400)
                throw new RuntimeException("Prefix B estimate failed: expected 400, got " + countB);
            System.out.println("Passed.");

            // 4. Test Range Estimation
            System.out.print("Testing Range Estimation [A-00050, B-00050)... ");
            TupleBuilder tb1 = new TupleBuilder(pool, desc);
            tb1.putField(0, "A-00050".getBytes());
            TupleBuilder tb2 = new TupleBuilder(pool, desc);
            tb2.putField(0, "B-00050".getBytes());
            long range = estimator.estimateRange(tb1.build().segment(), tb2.build().segment());
            if (range != 100)
                throw new RuntimeException("Range estimate failed: expected 100, got " + range);
            System.out.println("Passed.");

            System.out.println("--- Cardinality Estimator Test PASSED ---");
        }
    }

    private static void addMutation(
            List<TreeMutator.Mutation> list, DirectBufferPool pool, String key) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes());
        list.add(
                new TreeMutator.Mutation(
                        tb.build().segment(), MemorySegment.ofArray("val".getBytes())));
    }
}
