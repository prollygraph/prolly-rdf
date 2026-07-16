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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link MergeEngine}'s public result value-objects — {@link MergeEngine.MergeResult},
 * {@link MergeEngine.SquashResult}, {@link MergeEngine.Conflict}.
 *
 * <p>The current set-union merge policy never emits a {@code CONFLICT} result, so {@code
 * MergeResult.conflict(..)} and the {@code Conflict} record are public API that the merge paths
 * themselves don't exercise — pinned here so they don't rot uncovered.
 */
class MergeEngineResultTypesTest {

    @Test
    void mergeResult_ok_carries_commit_and_counts() {
        byte[] commit = {1, 2, 3};
        MergeEngine.MergeResult r = MergeEngine.MergeResult.ok(commit, 7, 2);
        assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());
        assertArrayEquals(commit, r.newCommit());
        assertEquals(7, r.incomingCount());
        assertEquals(2, r.sourceSideDeletes());
        assertTrue(r.conflicts().isEmpty(), "a clean merge has no conflicts");
    }

    @Test
    void mergeResult_upToDate_has_zero_counts_and_the_head() {
        byte[] head = {9, 9};
        MergeEngine.MergeResult r = MergeEngine.MergeResult.upToDate(head);
        assertEquals(MergeEngine.MergeResult.Kind.UP_TO_DATE, r.kind());
        assertArrayEquals(head, r.newCommit());
        assertEquals(0, r.incomingCount());
        assertEquals(0, r.sourceSideDeletes());
        assertTrue(r.conflicts().isEmpty());
    }

    @Test
    void mergeResult_conflict_has_no_commit_and_snapshots_the_list() {
        MergeEngine.Conflict c =
                new MergeEngine.Conflict(
                        MergeEngine.Term.uri("urn:s"), MergeEngine.Term.uri("urn:p"),
                        MergeEngine.Term.literal("ours"), MergeEngine.Term.literal("theirs"));
        List<MergeEngine.Conflict> src = new ArrayList<>(List.of(c));
        MergeEngine.MergeResult r = MergeEngine.MergeResult.conflict(src);
        assertEquals(MergeEngine.MergeResult.Kind.CONFLICT, r.kind());
        assertNull(r.newCommit(), "a conflict result records no commit");
        assertEquals(1, r.conflicts().size());
        // conflict() uses List.copyOf → a defensive, immutable snapshot.
        src.clear();
        assertEquals(1, r.conflicts().size(), "result must not alias the caller's list");
        assertThrows(
                UnsupportedOperationException.class,
                () -> r.conflicts().add(c),
                "the conflicts list must be immutable");
    }

    @Test
    void mergeResult_kind_has_exactly_three_values() {
        assertEquals(3, MergeEngine.MergeResult.Kind.values().length);
        assertNotNull(MergeEngine.MergeResult.Kind.valueOf("OK"));
        assertNotNull(MergeEngine.MergeResult.Kind.valueOf("UP_TO_DATE"));
        assertNotNull(MergeEngine.MergeResult.Kind.valueOf("CONFLICT"));
    }

    @Test
    void squashResult_empty_is_empty() {
        MergeEngine.SquashResult e = MergeEngine.SquashResult.empty();
        assertTrue(e.isEmpty());
        assertNull(e.newCommit());
        assertEquals(0, e.added());
        assertEquals(0, e.removed());
    }

    @Test
    void squashResult_with_a_commit_is_not_empty() {
        MergeEngine.SquashResult r = new MergeEngine.SquashResult(new byte[] {4}, 10, 3);
        assertFalse(r.isEmpty());
        assertEquals(10, r.added());
        assertEquals(3, r.removed());
    }

    @Test
    void conflict_record_holds_its_four_terms() {
        MergeEngine.Term s = MergeEngine.Term.uri("urn:s");
        MergeEngine.Term p = MergeEngine.Term.uri("urn:p");
        MergeEngine.Term ours = MergeEngine.Term.literal("a");
        MergeEngine.Term theirs = MergeEngine.Term.literal("b");
        MergeEngine.Conflict c = new MergeEngine.Conflict(s, p, ours, theirs);
        assertEquals(s, c.subject());
        assertEquals(p, c.predicate());
        assertEquals(ours, c.targetValue());
        assertEquals(theirs, c.sourceValue());
    }
}
