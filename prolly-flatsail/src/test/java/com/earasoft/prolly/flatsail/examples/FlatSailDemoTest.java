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
package com.earasoft.prolly.flatsail.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/** Exercises {@link FlatSailDemo} in CI so the usage example cannot bit-rot. */
class FlatSailDemoTest {
    static {
        RocksDB.loadLibrary();
    }

    @Test
    void demo_runs_and_produces_the_expected_output(@TempDir Path dir) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FlatSailDemo.run(dir, new PrintStream(buffer, true, StandardCharsets.UTF_8));
        String output = buffer.toString(StandardCharsets.UTF_8);

        assertTrue(output.contains("loaded 4 statements"), output);
        assertTrue(output.contains("total statements: 4"), output);
        // The names query, sorted, lists all three people.
        assertTrue(
                output.contains("- Alice")
                        && output.contains("- Bob")
                        && output.contains("- Carol"),
                output);
        // The join: alice knows bob, whose name is Bob.
        assertTrue(output.contains("who does alice know"), output);
        // The GRAPH clause reads only the named graph — Carol.
        assertTrue(output.contains("names in peopleGraph"), output);
        assertTrue(output.contains("=== demo complete ==="), output);
    }
}
