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
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 *
 *
 * <h3>Row-Level Audit Provenance Demo</h3>
 *
 * <p>This demo shows how to use the 'Blame' engine to identify the exact origin, author, and
 * timestamp of a specific data row.
 */
public class ProvenanceDemo {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Prolly Tree Row-Level Audit & Provenance Demo ===");
        Path tempDir = Files.createTempDirectory("prolly-provenance");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "audit-repo", desc, pool);
            db.createBranch("main", "EMPTY");
            VCUtils vc = new VCUtils(db, store, desc);

            // 1. Initial Insert
            commitRow(db, pool, "user-42", "Account Created", "Alice (Admin)", "Initial setup");

            // 2. Someone changes the email
            Thread.sleep(10); // Ensure timestamp diff
            commitRow(
                    db, pool, "user-42", "Email: alice@example.com", "Alice", "Updating my email");

            // 3. A malicious or accidental change occurs
            Thread.sleep(10);
            commitRow(
                    db,
                    pool,
                    "user-42",
                    "Email: hacked@evil.com",
                    "Unknown Script",
                    "Automated update");

            // 4. Audit the provenance of the 'user-42' row
            System.out.println("\nAUDIT LOG for key 'user-42':");
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, "user-42".getBytes());
            MemorySegment key = tb.build().segment();

            Commit origin = vc.blame("main", key);

            if (origin != null) {
                System.out.println("--------------------------------------------------");
                System.out.println("Last Modified By: " + origin.getAuthor());
                System.out.println("Commit Message:   " + origin.getMessage());
                System.out.println(
                        "Timestamp:        " + Instant.ofEpochMilli(origin.getTimestamp()));

                StaticMap sm =
                        new StaticMap(
                                store,
                                store.read(Objects.requireNonNull(origin.getRootValueHash()))
                                        .map(Node::fromBytes)
                                        .orElse(null),
                                desc);
                String val =
                        new String(
                                sm.get(key).get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
                System.out.println("Row Value at that time: " + val);
                System.out.println("--------------------------------------------------");
            }
        }
    }

    private static void commitRow(
            Database db, DirectBufferPool pool, String k, String v, String author, String msg) {
        byte[] parent = db.getHeadHash("main").orElse(null);
        StaticMap base = db.getBranch("main");
        MutableMap mm =
                new MutableMap(
                        base,
                        db.store(),
                        new TupleDescriptor(List.of(new Type(Encoding.String, false))),
                        pool);

        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        mm.put(tb.build().segment(), MemorySegment.ofArray(v.getBytes()));

        db.commit("main", mm.flush(), parent, author, msg);
    }
}
