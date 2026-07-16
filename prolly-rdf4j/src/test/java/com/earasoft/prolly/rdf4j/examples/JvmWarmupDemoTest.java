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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks {@link JvmWarmupDemo} into CI. Asserts the demo's <b>structure</b>, not class counts: in a
 * shared test fork the SPARQL engine is usually already loaded by an earlier test, so the first
 * query here may load nothing new (the demo says so). The class-listing payoff is when it runs in a
 * fresh JVM via {@code exec:java}.
 */
class JvmWarmupDemoTest {

    @Test
    void demoRunsAndReportsWarmup(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        JvmWarmupDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        assertTrue(
                output.contains("warmUp()") && output.contains("jdk.ClassLoad"),
                () -> "expected the preload (JFR class-load) section:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("classes loaded")),
                () -> "expected the preload class-load count:\n" + output);
        // The post-preload query curve always runs (three warm queries), regardless of JVM state.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("query 3:") && l.contains("classes")),
                () -> "expected the post-preload query curve:\n" + output);
        assertTrue(
                output.contains("no user query pays"),
                () -> "expected the closing takeaway:\n" + output);
    }
}
