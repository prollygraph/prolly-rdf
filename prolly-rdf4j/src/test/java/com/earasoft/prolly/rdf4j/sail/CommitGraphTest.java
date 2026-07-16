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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link CommitGraph} — the shared ancestry API over a {@link CommitLog}.
 *
 * <p>Post-ADR-0071 the graph is keyed by commit <b>id</b> (a content hash over tree + parents +
 * author + message), not the tree hash. So these tests build commits tracking each one's id (a
 * child's parent is its parent's id) and query/assert by id — the {@code hash(n)} sentinels are
 * only the per-commit tree hashes.
 */
class CommitGraphTest {

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

    /** A built DAG: the log plus its commit ids in build order (1-based via {@link #c}). */
    private record Dag(CommitLog log, List<byte[]> ids) {
        byte[] c(int n) {
            return ids.get(n - 1);
        }
    }

    /** A diamond: c1 genesis; c2 and c3 branch off c1; c4 merges c2+c3; c5 off c2. */
    private static Dag diamond() throws IOException {
        CommitLog log = CommitLog.inMemory();
        List<byte[]> ids = new ArrayList<>();
        ids.add(appendCommit(log, 1, hash(1), List.of()));
        ids.add(appendCommit(log, 2, hash(2), List.of(ids.get(0))));
        ids.add(appendCommit(log, 3, hash(3), List.of(ids.get(0))));
        ids.add(appendCommit(log, 4, hash(4), List.of(ids.get(1), ids.get(2))));
        ids.add(appendCommit(log, 5, hash(5), List.of(ids.get(1))));
        return new Dag(log, ids);
    }

    /**
     * A criss-cross: c1 genesis; c2 and c3 both off c1; c4 merges (c2,c3); c5 merges (c3,c2) — so
     * {@code mergeBase(c4,c5)} has <b>two</b> maximal common ancestors {c2,c3}, neither dominating
     * the other (test-strategy Step 17, the DAG shape the diamond can't produce).
     */
    private static Dag crissCross() throws IOException {
        CommitLog log = CommitLog.inMemory();
        List<byte[]> ids = new ArrayList<>();
        ids.add(appendCommit(log, 1, hash(1), List.of()));
        ids.add(appendCommit(log, 2, hash(2), List.of(ids.get(0))));
        ids.add(appendCommit(log, 3, hash(3), List.of(ids.get(0))));
        ids.add(appendCommit(log, 4, hash(4), List.of(ids.get(1), ids.get(2))));
        ids.add(appendCommit(log, 5, hash(5), List.of(ids.get(2), ids.get(1))));
        return new Dag(log, ids);
    }

    @Test
    void mergeBase_of_a_criss_cross_picks_the_most_recent_of_the_two_candidate_bases()
            throws IOException {
        Dag d = crissCross();
        CommitGraph g = new CommitGraph(d.log());
        // Two maximal common ancestors {c2,c3}; the documented tie-break is
        // most-recent-by-timestamp,
        // so c3 (t=3) wins over c2 (t=2).
        byte[] base = g.mergeBase(d.c(4), d.c(5)).orElseThrow();
        assertArrayEquals(
                d.c(3), base, "criss-cross tie broken to the most-recent candidate base (c3)");
        // The chosen base is genuinely a common ancestor of both merge heads — not a wrong pick.
        assertTrue(
                g.isAncestor(base, d.c(4)) && g.isAncestor(base, d.c(5)),
                "the chosen merge base is a common ancestor of both heads");
        // c2 is the OTHER maximal base: also a common ancestor of both, just not the tie-break
        // winner.
        assertTrue(
                g.isAncestor(d.c(2), d.c(4)) && g.isAncestor(d.c(2), d.c(5)),
                "the criss-cross genuinely has two candidate bases; c2 is the other");
    }

    @Test
    void ancestors_includes_self_and_all_reachable_parents() throws IOException {
        Dag d = diamond();
        CommitGraph g = new CommitGraph(d.log());
        assertEquals(
                Set.of(
                        HashUtils.toHex(d.c(1)),
                        HashUtils.toHex(d.c(2)),
                        HashUtils.toHex(d.c(3)),
                        HashUtils.toHex(d.c(4))),
                g.ancestors(d.c(4)).stream().map(HashUtils::toHex).collect(Collectors.toSet()));
        assertEquals(
                Set.of(HashUtils.toHex(d.c(1))),
                g.ancestors(d.c(1)).stream().map(HashUtils::toHex).collect(Collectors.toSet()));
    }

    @Test
    void isAncestor_is_inclusive_and_directional() throws IOException {
        Dag d = diamond();
        CommitGraph g = new CommitGraph(d.log());
        assertTrue(g.isAncestor(d.c(1), d.c(4)), "1 is an ancestor of 4");
        assertTrue(g.isAncestor(d.c(2), d.c(2)), "a commit is its own ancestor");
        assertFalse(g.isAncestor(d.c(4), d.c(1)), "4 is not an ancestor of 1");
        assertFalse(g.isAncestor(d.c(2), d.c(3)), "siblings are not ancestors");
        assertFalse(g.isAncestor(null, d.c(1)));
    }

    @Test
    void mergeBase_of_two_branches_is_the_fork_point() throws IOException {
        Dag d = diamond();
        CommitGraph g = new CommitGraph(d.log());
        assertArrayEquals(d.c(1), g.mergeBase(d.c(2), d.c(3)).orElseThrow());
    }

    @Test
    void mergeBase_is_merge_aware() throws IOException {
        // c4 = merge(c2, c3); c5 is off c2. The LCA of c4 and c5 is c2 — the
        // *lowest* common ancestor, not c1 (a plain hop-count walk gets this wrong).
        Dag d = diamond();
        CommitGraph g = new CommitGraph(d.log());
        assertArrayEquals(d.c(2), g.mergeBase(d.c(4), d.c(5)).orElseThrow());
    }

    @Test
    void mergeBase_of_a_commit_with_itself_is_itself() throws IOException {
        Dag d = diamond();
        CommitGraph g = new CommitGraph(d.log());
        assertArrayEquals(d.c(3), g.mergeBase(d.c(3), d.c(3)).orElseThrow());
    }

    @Test
    void mergeBase_of_disjoint_histories_is_empty() throws IOException {
        Dag d = diamond();
        byte[] id90 = appendCommit(d.log(), 9, hash(90), List.of()); // a separate genesis
        CommitGraph g = new CommitGraph(d.log());
        assertTrue(g.mergeBase(d.c(4), id90).isEmpty());
    }

    @Test
    void mergeBase_matches_MergeEngine_findLCA() throws IOException {
        // The Step-3 refactor makes MergeEngine.findLCA delegate to
        // CommitGraph.mergeBase — this asserts the two stay in lockstep.
        Dag d = diamond();
        CommitGraph g = new CommitGraph(d.log());
        for (int[] pair : new int[][] {{4, 5}, {2, 3}, {4, 1}, {3, 3}, {5, 3}}) {
            byte[] a = d.c(pair[0]);
            byte[] b = d.c(pair[1]);
            var viaGraph = g.mergeBase(a, b);
            var viaEngine = MergeEngine.findLCA(d.log(), a, b);
            assertEquals(
                    viaGraph.isPresent(),
                    viaEngine.isPresent(),
                    "presence parity for " + pair[0] + "," + pair[1]);
            if (viaGraph.isPresent()) {
                assertArrayEquals(
                        viaGraph.get(),
                        viaEngine.get(),
                        "base parity for " + pair[0] + "," + pair[1]);
            }
        }
    }
}
