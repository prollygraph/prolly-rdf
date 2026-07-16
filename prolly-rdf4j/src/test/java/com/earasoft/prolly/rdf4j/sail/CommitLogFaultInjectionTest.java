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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fault-injection coverage for {@link MergeEngine}'s {@link CommitLog} reads.
 *
 * <p>{@code CommitLog} is {@code final}, so the fault is injected through its backing file: {@code
 * entries()} parses each line and — by design — tolerates a torn <em>trailing</em> line (a crash
 * mid-append) but throws on corruption anywhere earlier. These tests confirm {@code findLCA}
 * surfaces genuine mid-file corruption (rather than silently computing a wrong merge base) and
 * still recovers from a torn trailing line.
 *
 * <p>Post-ADR-0071 {@code findLCA} walks the DAG by commit <b>id</b> (a content hash over tree +
 * parents + author + message), not the tree hash. So the hand-built tests track each commit's id (a
 * child's parent is its parent's id) and query/assert by id; the {@code hash(n)} sentinels are only
 * the per-commit tree hashes. The all-garbage-log test is the exception — its lookup arguments
 * never resolve (nothing was appended), so they stay arbitrary sentinels.
 */
class CommitLogFaultInjectionTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        for (int i = 0; i < 20; i++) h[i] = (byte) (seed + i);
        return h;
    }

    /** Append a commit and return its computed id (matches what {@code CommitLog} stored). */
    private static byte[] appendCommit(
            CommitLog log, Instant when, byte[] tree, List<byte[]> parents) throws IOException {
        log.append(when, tree, parents);
        return CommitId.of(tree, parents, "", "");
    }

    @Test
    void findLCA_throws_on_mid_file_corruption(@TempDir Path dir) throws Exception {
        // A valid entry, a garbage line, then another valid entry: the garbage
        // is NOT trailing, so entries() must fail loudly — findLCA propagates it
        // rather than walking a partial parent graph and returning a wrong base.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] treeA = hash(0x01), treeB = hash(0x40);
        byte[] idA = appendCommit(log, Instant.parse("2026-05-17T00:00:00Z"), treeA, List.of());
        Files.writeString(
                log.file(),
                "GARBAGE - not a commit-log line\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        byte[] idB = appendCommit(log, Instant.parse("2026-05-17T01:00:00Z"), treeB, List.of(idA));

        assertThrows(
                RuntimeException.class,
                () -> MergeEngine.findLCA(log, idA, idB),
                "findLCA must surface mid-file commit-log corruption");
    }

    @Test
    void findLCA_throws_on_an_all_garbage_log(@TempDir Path dir) throws Exception {
        // A log with no valid entries at all is genuine corruption — entries()
        // cannot recover a durable prefix, so it fails loudly. The lookup ids
        // never resolve (nothing was appended), so they are arbitrary sentinels.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        Files.writeString(
                log.file(),
                "not a commit\nalso not a commit\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);

        assertThrows(
                RuntimeException.class,
                () -> MergeEngine.findLCA(log, hash(0x01), hash(0x40)),
                "a log with no recoverable entries must fail loudly");
    }

    @Test
    void findLCA_recovers_from_a_torn_trailing_line(@TempDir Path dir) throws Exception {
        // Contrast: a torn LAST line (an append interrupted by a crash) is
        // recoverable — findLCA still works off the durable prefix.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] treeA = hash(0x01), treeB = hash(0x40);
        byte[] idA = appendCommit(log, Instant.parse("2026-05-17T00:00:00Z"), treeA, List.of());
        byte[] idB = appendCommit(log, Instant.parse("2026-05-17T01:00:00Z"), treeB, List.of(idA));
        Files.writeString(
                log.file(),
                "TORN partial line, no newline",
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        Optional<byte[]> lca = MergeEngine.findLCA(log, idB, idA);
        assertTrue(lca.isPresent(), "a torn trailing line must not break LCA");
        assertArrayEquals(idA, lca.get(), "LCA of a linear a→b chain is a");
    }

    @Test
    void structural_merge_surfaces_a_corrupt_commit_log(@TempDir Path dir) throws Exception {
        // End-to-end: a Sail whose CommitLog is corrupted between two commits.
        // mergeStructural → doStructuralMerge → findLCA reads the log and must
        // abort the merge rather than merge onto a wrong base.
        InMemoryNodeStore store = new InMemoryNodeStore();
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        log,
                        RefsStore.beside(dir));
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:a"), vf.createIRI("urn:p"), vf.createIRI("urn:b"));
                conn.commit();
            }
            // The head COMMIT ID (ADR-0071) is the handle mergeStructural's
            // source argument resolves against — not the tree hash.
            byte[] firstCommit = sail.currentCommitId();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:c"), vf.createIRI("urn:p"), vf.createIRI("urn:d"));
                conn.commit();
            }

            // Splice a garbage line into the MIDDLE of the now-two-line log.
            List<String> lines =
                    new ArrayList<>(Files.readAllLines(log.file(), StandardCharsets.UTF_8));
            assertEquals(2, lines.size(), "expected two valid commit-log lines");
            lines.add(1, "GARBAGE - not a commit-log line");
            Files.write(log.file(), lines, StandardCharsets.UTF_8);

            // Merging an earlier commit forces findLCA past its a==b short-circuit.
            assertThrows(
                    Throwable.class,
                    () -> MergeEngine.mergeStructural(sail, firstCommit),
                    "a structural merge must surface a corrupt commit log");
        } finally {
            sail.shutDown();
        }
    }
}
