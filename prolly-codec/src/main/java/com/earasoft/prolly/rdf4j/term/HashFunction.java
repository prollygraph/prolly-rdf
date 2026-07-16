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
 * 64-bit hash function for content-addressing terms in the dictionary.
 *
 * <p>The high bit of a TermID is reserved as a collision-extension flag, so users of this interface
 * MAY mask {@code result & 0x7FFFFFFFFFFFFFFFL} before treating the value as a natural TermID.
 *
 * <p>Implementations should be deterministic across JVMs and architectures: the same byte sequence
 * must produce the same hash whether the caller is on x86 or ARM, on JDK 21 or 25, in this JVM or
 * the next.
 */
public interface HashFunction {

    /** Hash a byte segment. */
    long hash(MemorySegment data);

    /** Hash a byte array. Equivalent to wrapping in a segment. */
    default long hash(byte[] data) {
        return hash(MemorySegment.ofArray(data));
    }

    /** Hash a slice of a byte array. */
    default long hash(byte[] data, int offset, int length) {
        return hash(MemorySegment.ofArray(data).asSlice(offset, length));
    }

    /**
     * Stable, format-version-tied name (e.g., "xxh3-64", "fnv1a-64", "blake3-64").
     *
     * <p>The chosen hash function is part of the on-disk format; readers must verify the name in
     * the manifest against their expectations. Changing the default requires a format-version bump.
     */
    String name();
}
