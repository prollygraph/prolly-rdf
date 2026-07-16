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

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the Sail's commit/rollback/snapshot semantics. These tests encode invariants
 * that Phase 4 CAS-rebase must preserve. Each test doubles as a <b>regression tripwire</b>: if a
 * future refactor breaks any of these invariants, the failing test surfaces the bug before it
 * lands.
 *
 * <p>Categories:
 *
 * <ul>
 *   <li>Snapshot capture fidelity (5 tests)
 *   <li>Commit advances Sail roots (4 tests)
 *   <li>Rollback preserves Sail roots (3 tests)
 *   <li>Multi-connection isolation (4 tests)
 *   <li>Per-tx behavior tripwires (3 tests)
 * </ul>
 */
class SailCommitContractTest {

    private static long drain(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        } finally {
            it.close();
        }
        return n;
    }

    // ==================================================================
    // Snapshot capture fidelity
    // ==================================================================
    @Nested
    class SnapshotCapture {

        @Test
        void fresh_sail_snapshot_has_all_null_roots() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                Snapshot snap = conn.forkSnapshotForTesting();
                assertNotNull(snap, "snapshot captured at construction");
                assertNull(snap.dictRoot(), "fresh Sail has no committed dict");
                assertNull(snap.namespacesRoot());
                assertNull(snap.statsRoot());
                for (QuadOrder order : QuadOrder.values()) {
                    assertNull(
                            snap.indexRoots().get(order), "fresh Sail has no committed " + order);
                }
            }
            sail.shutDown();
        }

        @Test
        void snapshot_contains_one_entry_per_quad_order() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                assertEquals(4, conn.forkSnapshotForTesting().indexRoots().size());
            }
            sail.shutDown();
        }

        @Test
        void snapshot_reflects_sail_state_at_fork_instant() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                // First connection — commit something to advance Sail roots.
                ValueFactory vf = sail.getValueFactory();
                try (ProllySailConnection c1 = (ProllySailConnection) sail.getConnection()) {
                    c1.begin();
                    c1.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    c1.commit();
                }
                // Second connection — its snapshot should capture the advanced state.
                try (ProllySailConnection c2 = (ProllySailConnection) sail.getConnection()) {
                    Snapshot snap = c2.forkSnapshotForTesting();
                    assertNotNull(snap.dictRoot(), "dict root advanced by first commit");
                    for (QuadOrder order : QuadOrder.values()) {
                        assertNotNull(
                                snap.indexRoots().get(order),
                                order + " root advanced by first commit");
                    }
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void snapshot_equals_sail_roots_at_construction_time() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                Snapshot snap = conn.forkSnapshotForTesting();
                assertSame(sail.dictRoot(), snap.dictRoot());
                assertSame(sail.namespacesRoot(), snap.namespacesRoot());
                assertSame(sail.statsRoot(), snap.statsRoot());
                for (QuadOrder order : QuadOrder.values()) {
                    assertSame(sail.indexRoot(order), snap.indexRoots().get(order));
                }
            }
            sail.shutDown();
        }

        @Test
        void snapshot_indexRoots_view_is_immutable() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                Snapshot snap = conn.forkSnapshotForTesting();
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> snap.indexRoots().put(QuadOrder.SPOC, null));
            }
            sail.shutDown();
        }
    }

    // ==================================================================
    // Commit advances Sail roots
    // ==================================================================
    @Nested
    class CommitAdvancesRoots {

        @Test
        void first_commit_advances_dict_root_from_null() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                assertNull(sail.dictRoot(), "no dict root before any commit");
                ValueFactory vf = sail.getValueFactory();
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                }
                assertNotNull(sail.dictRoot(), "dict root advanced after commit");
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void commit_advances_all_four_index_roots() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                }
                for (QuadOrder order : QuadOrder.values()) {
                    assertNotNull(
                            sail.indexRoot(order),
                            order + " root advanced (single addStatement writes to all 4 indexes)");
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void two_consecutive_commits_produce_distinct_dict_roots() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                StaticMap root1, root2;
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s1"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o1"));
                    conn.commit();
                    root1 = sail.dictRoot();
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s2"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o2"));
                    conn.commit();
                    root2 = sail.dictRoot();
                }
                assertNotNull(root1);
                assertNotNull(root2);
                assertNotSame(
                        root1, root2, "second commit produces a new dict StaticMap reference");
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void empty_commit_does_not_advance_roots() {
            // An empty commit flushes empty buffers — Dictionary.commit returns the
            // base StaticMap unchanged when there are no pending edits, so the
            // Sail's volatile ref points at "same" object after advanceX.
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                // Seed with one commit so roots exist.
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                }
                StaticMap rootBefore = sail.dictRoot();
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.commit();
                }
                StaticMap rootAfter = sail.dictRoot();
                // No writes between commits → MutableMap.flush returns the same StaticMap.
                assertSame(rootBefore, rootAfter, "empty commit must not allocate a new tree");
            } finally {
                sail.shutDown();
            }
        }
    }

    // ==================================================================
    // Rollback preserves Sail roots
    // ==================================================================
    @Nested
    class RollbackPreservesRoots {

        @Test
        void rollback_leaves_dict_root_null_on_fresh_sail() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.rollback();
                }
                assertNull(sail.dictRoot(), "rollback on fresh sail must not advance roots");
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void rollback_preserves_roots_byte_identity() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                // Seed.
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                }
                // Capture roots before rollback.
                StaticMap dictBefore = sail.dictRoot();
                StaticMap nsBefore = sail.namespacesRoot();
                StaticMap statsBefore = sail.statsRoot();
                StaticMap spocBefore = sail.indexRoot(QuadOrder.SPOC);
                // Connection writes + rollback.
                try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/discarded"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.setNamespace("ex", "http://example/");
                    conn.rollback();
                }
                // Sail roots are exactly the same references — no advance happened.
                assertSame(dictBefore, sail.dictRoot());
                assertSame(nsBefore, sail.namespacesRoot());
                assertSame(statsBefore, sail.statsRoot());
                assertSame(spocBefore, sail.indexRoot(QuadOrder.SPOC));
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void rollback_refreshes_fork_snapshot() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                Snapshot before = conn.forkSnapshotForTesting();
                conn.begin();
                ValueFactory vf = sail.getValueFactory();
                conn.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.rollback();
                Snapshot after = conn.forkSnapshotForTesting();
                // No Sail roots advanced (single-writer, no other committers), so the
                // refreshed snapshot should match the original one byte-wise.
                assertSame(before.dictRoot(), after.dictRoot());
                assertSame(before.namespacesRoot(), after.namespacesRoot());
            }
            sail.shutDown();
        }
    }

    // ==================================================================
    // Multi-connection isolation
    // ==================================================================
    @Nested
    class MultiConnectionIsolation {

        @Test
        void two_simultaneously_open_connections_share_the_same_snapshot() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection a = (ProllySailConnection) sail.getConnection();
                    ProllySailConnection b = (ProllySailConnection) sail.getConnection()) {
                assertSame(
                        a.forkSnapshotForTesting().dictRoot(),
                        b.forkSnapshotForTesting().dictRoot(),
                        "both connections forked from the same fresh sail");
            }
            sail.shutDown();
        }

        @Test
        void uncommitted_writes_in_a_not_visible_to_b() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection a = (ProllySailConnection) sail.getConnection();
                    ProllySailConnection b = (ProllySailConnection) sail.getConnection()) {
                ValueFactory vf = sail.getValueFactory();
                a.begin();
                a.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                // B's per-tx tables were forked before A's write — and the write
                // is on A's MutableMap, not the Sail's roots.
                assertEquals(0L, drain(b.getStatements(null, null, null, false)));
            }
            sail.shutDown();
        }

        @Test
        void as_commit_not_visible_to_b_already_open() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection a = (ProllySailConnection) sail.getConnection();
                    ProllySailConnection b = (ProllySailConnection) sail.getConnection()) {
                ValueFactory vf = sail.getValueFactory();
                a.begin();
                a.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                a.commit();
                // B's per-tx tables were forked BEFORE A's commit — B sees its
                // own pre-A snapshot, not the Sail's advanced root.
                assertEquals(
                        0L,
                        drain(b.getStatements(null, null, null, false)),
                        "snapshot isolation: B's view is frozen at fork time");
            }
            sail.shutDown();
        }

        @Test
        void as_commit_visible_to_c_opened_after() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (ProllySailConnection a = (ProllySailConnection) sail.getConnection()) {
                    a.begin();
                    a.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    a.commit();
                }
                try (ProllySailConnection c = (ProllySailConnection) sail.getConnection()) {
                    Set<Statement> all = new HashSet<>();
                    try (var it = c.getStatements(null, null, null, false)) {
                        while (it.hasNext()) all.add(it.next());
                    }
                    assertEquals(
                            1,
                            all.size(),
                            "C opened after A's commit; its fork captures the advanced roots");
                }
            } finally {
                sail.shutDown();
            }
        }
    }

    // ==================================================================
    // Per-tx behavior tripwires
    // ==================================================================
    @Nested
    class PerTxTripwires {

        @Test
        void fork_snapshot_field_is_populated_at_construction() {
            // Tripwire: if a future refactor drops the captureSnapshot() call,
            // this fails immediately.
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                assertNotNull(conn.forkSnapshotForTesting());
            }
            sail.shutDown();
        }

        @Test
        void fork_snapshot_field_repopulated_after_rollback() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                conn.begin();
                ValueFactory vf = sail.getValueFactory();
                conn.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.rollback();
                // After rollback, the snapshot is a fresh capture — not null,
                // not stale relative to the (unchanged) Sail roots.
                assertNotNull(conn.forkSnapshotForTesting());
            }
            sail.shutDown();
        }

        @Test
        void interleaved_commits_and_rollbacks_remain_consistent() {
            // Stress: 50 rounds of commit/rollback on the same connection.
            ProllySail sail = new ProllySail();
            sail.init();
            try (ProllySailConnection conn = (ProllySailConnection) sail.getConnection()) {
                ValueFactory vf = sail.getValueFactory();
                int committed = 0;
                for (int i = 0; i < 50; i++) {
                    conn.begin();
                    IRI s = vf.createIRI("http://example/s" + i);
                    conn.addStatement(
                            s, vf.createIRI("http://example/p"), vf.createIRI("http://example/o"));
                    if (i % 3 == 0) {
                        conn.rollback();
                    } else {
                        conn.commit();
                        committed++;
                    }
                }
                long actual = drain(conn.getStatements(null, null, null, false));
                assertEquals(committed, actual, "exactly the committed adds should be visible");
            }
            sail.shutDown();
        }
    }
}
