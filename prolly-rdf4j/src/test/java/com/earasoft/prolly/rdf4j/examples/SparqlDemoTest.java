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
 * Locks {@link SparqlDemo} into CI — the demo exercises the SPARQL query/update surface (INSERT
 * DATA, FILTER, GROUP BY, CONSTRUCT, ASK, DELETE/INSERT WHERE), so it doubles as a smoke test for
 * that surface.
 */
class SparqlDemoTest {

    @Test
    void demoExercisesTheSparqlSurface(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        SparqlDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [2] FILTER — before the birthday update, alice is 34 and carol 41.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("alice") && l.contains("age 34")),
                () -> "pre-update: alice should be 34:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("carol") && l.contains("age 41")),
                () -> "pre-update: carol should be 41:\n" + output);

        // [3] GROUP BY — both cities have 2 people.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("Paris") && l.contains("2 people")),
                () -> "Paris should have 2 people:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("Lyon") && l.contains("2 people")),
                () -> "Lyon should have 2 people:\n" + output);

        // [4] CONSTRUCT — all four people are typed as Adult.
        assertEquals(
                4,
                output.lines().filter(l -> l.contains(" a Adult")).count(),
                () -> "CONSTRUCT should type all 4 people as Adult:\n" + output);

        // [5] ASK — someone lives in Paris.
        assertTrue(
                output.contains("live in Paris? → true"),
                () -> "ASK for a Paris resident should be true:\n" + output);

        // [6] UPDATE — after the birthday, alice is 35 and carol 42.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("alice") && l.contains("age 35")),
                () -> "post-update: alice should be 35:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("carol") && l.contains("age 42")),
                () -> "post-update: carol should be 42:\n" + output);

        assertTrue(
                output.contains("all via SPARQL"), () -> "expected the final banner:\n" + output);
    }
}
