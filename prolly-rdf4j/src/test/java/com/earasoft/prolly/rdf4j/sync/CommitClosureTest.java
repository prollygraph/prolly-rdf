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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.earasoft.prolly.rdf4j.sail.CommitId;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link CommitClosure} — the history half of a sync transfer.
 *
 * <p>Post-ADR-0071 the closure walks the DAG by commit <b>id</b> (not the tree hash), so each test
 * builds commits tracking their ids (a child's parent is its parent's id) and queries {@code
 * reachable} by id. The {@code hash(n)} sentinels are only the per-commit tree hashes; {@code
 * seeds()} reads those off the <i>result</i> entries as a readable assert key.
 */
class CommitClosureTest {

    /** A distinct 20-byte hash whose first byte carries {@code seed}. */
    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        return h;
    }

    /** Append a commit and return its computed id (matches what {@code CommitLog} stored). */
    private static byte[] appendCommit(CommitLog log, int sec, byte[] tree, List<byte[]> parents)
            throws IOException {
        log.append(Instant.ofEpochSecond(sec), tree, parents);
        return CommitId.of(tree, parents, "", "");
    }

    /** The seed of each entry's metaTreeHash, in list order — a readable assert key. */
    private static List<Integer> seeds(List<CommitLog.Entry> entries) {
        return entries.stream().map(e -> e.metaTreeHash()[0] & 0xFF).toList();
    }

    @Test
    void linear_history_closure_is_all_commits_ancestors_first() throws IOException {
        CommitLog log = CommitLog.inMemory();
        byte[] c1 = appendCommit(log, 1, hash(1), List.of()); // genesis
        byte[] c2 = appendCommit(log, 2, hash(2), List.of(c1));
        byte[] c3 = appendCommit(log, 3, hash(3), List.of(c2));

        assertEquals(
                List.of(1, 2, 3),
                seeds(CommitClosure.reachable(log, c3)),
                "full linear closure, parents before children");
    }

    @Test
    void closure_of_an_interior_commit_excludes_descendants() throws IOException {
        CommitLog log = CommitLog.inMemory();
        byte[] c1 = appendCommit(log, 1, hash(1), List.of());
        byte[] c2 = appendCommit(log, 2, hash(2), List.of(c1));
        appendCommit(log, 3, hash(3), List.of(c2));

        assertEquals(List.of(1, 2), seeds(CommitClosure.reachable(log, c2)));
    }

    @Test
    void have_excludes_its_ancestors_leaving_the_delta() throws IOException {
        CommitLog log = CommitLog.inMemory();
        byte[] c1 = appendCommit(log, 1, hash(1), List.of());
        byte[] c2 = appendCommit(log, 2, hash(2), List.of(c1));
        byte[] c3 = appendCommit(log, 3, hash(3), List.of(c2));

        // The remote already holds commit 1 → only 2 and 3 need to travel.
        assertEquals(List.of(2, 3), seeds(CommitClosure.reachable(log, c3, List.of(c1))));
    }

    @Test
    void merge_commit_closure_spans_both_parents() throws IOException {
        CommitLog log = CommitLog.inMemory();
        byte[] c1 = appendCommit(log, 1, hash(1), List.of()); // genesis
        byte[] c2 = appendCommit(log, 2, hash(2), List.of(c1)); // branch A
        byte[] c3 = appendCommit(log, 3, hash(3), List.of(c1)); // branch B
        byte[] c4 = appendCommit(log, 4, hash(4), List.of(c2, c3)); // merge

        assertEquals(
                List.of(1, 2, 3, 4),
                seeds(CommitClosure.reachable(log, c4)),
                "closure spans both merge parents");

        // The remote has branch-A's tip (commit 2, hence commit 1) → the delta
        // is branch B plus the merge commit.
        assertEquals(List.of(3, 4), seeds(CommitClosure.reachable(log, c4, List.of(c2))));
    }

    @Test
    void head_absent_from_log_is_rejected() throws IOException {
        CommitLog log = CommitLog.inMemory();
        appendCommit(log, 1, hash(1), List.of());
        assertThrows(IllegalArgumentException.class, () -> CommitClosure.reachable(log, hash(99)));
    }

    @Test
    void have_absent_from_log_excludes_nothing() throws IOException {
        CommitLog log = CommitLog.inMemory();
        byte[] c1 = appendCommit(log, 1, hash(1), List.of());
        byte[] c2 = appendCommit(log, 2, hash(2), List.of(c1));

        // A `have` the log has never seen must not drop anything from the closure.
        assertEquals(List.of(1, 2), seeds(CommitClosure.reachable(log, c2, List.of(hash(77)))));
    }

    @Test
    void genesis_only_closure_is_the_single_commit() throws IOException {
        CommitLog log = CommitLog.inMemory();
        byte[] c1 = appendCommit(log, 1, hash(1), List.of());
        assertEquals(List.of(1), seeds(CommitClosure.reachable(log, c1)));
    }
}
