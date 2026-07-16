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
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link LeapfrogJoin}'s {@code MapIterator} stub methods — {@code prev}, {@code
 * seek}, {@code value}.
 *
 * <p>The leapfrog-join correctness tests drive {@code next}/{@code key}; the cursor surface a
 * forward key-join doesn't implement is pinned here: backward iteration and seek are unsupported,
 * and {@code value} is always the null segment (a key-join materializes no values).
 */
class LeapfrogJoinStubsTest {

    private static LeapfrogJoin emptyJoin() {
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        StaticMap empty = new StaticMap(new InMemoryNodeStore(), null, desc);
        return new LeapfrogJoin(List.of(empty.iter()), desc);
    }

    @Test
    void prev_is_unsupported() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> emptyJoin().prev(),
                "a leapfrog key-join iterates forward only");
    }

    @Test
    void seek_is_unsupported() {
        LeapfrogJoin join = emptyJoin();
        assertThrows(
                UnsupportedOperationException.class,
                () -> join.seek(MemorySegment.NULL),
                "seek is not part of the leapfrog-join contract");
    }

    @Test
    void value_is_always_the_null_segment() {
        assertEquals(
                MemorySegment.NULL,
                emptyJoin().value(),
                "a key-join materializes no values — value() is the null segment");
    }
}
