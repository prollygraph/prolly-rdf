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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 Step 6 of {@code plans/model-based-testing-rollout.md} — model-based simulation of the
 * Sail connection's <b>per-transaction staging overlay</b> (the read-your-writes buffer that {@code
 * addStatement} / {@code removeStatement} write into and {@code commit} flushes). It completes Step
 * 6 alongside the {@code Dictionary} / {@code SpocIndex} / {@code QuadIndex} model properties.
 *
 * <p><b>Why a seeded loop, not a jqwik {@code ActionChain} (the Step-3 precedent):</b> {@code
 * ProllySail} needs a file-backed {@link CommitLog} / {@link RefsStore} / {@link RootMetaTreeStore}
 * ({@code .beside(dir)}), so a fresh {@code ActionChain} {@code Model} per chain would create — and
 * leak — a temp directory per chain. This is exactly the situation that made {@code
 * DatabaseMultiBranchModelTest} use a {@link SplittableRandom}-seeded op loop over <b>one</b> store
 * instead of an {@code ActionChain}; this follows that established instrument: one connection per
 * seed, a long random op sequence, the model checked after every op.
 *
 * <p><b>What this pins that the existing coverage does not.</b> {@code SailDifferentialProperty}
 * replays add/remove/commit/rollback streams against an RDF4J {@code MemoryStore} but only compares
 * <b>after each commit</b>; {@code ProllySailReadYourWritesTest} checks read-your-writes but by a
 * handful of <i>fixed</i> sequences. Neither asserts read-your-writes as a <b>property over the
 * interleaving</b>. The staging overlay's defining contract is that an <i>uncommitted</i>
 * add/remove is immediately visible to {@code size} and {@code getStatements} <i>within the same
 * connection</i> (the subject of a previously-fixed flush-before-read bug), that {@code commit}
 * persists exactly the visible set, and that {@code rollback} restores the last-committed set. The
 * reference model is therefore a plain {@code Set<Statement>} — the direct specification of "what
 * should be visible right now" — checked after <b>every</b> op (add / remove / commit / rollback),
 * via the full {@code (*,*,*)} scan, each single-subject pattern, and {@code size}. Order-dependent
 * staging bugs a fixed sequence misses (add A → remove A → add A in one tx; a read after arbitrary
 * buffered state; rollback after a remove) surface the moment they happen.
 *
 * <p>Regime: a tiny vocabulary (3 subjects × 2 predicates × 3 objects = 18 distinct statements) so
 * add/remove churn re-touches the same statements (last-write-wins, tombstone-then-re-add) and a
 * single-subject pattern matches several buffered statements. Default graph only — context
 * isolation has its own tests.
 */
class ProllySailStagingModelTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    // Non-prefix-colliding subjects so a "subject " key prefix is unambiguous.
    private static final IRI[] S = {
        VF.createIRI("urn:s/1"), VF.createIRI("urn:s/2"), VF.createIRI("urn:s/3")
    };
    private static final IRI[] P = {VF.createIRI("urn:p/1"), VF.createIRI("urn:p/2")};
    private static final Value[] O = {
        VF.createIRI("urn:o/1"), VF.createIRI("urn:o/2"), VF.createLiteral("lit")
    };

    @Test
    void stagingReadYourWritesMatchesSetOracleAcrossSeeds() throws Exception {
        for (long seed = 1; seed <= 8; seed++) {
            Path dir = Files.createTempDirectory("sail-staging");
            ProllySail sail =
                    new ProllySail(
                            new InMemoryNodeStore(),
                            new HeapBufferPool(),
                            RootMetaTreeStore.beside(dir),
                            CommitLog.beside(dir),
                            RefsStore.beside(dir),
                            false);
            SailRepository repo = new SailRepository(sail);
            repo.init();
            try (RepositoryConnection conn = repo.getConnection()) {
                runOneSeed(seed, conn);
            } finally {
                repo.shutDown();
                deleteTree(dir);
            }
        }
    }

    private void runOneSeed(long seed, RepositoryConnection conn) {
        Set<Statement> committed =
                new HashSet<>(); // last committed root → what a post-commit/rollback read sees
        Set<Statement> visible =
                new HashSet<>(); // committed + pending edits → read-your-writes within the tx
        boolean inTxn = false;
        SplittableRandom rnd = new SplittableRandom(seed);

        for (int round = 0; round < 250; round++) {
            switch (rnd.nextInt(6)) {
                case 0, 1, 2 -> { // ADD (weighted — build a non-trivial set)
                    Statement st = randomStmt(rnd);
                    if (!inTxn) {
                        conn.begin();
                        inTxn = true;
                    }
                    conn.add(st);
                    visible.add(st);
                    assertReads(conn, visible, seed, round, "after add " + key(st));
                }
                case 3 -> { // REMOVE
                    Statement st = randomStmt(rnd);
                    if (!inTxn) {
                        conn.begin();
                        inTxn = true;
                    }
                    conn.remove(st);
                    visible.remove(st);
                    assertReads(conn, visible, seed, round, "after remove " + key(st));
                }
                case 4 -> { // COMMIT — visible becomes the new committed base
                    if (inTxn) {
                        conn.commit();
                        inTxn = false;
                        committed = new HashSet<>(visible);
                    }
                    assertReads(conn, visible, seed, round, "after commit");
                }
                default -> { // ROLLBACK — pending edits dropped, back to committed
                    if (inTxn) {
                        conn.rollback();
                        inTxn = false;
                        visible = new HashSet<>(committed);
                    }
                    assertReads(conn, visible, seed, round, "after rollback");
                }
            }
        }
        if (inTxn) {
            conn.commit();
            committed = new HashSet<>(visible);
        }
        assertReads(conn, committed, seed, 250, "final committed state");
    }

    /**
     * Every read surface must equal the model: total size, the full scan, and each single-subject
     * pattern.
     */
    private void assertReads(
            RepositoryConnection conn,
            Set<Statement> expected,
            long seed,
            int round,
            String where) {
        String at = " [seed " + seed + " round " + round + " " + where + "]";
        assertEquals(expected.size(), conn.size(), "size" + at);
        assertEquals(
                keys(expected),
                drainKeys(conn.getStatements(null, null, null, false)),
                "getStatements(*,*,*)" + at);
        for (IRI s : S) {
            Set<String> exp =
                    expected.stream()
                            .filter(st -> st.getSubject().equals(s))
                            .map(this::key)
                            .collect(Collectors.toSet());
            assertEquals(
                    exp,
                    drainKeys(conn.getStatements(s, null, null, false)),
                    "getStatements(" + s + ",*,*)" + at);
        }
    }

    private Statement randomStmt(SplittableRandom rnd) {
        return VF.createStatement(
                S[rnd.nextInt(S.length)], P[rnd.nextInt(P.length)], O[rnd.nextInt(O.length)]);
    }

    /**
     * Normalize to a context-free {@code s p o} key so the oracle and the Sail's returned
     * statements compare regardless of any representational difference.
     */
    private String key(Statement st) {
        return st.getSubject().stringValue()
                + " "
                + st.getPredicate().stringValue()
                + " "
                + st.getObject().stringValue();
    }

    private Set<String> keys(Set<Statement> stmts) {
        return stmts.stream().map(this::key).collect(Collectors.toSet());
    }

    private Set<String> drainKeys(CloseableIteration<? extends Statement> it) {
        Set<String> out = new HashSet<>();
        try (it) {
            while (it.hasNext()) out.add(key(it.next()));
        }
        return out;
    }

    private static void deleteTree(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
        }
    }
}
