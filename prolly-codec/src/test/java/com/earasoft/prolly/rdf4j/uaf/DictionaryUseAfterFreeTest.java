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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.dolthub.prolly.ArenaScopeProbe;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the dictionary encode path (plans/off-heap-use-after-free-tests.md
 * Phase 2 Step 10, folding in {@code TermCodec}/{@code TermEncoder}). {@link Dictionary} stages
 * encoded terms in a per-transaction {@code MutableMap} buffer over the pool (H2/H4). Pins: (1) the
 * encode→decode content is byte-identical through the poison pool and the heap pool — the buffer
 * build reads no freed memory; (2) encode <b>copies</b> the source term, so a decode survives the
 * {@code TermCodec} source arena being closed (D-4 — the encode decouples from the caller's
 * memory).
 */
class DictionaryUseAfterFreeTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;

    private static byte[] longBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    @Test
    void encodeDecodeContentIsByteIdenticalThroughPoisonAndHeapPool() {
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    Dictionary d = new Dictionary(store, pool, HashFunctions.defaultHash());
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (Arena a = Arena.ofConfined()) {
                        for (int i = 0; i < 40; i++) {
                            TermId id = d.encode(TermCodec.encodeXsdString("term-" + i, a));
                            out.writeBytes(longBytes(id.value()));
                            out.writeBytes(d.decode(id).orElseThrow().toArray(BYTE));
                        }
                    }
                    return out.toByteArray();
                });
    }

    @Test
    void encodeCopiesTheTerm_soDecodeSurvivesSourceArenaClose() {
        try (HeapBufferPool pool = new HeapBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            Dictionary d = new Dictionary(store, pool, HashFunctions.defaultHash());
            TermId id;
            byte[] expected;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment term = TermCodec.encodeXsdString("hello-world", a);
                expected = term.toArray(BYTE);
                id = d.encode(term);
            } // the source arena is now closed

            byte[] back = d.decode(id).orElseThrow().toArray(BYTE);
            assertArrayEquals(
                    expected,
                    back,
                    "encode must copy the term — decode must survive the source arena being closed");
        }
    }
}
