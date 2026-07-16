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
package com.earasoft.prolly.demo;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
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
import java.util.Objects;
import java.util.Optional;

/**
 *
 *
 * <h3>Root-Cause Analysis Demo</h3>
 *
 * <p>This demo shows how to use the 'Bisect' engine to automatically find the first commit that
 * introduced an invalid data state (e.g. negative balance).
 */
public class RootCauseAnalysisDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Prolly Tree Root-Cause Analysis Demo (Bisect) ===");
        Path tempDir = Files.createTempDirectory("prolly-rca");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc =
                    new TupleDescriptor(List.of(new Type(Encoding.Int64, false)), true);
            Database db = new Database(store, "finance-repo", desc, pool);
            db.createBranch("main", "EMPTY");
            VCUtils vc = new VCUtils(db, store, desc);

            // 1. Setup History
            byte[] firstCommit = null;
            byte[] lastCommit = null;

            System.out.println("Generating 50 transactions...");
            for (int i = 0; i <= 50; i++) {
                byte[] parent = db.getHeadHash("main").orElse(null);
                StaticMap base = db.getBranch("main");
                MutableMap mm = new MutableMap(base, store, desc, pool);

                TupleBuilder tbK = new TupleBuilder(pool, desc);
                tbK.putInt64(0, 1001);

                // Bug introduced at 27 and persists
                long balance = (i >= 27) ? -500 : (1000 + i * 10);

                TupleBuilder tbV = new TupleBuilder(pool, desc);
                tbV.putInt64(0, balance);

                mm.put(tbK.build().segment(), tbV.build().segment());
                db.commit("main", mm.flush(), parent, "bank-system", "Transaction " + i);

                byte[] current = db.getHeadHash("main").get();
                if (i == 0) firstCommit = current;
                lastCommit = current;
            }

            // 2. Perform Root-Cause Analysis
            System.out.println("\nAnalyzing history for 'Negative Balance' bug...");
            System.out.println("Range: Commit #0 to Commit #50");

            Commit culprit =
                    vc.bisect(
                            // The loop above runs ≥1 iteration (i=0), so both are set.
                            Objects.requireNonNull(firstCommit),
                            Objects.requireNonNull(lastCommit),
                            c -> {
                                StaticMap sm =
                                        new StaticMap(
                                                store,
                                                store.read(
                                                                Objects.requireNonNull(
                                                                        c.getRootValueHash()))
                                                        .map(Node::fromBytes)
                                                        .orElse(null),
                                                desc);
                                TupleBuilder tbK = new TupleBuilder(pool, desc);
                                tbK.putInt64(0, 1001);

                                Optional<MemorySegment> val = sm.get(tbK.build().segment());
                                if (val.isEmpty()) return false;
                                long balance = TypeCodec.decodeInt64(val.get());
                                return balance < 0;
                            });

            if (culprit != null) {
                System.out.println("--------------------------------------------------");
                System.out.println("FIRST BAD COMMIT IDENTIFIED:");
                System.out.println("Message:   " + culprit.getMessage());
                System.out.println("Author:    " + culprit.getAuthor());
                System.out.println(
                        "Timestamp: " + java.time.Instant.ofEpochMilli(culprit.getTimestamp()));

                // Double check the value
                StaticMap sm =
                        new StaticMap(
                                store,
                                store.read(Objects.requireNonNull(culprit.getRootValueHash()))
                                        .map(Node::fromBytes)
                                        .orElse(null),
                                desc);
                TupleBuilder tbK = new TupleBuilder(pool, desc);
                tbK.putInt64(0, 1001);
                long badBalance = TypeCodec.decodeInt64(sm.get(tbK.build().segment()).get());
                System.out.println("Verified Bad Value: " + badBalance);
                System.out.println("--------------------------------------------------");
            }
        }
    }
}
