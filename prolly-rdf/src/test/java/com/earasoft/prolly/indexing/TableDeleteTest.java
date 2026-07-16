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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link Table#delete} — {@code TableTest} (a {@code main()}-style smoke test)
 * exercises insert / update / secondary-index lookup but never the delete path, which must drop the
 * row from the primary map <em>and</em> every secondary index.
 */
class TableDeleteTest {

    private static final TupleDescriptor PK =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final TupleDescriptor ROW =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.String, false), // ID
                            new Type(Encoding.String, false), // Name
                            new Type(Encoding.Int64, false))); // Age
    private static final TupleDescriptor NAME_IDX =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.String, false), // Name
                            new Type(Encoding.String, false))); // ID (the PK)
    private static final IndexSchema NAME_SCHEMA =
            new IndexSchema("idx_name", NAME_IDX, new int[] {1, 0});

    private static Table freshTable(RocksNodeStore store, DirectBufferPool pool) {
        Map<IndexSchema, StaticMap> secondaries = new HashMap<>();
        secondaries.put(NAME_SCHEMA, new StaticMap(store, null, NAME_IDX));
        return new Table(store, pool, new StaticMap(store, null, PK), PK, ROW, secondaries);
    }

    @Test
    void delete_removes_the_row_from_the_primary_and_secondary_indexes(@TempDir Path dir)
            throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {

            Table table = freshTable(store, pool);

            TupleBuilder pkb = new TupleBuilder(pool);
            pkb.putField(0, "user1".getBytes());
            Tuple pk = pkb.build();

            TupleBuilder rb = new TupleBuilder(pool);
            rb.putField(0, "user1".getBytes());
            rb.putField(1, "Alice".getBytes());
            rb.putInt64(2, 30);
            table.put(pk.segment(), rb.build().segment());
            Table.TableState afterInsert = table.flush();
            assertTrue(
                    afterInsert.primary().get(pk.segment()).isPresent(),
                    "sanity: the row is present before the delete");

            // Re-open over the flushed state and delete the row.
            Table table2 =
                    new Table(
                            store, pool, afterInsert.primary(), PK, ROW, afterInsert.secondaries());
            table2.delete(pk.segment());
            Table.TableState afterDelete = table2.flush();

            assertFalse(
                    afterDelete.primary().get(pk.segment()).isPresent(),
                    "the row is gone from the primary index");

            // The secondary key is (Name, ID) per the {1,0} field map.
            TupleBuilder idxb = new TupleBuilder(pool, NAME_IDX);
            idxb.putField(0, "Alice".getBytes());
            idxb.putField(1, "user1".getBytes());
            assertFalse(
                    afterDelete
                            .secondaries()
                            .get(NAME_SCHEMA)
                            .get(idxb.build().segment())
                            .isPresent(),
                    "the secondary index entry is removed alongside the row");
        }
    }

    @Test
    void delete_of_an_absent_key_is_a_no_op(@TempDir Path dir) throws Exception {
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Table table = freshTable(store, pool);

            TupleBuilder pkb = new TupleBuilder(pool);
            pkb.putField(0, "ghost".getBytes());
            var ghost = pkb.build().segment();
            // delete() of a never-inserted key takes the row-absent branch
            // and must not throw.
            assertDoesNotThrow(() -> table.delete(ghost));
        }
    }
}
