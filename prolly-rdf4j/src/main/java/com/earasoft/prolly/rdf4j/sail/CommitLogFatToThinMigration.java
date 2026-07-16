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

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.NodeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * The one-shot operator migration from the <b>fat</b> {@code commits.log} format (pre-ADR-0073,
 * which carried the full commit content on every row) to the <b>thin</b> {@code <datetime> <id>}
 * format (ADR-0073, where the content lives in a content-addressed commit chunk).
 *
 * <p>Pre-1.0 there is <b>no defensive reader</b> for the old shape ({@link CommitLog} only parses
 * the thin row) — an existing store is migrated once by this tool. For each fat row it reconstructs
 * the {@link CommitObject} from the row's {@code {metaTreeHash, parents, author, message}}, writes
 * it to the {@code NodeStore}, and <b>asserts the chunk's hash equals the row's stored id</b> — a
 * free integrity check that the log is internally consistent. Only if every row checks out does it
 * rewrite {@code commits.log} to the thin form, atomically (temp file + {@code ATOMIC_MOVE}).
 *
 * @apiNote Idempotent-ish in effect but designed to run once: a <em>thin</em> log fed to it is
 *     rejected (its rows lack the fat fields), which is the correct signal that no migration is
 *     needed. On any {@code hash != id} mismatch it <b>refuses</b> — throws without rewriting the
 *     log (already-written chunks are content-addressed and harmless) — so the operator can
 *     reimport from source rather than trust a tampered/corrupt log.
 * @implNote Chunks are written <b>before</b> the log is rewritten, so a crash mid-run leaves the
 *     fat log intact + orphan chunks (re-runnable), never a torn log. Carries its own fat-row
 *     parser because the production {@code CommitLog} no longer understands the old format.
 */
public final class CommitLogFatToThinMigration {

    private CommitLogFatToThinMigration() {}

    /**
     * RFC 1123 datetime token count: {@code "Tue, 12 May 2026 23:14:48 GMT"} → 6 whitespace tokens.
     */
    private static final int DT_TOKEN_COUNT = 6;

    /**
     * Migrate the fat {@code commitsLog} in place to the thin format, writing each commit's chunk
     * to {@code store}.
     *
     * @return the number of commit rows migrated (0 if the log is absent/empty)
     * @throws IllegalStateException if a row's content does not hash to its stored id (refused; the
     *     log is left untouched), or if a row is not the fat format
     * @throws IOException on an I/O failure
     */
    public static int migrate(Path commitsLog, NodeStore store) throws IOException {
        Objects.requireNonNull(commitsLog, "commitsLog");
        Objects.requireNonNull(store, "store");
        if (!Files.exists(commitsLog)) {
            return 0;
        }
        List<String> lines = Files.readAllLines(commitsLog, StandardCharsets.UTF_8);
        CommitStore commits = new CommitStore(store);
        List<String> thin = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            FatRow row = parseFatRow(line);
            byte[] chunkId =
                    commits.write(
                            CommitObject.of(
                                    row.metaTreeHash, row.parents, row.author, row.message));
            if (!Arrays.equals(chunkId, row.id)) {
                throw new IllegalStateException(
                        "commit-log migration refused: row id "
                                + HashUtils.toHex(row.id)
                                + " does not match its content hash "
                                + HashUtils.toHex(chunkId)
                                + " — the log is inconsistent or tampered; reimport from source"
                                + " (nothing was rewritten)");
            }
            thin.add(row.datetime + ' ' + HashUtils.toHex(row.id));
        }
        // Every chunk is written + verified; only now rewrite the log, atomically.
        Path parent = Objects.requireNonNull(commitsLog.getParent());
        Path tmp = Files.createTempFile(parent, "commits", ".log.tmp");
        Files.write(tmp, thin, StandardCharsets.UTF_8);
        Files.move(
                tmp,
                commitsLog,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        return thin.size();
    }

    /**
     * Parse one fat row: {@code "<datetime> <id> <metaTreeHash> [<parent>…] [m=<b64>] [a=<b64>]"}.
     */
    private static FatRow parseFatRow(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < DT_TOKEN_COUNT + 2) {
            throw new IllegalStateException("not a fat commit-log line: " + line);
        }
        String datetime = String.join(" ", Arrays.copyOfRange(tokens, 0, DT_TOKEN_COUNT));
        try {
            // Validate the datetime is parseable (fail closed rather than migrate a garbage row).
            Instant unused = ZonedDateTime.parse(datetime, CommitLog.RFC_1123).toInstant();
            Objects.requireNonNull(unused);
            byte[] id = HashUtils.fromHex(tokens[DT_TOKEN_COUNT]);
            byte[] metaTreeHash = HashUtils.fromHex(tokens[DT_TOKEN_COUNT + 1]);
            List<byte[]> parents = new ArrayList<>();
            String message = "";
            String author = "";
            for (int i = DT_TOKEN_COUNT + 2; i < tokens.length; i++) {
                String t = tokens[i];
                if (t.startsWith("m=")) {
                    message =
                            new String(
                                    Base64.getDecoder().decode(t.substring(2)),
                                    StandardCharsets.UTF_8);
                } else if (t.startsWith("a=")) {
                    author =
                            new String(
                                    Base64.getDecoder().decode(t.substring(2)),
                                    StandardCharsets.UTF_8);
                } else {
                    parents.add(HashUtils.fromHex(t));
                }
            }
            return new FatRow(datetime, id, metaTreeHash, parents, message, author);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("malformed fat commit-log line: " + line, ex);
        }
    }

    private record FatRow(
            String datetime,
            byte[] id,
            byte[] metaTreeHash,
            List<byte[]> parents,
            String message,
            String author) {}
}
