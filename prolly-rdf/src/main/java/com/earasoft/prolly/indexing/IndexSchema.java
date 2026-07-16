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
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;

/**
 *
 *
 * <h3>Index Mapping Schema</h3>
 *
 * <p>Defines how a secondary index is projected from a primary data row.
 */
public class IndexSchema {
    private final String name;
    private final TupleDescriptor descriptor;
    private final int[] fieldMapping;

    public IndexSchema(String name, TupleDescriptor descriptor, int[] fieldMapping) {
        this.name = name;
        this.descriptor = descriptor;
        this.fieldMapping = fieldMapping;
    }

    public String getName() {
        return name;
    }

    public TupleDescriptor getDescriptor() {
        return descriptor;
    }

    /** Builds an index key from a primary row tuple. */
    public MemorySegment buildIndexKey(
            Tuple primaryRow, com.earasoft.prolly.pool.DirectBufferPool pool) {
        TupleBuilder tb = new TupleBuilder(pool, descriptor);
        for (int i = 0; i < fieldMapping.length; i++) {
            tb.putField(i, primaryRow.getFieldSegment(fieldMapping[i]));
        }
        return tb.build().segment();
    }
}
