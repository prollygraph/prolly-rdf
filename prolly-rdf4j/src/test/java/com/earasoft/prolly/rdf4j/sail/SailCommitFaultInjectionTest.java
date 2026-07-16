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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Fault-injection coverage for the {@link ProllySailConnection} commit path.
 *
 * <p>{@code commitInternal} flushes the seven per-transaction tables in parallel on the common
 * pool, joins their futures, and unwraps any {@link java.util.concurrent.CompletionException} so a
 * table-commit failure surfaces as its own cause rather than the JDK wrapper. These tests drive a
 * real storage failure into that path by wrapping the {@code NodeStore} in a {@link
 * FaultInjectingNodeStore} driven by a {@link SailFaultInjector} (Step 22) armed to fail the first
 * {@code STORE_WRITE} — which is the commit flush's first write, since {@code init()} and {@code
 * addStatement} do not write to the store (statements buffer in the per-transaction tables). The
 * control test uses {@link SailFaultInjector#none()} to confirm the wrapper is transparent.
 */
class SailCommitFaultInjectionTest {

    private static boolean causedByInjectedFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null
                    && c.getMessage().contains(FaultInjectingNodeStore.INJECTED)) {
                return true;
            }
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static long count(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try (it) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    /** Quietly abandon a connection whose commit failed. */
    private static void discard(SailConnection conn) {
        try {
            conn.rollback();
        } catch (Exception ignored) {
        }
        try {
            conn.close();
        } catch (Exception ignored) {
        }
    }

    /** A Sail whose store fails the first {@code STORE_WRITE} — the commit flush's first write. */
    private static ProllySail sailFailingFirstWrite() {
        SailFaultInjector injector =
                SailFaultInjector.failNth(SailFaultInjector.FaultPoint.STORE_WRITE, 1);
        return new ProllySail(
                new FaultInjectingNodeStore(new InMemoryNodeStore(), injector),
                new HeapBufferPool());
    }

    @Test
    void commit_surfaces_an_injected_store_failure() {
        ProllySail sail = sailFailingFirstWrite();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            SailConnection conn = sail.getConnection();
            conn.begin();
            for (int i = 0; i < 100; i++) {
                conn.addStatement(
                        vf.createIRI("urn:s:" + i),
                        vf.createIRI("urn:p"),
                        vf.createIRI("urn:o:" + i));
            }
            // The injector is pre-armed to fail the first STORE_WRITE; that write is the commit
            // flush's.
            Throwable ex =
                    assertThrows(
                            Throwable.class,
                            conn::commit,
                            "a storage failure during the parallel table commit must abort commit");
            assertTrue(
                    causedByInjectedFailure(ex),
                    "the injected failure must surface in the commit exception chain, got: " + ex);
            discard(conn);
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void write_lock_is_released_after_a_failed_commit() {
        // commitInternal releases the write lock in a finally block — so even
        // after a mid-commit failure a fresh connection must be able to commit.
        ProllySail sail = sailFailingFirstWrite();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();

            // First commit fails mid-flight (its first store write trips failNth(STORE_WRITE, 1)).
            SailConnection failing = sail.getConnection();
            failing.begin();
            failing.addStatement(
                    vf.createIRI("urn:a"), vf.createIRI("urn:p"), vf.createIRI("urn:b"));
            assertThrows(Throwable.class, failing::commit);
            discard(failing);

            // failNth fires once and only on the 1st STORE_WRITE — so the recovery commit's writes
            // (the 2nd onward) do not fail; a fresh connection must acquire the write lock and
            // commit.
            try (SailConnection ok = sail.getConnection()) {
                ok.begin();
                ok.addStatement(
                        vf.createIRI("urn:c"), vf.createIRI("urn:p"), vf.createIRI("urn:d"));
                ok.commit();
            }

            // The cleanly-committed statement is readable.
            try (SailConnection reader = sail.getConnection()) {
                long n = count(reader.getStatements(vf.createIRI("urn:c"), null, null, false));
                assertEquals(1, n, "the post-recovery commit's data must be present");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void clean_commit_without_injection_succeeds_through_the_wrapper() {
        // Control: the FaultInjectingNodeStore wrapper under SailFaultInjector.none(), must be
        // fully
        // transparent — so the failure tests above isolate the injection.
        ProllySail sail =
                new ProllySail(
                        new FaultInjectingNodeStore(
                                new InMemoryNodeStore(), SailFaultInjector.none()),
                        new HeapBufferPool());
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                for (int i = 0; i < 50; i++) {
                    conn.addStatement(
                            vf.createIRI("urn:s:" + i),
                            vf.createIRI("urn:p"),
                            vf.createIRI("urn:o:" + i));
                }
                conn.commit();
            }
            try (SailConnection reader = sail.getConnection()) {
                assertEquals(50, count(reader.getStatements(null, null, null, false)));
            }
        } finally {
            sail.shutDown();
        }
    }
}
