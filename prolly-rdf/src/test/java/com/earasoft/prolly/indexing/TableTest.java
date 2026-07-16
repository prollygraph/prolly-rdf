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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TableTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Table & Secondary Index Test ---");
        Path tempDir = Files.createTempDirectory("prolly-table");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            // Primary: (ID: String) -> (ID: String, Name: String, Age: Int64)
            TupleDescriptor pkDesc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TupleDescriptor rowDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false), // ID
                                    new Type(Encoding.String, false), // Name
                                    new Type(Encoding.Int64, false) // Age
                                    ));

            // Secondary: (Name: String, ID: String) -> Empty
            // Note: In a real DB, we'd include PK for uniqueness/lookup
            TupleDescriptor nameIdxDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false), // Name
                                    new Type(Encoding.String, false) // ID (PK)
                                    ));
            IndexSchema nameSchema = new IndexSchema("idx_name", nameIdxDesc, new int[] {1, 0});

            StaticMap primaryIdx = new StaticMap(store, null, pkDesc);
            Map<IndexSchema, StaticMap> secondaries = new HashMap<>();
            secondaries.put(nameSchema, new StaticMap(store, null, nameIdxDesc));

            Table table = new Table(store, pool, primaryIdx, pkDesc, rowDesc, secondaries);
            TupleBuilder tb = new TupleBuilder(pool);

            // 1. Insert Row
            System.out.print("Inserting row... ");
            tb.putField(0, "user1".getBytes());
            Tuple pk1 = tb.build();

            TupleBuilder rowBuilder = new TupleBuilder(pool);
            rowBuilder.putField(0, "user1".getBytes());
            rowBuilder.putField(1, "Alice".getBytes());
            rowBuilder.putInt64(2, 30);
            Tuple row1 = rowBuilder.build();

            table.put(pk1.segment(), row1.segment());
            Table.TableState state = table.flush();
            System.out.println("Done.");

            // 2. Verify Secondary Index
            System.out.print("Verifying secondary index... ");
            StaticMap nameIdx = state.secondaries().get(nameSchema);

            TupleBuilder idxKeyBuilder = new TupleBuilder(pool, nameIdxDesc);
            idxKeyBuilder.putField(0, "Alice".getBytes());
            idxKeyBuilder.putField(1, "user1".getBytes());
            Tuple searchKey = idxKeyBuilder.build();

            if (nameIdx.get(searchKey.segment()).isEmpty()) {
                throw new RuntimeException("Secondary index entry not found for Alice");
            }
            System.out.println("Passed.");

            // 3. Update Row (Change Name)
            System.out.print("Updating row (Alice -> Bob)... ");
            rowBuilder.putField(1, "Bob".getBytes());
            Tuple row1Updated = rowBuilder.build();

            table = new Table(store, pool, state.primary(), pkDesc, rowDesc, state.secondaries());
            table.put(pk1.segment(), row1Updated.segment());
            state = table.flush();
            System.out.println("Done.");

            // 4. Verify Cleanup
            System.out.print("Verifying old index entry cleanup... ");
            nameIdx = state.secondaries().get(nameSchema);
            if (nameIdx.get(searchKey.segment()).isPresent()) {
                throw new RuntimeException("Old secondary index entry still exists for Alice");
            }

            idxKeyBuilder.putField(0, "Bob".getBytes());
            if (nameIdx.get(idxKeyBuilder.build().segment()).isEmpty()) {
                throw new RuntimeException("New secondary index entry not found for Bob");
            }
            System.out.println("Passed.");

            System.out.println("--- Table & Index Test PASSED ---");
        }
    }
}
