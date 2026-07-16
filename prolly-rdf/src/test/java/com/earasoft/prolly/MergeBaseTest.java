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
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 *
 *
 * <h3>Merge Base (LCA) Correctness Test</h3>
 *
 * <p>Asserts that {@code Database#findLCA} returns the <i>lowest</i> common ancestor of two commit
 * DAGs, not just any common ancestor.
 *
 * <p><b>Bug fixed:</b> the prior implementation BFS-collected every ancestor of {@code A}, then
 * BFS-walked {@code B}'s parents and returned the first hit. For a diamond DAG that was usually the
 * <i>oldest</i> shared ancestor (the root of the diamond), not the merge point — three-way merge
 * then re-applied changes that had already converged at the actual base, producing spurious
 * conflicts or clobbering correct lines. The fix collects full ancestor sets, intersects them,
 * drops common nodes that are strict ancestors of other common nodes ("shadowed"), and picks the
 * latest of the survivors.
 *
 * <p><b>Topology under test:</b>
 *
 * <pre>
 *   A0 ──&gt; A1 ──┬──&gt; A2 (branch "left")
 *               │
 *               └──&gt; A2' (branch "right")
 * </pre>
 *
 * <p>The lowest common ancestor of "left" and "right" is {@code A1}, not {@code A0}. The pre-fix
 * BFS would commonly return {@code A0}.
 */
public class MergeBaseTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Merge Base (LCA) Correctness Test ---");
        Path tempDir = Files.createTempDirectory("prolly-merge-base");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "lca-repo", desc, pool);

            // A0
            db.createBranch("trunk", "EMPTY");
            commit(db, store, desc, pool, "trunk", "k1", "v0", null);
            byte[] a0 = db.getHeadHash("trunk").orElseThrow();
            byte[] a0RootValue = loadCommit(store, a0).getRootValueHash();
            sleep(2);

            // A1 (descends from A0)
            commit(db, store, desc, pool, "trunk", "k2", "v0", a0);
            byte[] a1 = db.getHeadHash("trunk").orElseThrow();
            byte[] a1RootValue = loadCommit(store, a1).getRootValueHash();
            sleep(2);

            // Branch A1 to "left" and "right".
            db.createBranch("left", "trunk");
            db.createBranch("right", "trunk");

            // left: a single commit on top of A1.
            commit(db, store, desc, pool, "left", "k1", "v-left", a1);
            byte[] leftHead = db.getHeadHash("left").orElseThrow();
            sleep(2);

            // right: a single commit on top of A1.
            commit(db, store, desc, pool, "right", "k1", "v-right", a1);
            byte[] rightHead = db.getHeadHash("right").orElseThrow();

            // findLCA is private — invoke via reflection. We assert the returned
            // data-root hash equals the data root of A1 (NOT A0).
            Method findLCA =
                    Database.class.getDeclaredMethod("findLCA", byte[].class, byte[].class);
            findLCA.setAccessible(true);

            byte[] lcaRoot = (byte[]) findLCA.invoke(db, leftHead, rightHead);
            System.out.println("LCA(left, right) data-root: " + toHex(lcaRoot));

            if (Arrays.equals(lcaRoot, a0RootValue)) {
                throw new RuntimeException(
                        "findLCA returned A0's root — the original buggy BFS would do this. "
                                + "Expected A1's root "
                                + toHex(a1RootValue));
            }
            if (!Arrays.equals(lcaRoot, a1RootValue)) {
                throw new RuntimeException(
                        "findLCA returned an unexpected root: "
                                + toHex(lcaRoot)
                                + " (expected A1="
                                + toHex(a1RootValue)
                                + ")");
            }
            System.out.println("LCA correctly identified as A1.");

            // Linear case: LCA(A1, leftHead) == A1.
            byte[] linearLca = (byte[]) findLCA.invoke(db, a1, leftHead);
            if (!Arrays.equals(linearLca, a1RootValue)) {
                throw new RuntimeException(
                        "Linear LCA wrong: got "
                                + toHex(linearLca)
                                + " expected "
                                + toHex(a1RootValue));
            }
            System.out.println("Linear LCA(A1, left) correctly identified as A1.");

            System.out.println("--- Merge Base (LCA) Correctness Test PASSED ---");
        }
    }

    private static void commit(
            Database db,
            RocksNodeStore store,
            TupleDescriptor desc,
            DirectBufferPool pool,
            String branch,
            String key,
            String value,
            byte[] parent) {
        MutableMap mm = new MutableMap(db.getBranch(branch), store, desc, pool);
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, key.getBytes());
        mm.put(tb.build().segment(), MemorySegment.ofArray(value.getBytes()));
        if (!db.commit(branch, mm.flush(), parent, "tester", "msg-" + key + "-" + value)) {
            throw new RuntimeException("commit failed on " + branch);
        }
    }

    private static Commit loadCommit(NodeStore store, byte[] hash) {
        return store.read(hash)
                .map(
                        seg ->
                                Commit.deserialize(
                                        seg.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)))
                .orElseThrow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
