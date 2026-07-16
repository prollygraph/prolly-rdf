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

/**
 * Built-in {@link HashFunction} implementations.
 *
 * <p>v2.0 ships with:
 *
 * <ul>
 *   <li>{@link #FNV1A_64} — well-defined placeholder, adequate for testing the dictionary plumbing.
 *       Replace with xxh3 for production once vendored.
 * </ul>
 *
 * <p>Adversarial-input deployments should swap in BLAKE3-64 (a future implementation will be wired
 * through a JNI binding).
 */
public final class HashFunctions {
    private HashFunctions() {}

    /** FNV-1a-64 — well-defined, simple, adequate for v2.0 dictionary plumbing. */
    public static final HashFunction FNV1A_64 = Fnv1a64.INSTANCE;

    /**
     * Default hash function for new Sails. Currently {@link #FNV1A_64}.
     *
     * <p>This is part of the on-disk format; changing the default requires a manifest
     * format-version bump.
     */
    public static HashFunction defaultHash() {
        return FNV1A_64;
    }
}
