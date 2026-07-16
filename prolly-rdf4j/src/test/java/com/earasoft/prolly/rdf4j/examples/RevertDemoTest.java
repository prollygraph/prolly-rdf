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
package com.earasoft.prolly.rdf4j.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks {@link RevertDemo} into CI — it reverts by re-pointing the manifest and proves the rollback
 * is reversible (no commit's data is destroyed).
 */
class RevertDemoTest {

    @Test
    void demoRevertsAndRollsForward(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        RevertDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [4] HEAD after three commits holds all three members.
        assertTrue(
                output.contains("members: [Alice, Bob, Carol]"),
                () -> "HEAD should hold all three members:\n" + output);

        // [5] revert to commit 1 — only Alice remains visible.
        assertTrue(
                output.contains("members after revert : [Alice]"),
                () -> "after reverting to commit 1, only Alice should be visible:\n" + output);

        // [6] roll forward to commit 3 — Bob and Carol return untouched.
        assertTrue(
                output.contains("members after roll-forward : [Alice, Bob, Carol]"),
                () -> "roll-forward must restore all three — nothing was destroyed:\n" + output);

        assertTrue(
                output.contains("every commit's data survives"),
                () -> "expected the final banner:\n" + output);
    }
}
