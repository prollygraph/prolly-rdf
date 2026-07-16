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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the {@link GettingStartedDemo} into CI. Without this, the demo's README-style narrative
 * would silently rot the moment the API shifts.
 */
class GettingStartedDemoTest {

    @Test
    void demoRunsAndShowsPersistenceAcrossReopen(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        GettingStartedDemo.run(
                tmp, new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8));
        String output = buf.toString(java.nio.charset.StandardCharsets.UTF_8);

        // Phase 1: ingest log line
        assertTrue(
                output.contains("Opening Sail and ingesting"),
                () -> "expected ingest banner; got:\n" + output);

        // Phase 1 query: at least one friendship line containing 'knows'
        assertTrue(
                output.contains("knows"),
                () -> "expected friendship rows mentioning 'knows'; got:\n" + output);

        // Phase 2: reopen + post-restart query both ran
        assertTrue(
                output.contains("Re-opening the Sail"),
                () -> "expected re-open banner; got:\n" + output);
        assertTrue(
                output.contains("triples"),
                () -> "expected triple count rows after reopen; got:\n" + output);

        // Final success banner
        assertTrue(
                output.contains("survived a full close+reopen cycle"),
                () -> "expected final success line; got:\n" + output);

        // And the post-restart query must have found 2 subjects (alice, bob), proving auto-restore.
        long subjectsAfterReopen =
                output.lines()
                        .filter(l -> l.contains("urn:demo:") && l.contains("triples"))
                        .count();
        assertEquals(
                2,
                subjectsAfterReopen,
                () -> "expected exactly 2 subject rows after reopen; got:\n" + output);
    }
}
