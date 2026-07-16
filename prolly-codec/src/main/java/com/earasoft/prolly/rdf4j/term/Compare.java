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
 * Canonical unsigned-byte comparator for {@link MemorySegment} ranges.
 *
 * <p>{@link MemorySegment#mismatch(MemorySegment)} returns the offset of the first differing byte
 * (or {@code -1} if equal). That is <em>not</em> an ordering — to compare for less-than, one must
 * read the differing byte and compare unsigned, then handle the prefix case where one segment is
 * shorter than the other.
 *
 * <p>This class is the only sanctioned compare for index keys and encoded terms. Never call {@code
 * Arrays.compare} (signed) directly, and never use {@code mismatch} alone in compare contexts.
 */
public final class Compare {
    private Compare() {}

    /**
     * Compare two segments byte-for-byte, treating each byte as unsigned.
     *
     * @return negative if {@code a < b}, zero if equal, positive if {@code a > b}
     */
    public static int compareUnsigned(MemorySegment a, MemorySegment b) {
        long m = a.mismatch(b);
        if (m < 0) return 0;
        long aSize = a.byteSize();
        long bSize = b.byteSize();
        if (m >= aSize) return -1; // a is a strict prefix of b
        if (m >= bSize) return 1; // b is a strict prefix of a
        return Byte.compareUnsigned(a.get(Layouts.BYTE, m), b.get(Layouts.BYTE, m));
    }
}
