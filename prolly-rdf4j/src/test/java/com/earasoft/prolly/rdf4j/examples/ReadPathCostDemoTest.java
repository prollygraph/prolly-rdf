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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Locks {@link ReadPathCostDemo} into CI. Asserts the probe's <b>structure</b> — the three sections
 * and that each emits its cost line — not the machine-specific numbers (absolute milliseconds and
 * the off-vs-on ratios vary by host; only the shape is invariant). The numeric payoff is when it
 * runs standalone via {@code exec:java} on a known machine.
 */
class ReadPathCostDemoTest {

    @Test
    void demoRunsAndReportsAllThreeSections() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ReadPathCostDemo.run(new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // Section 1 — the fixed/marginal decomposition.
        assertTrue(
                output.contains("[1] Fixed per-query floor")
                        && output.contains("fixed per-query floor ~"),
                () -> "expected the fixed-vs-marginal section:\n" + output);
        assertTrue(
                output.contains("marginal ~") && output.contains("ns/row"),
                () -> "expected the per-row marginal read-out:\n" + output);

        // Sections 2 + 3 — the paired decode-cache controls (tax regime + win regime).
        assertTrue(
                output.contains("[2] Decode cache") && output.contains("[3] Decode cache"),
                () -> "expected both decode-cache regime sections:\n" + output);
        long cacheLines = output.lines().filter(l -> l.contains("cache OFF:")).count();
        assertTrue(
                cacheLines == 2,
                () -> "expected a paired off/on control in each of the two regimes:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("time ") && l.contains("alloc ")),
                () -> "expected the off-vs-on ratio line:\n" + output);

        assertTrue(
                output.contains("ships off by default"),
                () -> "expected the closing takeaway:\n" + output);
    }
}
