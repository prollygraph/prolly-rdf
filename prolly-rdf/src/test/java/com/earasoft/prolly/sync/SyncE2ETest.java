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
package com.earasoft.prolly.sync;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SyncE2ETest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Distributed Sync E2E Test ---");
        Path remoteDir = Files.createTempDirectory("prolly-remote");
        Path localDir = Files.createTempDirectory("prolly-local");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore remoteRawStore = new RocksNodeStore(remoteDir.toString());
                RocksNodeStore localStore = new RocksNodeStore(localDir.toString())) {

            // Wrap remote in a client with simulated latency
            RemoteNodeStoreClient remoteClient = new RemoteNodeStoreClient(remoteRawStore);
            remoteClient.setLatency(1); // 1ms per chunk

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));

            // 1. Setup Remote Repo with data
            Database remoteDB = new Database(remoteRawStore, "shared-repo", desc, pool);
            remoteDB.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(remoteDB.getBranch("main"), remoteRawStore, desc, pool);
            for (int i = 0; i < 500; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("key-%05d", i).getBytes());
                mm.put(tb.build().segment(), MemorySegment.ofArray("val".getBytes()));
            }
            remoteDB.commit("main", mm.flush(), null, "admin", "Initial Load");

            byte[] remoteCommitHash = remoteDB.getHeadHash("main").get();
            Commit remoteCommit = remoteDB.getHead("main");

            // 2. Perform Sync (Pull)
            System.out.print("Pulling remote DAG (via network client)... ");
            long start = System.currentTimeMillis();
            SyncEngine sync = new SyncEngine(localStore, remoteClient);

            // Pull the Commit object
            localStore.write(remoteClient.read(remoteCommitHash).get());
            // Pull the data tree
            sync.pull(remoteCommit.getRootValueHash());
            long duration = System.currentTimeMillis() - start;
            System.out.println("Done (" + duration + "ms).");

            // 3. Verify Local Integrity
            System.out.print("Verifying local data integrity... ");
            Database localDB = new Database(localStore, "shared-repo", desc, pool);
            localStore.db().put(("ref:shared-repo/heads/main").getBytes(), remoteCommitHash);

            StaticMap localMap = localDB.getBranch("main");
            if (localMap.root().treeCount() != 500)
                throw new RuntimeException("Sync count mismatch");
            System.out.println("Passed.");

            System.out.println("--- Distributed Sync Test PASSED ---");
        }
    }
}
