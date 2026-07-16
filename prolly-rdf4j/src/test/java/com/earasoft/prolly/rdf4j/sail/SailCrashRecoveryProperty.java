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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;

/**
 * Phase 6 Step 23 of {@code prolly-rdf4j-test-strategy.md} (S-8) — the <b>crash → recover</b>
 * property at the {@code ProllySail} layer. It generalizes the example-based, Database-layer {@code
 * CrashRecoveryAtomicityTest} (a {@code main}-method walk over one hand-built crash) to a generated
 * property: drive a stream of <i>K</i> durable commits, crash the <i>(K+1)</i>-th mid-flush,
 * reopen, and assert the recovered Sail is exactly the last <b>durable</b> commit — never a torn or
 * advanced head.
 *
 * <p><b>The invariant.</b> One commit = chunks written to the store, <i>then</i> the durable head
 * advanced (the commit-log append + the root-meta-tree persist). So a failure during the chunk
 * write must leave the durable head where it was: a reopen auto-restores to commit K, its dataset
 * equal to the union of batches 1..K, and the crashed commit's statement absent. (Orphan chunks the
 * crashed commit wrote are unreachable from K's root, so content-addressing makes them invisible to
 * the recovered read — the dataset oracle is exact.)
 *
 * <p><b>How the crash is staged (three Sails over one durable backing).</b> The {@code
 * InMemoryNodeStore} object and the on-disk sidecars ({@code RootMetaTreeStore} / {@code CommitLog}
 * / {@code RefsStore} {@code beside(dir)}) <i>are</i> the durable storage and persist across a
 * reopen (the same pattern as {@code SailAutoRestoreFaultInjectionTest}). Sail&nbsp;#1 commits
 * batches 1..K cleanly; Sail&nbsp;#2 wraps the same backing in a {@link FaultInjectingNodeStore}
 * armed to fail the first {@code STORE_WRITE} (its auto-restore is reads only, so the first write
 * is the crashing commit's flush) and its commit must throw; Sail&nbsp;#3 reopens the bare backing
 * and must read back exactly batches 1..K. This sidesteps any arithmetic over per-commit write
 * counts — the crash is always "the next commit's first write".
 */
class SailCrashRecoveryProperty {

    /**
     * A Sail over {@code backing} with sidecars on disk beside {@code dir} (the reopenable shape).
     */
    private static ProllySail sail(InMemoryNodeStore backing, Path dir) {
        return new ProllySail(
                backing,
                new HeapBufferPool(),
                RootMetaTreeStore.beside(dir),
                CommitLog.beside(dir),
                RefsStore.beside(dir));
    }

    @Property(tries = 25)
    void a_crash_during_the_next_commit_recovers_to_the_last_durable_commit(
            @ForAll @From("commitBatchSizes") List<Integer> batchSizes,
            @ForAll @IntRange(min = 1, max = 3) int crashAt)
            throws IOException {
        Path dir = Files.createTempDirectory("prolly-sail-crash");
        InMemoryNodeStore backing = new InMemoryNodeStore();
        Set<String> oracle = new HashSet<>(); // subjects of every durably-committed statement
        try {
            // ---- Phase 1: K = batchSizes.size() durable commits, no faults. ----
            ProllySail s1 = sail(backing, dir);
            s1.init();
            try {
                ValueFactory vf = s1.getValueFactory();
                IRI p = vf.createIRI("urn:p");
                IRI o = vf.createIRI("urn:o");
                for (int c = 0; c < batchSizes.size(); c++) {
                    try (SailConnection conn = s1.getConnection()) {
                        conn.begin();
                        for (int j = 0; j < batchSizes.get(c); j++) {
                            String s = "urn:s:" + c + ":" + j;
                            conn.addStatement(vf.createIRI(s), p, o);
                            oracle.add(s);
                        }
                        conn.commit();
                    }
                }
            } finally {
                s1.shutDown();
            }

            // ---- Phase 2: attempt one more commit; crash on its first store write. ----
            SailFaultInjector injector =
                    SailFaultInjector.failNth(SailFaultInjector.FaultPoint.STORE_WRITE, crashAt);
            ProllySail s2 =
                    new ProllySail(
                            new FaultInjectingNodeStore(backing, injector),
                            new HeapBufferPool(),
                            RootMetaTreeStore.beside(dir),
                            CommitLog.beside(dir),
                            RefsStore.beside(dir));
            s2.init(); // auto-restore is reads only, so the STORE_WRITE injection does not
            // fire here
            try {
                ValueFactory vf = s2.getValueFactory();
                SailConnection crashing = s2.getConnection();
                crashing.begin();
                // A batch large enough that the parallel flush does >= 3 store writes, so crashAt
                // in 1..3
                // always lands mid-flush (crashAt-1 chunks durable before the crash — partial
                // durability).
                for (int j = 0; j < 8; j++) {
                    crashing.addStatement(
                            vf.createIRI("urn:s:crash:" + j),
                            vf.createIRI("urn:p"),
                            vf.createIRI("urn:o"));
                }
                assertThrows(
                        Throwable.class,
                        crashing::commit,
                        "the injected store failure (at write #"
                                + crashAt
                                + ") must abort the (K+1)-th commit");
                try {
                    crashing.rollback();
                } catch (Exception ignored) {
                }
                try {
                    crashing.close();
                } catch (Exception ignored) {
                }
            } finally {
                s2.shutDown();
            }

            // ---- Phase 3: reopen the bare backing; the recovered dataset must be exactly 1..K.
            // ----
            ProllySail s3 = sail(backing, dir);
            s3.init();
            Set<String> recovered = new HashSet<>();
            try {
                try (SailConnection r = s3.getConnection();
                        CloseableIteration<? extends Statement> it =
                                r.getStatements(null, null, null, false)) {
                    while (it.hasNext()) {
                        recovered.add(it.next().getSubject().stringValue());
                    }
                }
            } finally {
                s3.shutDown();
            }

            assertEquals(
                    oracle,
                    recovered,
                    "recovery must restore exactly the last durable commit's dataset (batches 1.."
                            + batchSizes.size()
                            + ")");
            assertFalse(
                    recovered.stream().anyMatch(s -> s.startsWith("urn:s:crash")),
                    "no statement from the crashed (K+1)-th commit may have landed");
        } finally {
            deleteRecursively(dir);
        }
    }

    /** Batch sizes for 1..5 durable commits, each commit adding 1..4 distinct statements. */
    @Provide
    Arbitrary<List<Integer>> commitBatchSizes() {
        return Arbitraries.integers().between(1, 4).list().ofMinSize(1).ofMaxSize(5);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            pth -> {
                                try {
                                    Files.deleteIfExists(pth);
                                } catch (IOException ignored) {
                                    // best-effort temp cleanup
                                }
                            });
        }
    }
}
