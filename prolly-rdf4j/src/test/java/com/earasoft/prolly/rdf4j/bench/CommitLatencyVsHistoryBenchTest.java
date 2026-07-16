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
package com.earasoft.prolly.rdf4j.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks {@link CommitLatencyVsHistoryBench} into CI. Asserts the bench's <b>structure</b> — the two
 * CSVs with their pinned headers, one window row per window, and the no-op-guard contract check
 * (commit-log entries == N) — not the machine-specific latencies. The numeric payoff is a real run
 * via {@code exec:java} (see the plan's Phase 1); this just keeps the harness honest and runnable.
 */
class CommitLatencyVsHistoryBenchTest {

    @Test
    void emitsWindowedAndSampleCsvsAndPassesTheNoOpGuard(@TempDir Path store, @TempDir Path out)
            throws Exception {
        int n = 300;
        int windows = 10;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CommitLatencyVsHistoryBench.run(
                store, out, n, windows, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String stdout = buf.toString(StandardCharsets.UTF_8);

        // ---- the two CSVs exist with their pinned headers ----
        Path windowsCsv = out.resolve("commit-latency-windows.csv");
        Path samplesCsv = out.resolve("commit-latency-samples.csv");
        assertTrue(Files.exists(windowsCsv), () -> "windows CSV missing:\n" + stdout);
        assertTrue(Files.exists(samplesCsv), () -> "samples CSV missing:\n" + stdout);

        List<String> windowRows = Files.readAllLines(windowsCsv);
        assertEquals(
                CommitLatencyVsHistoryBench.WINDOWS_CSV_HEADER,
                windowRows.get(0),
                "windows CSV header drifted");
        // One data row per window (header + `windows` rows).
        assertEquals(
                windows + 1, windowRows.size(), () -> "expected one row per window:\n" + stdout);
        // Every data row has the same field count as the header (no ragged CSV).
        int cols = CommitLatencyVsHistoryBench.WINDOWS_CSV_HEADER.split(",").length;
        for (String row : windowRows.subList(1, windowRows.size())) {
            assertEquals(cols, row.split(",", -1).length, () -> "ragged window row: " + row);
        }

        List<String> sampleRows = Files.readAllLines(samplesCsv);
        assertEquals(
                CommitLatencyVsHistoryBench.SAMPLES_CSV_HEADER,
                sampleRows.get(0),
                "samples CSV header drifted");
        assertTrue(sampleRows.size() > 1, () -> "expected downsampled rows:\n" + stdout);

        // ---- the contract checks the bench prints ----
        assertTrue(
                stdout.contains("commit-log entries: 300 (expected 300)")
                        && stdout.contains("OK (no no-op skipped the append)"),
                () -> "no-op-guard contract should hold (each commit is distinct):\n" + stdout);
        assertTrue(
                stdout.contains("overall: mean"),
                () -> "expected the overall distribution:\n" + stdout);
        assertTrue(
                stdout.contains("tables_us") && stdout.contains("resid_us"),
                () -> "expected the attribution columns:\n" + stdout);
    }
}
