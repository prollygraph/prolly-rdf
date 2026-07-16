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

import com.dolthub.prolly.NodeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link ProllySail}'s accessor surface and its no-op-commit detection helpers.
 *
 * <p>The behavioural integration tests ({@code ProllySailContextTest}, {@code
 * SailCommitContractTest}, …) drive {@code ProllySail} through a {@code SailConnection}; this file
 * pins the directly-callable surface an in-memory Sail exposes — the persistence-sidecar getters,
 * the OSS-default event-sink state, and the static {@link ProllySail#isDataTreeNoOp} branch
 * predicate that gates sidecar commits.
 */
class ProllySailAccessorsTest {

    private ProllySail sail;

    @BeforeEach
    void setUp() {
        sail = new ProllySail(); // in-memory: InMemoryNodeStore + HeapBufferPool
        sail.init();
    }

    @AfterEach
    void tearDown() {
        sail.shutDown();
    }

    // ---- persistence sidecars ------------------------------------------

    @Test
    void in_memory_sail_wires_in_memory_sidecars_but_no_restore_pointer() {
        assertNotNull(sail.store(), "the backing NodeStore is always present");
        assertNotNull(sail.pool());
        assertNotNull(sail.meterRegistry());
        assertNotNull(sail.valueFactoryInternal(), "the value factory is wired at construction");
        assertTrue(
                sail.rootMetaTreeStore().isEmpty(),
                "in-memory sail has no auto-restore pointer file");
        // #127/#128: with no RootMetaTreeStore the CommitLog and RefsStore
        // still fall back to in-memory variants so /sparql/commits and
        // /sparql/branches work in dev mode rather than 404ing.
        assertTrue(
                sail.commitLog().isPresent(),
                "in-memory sail falls back to an in-memory commit log");
        assertTrue(
                sail.refsStore().isPresent(),
                "in-memory sail falls back to an in-memory branches refs store");
    }

    @Test
    void fresh_sail_has_no_commit_state() {
        assertNull(sail.currentCommitHash(), "no commits have happened yet");
        assertNull(sail.currentCommitInstant(), "no commit instant without a commit");
        assertEquals("main", sail.currentBranch(), "v2.0 commits always land on main");
        assertArrayEquals(
                new byte[0],
                sail.repoId(),
                "no commit log → the unscoped repo-id sentinel (byte[0])");
    }

    // ---- event sink (OSS default = inert) ------------------------------

    @Test
    void event_sink_is_inert_on_the_oss_default() {
        assertNull(sail.eventSinkFactory(), "OSS distribution binds no event-sink factory");
        assertFalse(sail.eventSinkEnabled(), "the runtime opt-in flag is off by default");
        assertFalse(
                sail.eventSinkActive(), "inactive unless the flag is on AND a factory is bound");
        assertNull(sail.eventSinkRoot(), "no sink root while the sink is disabled");
    }

    @Test
    void event_sink_root_reflects_the_last_write() {
        // Both the admin swap-in and the commit-path advance accept null
        // (the disabled-sink state); pin that the accessor mirrors the
        // most recent write through either door.
        sail.replaceEventSinkRoot(null);
        assertNull(sail.eventSinkRoot());
        sail.advanceEventSinkRoot(null);
        assertNull(sail.eventSinkRoot());
    }

    // ---- no-op commit detection ----------------------------------------

    @Test
    void wouldBeNoOpCommit_is_false_before_any_commit() {
        assertFalse(
                sail.wouldBeNoOpCommit(),
                "the first commit is never a no-op — there is no baseline to diff against");
    }

    @Test
    void setNextCommitProvenanceFold_accepts_a_one_shot_directive() {
        // The one-shot merge-provenance fold setter; null clears it. Pinning
        // that arming it does not throw on an in-memory sail.
        assertDoesNotThrow(() -> sail.setNextCommitProvenanceFold(null, null));
    }

    // ---- isDataTreeNoOp (static branch predicate) ----------------------

    @Test
    void isDataTreeNoOp_null_root_is_not_a_noop() {
        NodeStore store = sail.store();
        byte[] x = {1, 2, 3};
        assertFalse(ProllySail.isDataTreeNoOp(store, null, x), "a null left root → not a no-op");
        assertFalse(ProllySail.isDataTreeNoOp(store, x, null), "a null right root → not a no-op");
    }

    @Test
    void isDataTreeNoOp_byte_identical_roots_are_a_noop() {
        // Distinct arrays, identical content — the Arrays.equals short-circuit
        // fires before any store read.
        byte[] a = {9, 8, 7, 6};
        byte[] b = {9, 8, 7, 6};
        assertTrue(
                ProllySail.isDataTreeNoOp(sail.store(), a, b),
                "identical root hashes are trivially a no-op");
    }

    @Test
    void isDataTreeNoOp_unresolvable_roots_are_treated_as_changed() {
        // Two different hashes that resolve to no RootMetaTree in the store:
        // readFrom() returns empty, so the predicate conservatively reports
        // "changed" rather than risk skipping a real commit.
        byte[] a = new byte[20];
        byte[] b = new byte[20];
        b[0] = 1;
        assertFalse(
                ProllySail.isDataTreeNoOp(sail.store(), a, b),
                "hashes with no backing RootMetaTree are treated as a change");
    }
}
