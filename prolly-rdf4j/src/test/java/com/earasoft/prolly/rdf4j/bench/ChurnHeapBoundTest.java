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
package com.earasoft.prolly.rdf4j.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Random;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of {@code plans/versioning-churn-no-oom.md} — the scattered-churn bounded-heap no-OOM pin
 * (Goal 2), and an attribution-by-elimination for Goal 1.
 *
 * <p><b>Why this is dev-box-valid (D-1 was over-broad).</b> The plan gated memory work on the soak
 * runner because the dev box's ZFS-ARC/swap/cgroup confounds distort <em>physical RAM / RSS /
 * native</em> — which is what the sibling full-file <em>native</em> {@code std::bad_alloc}
 * measured. But the versioning OOM is a <b>Java-heap</b> OOM (the 8&nbsp;GiB {@code -Xmx} cap,
 * retaining {@code prevReach} + Statement objects), and a Java-heap measurement is <em>not</em>
 * ARC-confounded (the ARC is off-heap; RocksDB's native memory is off-heap). This probe samples
 * <b>used Java heap after GC</b> per release, with the persistent nodes on <b>disk</b> (production
 * {@code RocksNodeStore}), so the heap reflects only the Sail's resident + per-release transient
 * state.
 *
 * <p><b>Why it removes the harness confound (attribution by elimination).</b> {@code
 * NcitVersioningBenchmark} OOM'd at 8&nbsp;GiB while retaining ~460k {@code Statement}s + a growing
 * {@code prevReach} map. This probe retains <b>neither</b>: liveness is a {@link BitSet} (one bit
 * per id, ~KB), and {@code Statement}s are regenerated transiently per release and dropped after
 * commit. If the harness-free churn keeps the heap <b>bounded</b> (plateau), the original OOM was
 * the harness, not the Sail — confirming the code-grounded verdict (the re-anchor note in the
 * plan). A real Sail-side cross-release accumulation would instead grow the heap monotonically.
 */
class ChurnHeapBoundTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI P = VF.createIRI("urn:prolly:churn:p");

    private static Statement stmt(int id) {
        return VF.createStatement(
                VF.createIRI("urn:prolly:churn:s" + id),
                P,
                VF.createIRI("urn:prolly:churn:o" + id));
    }

    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            rt.gc();
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    void scatteredChurnKeepsJavaHeapBounded() throws Exception {
        int base = 10_000;
        int releases = 30;
        int churn =
                1_000; // half added, half deleted per release — corpus stays ~base, history grows
        int half = churn / 2;

        Path dir = Files.createTempDirectory("prolly-churn-heap-");
        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();

        BitSet live = new BitSet();
        Random rnd = new Random(42);
        long[] heap = new long[releases + 1];

        try {
            // ---- base release (batched) ----
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                for (int i = 0; i < base; i++) {
                    conn.add(stmt(i));
                    live.set(i);
                    if ((i + 1) % 2_000 == 0) {
                        conn.commit();
                        conn.begin();
                    }
                }
                conn.commit();
            }
            int nextId = base;
            heap[0] = usedHeapAfterGc();
            System.out.printf("[churn-heap] base=%,d  heap=%,dKiB%n", base, heap[0] / 1024);

            // ---- scattered-churn releases ----
            for (int k = 1; k <= releases; k++) {
                int[] dels = new int[half];
                int picked = 0;
                while (picked
                        < half) { // rejection-sample distinct live ids (no full live list kept)
                    int id = rnd.nextInt(nextId);
                    if (live.get(id)) {
                        dels[picked++] = id;
                        live.clear(id); // avoid re-picking within this release
                    }
                }
                try (RepositoryConnection conn = repo.getConnection()) {
                    conn.begin();
                    for (int id : dels) conn.remove(stmt(id));
                    for (int j = 0; j < half; j++) {
                        conn.add(stmt(nextId));
                        live.set(nextId);
                        nextId++;
                    }
                    conn.commit();
                }
                heap[k] = usedHeapAfterGc();
                if (k % 5 == 0 || k == 1) {
                    System.out.printf(
                            "[churn-heap] release=%2d  corpus=%,d  nextId=%,d  heap=%,dKiB%n",
                            k, live.cardinality(), nextId, heap[k] / 1024);
                }
            }

            // Correctness (Goal 2's query-equal dimension): the churn must leave exactly the live
            // corpus — a count-equal proxy; byte-level convergence of the churned tree is covered
            // by
            // MerkleConvergenceStressTest + the fast-forward differential.
            long actual;
            try (RepositoryConnection conn = repo.getConnection()) {
                actual = conn.size();
            }
            assertEquals(
                    live.cardinality(),
                    actual,
                    "scattered churn must leave exactly the expected live corpus");
            System.out.printf("[churn-heap] final corpus verified: %,d statements%n", actual);
        } finally {
            repo.shutDown();
            store.close();
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (Exception ignore) {
                                        // best-effort temp cleanup
                                    }
                                });
            }
        }

        // Plateau check: the corpus stays ~base while history accumulates on disk, so the Java heap
        // must NOT grow with release count. A monotonic/linear climb would be a cross-release leak
        // (the OOM class). Compare a late window to an early post-warmup window; bound generously
        // to
        // tolerate GC nondeterminism while still catching an OOM-class leak.
        long earlyMin = Long.MAX_VALUE;
        for (int k = 3; k <= 8; k++) earlyMin = Math.min(earlyMin, heap[k]);
        long lateMax = 0;
        for (int k = releases - 5; k <= releases; k++) lateMax = Math.max(lateMax, heap[k]);
        System.out.printf(
                "[churn-heap] earlyMin(rel 3-8)=%,dKiB  lateMax(rel %d-%d)=%,dKiB  ratio=%.2f%n",
                earlyMin / 1024,
                releases - 5,
                releases,
                lateMax / 1024,
                (double) lateMax / earlyMin);
        assertTrue(
                lateMax < earlyMin * 2.5,
                "scattered-churn Java heap must stay bounded as history grows (no cross-release leak):"
                        + " earlyMin="
                        + earlyMin / 1024
                        + "KiB lateMax="
                        + lateMax / 1024
                        + "KiB");
    }
}
