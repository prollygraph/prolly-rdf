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
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link MergeEngine#findLCA} on commit graphs that contain <em>merge commits</em> (multi-parent).
 * {@code MergeEngineTest} only covers linear and single-fork chains; once {@code mergeStructural}
 * starts producing two-parent commits, the graph is a genuine DAG and the merge-base must still be
 * the <em>lowest</em> common ancestor.
 *
 * <p>Post-ADR-0071 a commit is keyed by its <b>id</b> (a content hash over tree + parents + author
 * + message), not the tree hash. So each commit is appended while tracking the id {@code CommitLog}
 * computed for it, and a child's parents are its <em>parents' ids</em> — {@link #findLCA} then
 * returns those ids. The {@code h(n)} sentinels are only the per-commit <b>tree</b> hashes; the
 * assertions compare the captured ids, which is what the merge-base machinery actually walks.
 */
class MergeEngineLcaDagTest {

    private static byte[] h(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        return out;
    }

    /**
     * Append a commit with the given tree hash and parent <b>ids</b>, returning its computed commit
     * id (the same value {@code CommitLog} stored, so it can serve as a child's parent id).
     */
    private static byte[] appendCommit(
            CommitLog log, Instant when, byte[] tree, List<byte[]> parents) throws IOException {
        log.append(when, tree, parents);
        return CommitId.of(tree, parents, "", "");
    }

    @Test
    void lca_on_a_dag_with_a_merge_commit(@TempDir Path dir) throws Exception {
        // Linear C0→C1→C2→C3, plus C4 = merge(C3, C1).
        //
        //   C0 → C1 → C2 → C3
        //         \         \
        //          \---------→ C4   (C4's parents: C3, C1)
        //
        // merge-base(C2, C4): C4's ancestors = {C4,C3,C2,C1,C0};
        // C2's = {C2,C1,C0}; common = {C2,C1,C0}; the lowest is C2
        // (C2 is itself an ancestor of C4 via C3).
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] c0 = appendCommit(log, Instant.parse("2026-05-15T00:00:00Z"), h(0), List.of());
        byte[] c1 = appendCommit(log, Instant.parse("2026-05-15T01:00:00Z"), h(1), List.of(c0));
        byte[] c2 = appendCommit(log, Instant.parse("2026-05-15T02:00:00Z"), h(2), List.of(c1));
        byte[] c3 = appendCommit(log, Instant.parse("2026-05-15T03:00:00Z"), h(3), List.of(c2));
        byte[] c4 = appendCommit(log, Instant.parse("2026-05-15T04:00:00Z"), h(4), List.of(c3, c1));

        Optional<byte[]> lca = MergeEngine.findLCA(log, c2, c4);
        assertTrue(lca.isPresent());
        assertArrayEquals(
                c2,
                lca.get(),
                "merge-base of C2 and the merge commit C4 is C2 — C2 is an "
                        + "ancestor of C4 via C3, so it is the lowest common ancestor");
    }

    @Test
    void lca_of_two_branches_joined_by_a_merge(@TempDir Path dir) throws Exception {
        // base → a1 → a2   (branch A)
        // base → b1        (branch B)
        // m = merge(a2, b1)
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] base = appendCommit(log, Instant.parse("2026-05-15T00:00:00Z"), h(0x10), List.of());
        byte[] a1 =
                appendCommit(log, Instant.parse("2026-05-15T01:00:00Z"), h(0x11), List.of(base));
        byte[] a2 = appendCommit(log, Instant.parse("2026-05-15T02:00:00Z"), h(0x12), List.of(a1));
        byte[] b1 =
                appendCommit(log, Instant.parse("2026-05-15T03:00:00Z"), h(0x21), List.of(base));
        byte[] m =
                appendCommit(log, Instant.parse("2026-05-15T04:00:00Z"), h(0x30), List.of(a2, b1));

        // a2 is a direct parent of m → it is itself the merge-base.
        assertArrayEquals(
                a2,
                MergeEngine.findLCA(log, a2, m).orElseThrow(),
                "a branch tip merged into m is its own merge-base with m");
        // b1 likewise.
        assertArrayEquals(b1, MergeEngine.findLCA(log, b1, m).orElseThrow());
        // The two original branch tips still fork at base.
        assertArrayEquals(base, MergeEngine.findLCA(log, a2, b1).orElseThrow());
    }

    @Test
    void lca_across_a_chain_of_merges(@TempDir Path dir) throws Exception {
        // base → x, base → y, m1 = merge(x, y);
        // then base → z, m2 = merge(m1, z).
        // merge-base(m1, m2) is m1 (m1 is a direct parent of m2).
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] base = appendCommit(log, Instant.parse("2026-05-15T00:00:00Z"), h(0x40), List.of());
        byte[] x = appendCommit(log, Instant.parse("2026-05-15T01:00:00Z"), h(0x41), List.of(base));
        byte[] y = appendCommit(log, Instant.parse("2026-05-15T02:00:00Z"), h(0x42), List.of(base));
        byte[] m1 =
                appendCommit(log, Instant.parse("2026-05-15T03:00:00Z"), h(0x50), List.of(x, y));
        byte[] z = appendCommit(log, Instant.parse("2026-05-15T04:00:00Z"), h(0x43), List.of(base));
        byte[] m2 =
                appendCommit(log, Instant.parse("2026-05-15T05:00:00Z"), h(0x51), List.of(m1, z));

        assertArrayEquals(
                m1,
                MergeEngine.findLCA(log, m1, m2).orElseThrow(),
                "m1 is a parent of m2 → it is the merge-base");
        // x diverged before m1; merge-base(x, m2) is x (x is an ancestor of
        // m2 through m1, so x dominates base/y on that path).
        assertArrayEquals(x, MergeEngine.findLCA(log, x, m2).orElseThrow());
    }
}
