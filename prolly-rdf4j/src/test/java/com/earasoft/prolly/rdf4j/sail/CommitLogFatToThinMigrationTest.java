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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-0073 Phase 4 (Step 10) — the one-shot fat→thin {@code commits.log} migration. A store written
 * before the thin-format cut-over carries the full content on each row; the tool reconstructs each
 * commit's chunk (asserting its hash equals the row's stored id), then rewrites the log thin. After
 * migration the log reads back through the (thin-only) {@link CommitLog} reconstructing content
 * from the chunks — and a tampered row (content not hashing to its id) is refused with the log
 * untouched.
 */
class CommitLogFatToThinMigrationTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        return h;
    }

    private static String rfc(Instant t) {
        return CommitLog.RFC_1123.format(ZonedDateTime.ofInstant(t, ZoneOffset.UTC));
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** A well-formed fat row: {@code <datetime> <id> <tree> [parents] [m=..] [a=..]}. */
    private static String fatRow(
            Instant t, byte[] tree, List<byte[]> parents, String message, String author) {
        byte[] id = CommitId.of(tree, parents, author, message);
        StringBuilder sb =
                new StringBuilder(rfc(t))
                        .append(' ')
                        .append(HashUtils.toHex(id))
                        .append(' ')
                        .append(HashUtils.toHex(tree));
        for (byte[] p : parents) {
            sb.append(' ').append(HashUtils.toHex(p));
        }
        if (!message.isEmpty()) {
            sb.append(" m=").append(b64(message));
        }
        if (!author.isEmpty()) {
            sb.append(" a=").append(b64(author));
        }
        return sb.toString();
    }

    @Test
    void migrates_a_fat_log_to_thin_and_makes_commits_chunk_readable(@TempDir Path dir)
            throws Exception {
        NodeStore store = new InMemoryNodeStore();
        Path log = dir.resolve("commits.log");
        Instant t1 = Instant.parse("2026-05-12T23:14:48Z");
        Instant t2 = Instant.parse("2026-05-13T08:30:00Z");
        byte[] id1 = CommitId.of(hash(0x01), List.of(), "alice", "genesis");
        Files.writeString(
                log,
                fatRow(t1, hash(0x01), List.of(), "genesis", "alice")
                        + "\n"
                        + fatRow(t2, hash(0x02), List.of(id1), "second", "bob")
                        + "\n");

        int migrated = CommitLogFatToThinMigration.migrate(log, store);
        assertEquals(2, migrated, "both fat rows migrate");

        // The log is now thin: every row is 6 datetime tokens + the commit id.
        for (String line : Files.readAllLines(log)) {
            if (line.isBlank()) {
                continue;
            }
            assertEquals(7, line.trim().split("\\s+").length, "thin row: 6 datetime tokens + id");
        }

        // ...and reads back through the thin CommitLog, reconstructing content from the chunks.
        CommitLog thin = CommitLog.beside(dir, store);
        List<CommitLog.Entry> entries = thin.entries();
        assertEquals(2, entries.size());
        assertArrayEquals(hash(0x01), entries.get(0).metaTreeHash());
        assertEquals("alice", entries.get(0).author());
        assertEquals("genesis", entries.get(0).message());
        assertEquals(t2, entries.get(1).timestamp());
        assertEquals(1, entries.get(1).parents().size());
        assertArrayEquals(
                id1, entries.get(1).parents().get(0), "the parent link survives migration");
    }

    @Test
    void refuses_a_tampered_row_and_leaves_the_log_untouched(@TempDir Path dir) throws Exception {
        NodeStore store = new InMemoryNodeStore();
        Path log = dir.resolve("commits.log");
        // A fat row whose stored id (0xFF…) does NOT match the content hash of its {tree, …}.
        Instant t = Instant.parse("2026-05-12T23:14:48Z");
        String tampered =
                rfc(t)
                        + " "
                        + HashUtils.toHex(hash(0xFF))
                        + " "
                        + HashUtils.toHex(hash(0x01))
                        + "\n";
        Files.writeString(log, tampered);
        String before = Files.readString(log);

        assertThrows(
                IllegalStateException.class,
                () -> CommitLogFatToThinMigration.migrate(log, store),
                "a row whose content does not hash to its id must be refused");
        assertEquals(before, Files.readString(log), "a refused migration must not rewrite the log");
    }

    @Test
    void an_absent_log_migrates_zero_rows(@TempDir Path dir) throws Exception {
        assertEquals(
                0,
                CommitLogFatToThinMigration.migrate(
                        dir.resolve("commits.log"), new InMemoryNodeStore()));
    }
}
