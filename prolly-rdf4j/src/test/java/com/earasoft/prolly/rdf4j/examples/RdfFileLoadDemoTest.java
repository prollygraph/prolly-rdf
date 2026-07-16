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
 * Locks {@link RdfFileLoadDemo} into CI — it parses and loads a Turtle file and an N-Triples file
 * (into a named graph) through RDF4J's {@code add()}.
 */
class RdfFileLoadDemoTest {

    @Test
    void demoLoadsTurtleAndNTriplesFiles(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        RdfFileLoadDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        // [4] all four people loaded — three from Turtle, one from N-Triples.
        assertTrue(output.contains("Alice, age 34"), () -> output);
        assertTrue(output.contains("Bob, age 29"), () -> output);
        assertTrue(output.contains("Carol, age 41"), () -> output);
        assertTrue(
                output.contains("Dave, age 27"),
                () -> "Dave (from the N-Triples file) should be loaded:\n" + output);

        // [5] the 'knows' edges parsed out of the Turtle file.
        assertTrue(output.contains("alice knows bob"), () -> output);
        assertTrue(output.contains("carol knows alice"), () -> output);

        // [6] per-graph counts — 8 default-graph triples, 2 in the named graph.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("default graph") && l.contains("8")),
                () -> "people.ttl should load 8 triples into the default graph:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("urn:g:external") && l.contains("2")),
                () -> "external.nt should load 2 triples into the named graph:\n" + output);

        assertTrue(
                output.contains("parse and load straight through"),
                () -> "expected the final banner:\n" + output);
    }
}
