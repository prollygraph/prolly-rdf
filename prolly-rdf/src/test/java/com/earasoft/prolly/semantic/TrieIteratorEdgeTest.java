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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Deterministic edge-branch coverage for {@link TrieIterator} — the paths the randomized {@link
 * TrieIteratorProperty} leaves uncovered: the level accessors, the {@code Int64} successor path
 * (the TermId column type, never exercised by the String-keyed property), the unsupported-encoding
 * contract, and the {@code LevelIterator} cursor stubs ({@code prev}/{@code value}).
 */
class TrieIteratorEdgeTest {

    private static StaticMap singleCol(DirectBufferPool pool, Encoding enc, List<byte[]> values) {
        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(enc, false)));
        InMemoryNodeStore store = new InMemoryNodeStore();
        MutableMap mm = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
        for (byte[] v : values) {
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, v);
            mm.put(tb.build().segment(), MemorySegment.NULL);
        }
        return mm.flush();
    }

    private static byte[] le8(long x) {
        byte[] b = new byte[8];
        MemorySegment.ofArray(b)
                .set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, x);
        return b;
    }

    private static byte[] s(String v) {
        return v.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void accessorsReflectArityAndPosition() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            TupleDescriptor desc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false)));
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, desc), store, desc, pool);
            TupleBuilder tb = new TupleBuilder(pool);
            tb.putField(0, s("a"));
            tb.putField(1, s("b"));
            mm.put(tb.build().segment(), MemorySegment.NULL);

            TrieIterator trie = new TrieIterator(mm.flush(), desc, pool);
            assertEquals(2, trie.arity(), "arity is the tuple width");
            assertEquals(0, trie.depth(), "depth starts at the root level");
            assertEquals(0, trie.currentColumn(), "current column starts at 0");
            trie.open();
            assertEquals(1, trie.depth(), "after open(), one level is bound");
            assertEquals(1, trie.currentColumn(), "current column advanced to 1");
            assertTrue(trie.seekCount() >= 0, "seek counter is non-negative");
        }
    }

    @Test
    void nextWalksDistinctValuesAtLevel() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap map = singleCol(pool, Encoding.String, List.of(s("a"), s("b"), s("c")));
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TrieIterator trie = new TrieIterator(map, desc, pool);
            List<String> walked = new ArrayList<>();
            while (!trie.atEnd()) {
                walked.add(new String(trie.key(), StandardCharsets.UTF_8));
                trie.next();
            }
            assertEquals(
                    List.of("a", "b", "c"), walked, "next() yields the sorted distinct values");
        }
    }

    @Test
    void int64SuccessorPathWalksNumericKeys() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            // Int64 column: successor is value+1 re-encoded (the TermId path).
            StaticMap map = singleCol(pool, Encoding.Int64, List.of(le8(1), le8(2), le8(5)));
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));
            TrieIterator trie = new TrieIterator(map, desc, pool);
            List<Long> walked = new ArrayList<>();
            while (!trie.atEnd()) {
                byte[] k = trie.key();
                walked.add(
                        MemorySegment.ofArray(k)
                                .get(
                                        ValueLayout.JAVA_LONG_UNALIGNED.withOrder(
                                                ByteOrder.LITTLE_ENDIAN),
                                        0));
                trie.next();
            }
            assertEquals(
                    List.of(1L, 2L, 5L),
                    walked,
                    "Int64 trie walk yields the numeric keys in order");
        }
    }

    @Test
    void unsupportedEncodingSuccessorThrows() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            // Float64 is outside successor's supported set (IRI/String/Bytes/Int64).
            byte[] f1 = new byte[8], f2 = new byte[8];
            MemorySegment.ofArray(f1).set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0, 1.0);
            MemorySegment.ofArray(f2).set(ValueLayout.JAVA_DOUBLE_UNALIGNED, 0, 2.0);
            StaticMap map = singleCol(pool, Encoding.Float64, List.of(f1, f2));
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.Float64, false)));
            TrieIterator trie = new TrieIterator(map, desc, pool);
            assertFalse(trie.atEnd(), "the Float64 trie has a first value");
            assertThrows(
                    UnsupportedOperationException.class,
                    trie::next,
                    "successor is undefined for Float64 — next() must reject it");
        }
    }

    @Test
    void levelIteratorPrevAndValueAreStubs() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap map = singleCol(pool, Encoding.String, List.of(s("a"), s("b")));
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TrieIterator trie = new TrieIterator(map, desc, pool);
            MapIterator level = trie.levelIterator();
            assertTrue(
                    level.next(),
                    "the level iterator starts before the first value, then advances onto it");
            assertFalse(
                    level.prev(), "the level iterator is forward-only — prev() is always false");
            assertSame(
                    MemorySegment.NULL,
                    level.value(),
                    "a key-level iterator materializes no value");
        }
    }

    @Test
    void openOnExhaustedTrie_throws() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap empty = singleCol(pool, Encoding.String, List.of());
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TrieIterator trie = new TrieIterator(empty, desc, pool);
            assertTrue(trie.atEnd(), "an empty trie is at end immediately");
            assertThrows(
                    IllegalStateException.class,
                    trie::open,
                    "open() with no current key is a contract violation");
        }
    }

    @Test
    void upAtRoot_throws() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap map = singleCol(pool, Encoding.String, List.of(s("a")));
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TrieIterator trie = new TrieIterator(map, desc, pool);
            assertThrows(
                    IllegalStateException.class, trie::up, "up() at the root has nothing to pop");
        }
    }

    @Test
    void nextAndSeekOnEmptyTrie_areAtEnd() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap empty = singleCol(pool, Encoding.String, List.of());
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TrieIterator trie = new TrieIterator(empty, desc, pool);
            assertFalse(trie.next(), "next() on an empty trie yields nothing");
            trie.seek(s("z"));
            assertTrue(trie.atEnd(), "seek() on an empty trie stays at end");
        }
    }

    @Test
    void seekPastAllValues_landsAtEnd() {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            StaticMap map = singleCol(pool, Encoding.String, List.of(s("a"), s("b")));
            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TrieIterator trie = new TrieIterator(map, desc, pool);
            trie.seek(s("z")); // beyond the column maximum
            assertTrue(trie.atEnd(), "seeking past the last value lands at end");
        }
    }
}
