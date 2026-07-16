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
 * Locks {@link BlameBisectDemo} into CI — it builds git-style blame and bisect over commit
 * snapshots; both must converge on the regression commit (#4).
 */
class BlameBisectDemoTest {

    @Test
    void demoBisectsAndBlamesToTheRegressionCommit(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BlameBisectDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [2] bisect lands on commit 4 and uses fewer probes than a linear scan.
        assertTrue(
                output.contains("regression first appears at commit 4"),
                () -> "bisect should find the regression at commit 4:\n" + output);
        assertTrue(
                output.contains("found in 3 probes"),
                () -> "binary search over 6 commits should take 3 probes:\n" + output);

        // [3] blame attributes the 'degraded' marker to commit 4.
        assertTrue(
                output.contains("introduced by commit 4"),
                () -> "blame should attribute 'degraded' to commit 4:\n" + output);

        // Both techniques converge.
        assertTrue(output.contains("the same regression, found two ways"), () -> output);

        // Sanity: the bisect probes are a strict subset of the 6 commits — it
        // must NOT have probed commit 1 or 2 (mid lands at 3, then 5, then 4).
        assertTrue(output.contains("probe commit 3"), () -> output);
        assertFalse(
                output.contains("probe commit 1"),
                () -> "bisect should not probe commit 1:\n" + output);

        assertTrue(
                output.contains("log / diff / revert compose the same way"),
                () -> "expected the final banner:\n" + output);
    }
}
