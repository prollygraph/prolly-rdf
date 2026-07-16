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
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 *
 *
 * <h3>Table Orchestrator</h3>
 *
 * <p>Provides a high-level relational abstraction. It ensures that updates to the Primary Prolly
 * Tree are atomically propagated to all defined Secondary indices.
 */
public class Table {
    private final NodeStore store;
    private final DirectBufferPool pool;
    private final TupleDescriptor primaryKeyDesc;
    private final TupleDescriptor primaryRowDesc;

    private MutableMap primaryMap;
    private final Map<IndexSchema, MutableMap> secondaryMaps;

    public Table(
            NodeStore store,
            DirectBufferPool pool,
            StaticMap primaryIndex,
            TupleDescriptor primaryKeyDesc,
            TupleDescriptor primaryRowDesc,
            Map<IndexSchema, StaticMap> secondaryIndices) {
        this.store = store;
        this.pool = pool;
        this.primaryKeyDesc = primaryKeyDesc;
        this.primaryRowDesc = primaryRowDesc;
        this.primaryMap = new MutableMap(primaryIndex, store, primaryKeyDesc, pool);
        this.secondaryMaps = new HashMap<>();

        secondaryIndices.forEach(
                (schema, staticMap) -> {
                    secondaryMaps.put(
                            schema, new MutableMap(staticMap, store, schema.getDescriptor(), pool));
                });
    }

    /** Puts a row into the table, updating all indices. */
    public void put(MemorySegment pkSegment, MemorySegment rowSegment) {
        // 1. Check for old row to clean up secondary indices
        Optional<MemorySegment> oldRowSegment = primaryMap.get(pkSegment);
        if (oldRowSegment.isPresent()) {
            Tuple oldRow = new Tuple(oldRowSegment.get());
            for (var entry : secondaryMaps.entrySet()) {
                MemorySegment oldIndexKey = entry.getKey().buildIndexKey(oldRow, pool);
                entry.getValue().delete(oldIndexKey);
            }
        }

        // 2. Update Primary Index
        primaryMap.put(pkSegment, rowSegment);

        // 3. Update Secondary Indices
        Tuple newRow = new Tuple(rowSegment);
        for (var entry : secondaryMaps.entrySet()) {
            MemorySegment newIndexKey = entry.getKey().buildIndexKey(newRow, pool);
            entry.getValue().put(newIndexKey, MemorySegment.NULL);
        }
    }

    /** Deletes a row by its primary key. */
    public void delete(MemorySegment pkSegment) {
        Optional<MemorySegment> oldRowSegment = primaryMap.get(pkSegment);
        if (oldRowSegment.isPresent()) {
            Tuple oldRow = new Tuple(oldRowSegment.get());
            for (var entry : secondaryMaps.entrySet()) {
                MemorySegment oldIndexKey = entry.getKey().buildIndexKey(oldRow, pool);
                entry.getValue().delete(oldIndexKey);
            }
            primaryMap.delete(pkSegment);
        }
    }

    /** Flushes all pending edits to storage. */
    public TableState flush() {
        StaticMap newPrimary = primaryMap.flush();
        Map<IndexSchema, StaticMap> newSecondaries = new HashMap<>();
        secondaryMaps.forEach(
                (schema, mutable) -> {
                    newSecondaries.put(schema, mutable.flush());
                });
        return new TableState(newPrimary, newSecondaries);
    }

    public static record TableState(StaticMap primary, Map<IndexSchema, StaticMap> secondaries) {}
}
