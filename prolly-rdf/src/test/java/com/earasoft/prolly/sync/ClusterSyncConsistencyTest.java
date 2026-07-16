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

/**
 *
 *
 * <h3>Cluster Synchronization Consistency Test</h3>
 *
 * <p>Simulates two nodes in a cluster (Alpha and Beta). Node Alpha undergoes a complex series of
 * versioning operations (branching, merging, updating). Node Beta performs a Merkle Pull to catch
 * up. The test verifies that both nodes converge to the identical cryptographic state.
 */
public class ClusterSyncConsistencyTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Cluster Sync Consistency Test ---");
        Path pathAlpha = Files.createTempDirectory("prolly-sync-alpha");
        Path pathBeta = Files.createTempDirectory("prolly-sync-beta");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore storeAlpha = new RocksNodeStore(pathAlpha.toString());
                RocksNodeStore storeBeta = new RocksNodeStore(pathBeta.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database dbAlpha = new Database(storeAlpha, "repo", desc, pool);
            dbAlpha.createBranch("main", "EMPTY");

            // 1. Node Alpha: Initial history
            MutableMap mm = new MutableMap(dbAlpha.getBranch("main"), storeAlpha, desc, pool);
            for (int i = 0; i < 500; i++) put(mm, pool, "k-" + i, "v-init");
            dbAlpha.commit("main", mm.flush(), null, "admin", "initial");
            byte[] c1 = dbAlpha.getHeadHash("main").get();

            // 2. Node Alpha: Divergent work on feature branch
            dbAlpha.createBranch("feature", "main");
            MutableMap mmF = new MutableMap(dbAlpha.getBranch("feature"), storeAlpha, desc, pool);
            for (int i = 250; i < 750; i++) put(mmF, pool, "k-" + i, "v-feature");
            dbAlpha.commit("feature", mmF.flush(), c1, "dev", "work on feature");

            // 3. Node Alpha: Merge feature back to main
            System.out.println("Node Alpha: Performing complex merge...");
            dbAlpha.merge("main", "feature", "admin", "merge feature");
            byte[] finalRootAlpha = dbAlpha.getBranch("main").root().bytes();
            byte[] finalHeadAlpha = dbAlpha.getHeadHash("main").get();

            // 4. Node Beta: Synchronization (Merkle Pull)
            System.out.println("Node Beta: Pulling Merkle DAG from Node Alpha...");
            SyncEngine sync = new SyncEngine(storeBeta, storeAlpha);

            // Sync the commit head and its recursive data tree
            storeBeta.write(storeAlpha.read(finalHeadAlpha).get());
            Commit head = dbAlpha.getHead("main");
            sync.pull(head.getRootValueHash());

            // 5. Node Beta: Verify Convergence
            System.out.print("Node Beta: Verifying cryptographic convergence... ");
            Database dbBeta = new Database(storeBeta, "repo", desc, pool);
            // Manually update Beta's ref to match Alpha's head (simulating manifest update)
            storeBeta.db().put("ref:repo/heads/main".getBytes(), finalHeadAlpha);

            byte[] finalRootBeta = dbBeta.getBranch("main").root().bytes();
            if (!Arrays.equals(finalRootAlpha, finalRootBeta)) {
                throw new RuntimeException(
                        "Sync Convergence Failure! Node Beta has different root data than Node Alpha.");
            }

            if (dbBeta.getBranch("main").root().treeCount() != 750) {
                throw new RuntimeException(
                        "Count Mismatch on Beta: " + dbBeta.getBranch("main").root().treeCount());
            }
            System.out.println("Passed.");

            System.out.println(
                    "Cluster State: All nodes synchronized and cryptographically identical.");
            System.out.println("--- Cluster Sync Consistency Test PASSED ---");
        }
    }

    private static void put(MutableMap m, DirectBufferPool pool, String k, String v) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        m.put(tb.build().segment(), MemorySegment.ofArray(v.getBytes()));
    }
}
