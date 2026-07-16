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

import com.earasoft.prolly.rdf4j.concurrency.ConcurrencyHarness;
import java.time.Duration;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Concurrency stress / fuzz tests for the {@code ProllySail} write lock (plan 11, Phase D).
 *
 * <ul>
 *   <li>{@link #mixedWorkloadStress_keepsStoreConsistentAndLockFree} — a multi-threaded soak of
 *       writers (random commit/rollback) and pure readers, cross-checked against an oracle of
 *       committed statements.
 *   <li>{@link #lockLifecycleFuzzer_neverLeaksTheWriteLock} — random connection lifecycles whose
 *       individual calls are dispatched onto <em>random</em> threads, asserting the write lock
 *       never leaks.
 * </ul>
 */
class ProllySailConcurrencyStressTest {

    @Test
    void mixedWorkloadStress_keepsStoreConsistentAndLockFree() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = vf.createIRI("http://example.org/p");
            IRI o = vf.createIRI("http://example.org/o");
            ConcurrencyHarness.MutexProbe probe = new ConcurrencyHarness.MutexProbe();
            Set<String> committed = ConcurrentHashMap.newKeySet();

            int threads = 6;
            int itersPerThread = 60;
            ConcurrencyHarness.runConcurrent(
                    threads,
                    Duration.ofSeconds(60),
                    idx -> {
                        Random rnd = new Random(1000L + idx);
                        for (int r = 0; r < itersPerThread; r++) {
                            int dice = rnd.nextInt(10);
                            if (dice < 2) {
                                // Pure reader — runs concurrently with writers, takes
                                // no write lock; must not throw (a C5 publication race
                                // would surface here).
                                try (SailConnection c = sail.getConnection();
                                        CloseableIteration<? extends Statement> it =
                                                c.getStatements(null, null, null, false)) {
                                    while (it.hasNext()) {
                                        it.next();
                                    }
                                }
                            } else {
                                // Writer — add a unique statement, randomly commit or
                                // roll back. Unique subjects ⇒ the committed set is an
                                // exact oracle for the final store contents.
                                String subj = "http://example.org/s/" + idx + "/" + r;
                                try (SailConnection c = sail.getConnection()) {
                                    c.begin();
                                    probe.enter();
                                    c.addStatement(vf.createIRI(subj), p, o);
                                    probe.exit();
                                    if (dice < 8) {
                                        c.commit();
                                        committed.add(subj);
                                    } else {
                                        c.rollback();
                                    }
                                }
                            }
                        }
                    });

            assertEquals(
                    1, probe.maxObserved(), "writers must stay serialized through the whole soak");
            assertEquals(
                    1, sail.writeLockAvailablePermits(), "write lock must be free after the soak");

            Set<String> inStore = new HashSet<>();
            try (SailConnection c = sail.getConnection();
                    CloseableIteration<? extends Statement> it =
                            c.getStatements(null, null, null, false)) {
                while (it.hasNext()) {
                    inStore.add(it.next().getSubject().stringValue());
                }
            }
            assertEquals(
                    committed,
                    inStore,
                    "store must contain exactly the committed statements — no lost "
                            + "or phantom writes under concurrency");
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void lockLifecycleFuzzer_neverLeaksTheWriteLock() throws Exception {
        ProllySail sail = new ProllySail();
        sail.init();
        ExecutorService dispatch = Executors.newFixedThreadPool(4);
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = vf.createIRI("http://example.org/p");
            IRI o = vf.createIRI("http://example.org/o");
            Random rnd = new Random(42);

            for (int trial = 0; trial < 250; trial++) {
                // Each lifecycle call runs on a random pool thread — begin may
                // land on a different thread than commit/rollback/close, the
                // cross-thread pattern that exposed the original leak.
                SailConnection conn = onRandomThread(dispatch, sail::getConnection);
                boolean begun = rnd.nextBoolean();
                if (begun) {
                    onRandomThread(
                            dispatch,
                            () -> {
                                conn.begin();
                                return null;
                            });
                    if (rnd.nextBoolean()) {
                        String s = "http://example.org/s/" + trial;
                        onRandomThread(
                                dispatch,
                                () -> {
                                    conn.addStatement(vf.createIRI(s), p, o);
                                    return null;
                                });
                    }
                    int finish = rnd.nextInt(3); // 0 commit · 1 rollback · 2 neither
                    if (finish == 0) {
                        onRandomThread(
                                dispatch,
                                () -> {
                                    conn.commit();
                                    return null;
                                });
                    } else if (finish == 1) {
                        onRandomThread(
                                dispatch,
                                () -> {
                                    conn.rollback();
                                    return null;
                                });
                    }
                }
                // Close — possibly without a commit/rollback (the leak-prone path).
                onRandomThread(
                        dispatch,
                        () -> {
                            conn.close();
                            return null;
                        });

                assertEquals(
                        1,
                        sail.writeLockAvailablePermits(),
                        "trial " + trial + " leaked the write lock (begun=" + begun + ")");
            }
        } finally {
            dispatch.shutdownNow();
            sail.shutDown();
        }
    }

    /** Run {@code call} on a pool thread and wait for it — sequential, but cross-thread. */
    private static <T> T onRandomThread(ExecutorService ex, Callable<T> call) throws Exception {
        return ex.submit(call).get(10, TimeUnit.SECONDS);
    }
}
