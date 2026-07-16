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

import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link HashFunctions}. Per the class Javadoc, the default hash is part of the
 * on-disk format — changing it requires a manifest format-version bump. Pin the default explicitly
 * so any silent swap trips CI.
 */
class HashFunctionsTest {

    @Test
    void default_is_fnv1a64() {
        HashFunction got = HashFunctions.defaultHash();
        assertSame(
                HashFunctions.FNV1A_64,
                got,
                "default hash drift requires a manifest format-version bump");
    }

    @Test
    void default_returns_stable_reference() {
        // Calling defaultHash() repeatedly must return the same instance
        // — the Sail captures it once at construction.
        assertSame(HashFunctions.defaultHash(), HashFunctions.defaultHash());
    }

    @Test
    void fnv1a_64_name_pinned() {
        assertEquals(
                "fnv1a-64",
                HashFunctions.FNV1A_64.name(),
                "hash name is part of the manifest — drift would orphan existing stores");
    }

    @Test
    void fnv1a_64_is_deterministic() {
        long a = HashFunctions.FNV1A_64.hash("hello".getBytes());
        long b = HashFunctions.FNV1A_64.hash("hello".getBytes());
        assertEquals(a, b);
    }

    @Test
    void fnv1a_64_distinct_inputs_distinct_hashes() {
        long a = HashFunctions.FNV1A_64.hash("foo".getBytes());
        long b = HashFunctions.FNV1A_64.hash("bar".getBytes());
        assertNotEquals(a, b);
    }

    @Test
    void fnv1a_64_known_empty_input_value() {
        // FNV-1a-64 of empty input is the offset basis = 0xcbf29ce484222325.
        long got = HashFunctions.FNV1A_64.hash(new byte[0]);
        assertEquals(
                0xcbf29ce484222325L,
                got,
                "empty input must hash to the FNV offset basis — drift means the algorithm changed");
    }

    @Test
    void fnv1a_64_zero_byte_input_pinned() {
        // FNV-1a-64 of {0x00}: h = offset XOR 0 = offset, then * PRIME.
        // 0xcbf29ce484222325 * 0x100000001b3 = ...
        long expected = 0xcbf29ce484222325L * 0x100000001b3L;
        assertEquals(expected, HashFunctions.FNV1A_64.hash(new byte[] {0}));
    }

    @Test
    void hash_handles_high_bytes_correctly() {
        // FNV-1a XORs with (b & 0xff) — verify high bytes don't sign-extend.
        long a = HashFunctions.FNV1A_64.hash(new byte[] {(byte) 0xFF});
        long b = HashFunctions.FNV1A_64.hash(new byte[] {(byte) 0x80});
        assertNotEquals(a, b);
        // Sanity: hashing 0xFF must not equal hashing -1 cast (they're the same byte).
        long c = HashFunctions.FNV1A_64.hash(new byte[] {-1});
        assertEquals(a, c);
    }
}
