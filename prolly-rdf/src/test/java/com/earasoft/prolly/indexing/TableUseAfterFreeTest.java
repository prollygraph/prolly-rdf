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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for {@code Table} — the secondary-index path the recycling bug corrupted
 * (plans/off-heap-use-after-free-tests.md Phase 3 Step 12). The full
 * insert→index→lookup→update→cleanup cycle is already pinned functionally by {@code TableTest} (the
 * recycling-bug regression). {@code Table} is {@code DirectBufferPool}-coupled, so the
 * **poison-differential** version of this is owned by {@code buffer-pool-interface-decoupling}
 * Phase 2 Step 3 (it needs the widening to accept the harness). What's feasible + new now: the
 * built secondary index is store-backed, so it **survives the build pool's arena being closed**
 * (the Table-level UAF boundary, H4) — a regression that made the index hold a pool-backed segment
 * would read garbage after close.
 */
class TableUseAfterFreeTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void secondaryIndexSurvivesTheBuildPoolClose() {
        TupleDescriptor pkDesc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        TupleDescriptor rowDesc =
                new TupleDescriptor(
                        List.of(
                                new Type(Encoding.String, false), // ID
                                new Type(Encoding.String, false), // Name
                                new Type(Encoding.Int64, false))); // Age
        TupleDescriptor nameIdxDesc =
                new TupleDescriptor(
                        List.of(
                                new Type(Encoding.String, false), // Name
                                new Type(Encoding.String, false))); // ID
        IndexSchema nameSchema = new IndexSchema("idx_name", nameIdxDesc, new int[] {1, 0});

        int n = 20;
        InMemoryNodeStore store = new InMemoryNodeStore();
        Table.TableState state;

        try (DirectBufferPool pool = new DirectBufferPool()) {
            Map<IndexSchema, StaticMap> secondaries = new HashMap<>();
            secondaries.put(nameSchema, new StaticMap(store, null, nameIdxDesc));
            Table table =
                    new Table(
                            store,
                            pool,
                            new StaticMap(store, null, pkDesc),
                            pkDesc,
                            rowDesc,
                            secondaries);
            for (int i = 0; i < n; i++) {
                TupleBuilder pk = new TupleBuilder(pool);
                pk.putField(0, utf8("user" + i));
                TupleBuilder row = new TupleBuilder(pool);
                row.putField(0, utf8("user" + i));
                row.putField(1, utf8("Name" + i));
                row.putInt64(2, i);
                table.put(pk.build().segment(), row.build().segment());
            }
            state = table.flush();
        } // DirectBufferPool closed — its off-heap arena is freed here

        // The secondary index is store-backed (heap reads), so iterating it after the build pool's
        // arena
        // is gone returns the correct entries — the index holds no pool-backed segment.
        StaticMap nameIdx = state.secondaries().get(nameSchema);
        Set<String> names = new TreeSet<>();
        MapIterator it = nameIdx.iter();
        while (it.next()) {
            names.add(new String(new Tuple(it.key()).getField(0), StandardCharsets.UTF_8));
        }

        Set<String> expected = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            expected.add("Name" + i);
        }
        assertEquals(
                expected,
                names,
                "secondary index entries must be correct after the build pool closed (store-backed)");
    }
}
