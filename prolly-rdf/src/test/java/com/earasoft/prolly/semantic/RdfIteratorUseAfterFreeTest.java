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
import com.earasoft.prolly.pool.DirectBufferPool;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the rdf query iterators (plans/off-heap-use-after-free-tests.md Phase
 * 3 Step 11).
 *
 * <p><b>Finding (why this isn't a poison-pool differential):</b> {@link TrieIterator}, {@code
 * LeapfrogTriejoin}, {@code ProjectingIterator}, and {@code GraphPatternEngine} are all coupled to
 * the <b>concrete {@link DirectBufferPool}</b>, not the {@code BufferPool} interface — so the
 * poison harness (a separate {@code BufferPool} impl) cannot drive them, and the query layer is
 * hardcoded off-heap regardless of the Sail's pool. Their UAF-safety therefore rests on (a) the
 * heap-backed store reads already pinned at the Node/Cursor level (Steps 5–6, inherited) for the
 * index data, and (b) their <b>output boundary returning {@code byte[]} copies</b>, which this test
 * pins: a scan's results survive the pool's arena being closed, because {@link TrieIterator#key()}
 * hands back a heap array, not a view into the pool. (Follow-on worth filing: refactor these to the
 * {@code BufferPool} interface so they can be poison-tested AND so the query path isn't forced
 * off-heap.)
 */
class RdfIteratorUseAfterFreeTest {

    private static final TupleDescriptor STRING_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));

    @Test
    void trieIteratorResultsAreHeapCopies_validAfterTheDirectBufferPoolCloses() {
        List<String> keys = new ArrayList<>();
        int n = 40;

        try (DirectBufferPool pool = new DirectBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            MutableMap mm =
                    new MutableMap(
                            new StaticMap(store, null, STRING_DESC), store, STRING_DESC, pool);
            for (int i = 0; i < n; i++) {
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, String.format("k%04d", i).getBytes(StandardCharsets.UTF_8));
                mm.put(
                        tb.build().segment(),
                        MemorySegment.ofArray(("v" + i).getBytes(StandardCharsets.UTF_8)));
            }
            TrieIterator trie = new TrieIterator(mm.flush(), STRING_DESC, pool);
            while (!trie.atEnd()) {
                keys.add(
                        new String(
                                trie.key(), StandardCharsets.UTF_8)); // key() returns a byte[] copy
                trie.next();
            }
        } // DirectBufferPool closed — its off-heap arena is freed here

        // The collected keys are heap byte[]/String copies, so they remain correct after the pool's
        // arena is gone — the iterator's output does not alias the off-heap scratch it used
        // internally.
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            expected.add(String.format("k%04d", i));
        }
        assertEquals(
                expected, keys, "TrieIterator results must be heap copies, valid after pool close");
    }
}
