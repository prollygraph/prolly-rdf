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
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Resource-leak regression for the {@code DirectBufferPool} off-heap leak ({@code
 * bugs/direct-buffer-pool-write-path-leak.md}). A web-Google ingest through {@code ProllySail} +
 * {@code DirectBufferPool} out-of-memoried (~5 GiB off-heap runaway) because the write path borrows
 * scratch segments and <b>never releases them</b>, and the shared pool's {@code Arena.ofShared()}
 * frees nothing until {@code close()} — so the off-heap footprint grew without bound.
 *
 * <h2>What the fix is, and what this test now pins</h2>
 *
 * <p>The fix ({@link BufferPool#newTransactionScope()}) scopes a buffer pool to one transaction:
 * {@code ProllySailConnection.forkTables()} opens a scope and frees it wholesale at the next fork
 * (begin/rollback) or at connection close. For the on-heap default ({@code HeapBufferPool},
 * production) the scope is the shared pool itself and {@code close()} is a no-op; for an
 * arena-backed {@code DirectBufferPool} the scope is a fresh child arena, freed per transaction —
 * bounding the off-heap footprint to one transaction's working set.
 *
 * <p>This asserts the <b>resource</b> invariant the leak violated, deterministically and at any
 * scale — <b>not</b> resident-set-size (flaky, garbage-collector- and output-dependent). It injects
 * a pool that hands out tracked per-transaction scopes and asserts, after many commits:
 *
 * <ul>
 *   <li><b>every scope opened is closed</b> ({@code created == closed} after the connection closes)
 *       — the off-heap arena is actually freed each transaction. The leak's signature is the
 *       opposite: scopes (or, in the pre-fix code, the one shared pool) opened and never freed.
 *   <li><b>scopes never pile up</b> (peak live ≤ 2) — bounded off-heap, not linear-in-commits.
 *   <li><b>shared-pool borrows are bounded, not per-statement</b> — the per-statement write scratch
 *       flows through the per-transaction scope, so the shared pool sees only the Sail-level {@code
 *       PrefixTable}'s one-time prefix-registration scratch (a small constant, independent of the
 *       statement count). The pre-fix code borrowed the shared pool ~1M times over 100k statements
 *       (≈ ten per statement) and never released; the leak signature is therefore borrows that
 *       scale with statements, which this asserts against.
 * </ul>
 *
 * <p><b>Gate:</b> if {@code DirectBufferPool} is ever wired as the production write pool (the
 * zero-copy goal of {@code prolly-rdf4j/plans/write-path-zero-copy.md}), this test must stay green
 * — it proves the per-transaction scoping bounds the off-heap footprint.
 */
class DirectBufferPoolWritePathLeakTest {

    @Test
    void writePath_perTransactionPoolScope_isFreedEveryCommit(@TempDir Path dir) throws Exception {
        int rounds = 20;
        int perRound = 5000;

        ScopeTrackingPool pool = new ScopeTrackingPool();
        // The exact ProllySail construction GraphIngestBench's prolly arm uses (the config that
        // OOMed at scale), but with the shared pool wrapped so the per-transaction scopes are
        // observable.
        ProllySail sail = new ProllySail(new RocksNodeStore(dir.resolve("rocks").toString()), pool);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection conn = repo.getConnection()) {
                ValueFactory vf = conn.getValueFactory();
                for (int r = 0; r < rounds; r++) {
                    conn.begin();
                    for (int i = 0; i < perRound; i++) {
                        int id = r * perRound + i;
                        conn.add(
                                vf.createIRI("urn:s/" + id),
                                vf.createIRI("urn:p/" + (id % 16)),
                                vf.createIRI("urn:o/" + id));
                    }
                    conn.commit();
                }
            }
            // The connection has closed: every per-transaction scope it opened — including the last
            // one, freed by closeInternal — must now be closed. A per-commit off-heap leak leaves
            // scopes open (created > closed); pre-fix, NO scopes were opened at all and the shared
            // pool was borrowed directly and never freed.
            long created = pool.scopesCreated.get();
            long closed = pool.scopesClosed.get();
            assertTrue(
                    created >= rounds,
                    "expected at least one per-transaction pool scope per commit; created="
                            + created
                            + " rounds="
                            + rounds);
            assertEquals(
                    created,
                    closed,
                    "every per-transaction pool scope must be CLOSED (off-heap arena freed) after the"
                            + " connection closes — created="
                            + created
                            + " closed="
                            + closed
                            + " (a per-commit leak leaves scopes open)");
            assertTrue(
                    pool.peakLiveScopes.get() <= 2,
                    "per-transaction pool scopes must not pile up (bounded off-heap, not"
                            + " linear-in-commits) — peakLive="
                            + pool.peakLiveScopes.get());
            // The shared pool sees only the Sail-level PrefixTable's one-time prefix-registration
            // scratch — a small constant, independent of statement count (all IRIs here share the
            // `urn:` prefix). A per-statement leak (the pre-fix bug) borrowed it ~ten times per
            // statement: ~1M for this 100k-statement run, i.e. >= rounds*perRound. Asserting the
            // count stays below even ONE round cleanly separates the bounded constant (~15) from
            // any
            // per-statement regression (>= 100k). NOT a magic threshold: it is the smallest
            // statement-scaling signature, an order of magnitude above the constant.
            long sharedBorrows = pool.sharedBorrows.get();
            assertTrue(
                    sharedBorrows < perRound,
                    "shared-pool borrows must be bounded (Sail-level PrefixTable scratch), NOT scale"
                            + " per-statement — a per-statement leak would be >= rounds*perRound="
                            + ((long) rounds * perRound)
                            + ". sharedBorrows="
                            + sharedBorrows);
        } finally {
            repo.shutDown();
            pool.close();
        }
    }

    /**
     * A {@link BufferPool} that hands out <i>tracked</i> per-transaction {@link DirectBufferPool}
     * children so the test can see that each transaction's scope is opened and then freed.
     * Borrowing the shared pool itself (which the fix forbids on the write path) is counted so the
     * test can assert it never happens.
     */
    private static final class ScopeTrackingPool implements BufferPool {
        final AtomicInteger scopesCreated = new AtomicInteger();
        final AtomicInteger scopesClosed = new AtomicInteger();
        final AtomicInteger liveScopes = new AtomicInteger();
        final AtomicInteger peakLiveScopes = new AtomicInteger();
        final AtomicLong sharedBorrows = new AtomicLong();
        // Real off-heap pool backing any direct borrow, so the test still functions if the shared
        // pool is (unexpectedly) borrowed; the count is what the assertion checks.
        private final DirectBufferPool shared = new DirectBufferPool();

        @Override
        public MemorySegment borrow(int size) {
            sharedBorrows.incrementAndGet();
            return shared.borrow(size);
        }

        @Override
        public BufferPool newTransactionScope() {
            scopesCreated.incrementAndGet();
            int live = liveScopes.incrementAndGet();
            peakLiveScopes.accumulateAndGet(live, Math::max);
            return new DirectBufferPool() {
                @Override
                public void close() {
                    super.close();
                    scopesClosed.incrementAndGet();
                    liveScopes.decrementAndGet();
                }
            };
        }

        @Override
        public void close() {
            shared.close();
        }
    }
}
