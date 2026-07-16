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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HashUtils;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the commit-identity invariants of {@link CommitId} (ADR-0071) in isolation, before anything
 * depends on it. The identity must be deterministic and sensitive to every load-bearing field, with
 * an injective field encoding so boundaries cannot collide.
 */
class CommitIdTest {

    private static byte[] h(String s) {
        return HashUtils.hash(s.getBytes(StandardCharsets.UTF_8));
    }

    private static final byte[] TREE = h("tree");
    private static final byte[] P1 = h("p1");
    private static final byte[] P2 = h("p2");

    @Test
    void deterministic_same_inputs_same_id() {
        assertArrayEquals(
                CommitId.of(TREE, List.of(P1, P2), "alice", "msg"),
                CommitId.of(TREE, List.of(P1, P2), "alice", "msg"));
    }

    @Test
    void id_width_matches_the_hash_width() {
        int width = HashUtils.hash(new byte[] {1}).length;
        assertEquals(
                width,
                CommitId.of(TREE, List.of(), "", "").length,
                "commit id must slot into the existing hash slots (refs, parent lists)");
    }

    @Test
    void different_tree_different_id() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(P1), "a", "m"),
                        CommitId.of(h("other-tree"), List.of(P1), "a", "m")));
    }

    @Test
    void different_parent_members_different_id() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(P1), "a", "m"),
                        CommitId.of(TREE, List.of(P2), "a", "m")));
    }

    @Test
    void parent_order_is_significant() {
        // D-3: "merge B into A" != "merge A into B".
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(P1, P2), "a", "m"),
                        CommitId.of(TREE, List.of(P2, P1), "a", "m")));
    }

    @Test
    void parent_count_is_significant() {
        // Genesis (0 parents) must differ from a single-parent commit with the same tree.
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(), "a", "m"),
                        CommitId.of(TREE, List.of(P1), "a", "m")));
    }

    @Test
    void different_author_different_id() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(P1), "alice", "m"),
                        CommitId.of(TREE, List.of(P1), "bob", "m")));
    }

    @Test
    void different_message_different_id() {
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(P1), "a", "first"),
                        CommitId.of(TREE, List.of(P1), "a", "second")));
    }

    @Test
    void field_boundaries_are_unambiguous() {
        // Injectivity: (author="ab", message="c") must not collide with (author="a", message="bc").
        assertFalse(
                Arrays.equals(
                        CommitId.of(TREE, List.of(P1), "ab", "c"),
                        CommitId.of(TREE, List.of(P1), "a", "bc")));
    }

    @Test
    void genesis_is_well_founded() {
        assertDoesNotThrow(() -> CommitId.of(TREE, List.of(), "a", "genesis"));
    }

    @Test
    void null_metaTreeHash_fails_closed() {
        assertThrows(IllegalArgumentException.class, () -> CommitId.of(null, List.of(), "a", "m"));
    }

    @Test
    void null_author_and_message_coerce_to_empty() {
        // Matches CommitLog.Entry's null -> "" coercion, so wiring it in is surprise-free.
        assertArrayEquals(
                CommitId.of(TREE, List.of(P1), null, null), CommitId.of(TREE, List.of(P1), "", ""));
    }

    @Test
    void null_parent_list_coerces_to_empty() {
        // Matches CommitLog.Entry's null-parents -> empty coercion.
        assertArrayEquals(
                CommitId.of(TREE, null, "a", "m"), CommitId.of(TREE, List.of(), "a", "m"));
    }
}
