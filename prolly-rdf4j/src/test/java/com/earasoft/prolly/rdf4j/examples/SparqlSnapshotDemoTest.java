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
 * Locks {@link SparqlSnapshotDemo} into CI — it exercises SPARQL time-travel (the same query
 * against each commit's snapshot) for both default-graph triples and named-graph quads.
 */
class SparqlSnapshotDemoTest {

    @Test
    void demoRunsSparqlAgainstEachCommitSnapshot(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        SparqlSnapshotDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [4] SUM(?price) — 30 at c1 (10+20), 32 at c2 (12+20), 62 at HEAD (+30).
        assertTrue(
                output.lines().anyMatch(l -> l.contains("commit 1") && l.contains("30")),
                () -> "total at commit 1 should be 30:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("commit 2") && l.contains("32")),
                () -> "total at commit 2 should be 32:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("HEAD") && l.contains("62")),
                () -> "total at HEAD should be 62:\n" + output);

        // [5] widget price — 10 at c1, 12 at HEAD (the SPARQL UPDATE landed).
        assertTrue(
                output.lines().anyMatch(l -> l.contains("commit 1") && l.contains("10")),
                () -> "widget price at commit 1 should be 10:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("HEAD") && l.contains("12")),
                () -> "widget price at HEAD should be 12:\n" + output);

        // [6] named-graph query on the HEAD snapshot — all three audit notes.
        assertTrue(output.contains("initial prices"), () -> output);
        assertTrue(output.contains("widget price +2"), () -> output);
        assertTrue(output.contains("added gizmo"), () -> output);

        // ...and the same named-graph query on commit 1's snapshot sees only one note.
        assertTrue(
                output.contains("[initial prices]"),
                () -> "commit 1's audit graph should hold exactly one note:\n" + output);

        assertTrue(
                output.contains("triples and quads alike"),
                () -> "expected the final banner:\n" + output);
    }
}
