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
import java.nio.file.Path;
import java.util.List;

public class MultiTenantTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Multi-Tenant Test ---");
        Path tempDir = Files.createTempDirectory("prolly-multi-tenant");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TupleBuilder tb = new TupleBuilder(pool);
            Database dbA = new Database(store, "repoA", desc, pool);
            Database dbB = new Database(store, "repoB", desc, pool);
            dbA.createBranch("main", "EMPTY");
            dbB.createBranch("main", "EMPTY");
            StaticMap baseA = dbA.getBranch("main");
            MutableMap mmA = new MutableMap(baseA, store, desc, pool);
            tb.putField(0, "itemA".getBytes());
            mmA.put(tb.build().segment(), MemorySegment.ofArray("valA".getBytes()));
            dbA.commit("main", mmA.flush(), null, "author", "vA");
            StaticMap baseB = dbB.getBranch("main");
            MutableMap mmB = new MutableMap(baseB, store, desc, pool);
            tb.putField(0, "itemB".getBytes());
            mmB.put(tb.build().segment(), MemorySegment.ofArray("valB".getBytes()));
            dbB.commit("main", mmB.flush(), null, "author", "vB");
            StaticMap curA = dbA.getBranch("main");
            StaticMap curB = dbB.getBranch("main");
            tb.putField(0, "itemA".getBytes());
            if (curA.get(tb.build().segment()).isEmpty()) throw new RuntimeException("repoA error");
            if (curB.get(tb.build().segment()).isPresent())
                throw new RuntimeException("repoB isolation error");
            System.out.println("--- Multi-Tenant Test PASSED ---");
        }
    }
}
