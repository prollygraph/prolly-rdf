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
 * Locks {@link SquashMergeDemo} into CI — it exercises {@code squashMerge}: collapsing a branch's
 * net diff into one single-parent commit.
 */
class SquashMergeDemoTest {

    @Test
    void demoSquashMergesABranch(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        SquashMergeDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [5] the branch's net diff: +Bob, +Carol, +Alice/Lead = 3 added; -Alice/Engineer = 1
        // removed.
        assertTrue(
                output.contains("added 3 triple(s), removed 1 triple(s)"),
                () -> "expected the squash net diff (3 added, 1 removed):\n" + output);

        // [6] post-squash main holds the union with Alice promoted.
        int afterIdx = output.indexOf("[6] After squash");
        assertTrue(afterIdx >= 0, () -> output);
        String afterRoster = output.substring(afterIdx);
        assertTrue(afterRoster.contains("urn:team:bob"), () -> "Bob merged in:\n" + output);
        assertTrue(afterRoster.contains("urn:team:carol"), () -> "Carol merged in:\n" + output);
        assertTrue(
                afterRoster.contains("urn:team:dave"), () -> "Dave (main's own) kept:\n" + output);
        assertTrue(
                afterRoster.contains("Lead"),
                () -> "Alice's feature-branch promotion should land:\n" + output);
        assertFalse(
                afterRoster.contains("Engineer"),
                () -> "Alice's old role should be squashed out:\n" + output);

        // [7] the squash is a single-parent commit — linear history.
        assertTrue(
                output.contains("ONE commit with 1 parent"),
                () -> "the squash commit must have exactly 1 parent:\n" + output);

        assertTrue(
                output.contains("collapses a branch's work into one commit"),
                () -> "expected the final banner:\n" + output);
    }
}
