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
package com.earasoft.prolly.rdf4j.term;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.foreign.Arena;
import org.junit.jupiter.api.Test;

class DictionaryMetricsTest {

    /** Counter value for {@code name}, or 0 if the meter was never created. */
    private static double counter(SimpleMeterRegistry m, String name) {
        var c = m.find(name).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    void encode_insert_increments_insert_counter() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        Dictionary d =
                new Dictionary(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        HashFunctions.defaultHash(),
                        Dictionary.MAX_SALT,
                        name -> m.counter(name).increment());
        try (Arena a = Arena.ofConfined()) {
            d.encode(TermCodec.encodeInteger(42L, a));
            d.encode(TermCodec.encodeInteger(100L, a));
            d.encode(TermCodec.encodeInteger(200L, a));
        }
        assertEquals(3d, counter(m, "dict.encode.insert"));
        assertEquals(0d, counter(m, "dict.encode.hit"));
    }

    @Test
    void encode_dedupe_increments_hit_counter() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        Dictionary d =
                new Dictionary(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        HashFunctions.defaultHash(),
                        Dictionary.MAX_SALT,
                        name -> m.counter(name).increment());
        try (Arena a = Arena.ofConfined()) {
            d.encode(TermCodec.encodeInteger(42L, a));
            d.encode(TermCodec.encodeInteger(42L, a));
            d.encode(TermCodec.encodeInteger(42L, a));
        }
        assertEquals(1d, counter(m, "dict.encode.insert"));
        assertEquals(2d, counter(m, "dict.encode.hit"));
    }

    @Test
    void decode_hit_and_miss_counters() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        Dictionary d =
                new Dictionary(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        HashFunctions.defaultHash(),
                        Dictionary.MAX_SALT,
                        name -> m.counter(name).increment());
        try (Arena a = Arena.ofConfined()) {
            TermId id = d.encode(TermCodec.encodeInteger(42L, a));
            // hit
            d.decode(id);
            d.decode(id);
            // miss
            d.decode(TermId.of(0xDEAD_BEEFL));
            d.decode(TermId.of(0xCAFE_BABEL));
        }
        assertEquals(2d, counter(m, "dict.decode.hit"));
        assertEquals(2d, counter(m, "dict.decode.miss"));
    }

    @Test
    void noop_metrics_no_recording_when_no_metrics_passed() {
        Dictionary d =
                new Dictionary(
                        new InMemoryNodeStore(), new HeapBufferPool(), HashFunctions.defaultHash());
        try (Arena a = Arena.ofConfined()) {
            d.encode(TermCodec.encodeInteger(42L, a));
        }
        // No explosion — noop metrics is just no-op
        assertTrue(d.hashFunction().name().length() > 0);
    }
}
