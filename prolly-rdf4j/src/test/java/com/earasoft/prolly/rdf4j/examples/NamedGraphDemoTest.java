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
 * Locks {@link NamedGraphDemo} into CI — it exercises quad (named-graph) support: per-graph writes,
 * single-graph and cross-graph SPARQL, the default-graph boundary, the {@code getStatements}
 * context API, and dropping a graph.
 */
class NamedGraphDemoTest {

    @Test
    void demoExercisesNamedGraphs(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        NamedGraphDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [4] cross-graph provenance: each source names a different employer.
        assertTrue(
                output.lines()
                        .anyMatch(
                                l ->
                                        l.contains("crm")
                                                && l.contains("urn:org:acme")
                                                && !l.contains("acme-co")),
                () -> "the crm graph should claim 'acme':\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("linkedin") && l.contains("acme-co")),
                () -> "the linkedin graph should claim 'acme-co':\n" + output);

        // [5] a plain BGP sees the union of all 4 statements.
        assertTrue(
                output.contains("4 statements visible across all graphs"),
                () -> "a plain BGP should see the union of all graphs (4 statements):\n" + output);

        // [6] getStatements context counts — note the contrast with [5]: the
        // null-context API isolates the default graph to its 1 statement.
        assertTrue(output.contains("default graph : 1"), () -> output);
        assertTrue(output.contains("crm           : 1"), () -> output);
        assertTrue(output.contains("linkedin      : 2"), () -> output);
        assertTrue(output.contains("all graphs    : 4"), () -> output);

        // [7] after dropping 'linkedin', the cross-graph re-query result names
        // only crm — linkedin's quads are gone. (Scope past the [7] header,
        // which itself mentions 'linkedin'.)
        int requeryIdx = output.indexOf("re-query — sources still naming");
        assertTrue(requeryIdx >= 0, () -> output);
        String requeryResults = output.substring(requeryIdx);
        assertTrue(
                requeryResults.contains("urn:src:crm"),
                () -> "crm should survive the drop:\n" + output);
        assertFalse(
                requeryResults.contains("urn:src:linkedin"),
                () -> "the dropped 'linkedin' graph's quads must be gone:\n" + output);

        assertTrue(
                output.contains("provenance — distinct"),
                () -> "expected the final banner:\n" + output);
    }
}
