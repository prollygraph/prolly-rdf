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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Property-level strengthening of {@link BufferPoolWritePathScopeParityTest} (Step 5 of {@code
 * plans/prepublic/production-primitive-parity-gate.md}): the write-path scope discipline that
 * bounds off-heap holds under <b>random</b> write workloads — varied transaction counts, sizes, and
 * add/remove mix — over <b>both</b> {@link HeapBufferPool} (production) and {@link
 * DirectBufferPool}.
 *
 * <p><b>Why a property on top of the parameterized test.</b> The fixed test pins the invariant for
 * one shape (10 commits × 2000 adds). A leak could hide in a shape it does not probe — many tiny
 * commits, remove-heavy mixes, one huge commit. This generates those shapes and asserts the
 * resource invariant survives all of them. <b>Same-workload parity:</b> each example runs the
 * identical workload through Heap and Direct and asserts <em>identical</em> scope accounting — the
 * discipline is pool-agnostic by design ({@code ProllySailConnection.forkTables()}), so any
 * per-pool divergence is the leak's signature.
 *
 * <p><b>The invariant (any workload).</b> Every per-transaction scope opened is closed
 * (created==closed — no leaked arena); scopes never pile up (peakLive≤2 — measured peak 1 — bounded
 * off-heap); and the shared pool is borrowed only for one-time Sail-level scratch — a <b>measured
 * constant of 15 borrows across every shape probed (totalOps 1..1018)</b>, never per statement (a
 * per-statement leak would make {@code sharedBorrows} scale with the statement count, ≈ totalOps).
 * Bounded tries per the project's measure-gated discipline — this complements, does not replace,
 * the deterministic parameterized test (each try builds two real RocksDB stores).
 *
 * <p><i>Out of scope:</i> empty transactions — every generated transaction has ≥1 op, so
 * created≥transactions holds cleanly.
 */
class BufferPoolWritePathScopeProperty {

    @Property(tries = 12)
    void scopeDisciplineHoldsAcrossPoolsForAnyWorkload(
            @ForAll("workloads") List<List<int[]>> workload) throws Exception {
        int txns = workload.size();
        long totalOps = workload.stream().mapToLong(List::size).sum();

        Accounting heap = run(HeapBufferPool::new, workload);
        Accounting direct = run(DirectBufferPool::new, workload);

        assertInvariant("HeapBufferPool (production)", heap, txns, totalOps);
        assertInvariant("DirectBufferPool", direct, txns, totalOps);

        // Same-workload parity: the scope discipline is pool-agnostic, so the accounting is
        // identical.
        assertEquals(heap.created, direct.created, "scopesCreated must be identical across pools");
        assertEquals(heap.closed, direct.closed, "scopesClosed must be identical across pools");
        assertEquals(
                heap.peakLive, direct.peakLive, "peakLiveScopes must be identical across pools");
    }

    private static void assertInvariant(String pool, Accounting a, int txns, long totalOps) {
        assertEquals(
                a.created,
                a.closed,
                pool
                        + ": every per-transaction scope must be closed — created="
                        + a.created
                        + " closed="
                        + a.closed);
        assertTrue(
                a.created >= txns,
                pool + ": at least one scope per commit — created=" + a.created + " txns=" + txns);
        assertTrue(
                a.peakLive <= 2,
                pool + ": per-transaction scopes must not pile up — peakLive=" + a.peakLive);
        // Shared borrows are one-time Sail-level scratch — a MEASURED CONSTANT (15 across every
        // shape
        // probed, totalOps 1..1018), NOT scaling with the workload. A per-statement (or per-commit)
        // leak would make this grow with the statement/commit count (≈ totalOps); the flat bound
        // (4×
        // headroom over the measured 15) catches that while staying robust to minor variation. The
        // generator reaches totalOps well past this bound, so a per-statement leak would fail here.
        assertTrue(
                a.sharedBorrows < 64,
                pool
                        + ": shared-pool borrows must be a small constant (Sail scratch), NOT"
                        + " workload-scaling — sharedBorrows="
                        + a.sharedBorrows
                        + " (totalOps="
                        + totalOps
                        + ")");
    }

    private Accounting run(Supplier<BufferPool> factory, List<List<int[]>> workload)
            throws Exception {
        Path dir = Files.createTempDirectory("pool-prop-");
        ScopeTrackingPool pool = new ScopeTrackingPool(factory.get());
        ProllySail sail = new ProllySail(new RocksNodeStore(dir.resolve("rocks").toString()), pool);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = conn.getValueFactory();
                for (List<int[]> txn : workload) {
                    conn.begin();
                    for (int[] op : txn) {
                        IRI s = vf.createIRI("urn:s/" + op[1]);
                        if (op[0] == 1) {
                            conn.remove(s, null, null); // remove-by-subject (no-op if absent)
                        } else {
                            conn.add(
                                    s,
                                    vf.createIRI("urn:p/" + (op[1] % 8)),
                                    vf.createIRI("urn:o/" + op[1]));
                        }
                    }
                    conn.commit();
                }
            }
            return new Accounting(
                    pool.scopesCreated.get(),
                    pool.scopesClosed.get(),
                    pool.peakLiveScopes.get(),
                    pool.sharedBorrows.get());
        } finally {
            repo.shutDown();
            pool.close();
            deleteTree(dir);
        }
    }

    @Provide
    Arbitrary<List<List<int[]>>> workloads() {
        // op = {kind, id}: kind 0=ADD (biased 3:1), 1=REMOVE-by-subject; id over a small
        // overlapping
        // space so removes hit prior adds. 1-6 transactions, each 20-200 ops — varied shapes that
        // reach totalOps well past the 64-borrow bound, so the no-leak check bites in its regime.
        Arbitrary<int[]> op =
                Combinators.combine(
                                Arbitraries.of(0, 0, 0, 1), Arbitraries.integers().between(0, 63))
                        .as((k, id) -> new int[] {k, id});
        return op.list().ofMinSize(20).ofMaxSize(200).list().ofMinSize(1).ofMaxSize(6);
    }

    private record Accounting(long created, long closed, int peakLive, long sharedBorrows) {}

    private static void deleteTree(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
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
}
