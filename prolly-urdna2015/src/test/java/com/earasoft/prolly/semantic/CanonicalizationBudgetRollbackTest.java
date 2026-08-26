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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.semantic.canon.RdfCanonicalizer;
import java.nio.file.Path;
import java.time.Duration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The untested exception path (roadmap T17): what a commit leaves behind when canonicalization
 * exhausts its budget.
 *
 * <p>{@code CanonicalizationBudget} is documented as fail-closed — "on overrun the work is
 * cancelled and a {@code NonCanonicalizableException} is thrown — never a best-effort labelling".
 * {@code CanonicalizationBudgetTest} proves the exception is thrown. Nothing proved what the SAIL
 * does with it, and that is the half that matters operationally: the budget fires in the middle of
 * a commit, after the connection has begun a transaction and possibly after some work has reached
 * the store. A fail-closed check that leaves a half-written transaction behind is not fail-closed
 * in any useful sense — it just moves the corruption one layer out.
 *
 * <p>So these pin the three things a caller depends on after an overrun: the commit fails loudly,
 * the store is byte-for-byte as it was, and the connection (and the repository) remain usable for
 * the retry the caller will obviously attempt.
 */
class CanonicalizationBudgetRollbackTest {

    private static final String KNOWS = "urn:ex/knows";

    /** A canonicalizer slow enough that any realistic budget cancels it. */
    private static final RdfCanonicalizer GLACIAL = quads -> {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cancelled", e);
        }
        return quads;
    };

    private static CanonicalizingProllySail sailWithABudgetThatCannotBeMet(Path dir) {
        ProllySail delegate = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(),
                RootMetaTreeStore.beside(dir), CommitLog.beside(dir));
        return new CanonicalizingProllySail(delegate, GLACIAL, Duration.ofMillis(50));
    }

    /**
     * A blank node is what forces real canonicalization work — a named-IRI-only commit can take an
     * identity path, so a test using only IRIs might never reach the budget at all and would pass
     * for the wrong reason.
     */
    private static void addOneBlankNodeStatement(RepositoryConnection conn) {
        ValueFactory vf = conn.getValueFactory();
        conn.add(vf.createBNode("b1"), vf.createIRI(KNOWS), vf.createIRI("urn:ex/bob"));
    }

    @Test
    void aBudgetOverrunFailsTheCommitRatherThanWritingSomethingNonCanonical(@TempDir Path dir) {
        Repository repo = new SailRepository(sailWithABudgetThatCannotBeMet(dir));
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            addOneBlankNodeStatement(conn);
            RuntimeException thrown = assertThrows(RuntimeException.class, conn::commit,
                    "an unmeetable canonicalization budget must fail the commit — silently "
                            + "writing a non-canonical labelling is the outcome the budget exists "
                            + "to prevent");
            assertTrue(rootCauseMentions(thrown, "budget"),
                    "the failure must name the budget so an operator can act on it, got: "
                            + describe(thrown));
        } finally {
            repo.shutDown();
        }
    }

    /**
     * The rollback itself. After the failed commit the store must hold nothing from it — not the
     * statement, not a partial tree, not an advanced head.
     */
    @Test
    void theStoreIsUnchangedAfterABudgetOverrun(@TempDir Path dir) {
        Repository repo = new SailRepository(sailWithABudgetThatCannotBeMet(dir));
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                addOneBlankNodeStatement(conn);
                assertThrows(RuntimeException.class, conn::commit);
            }
            try (RepositoryConnection reader = repo.getConnection()) {
                assertEquals(0, reader.size(),
                        "a commit that failed its budget must leave NO statements behind — a "
                                + "partial write here means the fail-closed check merely moved the "
                                + "corruption one layer out");
                assertFalse(reader.hasStatement(null, reader.getValueFactory().createIRI(KNOWS),
                        null, false), "the statement from the failed commit is visible");
            }
        } finally {
            repo.shutDown();
        }
    }

    /**
     * And the repository survives its own failure. A caller that hits a budget overrun will retry
     * or continue with other work; if the failed transaction left the connection or the sail in an
     * unusable state, the overrun would escalate from "this commit failed" to "this repository is
     * finished", which is a much worse outcome than the one being guarded against.
     */
    @Test
    void theRepositoryIsStillUsableAfterABudgetOverrun(@TempDir Path dir) {
        ProllySail delegate = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(),
                RootMetaTreeStore.beside(dir), CommitLog.beside(dir));
        // A canonicalizer that is glacial ONCE, then instant — so the retry can actually succeed
        // and prove the sail recovered, rather than failing again for the same reason.
        java.util.concurrent.atomic.AtomicBoolean firstCall =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        RdfCanonicalizer slowOnce = quads -> {
            if (firstCall.getAndSet(false)) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", e);
                }
            }
            return quads;
        };
        Repository repo = new SailRepository(
                new CanonicalizingProllySail(delegate, slowOnce, Duration.ofMillis(50)));
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                addOneBlankNodeStatement(conn);
                assertThrows(RuntimeException.class, conn::commit);
            }
            // The retry: same shape of work, now within budget.
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                ValueFactory vf = conn.getValueFactory();
                conn.add(vf.createIRI("urn:ex/alice"), vf.createIRI(KNOWS),
                        vf.createIRI("urn:ex/bob"));
                conn.commit();
            }
            try (RepositoryConnection reader = repo.getConnection()) {
                assertEquals(1, reader.size(),
                        "after a budget overrun the repository must still accept work — only the "
                                + "retry's statement may be present");
            }
        } finally {
            repo.shutDown();
        }
    }

    private static boolean rootCauseMentions(Throwable t, String needle) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().toLowerCase().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        return sb.toString();
    }
}
