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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.InMemoryNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Durability / crash-safety tests for {@link CommitLog} — the append-only {@code commits.log} that
 * backs {@code /sparql/timemap} and {@code findByHash}.
 *
 * <p>{@code CommitLogTest} covers the clean append/read path. This file exercises the file states a
 * process crash mid-append can leave behind: stray blank lines, and a torn (partial) trailing line.
 */
class CommitLogCrashSafetyTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        return h;
    }

    @Test
    void entries_skips_blank_and_whitespace_lines(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T10:00:00Z"), hash(1));
        // A crash that flushed only newlines, or a manual edit, can leave
        // blank/whitespace-only lines between real entries.
        Files.writeString(dir.resolve("commits.log"), "\n   \n\t\n", StandardOpenOption.APPEND);
        log.append(Instant.parse("2026-05-12T11:00:00Z"), hash(2));

        assertEquals(
                2,
                log.entries().size(),
                "blank/whitespace lines are skipped; the real entries survive");
    }

    @Test
    void a_torn_trailing_line_is_dropped_recovering_the_durable_prefix(@TempDir Path dir)
            throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T10:00:00Z"), hash(1));
        log.append(Instant.parse("2026-05-12T11:00:00Z"), hash(2));
        // Simulate a crash mid-append: a partial, non-blank trailing line.
        Files.writeString(
                dir.resolve("commits.log"), "Tue, 12 May 2026 12:0", StandardOpenOption.APPEND);

        // The torn final line is an interrupted append — entries() drops it
        // and recovers the durable prefix rather than bricking the whole log.
        assertEquals(
                2,
                log.entries().size(),
                "a torn trailing line is dropped; the committed entries before it survive");
    }

    @Test
    void a_malformed_non_trailing_line_still_fails_loudly(@TempDir Path dir) throws Exception {
        // A bad line that is NOT the last is genuine mid-file corruption, not a
        // torn append — it must still surface loudly rather than silently lose data.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T10:00:00Z"), hash(1));
        Files.writeString(
                dir.resolve("commits.log"),
                "garbage-not-a-commit-line\nTue, 12 May 2026 12:00:00 GMT "
                        + "0102030405060708090a0b0c0d0e0f1011121314\n",
                StandardOpenOption.APPEND);

        assertThrows(
                IllegalStateException.class,
                log::entries,
                "a malformed line with valid entries after it is real corruption, not a torn tail");
    }

    @Test
    void an_absent_log_reads_as_empty(@TempDir Path dir) throws Exception {
        // A crash before the first commit-log write leaves no file at all.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        assertTrue(
                log.entries().isEmpty(), "a missing commit log is an empty history, not an error");
        assertTrue(log.latest().isEmpty());
    }
}
