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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.util.Optional;

public class IntegrityTest {
    public static void main(String[] args) {
        System.out.println("--- Prolly Tree Integrity Verification Test ---");

        NodeStore mockStore =
                new NodeStore() {
                    @Override
                    public Optional<java.lang.foreign.MemorySegment> read(byte[] hash) {
                        return Optional.of(
                                java.lang.foreign.MemorySegment.ofArray("corrupt-data".getBytes()));
                    }

                    @Override
                    public byte[] write(java.lang.foreign.MemorySegment data) {
                        return new byte[20];
                    }

                    @Override
                    public byte[] write(byte[] data) {
                        return new byte[20];
                    }
                };

        IntegrityVerifyingNodeStore verifyingStore = new IntegrityVerifyingNodeStore(mockStore);

        System.out.print("Verifying detection of corrupted read... ");
        try {
            verifyingStore.read(new byte[20]);
            // Fail via THROW, not System.exit(1): under MainMethodTests this
            // runs in the shared Surefire fork, so exit(1) would abort the
            // whole test JVM (reported as a crashed fork, not a clean failure)
            // and take every other test down with it.
            throw new AssertionError(
                    "IntegrityVerifyingNodeStore failed to detect a corrupted read");
        } catch (ProllyCorruptionException e) {
            System.out.println("Passed (Caught: " + e.getMessage() + ")");
        }
        System.out.println("--- Integrity Test PASSED ---");
    }
}
