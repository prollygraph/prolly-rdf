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

import java.lang.foreign.MemorySegment;

/**
 * FNV-1a-64 hash function — well-defined placeholder for Phase 1 plumbing.
 *
 * <p>The algorithm:
 *
 * <pre>
 *   h := 0xcbf29ce484222325  (offset basis)
 *   for each byte b:
 *     h := h XOR b
 *     h := h * 0x100000001b3  (FNV prime)
 * </pre>
 *
 * <p>FNV-1a is non-cryptographic and has known clustering for sequential integer inputs. Adequate
 * for v2.0; replace with xxh3-64 (vendored ~100 LOC) when performance benchmarks demand it.
 */
final class Fnv1a64 implements HashFunction {
    static final Fnv1a64 INSTANCE = new Fnv1a64();

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    @Override
    public long hash(MemorySegment data) {
        long h = FNV_OFFSET_BASIS;
        long n = data.byteSize();
        for (long i = 0; i < n; i++) {
            h ^= (data.get(Layouts.BYTE, i) & 0xffL);
            h *= FNV_PRIME;
        }
        return h;
    }

    @Override
    public String name() {
        return "fnv1a-64";
    }
}
