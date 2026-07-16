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
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.util.*;

public class DebugMutationTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Debug Mutation Test ---");
        DirectBufferPool pool = new DirectBufferPool();
        RocksNodeStore store =
                new RocksNodeStore(Files.createTempDirectory("prolly-debug").toString());
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        TreeMutator mutator = new TreeMutator(store, desc, pool);

        // 1. Build initial tree with [K1, K3]
        System.out.println("Build 1: [k1, k3]");
        List<TreeMutator.Mutation> b1 = List.of(mut(pool, "k1", "v1"), mut(pool, "k3", "v3"));
        Node root = mutator.applyMutations(null, b1.iterator());
        dump("Tree 1", root, store);

        // 2. Insert K2 in the middle
        System.out.println("\nBuild 2: Insert k2 -> [k1, k2, k3]");
        List<TreeMutator.Mutation> b2 = List.of(mut(pool, "k2", "v2"));
        root = mutator.applyMutations(root, b2.iterator());
        dump("Tree 2", root, store);
        // Insert-in-middle must land k2 in sorted position: ordered scan is
        // exactly [k1, k2, k3]. (A THROW is what fails the DynamicTest --
        // MainMethodTests treats a clean return as PASS, so before this the
        // trace dumps could never fail.)
        assertKeys(root, store, List.of("k1", "k2", "k3"));

        // 3. Update K1
        System.out.println("\nBuild 3: Update k1 -> [k1_new, k2, k3]");
        List<TreeMutator.Mutation> b3 = List.of(mut(pool, "k1", "v1_new"));
        root = mutator.applyMutations(root, b3.iterator());
        dump("Tree 3", root, store);
        // Update-in-place: key set unchanged, k1's VALUE replaced (not a
        // duplicate key, not a stale value).
        assertKeys(root, store, List.of("k1", "k2", "k3"));
        assertValue(pool, desc, root, store, "k1", "v1_new");
    }

    private static void assertKeys(Node root, NodeStore store, List<String> expected) {
        StaticMap map = new StaticMap(store, root, null);
        List<String> actual = new java.util.ArrayList<>();
        MapIterator it = map.iter();
        while (it.next()) actual.add(new String(new Tuple(it.key()).getField(0)));
        if (!actual.equals(expected)) {
            throw new AssertionError("expected ordered keys " + expected + " but got " + actual);
        }
    }

    private static void assertValue(
            DirectBufferPool pool,
            TupleDescriptor desc,
            Node root,
            NodeStore store,
            String key,
            String expected) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes());
        // StaticMap.get() needs the descriptor to compare keys (iter() does
        // not, which is why dump()'s null worked there but not here).
        StaticMap map = new StaticMap(store, root, desc);
        java.util.Optional<MemorySegment> got = map.get(tb.build().segment());
        if (got.isEmpty()) throw new AssertionError("key " + key + " missing after update");
        byte[] bytes = got.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
        String actual = new String(bytes);
        if (!actual.equals(expected)) {
            throw new AssertionError("expected " + key + "=" + expected + " but got " + actual);
        }
    }

    private static TreeMutator.Mutation mut(DirectBufferPool pool, String k, String v) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return new TreeMutator.Mutation(tb.build().segment(), MemorySegment.ofArray(v.getBytes()));
    }

    private static void dump(String label, Node root, NodeStore store) {
        System.out.print(label + ": ");
        StaticMap map = new StaticMap(store, root, null);
        MapIterator it = map.iter();
        while (it.next()) {
            System.out.print(new String(new Tuple(it.key()).getField(0)) + " ");
        }
        System.out.println();
    }
}
