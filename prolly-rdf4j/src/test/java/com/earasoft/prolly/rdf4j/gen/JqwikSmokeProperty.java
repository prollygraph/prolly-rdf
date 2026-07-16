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
package com.earasoft.prolly.rdf4j.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Phase 0 Step 1 of {@code prolly-rdf4j-test-strategy.md} — smoke-confirms <b>jqwik</b> runs in
 * this module under JUnit Platform 6 with the {@code --enable-preview} / {@code
 * --enable-native-access=ALL-UNNAMED} surefire argLine (the same JVM config the RocksDB-backed Sail
 * tests need). If jqwik or the platform wiring were broken, this would fail to discover/execute
 * before any of the real property suites (D-3 generators, the S-2 differential oracle) land.
 */
class JqwikSmokeProperty {

    @Property(tries = 200)
    void intsRoundTripThroughDecimalString(@ForAll int n) {
        assertEquals(n, Integer.parseInt(Integer.toString(n)));
    }

    @Property(tries = 200)
    void absoluteValueIsNonNegativeForNonMinInt(
            @ForAll @IntRange(min = -1_000_000, max = 1_000_000) int n) {
        assertTrue(Math.abs(n) >= 0);
    }
}
