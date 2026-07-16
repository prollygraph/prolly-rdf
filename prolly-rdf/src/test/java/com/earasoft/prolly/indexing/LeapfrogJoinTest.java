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
package com.earasoft.prolly.indexing;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class LeapfrogJoinTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Leapfrog Triejoin Test ---");
        Path tempDir = Files.createTempDirectory("prolly-join");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TupleBuilder tb = new TupleBuilder(pool);

            // 1. Setup two trees with intersecting data
            // Tree A: {1, 3, 5, 7, 9}
            StaticMap smA =
                    buildSet(
                            store,
                            pool,
                            desc,
                            List.of("key-1", "key-3", "key-5", "key-7", "key-9"));
            // Tree B: {2, 3, 6, 7, 10}
            StaticMap smB =
                    buildSet(
                            store,
                            pool,
                            desc,
                            List.of("key-2", "key-3", "key-6", "key-7", "key-10"));

            // 2. Perform Join (Intersection should be {key-3, key-7})
            System.out.print("Performing intersection join... ");
            LeapfrogJoin join = new LeapfrogJoin(List.of(smA.iter(), smB.iter()), desc);

            int found = 0;
            while (join.next()) {
                String k = new String(new Tuple(join.key()).getField(0));
                if (found == 0 && !k.equals("key-3"))
                    throw new RuntimeException("Match 1 failed: " + k);
                if (found == 1 && !k.equals("key-7"))
                    throw new RuntimeException("Match 2 failed: " + k);
                found++;
            }

            if (found != 2) throw new RuntimeException("Expected 2 matches, found " + found);
            System.out.println("Passed.");

            System.out.println("--- Leapfrog Join Test PASSED ---");
        }
    }

    private static StaticMap buildSet(
            NodeStore ns,
            com.earasoft.prolly.pool.DirectBufferPool pool,
            TupleDescriptor desc,
            List<String> keys) {
        StaticMap sm = new StaticMap(ns, null, desc);
        MutableMap mm = new MutableMap(sm, ns, desc, pool);
        TupleBuilder tb = new TupleBuilder(pool);
        for (String k : keys) {
            tb.putField(0, k.getBytes());
            mm.put(tb.build().segment(), MemorySegment.NULL);
        }
        return mm.flush();
    }
}
