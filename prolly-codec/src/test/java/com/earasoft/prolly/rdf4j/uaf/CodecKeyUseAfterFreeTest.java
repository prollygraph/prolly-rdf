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
package com.earasoft.prolly.rdf4j.uaf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.ArenaScopeProbe;
import com.dolthub.prolly.PoisoningBufferPool;
import com.dolthub.prolly.Tuple;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Int64Key;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the codec key builders (plans/off-heap-use-after-free-tests.md Phase
 * 2 Step 9) — the first downstream cluster, consuming the poison harness from prolly-port-core's
 * test-jar (D-5). {@link SpocKey#toTupleSegment} / {@link Int64Key#toTupleSegment} build
 * short-lived lookup-key tuples by borrowing from the pool (H2/H3). Pins: the built key is
 * byte-identical through the poison pool and the heap pool (no use-after-free in the build), and a
 * key tuple is pool-backed — it dies with the pool's arena (H1) while a decoded {@link SpocKey}
 * (which copies the ids out) survives.
 */
class CodecKeyUseAfterFreeTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    @Test
    void spocKeyTupleIsByteIdenticalThroughPoisonAndHeapPool() {
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool ->
                        new SpocKey(TermId.of(11), TermId.of(22), TermId.of(33), TermId.of(44))
                                .toTupleSegment(pool)
                                .toArray(BYTE));
    }

    @Test
    void int64KeyTupleIsByteIdenticalThroughPoisonAndHeapPool() {
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> Int64Key.toTupleSegment(pool, 0x0102030405060708L).toArray(BYTE));
    }

    @Test
    void spocKeyTupleDecodesWhileAlive_thenDiesWithThePoolArena() {
        PoisoningBufferPool pool = new PoisoningBufferPool();
        MemorySegment seg =
                new SpocKey(TermId.of(7), TermId.of(8), TermId.of(9), TermId.of(10))
                        .toTupleSegment(pool);

        // Decode while the pool is open — fromTuple copies the ids out into an independent SpocKey.
        SpocKey decoded = SpocKey.fromTuple(new Tuple(seg));
        assertEquals(TermId.of(7), decoded.col0());

        pool.close(); // closes the arena backing the key tuple

        assertThrows(
                IllegalStateException.class,
                () -> new Tuple(seg).getField(0),
                "a key tuple read after its pool's arena closed must throw, not read freed memory");
        // The decoded key is independent of the segment, so it survives the close.
        assertEquals(TermId.of(10), decoded.col3());
    }
}
