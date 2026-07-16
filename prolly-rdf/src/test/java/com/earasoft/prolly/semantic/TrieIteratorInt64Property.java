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

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.dolthub.prolly.TypeCodec;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Phase 1 Step 4 of {@code plans/unify-rdf-encoding-on-term-codec.md} — pins the <b>fixed-width
 * successor-seek</b> (ADR-0036 D-5). The {@link TrieIterator}'s subtree-skip historically appended
 * a {@code 0x00} byte; for an {@code Int64} column (e.g. dictionary-encoded {@code TermId}) the
 * comparator reads exactly 8 little-endian bytes ({@code readInt64}) and ignores the trailing byte,
 * so append-0x00 would never advance — an infinite loop / missed values. The fix is numeric {@code
 * value+1}. This walks a depth-2 {@code Int64}/{@code Int64} trie and asserts it enumerates exactly
 * the stored pairs (the existing {@code TrieIteratorProperty} covers the variable-length IRI
 * column).
 */
class TrieIteratorInt64Property {

    private static final TupleDescriptor I64x2 =
            new TupleDescriptor(
                    List.of(new Type(Encoding.Int64, false), new Type(Encoding.Int64, false)));

    @Property(tries = 100)
    void int64TrieEnumeratesExactlyTheStoredPairs(@ForAll @From("pairs") Set<List<Long>> pairs) {
        try (DirectBufferPool pool = new DirectBufferPool()) {
            InMemoryNodeStore store = new InMemoryNodeStore();
            MutableMap mm = new MutableMap(new StaticMap(store, null, I64x2), store, I64x2, pool);
            for (List<Long> p : pairs) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, le(p.get(0)));
                tb.putField(1, le(p.get(1)));
                mm.put(tb.build().segment(), MemorySegment.NULL);
            }
            StaticMap index = mm.flush();

            // Depth-first walk over the two Int64 levels via open/next/up.
            Set<List<Long>> got = new HashSet<>();
            TrieIterator t = new TrieIterator(index, I64x2, pool);
            while (!t.atEnd()) {
                long c0 = readLong(t.key());
                t.open();
                while (!t.atEnd()) {
                    got.add(List.of(c0, readLong(t.key())));
                    t.next(); // Int64 successor: numeric +1
                }
                t.up();
                t.next(); // Int64 successor at level 0
            }
            assertEquals(pairs, got, "Int64 trie must enumerate exactly the stored (c0,c1) pairs");
        }
    }

    @Provide
    Arbitrary<Set<List<Long>>> pairs() {
        // Small signed range incl. negatives + boundaries, so values repeat across
        // level 0 (multiple c1 per c0) and the signed Long.compare order is exercised.
        Arbitrary<Long> v = Arbitraries.longs().between(-8, 8);
        return Combinators.combine(v, v).as(List::of).set().ofMinSize(1).ofMaxSize(20);
    }

    private static byte[] le(long x) {
        byte[] b = new byte[8];
        MemorySegment.ofArray(b)
                .set(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0, x);
        return b;
    }

    private static long readLong(byte[] b) {
        return TypeCodec.readInt64(MemorySegment.ofArray(b));
    }
}
