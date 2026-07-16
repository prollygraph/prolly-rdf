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

import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProllySailTest {

    private static Set<Statement> drain(CloseableIteration<? extends Statement> it) {
        Set<Statement> out = new HashSet<>();
        try {
            while (it.hasNext()) out.add(it.next());
        } finally {
            it.close();
        }
        return out;
    }

    @Nested
    class Lifecycle {
        @Test
        void initialize_and_shutdown() {
            ProllySail sail = new ProllySail();
            sail.init();
            assertDoesNotThrow(sail::shutDown);
        }

        @Test
        void is_writable() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                assertTrue(sail.isWritable());
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void value_factory_is_prolly() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                assertNotNull(sail.getValueFactory());
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void connection_open_and_close() {
            ProllySail sail = new ProllySail();
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                assertTrue(conn.isOpen());
            }
            sail.shutDown();
        }

        /**
         * Regression for #125 — first commit on a fresh in-memory sail (no {@link
         * RootMetaTreeStore} configured) must still advance {@code currentCommitHash}. Without
         * this, the rest layer's /sparql/update no-op detection (pre/post hash compare) fires 422
         * on every write in in-memory mode.
         */
        @Test
        void currentCommitHash_advances_in_memory_mode() {
            ProllySail sail = new ProllySail(); // no rootMetaTreeStore / commitLog / refsStore
            sail.init();
            try {
                assertNull(sail.currentCommitHash(), "fresh sail has no head");
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/a"),
                            vf.createIRI("http://example/b"),
                            vf.createIRI("http://example/c"));
                    conn.commit();
                }
                assertNotNull(
                        sail.currentCommitHash(),
                        "first commit must advance currentCommitHash even without a RootMetaTreeStore");
                assertNotNull(
                        sail.currentCommitInstant(),
                        "first commit must also stamp currentCommitInstant");
            } finally {
                sail.shutDown();
            }
        }

        /**
         * Regression for #127 — even without a {@link RootMetaTreeStore}, the sail uses an
         * in-memory {@link CommitLog} so endpoints that resolve commit hashes (event log,
         * /sparql/commits, Memento headers) keep working. Previous behavior left {@code
         * commitLog()} empty, so events from {@code /sparql/provenance/log} couldn't resolve to an
         * introducing commit.
         */
        @Test
        void commitLog_is_in_memory_when_no_store_dir() throws java.io.IOException {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                assertTrue(
                        sail.commitLog().isPresent(),
                        "in-memory mode should have an in-memory CommitLog (not Optional.empty)");
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/a"),
                            vf.createIRI("http://example/b"),
                            vf.createIRI("http://example/c"));
                    conn.commit();
                }
                java.util.List<CommitLog.Entry> entries = sail.commitLog().get().entries();
                assertEquals(1, entries.size(), "one commit should produce one log entry");
                assertArrayEquals(
                        sail.currentCommitHash(),
                        entries.get(0).metaTreeHash(),
                        "log entry hash must match currentCommitHash");
            } finally {
                sail.shutDown();
            }
        }
    }

    @Nested
    class WriteAndRead {
        @Test
        void add_single_statement_then_read_back() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/alice");
                IRI p = vf.createIRI("http://example/knows");
                IRI o = vf.createIRI("http://example/bob");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Statement> all = drain(conn.getStatements(null, null, null, false));
                    assertEquals(1, all.size());
                    Statement st = all.iterator().next();
                    assertEquals(s, st.getSubject());
                    assertEquals(p, st.getPredicate());
                    assertEquals(o, st.getObject());
                    assertNull(st.getContext());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void add_multiple_statements_and_count() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    for (int i = 0; i < 50; i++) {
                        conn.addStatement(
                                vf.createIRI("http://example/s" + i),
                                vf.createIRI("http://example/p"),
                                vf.createLiteral(i));
                    }
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(50L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void getStatements_subject_bound_filters_correctly() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI alice = vf.createIRI("http://example/alice");
                IRI bob = vf.createIRI("http://example/bob");
                IRI knows = vf.createIRI("http://example/knows");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(alice, knows, bob);
                    conn.addStatement(bob, knows, alice);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Statement> aliceOut = drain(conn.getStatements(alice, null, null, false));
                    assertEquals(1, aliceOut.size());
                    Statement st = aliceOut.iterator().next();
                    assertEquals(alice, st.getSubject());
                    assertEquals(bob, st.getObject());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void getStatements_predicate_bound_filters_via_post_scan() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI alice = vf.createIRI("http://example/alice");
                IRI knows = vf.createIRI("http://example/knows");
                IRI age = vf.createIRI("http://example/age");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(alice, knows, vf.createIRI("http://example/bob"));
                    conn.addStatement(alice, age, vf.createLiteral(30));
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Statement> agePreds = drain(conn.getStatements(null, age, null, false));
                    assertEquals(1, agePreds.size());
                    assertEquals(age, agePreds.iterator().next().getPredicate());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void getStatements_object_bound_filters() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI p = vf.createIRI("http://example/p");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(vf.createIRI("http://example/s1"), p, vf.createLiteral(10));
                    conn.addStatement(vf.createIRI("http://example/s2"), p, vf.createLiteral(20));
                    conn.addStatement(vf.createIRI("http://example/s3"), p, vf.createLiteral(10));
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Statement> matches =
                            drain(conn.getStatements(null, null, vf.createLiteral(10), false));
                    assertEquals(2, matches.size());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void getStatements_full_pattern_match_returns_single_row() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertTrue(conn.hasStatement(s, p, o, false));
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void duplicate_add_is_idempotent() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o);
                    conn.addStatement(s, p, o);
                    conn.addStatement(s, p, o);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(1L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }
    }

    @Nested
    class Rollback {
        @Test
        void rollback_discards_writes() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.rollback();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(0L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void rollback_after_commit_does_not_undo() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                    // Now in a fresh implicit tx; rollback won't undo the previous commit
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/s2"),
                            vf.createIRI("http://example/p2"),
                            vf.createIRI("http://example/o2"));
                    conn.rollback();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(
                            1L, conn.size(), "first commit's row survives rollback of second tx");
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void rollback_then_continue_in_new_tx() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/lost"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.rollback();
                    // Same connection, new tx, different writes — should commit cleanly
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/kept"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(1L, conn.size());
                    Set<Statement> all = drain(conn.getStatements(null, null, null, false));
                    Statement st = all.iterator().next();
                    assertEquals("http://example/kept", st.getSubject().stringValue());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void rollback_does_not_consume_term_ids_persistently() {
            // Buffered dict inserts that get rolled back should not show up after
            // re-reading the dict (which means the dict's persistent state is unchanged).
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(
                            vf.createIRI("http://example/rolled-back"),
                            vf.createIRI("http://example/p"),
                            vf.createIRI("http://example/o"));
                    conn.rollback();
                }
                // Open a fresh connection: dict should be empty (no terms persisted)
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(0L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }
    }

    @Nested
    class Removal {
        @Test
        void remove_specific_statement() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.removeStatements(s, p, o);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(0L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void remove_with_wildcard_subject() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI p = vf.createIRI("http://example/age");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(vf.createIRI("http://example/a1"), p, vf.createLiteral(1));
                    conn.addStatement(vf.createIRI("http://example/a2"), p, vf.createLiteral(2));
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.removeStatements(null, p, null);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(0L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void clear_removes_all() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    for (int i = 0; i < 10; i++) {
                        conn.addStatement(
                                vf.createIRI("http://example/s" + i),
                                vf.createIRI("http://example/p"),
                                vf.createLiteral(i));
                    }
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.clear();
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(0L, conn.size());
                }
            } finally {
                sail.shutDown();
            }
        }
    }

    @Nested
    class Contexts {
        @Test
        void default_graph_has_null_context() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o); // no contexts → default graph
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Statement> all = drain(conn.getStatements(null, null, null, false));
                    assertEquals(1, all.size());
                    assertNull(all.iterator().next().getContext());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void named_graph_round_trip() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                IRI g = vf.createIRI("http://example/g1");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o, g);
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Statement> all = drain(conn.getStatements(null, null, null, false));
                    assertEquals(1, all.size());
                    assertEquals(g, all.iterator().next().getContext());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void context_filter_isolates_graphs() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                IRI g1 = vf.createIRI("http://example/g1");
                IRI g2 = vf.createIRI("http://example/g2");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o, g1);
                    conn.addStatement(s, p, o, g2);
                    conn.addStatement(s, p, o); // default graph
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(3L, conn.size());
                    Set<Statement> g1Only = drain(conn.getStatements(null, null, null, false, g1));
                    assertEquals(1, g1Only.size());
                    assertEquals(g1, g1Only.iterator().next().getContext());
                }
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void context_ids_iterates_distinct_graphs() {
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI s = vf.createIRI("http://example/s");
                IRI p = vf.createIRI("http://example/p");
                IRI o = vf.createIRI("http://example/o");
                IRI g1 = vf.createIRI("http://example/g1");
                IRI g2 = vf.createIRI("http://example/g2");
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.addStatement(s, p, o, g1);
                    conn.addStatement(s, p, o, g2);
                    conn.addStatement(s, p, o); // default — should NOT appear
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    Set<Resource> ctxs = new HashSet<>();
                    try (CloseableIteration<? extends Resource> it = conn.getContextIDs()) {
                        while (it.hasNext()) ctxs.add(it.next());
                    }
                    assertEquals(Set.of(g1, g2), ctxs);
                }
            } finally {
                sail.shutDown();
            }
        }
    }

    @Nested
    class RepositoryWrapper {
        @Test
        void boots_via_sail_repository() {
            // Smoke test that the Sail integrates with the RDF4J Repository wrapper
            org.eclipse.rdf4j.repository.Repository repo = new SailRepository(new ProllySail());
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                ValueFactory vf = repo.getValueFactory();
                conn.add(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createLiteral("hello"));
                conn.commit();
                assertEquals(1L, conn.size());
            } finally {
                repo.shutDown();
            }
        }
    }

    @Nested
    class Namespaces {
        @Test
        void set_get_namespace() throws SailException {
            ProllySail sail = new ProllySail();
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.setNamespace("ex", "http://example/");
                conn.commit();
                assertEquals("http://example/", conn.getNamespace("ex"));
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void remove_namespace() throws SailException {
            ProllySail sail = new ProllySail();
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.setNamespace("ex", "http://example/");
                conn.removeNamespace("ex");
                conn.commit();
                assertNull(conn.getNamespace("ex"));
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void clear_namespaces() throws SailException {
            ProllySail sail = new ProllySail();
            sail.init();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.setNamespace("ex", "http://example/");
                conn.setNamespace("foo", "http://foo/");
                conn.clearNamespaces();
                conn.commit();
                assertNull(conn.getNamespace("ex"));
                assertNull(conn.getNamespace("foo"));
            } finally {
                sail.shutDown();
            }
        }

        @Test
        void namespaces_survive_across_connections() throws SailException {
            // Backed by a persistent prolly tree; second connection should see
            // the namespace that the first connection committed.
            ProllySail sail = new ProllySail();
            sail.init();
            try {
                try (SailConnection conn = sail.getConnection()) {
                    conn.begin();
                    conn.setNamespace("ex", "http://example/v1/");
                    conn.commit();
                }
                try (SailConnection conn = sail.getConnection()) {
                    assertEquals("http://example/v1/", conn.getNamespace("ex"));
                }
            } finally {
                sail.shutDown();
            }
        }
    }
}
