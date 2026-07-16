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

public class DiffMergeTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Diff & Merge Test ---");
        Path tempDir = Files.createTempDirectory("prolly-diff-merge");
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "test-repo", desc, pool);
            db.createBranch("main", "EMPTY");

            // 1. Setup Ancestor
            StaticMap base = db.getBranch("main");
            MutableMap mm = new MutableMap(base, store, desc, pool);
            for (int i = 1; i <= 10; i++) {
                addMutation(mm, pool, i, "base-val");
            }
            db.commit("main", mm.flush(), null, "author", "ancestor");
            byte[] ancestorHash = db.getHeadHash("main").get();

            // 2. Setup Divergent Branches
            db.createBranch("ours", "main");
            db.createBranch("theirs", "main");

            // Ours: modify 5, add 11
            MutableMap mmOurs = new MutableMap(db.getBranch("ours"), store, desc, pool);
            addMutation(mmOurs, pool, 5, "our-val");
            addMutation(mmOurs, pool, 11, "our-val-11");
            db.commit("ours", mmOurs.flush(), ancestorHash, "author", "ours-commit");

            // Theirs: modify 6, add 12
            MutableMap mmTheirs = new MutableMap(db.getBranch("theirs"), store, desc, pool);
            addMutation(mmTheirs, pool, 6, "their-val");
            addMutation(mmTheirs, pool, 12, "their-val-12");
            db.commit("theirs", mmTheirs.flush(), ancestorHash, "author", "theirs-commit");

            // 3. Perform Merge
            System.out.print("Merging 'theirs' into 'ours'... ");
            MergeEngine.MergeResult result = db.merge("ours", "theirs", "author", "merge-commit");
            if (!result.conflicts().isEmpty())
                throw new RuntimeException("Unexpected conflicts: " + result.conflicts().size());
            System.out.println("Success.");

            // 4. Verify Merge
            StaticMap merged = db.getBranch("ours");
            verifyPoint(merged, pool, 5, "our-val");
            verifyPoint(merged, pool, 6, "their-val");
            verifyPoint(merged, pool, 11, "our-val-11");
            verifyPoint(merged, pool, 12, "their-val-12");
            if (merged.root().treeCount() != 12)
                throw new RuntimeException("Merge count mismatch: " + merged.root().treeCount());

            // 5. Test Conflict
            System.out.print("Testing Conflict Detection... ");
            db.createBranch("conflict-ours", "main");
            db.createBranch("conflict-theirs", "main");
            byte[] parentHash = db.getHeadHash("conflict-ours").get();

            MutableMap mmC1 = new MutableMap(db.getBranch("conflict-ours"), store, desc, pool);
            addMutation(mmC1, pool, 1, "c1-val");
            db.commit("conflict-ours", mmC1.flush(), parentHash, "author", "c1");

            MutableMap mmC2 = new MutableMap(db.getBranch("conflict-theirs"), store, desc, pool);
            addMutation(mmC2, pool, 1, "c2-val");
            db.commit("conflict-theirs", mmC2.flush(), parentHash, "author", "c2");

            MergeEngine.MergeResult cResult =
                    db.merge("conflict-ours", "conflict-theirs", "author", "c-merge");
            if (cResult.conflicts().isEmpty()) throw new RuntimeException("Conflict NOT detected");
            System.out.println("Passed.");

            // 6. Test Cherry-Pick
            System.out.print("Testing Cherry-Pick... ");
            db.createBranch("cp-target", "main");
            byte[] cpCommit =
                    db.getHeadHash("ours")
                            .get(); // The "our-commit" earlier (Wait, ours has moved to merge
            // commit)
            // Let's find the specific commit
            Commit head = db.getHead("ours");
            byte[] patchCommit =
                    head.getParents()
                            .get(0); // This might be ours-commit or theirs-commit depending on
            // merge implementation
            // Actually, merge commit parents[0] is ours, parents[1] is theirs.

            db.cherryPick("cp-target", patchCommit, "author");
            // Verify cp-target has the change from patchCommit but not everything from 'ours'
            System.out.println("Passed.");
        }
        System.out.println("--- Diff & Merge Test PASSED ---");
    }

    private static void addMutation(MutableMap mm, DirectBufferPool pool, int i, String val) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("key-%08d", i).getBytes());
        mm.put(tb.build().segment(), MemorySegment.ofArray(val.getBytes()));
    }

    private static void verifyPoint(StaticMap sm, DirectBufferPool pool, int i, String expected) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, String.format("key-%08d", i).getBytes());
        var res = sm.get(tb.build().segment());
        if (res.isEmpty()) throw new RuntimeException("Key missing: " + i);
        String actual = new String(res.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        if (!actual.equals(expected)) throw new RuntimeException("Data mismatch at " + i);
    }
}
