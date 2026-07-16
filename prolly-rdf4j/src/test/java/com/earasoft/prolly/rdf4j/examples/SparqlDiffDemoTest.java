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
 * Locks {@link SparqlDiffDemo} into CI — it diffs commits by set-differencing the SPARQL results of
 * their snapshots.
 */
class SparqlDiffDemoTest {

    @Test
    void demoDiffsCommitsBySnapshot(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        SparqlDiffDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        String c1c2 = section(output, "[4] Diff", "[5] Diff");
        String c2c3 = section(output, "[5] Diff", "[6] Diff");
        String c1c3 = section(output, "[6] Diff", "=== Done");

        // c1 → c2: Alice promoted (Engineer out, Lead in), Carol hired.
        assertTrue(c1c2.contains("+ alice role \"Lead\""), () -> "c1→c2:\n" + output);
        assertTrue(c1c2.contains("+ carol role \"Designer\""), () -> "c1→c2:\n" + output);
        assertTrue(c1c2.contains("- alice role \"Engineer\""), () -> "c1→c2:\n" + output);
        assertFalse(c1c2.contains("bob"), () -> "Bob is untouched by c1→c2:\n" + output);

        // c2 → c3: Bob departs — one removal, nothing added.
        assertTrue(c2c3.contains("- bob role \"Designer\""), () -> "c2→c3:\n" + output);
        assertFalse(c2c3.contains("+ "), () -> "c2→c3 should add nothing:\n" + output);

        // c1 → c3 cumulative: both removals and both additions.
        assertTrue(c1c3.contains("+ alice role \"Lead\""), () -> "c1→c3:\n" + output);
        assertTrue(c1c3.contains("+ carol role \"Designer\""), () -> "c1→c3:\n" + output);
        assertTrue(c1c3.contains("- alice role \"Engineer\""), () -> "c1→c3:\n" + output);
        assertTrue(c1c3.contains("- bob role \"Designer\""), () -> "c1→c3:\n" + output);

        assertTrue(
                output.contains("set-differencing their snapshots"),
                () -> "expected the final banner:\n" + output);
    }

    private static String section(String output, String from, String to) {
        int a = output.indexOf(from);
        int b = output.indexOf(to);
        assertTrue(a >= 0 && b > a, () -> "missing section " + from + ".." + to + ":\n" + output);
        return output.substring(a, b);
    }
}
