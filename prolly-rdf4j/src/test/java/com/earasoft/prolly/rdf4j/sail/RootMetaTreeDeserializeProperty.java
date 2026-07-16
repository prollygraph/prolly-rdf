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
package com.earasoft.prolly.rdf4j.sail;

import com.dolthub.prolly.UnsupportedFormatException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/**
 * Deserializer robustness for {@link RootMetaTree#deserialize} over arbitrary bytes
 * (untrusted-input-boundary-hardening Step 2). A content-addressed sidecar is still a trust
 * boundary: a malformed or hostile buffer must be rejected with a <b>controlled</b> exception and
 * must never out-of-memory, throw {@code NegativeArraySizeException}, or fail in any uncontrolled
 * way.
 *
 * <p>The controlled rejections are {@code UnsupportedFormatException} (the ADR-0067 magic/version
 * header check, which most random byte arrays trip), {@code IllegalArgumentException} (the Step-2
 * count bounds — a truncated count field, or a count outside what the buffer can hold), and {@code
 * IndexOutOfBoundsException} (a per-entry length that runs past the segment, caught by the {@link
 * java.lang.foreign.MemorySegment} bounds check). Anything else escaping this test is a hardening
 * regression.
 */
class RootMetaTreeDeserializeProperty {

    @Property(tries = 2000)
    void deserialize_rejects_arbitrary_bytes_with_a_controlled_exception(@ForAll byte[] bytes) {
        try {
            RootMetaTree mt = RootMetaTree.deserialize(bytes);
            // If it parsed, it must be a usable object — exercise its surface.
            mt.entries();
            mt.isEmpty();
        } catch (UnsupportedFormatException
                | IllegalArgumentException
                | IndexOutOfBoundsException expected) {
            // Controlled rejection of malformed bytes — correct behavior.
            // UnsupportedFormatException
            // covers the ADR-0067 magic/version header check (most random arrays fail it);
            // IllegalArgumentException / IndexOutOfBoundsException cover the post-header bounds.
        }
    }
}
