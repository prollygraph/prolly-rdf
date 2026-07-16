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
package com.earasoft.prolly.semantic;

import com.dolthub.prolly.*;
import com.earasoft.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 *
 *
 * <h3>Projecting Iterator</h3>
 *
 * <p>An iterator that filters by a fixed prefix and projects a specific field as the primary join
 * key.
 */
public class ProjectingIterator implements MapIterator {
    private final StaticMap map;
    private final TupleDescriptor desc;
    private final DirectBufferPool pool;
    private final List<String> prefix;
    private final int projectIdx;

    private MapIterator inner;
    // @Nullable: unset until the first next() positions the iterator; key() reads it only after a
    // next()==true, which it asserts.
    private @Nullable MemorySegment currentProjected;
    private boolean done = false;

    public ProjectingIterator(
            StaticMap map,
            TupleDescriptor desc,
            DirectBufferPool pool,
            List<String> prefix,
            int projectIdx) {
        this.map = map;
        this.desc = desc;
        this.pool = pool;
        this.prefix = prefix;
        this.projectIdx = projectIdx;

        TupleBuilder tb = new TupleBuilder(pool, desc);
        for (int i = 0; i < prefix.size(); i++) {
            tb.putField(i, prefix.get(i).getBytes(StandardCharsets.UTF_8));
        }
        this.inner = map.iterRange(tb.build().segment());
    }

    @Override
    public boolean next() {
        if (done) return false;
        while (inner.next()) {
            Tuple t = new Tuple(inner.key());
            if (!matchesPrefix(t)) {
                done = true;
                return false;
            }

            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, t.getField(projectIdx));
            currentProjected = tb.build().segment();
            return true;
        }
        done = true;
        return false;
    }

    @Override
    public void seek(MemorySegment key) {
        if (done) return;
        Tuple varTup = new Tuple(key);
        byte[] varVal = varTup.getField(0);

        TupleBuilder tb = new TupleBuilder(pool, desc);
        for (int i = 0; i < prefix.size(); i++) {
            tb.putField(i, prefix.get(i).getBytes(StandardCharsets.UTF_8));
        }
        tb.putField(projectIdx, varVal);

        inner.seek(tb.build().segment());
    }

    private boolean matchesPrefix(Tuple t) {
        for (int i = 0; i < prefix.size(); i++) {
            byte[] f = t.getField(i);
            if (f == null || !Arrays.equals(f, prefix.get(i).getBytes(StandardCharsets.UTF_8)))
                return false;
        }
        return true;
    }

    @Override
    public boolean prev() {
        return false;
    }

    @Override
    public MemorySegment key() {
        return Objects.requireNonNull(currentProjected, "key() read before a successful next()");
    }

    @Override
    public MemorySegment value() {
        return MemorySegment.NULL;
    }
}
