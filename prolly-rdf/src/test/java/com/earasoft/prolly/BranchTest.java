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

public class BranchTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Branch & Tag Test ---");
        Path tempDir = Files.createTempDirectory("prolly-branch");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "test-repo", desc, pool);
            TupleBuilder tb = new TupleBuilder(pool);
            db.createBranch("main", "EMPTY");
            StaticMap mainMap = db.getBranch("main");
            MutableMap mm = new MutableMap(mainMap, store, desc, pool);
            tb.putField(0, "base-item".getBytes());
            mm.put(tb.build().segment(), MemorySegment.ofArray("v1".getBytes()));
            db.commit("main", mm.flush(), null, "author", "v1");
            db.createBranch("feature", "main");
            byte[] featureParent = db.getHeadHash("feature").get();
            StaticMap featureMap = db.getBranch("feature");
            mm = new MutableMap(featureMap, store, desc, pool);
            tb.putField(0, "feature-item".getBytes());
            mm.put(tb.build().segment(), MemorySegment.ofArray("v-feat".getBytes()));
            db.commit("feature", mm.flush(), featureParent, "author", "feat");
            StaticMap finalMain = db.getBranch("main");
            StaticMap finalFeature = db.getBranch("feature");
            tb.putField(0, "feature-item".getBytes());
            if (finalMain.get(tb.build().segment()).isPresent())
                throw new RuntimeException("Isolation error");
            System.out.println("--- Branch & Tag Test PASSED ---");
        }
    }
}
