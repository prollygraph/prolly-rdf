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
package com.earasoft.prolly.flatsail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The flatsail's cancellation behaviour (roadmap T18, reduced — see the ruling below).
 *
 * <h2>Why this class does not contain the fault-injection test the task asked for</h2>
 * T18 specified a "RocksDB exception translation" test. It is not buildable today, and the attempt
 * is worth recording because the reason is a property of the Sail rather than of the test.
 *
 * <p>{@code RocksDbFlatSailConnection} translates {@code RocksDBException} into
 * {@link org.eclipse.rdf4j.sail.SailException} at four sites, so the translation code exists and
 * is plausibly right — but nothing can reach it. {@code RocksFlatStore} holds a {@code RocksDB}
 * handle directly, with no interface between the Sail and the engine to decorate; there is no
 * flatsail equivalent of {@code ErrorInjectingNodeStore}. The one injection available from outside
 * — closing the store under a live connection — does not throw: it is undefined behaviour at the
 * JNI layer and <b>aborts the JVM</b> (verified: SIGABRT, exit 134, with an {@code hs_err} dump).
 * So the specified test cannot be written without first adding a seam to production code, which is
 * a change this task does not authorise.
 *
 * <p>The consequence is worth stating plainly rather than leaving in a plan: the flat Sail's
 * storage-failure paths are unreachable by any test, so a genuine IO error in production would be
 * the first execution those four translation sites ever get. Parked as a seam request.
 *
 * <p>What IS testable without a seam is cancellation, which is below.
 */
class RocksDbFlatSailFaultTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * An interrupted scan must terminate rather than hang. Interruption arrives from outside — a
     * cancelled query, a shutting-down pool — and the unacceptable outcome is an iteration that
     * cannot be stopped, because that pins a connection until the JVM dies. Mirrors the intent of
     * {@code ProllySailInterruptTest} for the flat Sail.
     */
    @Test
    void anInterruptedScanTerminatesRatherThanHanging(@TempDir Path dir) throws Exception {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir.resolve("db"));
        sail.init();
        try {
            try (SailConnection conn = sail.getConnection()) {
                conn.begin(IsolationLevels.NONE);
                for (int i = 0; i < 2_000; i++) {
                    conn.addStatement(VF.createIRI("urn:f#s" + i), VF.createIRI("urn:f#p"),
                            VF.createIRI("urn:f#o" + i));
                }
                conn.commit();
            }

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread scanner = new Thread(() -> {
                try (SailConnection conn = sail.getConnection();
                        var it = conn.getStatements(null, null, null, false)) {
                    Thread.currentThread().interrupt(); // cancelled mid-scan
                    while (it.hasNext()) {
                        it.next();
                    }
                } catch (RuntimeException aborted) {
                    // Completing the scan and aborting with a translated exception are BOTH
                    // acceptable — the contract pinned here is termination, not a specific
                    // outcome. Hanging is the failure, and the join below is what detects it.
                    failure.set(aborted);
                }
            }, "flatsail-scanner");
            scanner.start();
            scanner.join(30_000);

            assertEquals(Thread.State.TERMINATED, scanner.getState(),
                    "an interrupted scan must terminate within 30s — a scan that cannot be "
                            + "cancelled pins its connection until the process exits");
        } finally {
            sail.shutDown();
        }
    }

    /**
     * The interrupt must not corrupt the store: after a cancelled scan the data is intact and the
     * Sail still serves reads. A cancellation that left the connection or the store unusable would
     * turn "this query was cancelled" into "this database is finished".
     */
    @Test
    void theStoreIsStillReadableAfterAnInterruptedScan(@TempDir Path dir) throws Exception {
        RocksDbFlatSail sail = new RocksDbFlatSail(dir.resolve("db"));
        sail.init();
        try {
            try (SailConnection conn = sail.getConnection()) {
                conn.begin(IsolationLevels.NONE);
                for (int i = 0; i < 100; i++) {
                    conn.addStatement(VF.createIRI("urn:f#s" + i), VF.createIRI("urn:f#p"),
                            VF.createIRI("urn:f#o" + i));
                }
                conn.commit();
            }

            Thread scanner = new Thread(() -> {
                try (SailConnection conn = sail.getConnection();
                        var it = conn.getStatements(null, null, null, false)) {
                    Thread.currentThread().interrupt();
                    while (it.hasNext()) {
                        it.next();
                    }
                } catch (RuntimeException ignored) {
                    // see above
                }
            }, "flatsail-scanner-2");
            scanner.start();
            scanner.join(30_000);

            try (SailConnection conn = sail.getConnection();
                    var it = conn.getStatements(null, null, null, false)) {
                int seen = 0;
                while (it.hasNext()) {
                    it.next();
                    seen++;
                }
                assertEquals(100, seen,
                        "every statement must still be readable after a cancelled scan — a "
                                + "cancellation that damages the store escalates a cancelled query "
                                + "into a lost database");
            }
            assertTrue(true);
        } finally {
            sail.shutDown();
        }
    }
}
