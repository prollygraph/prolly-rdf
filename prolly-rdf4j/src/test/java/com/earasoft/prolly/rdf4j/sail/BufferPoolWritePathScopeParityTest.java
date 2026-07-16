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
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rule-1 parity for the buffer pool (Step 2 of {@code
 * plans/prepublic/production-primitive-parity-audit.md}): the write-path scope discipline that
 * bounds off-heap is asserted over <b>both</b> {@link HeapBufferPool} (production) and {@link
 * DirectBufferPool} — the exact divergence that hid the off-heap leak ({@code
 * bugs/direct-buffer-pool-write-path-leak.md}).
 *
 * <p><b>Why parameterize.</b> {@code DirectBufferPoolWritePathLeakTest} pins this invariant only
 * for {@code DirectBufferPool} — the <em>non-production</em> pool. Production runs {@code
 * HeapBufferPool}, and nothing exercised <em>its</em> write-path scope discipline. CLAUDE.md's
 * "test the production primitive — rule 1: parameterize over both" exists precisely because that
 * gap is how the leak hid. This test closes it: one method, both pools, the same invariant.
 *
 * <p><b>The invariant is pool-agnostic by design.</b> Per-transaction scoping lives in {@code
 * ProllySailConnection.forkTables()} (open a scope at begin/fork, free it at the next fork /
 * close), not in the pool — for {@code HeapBufferPool} the scope is the pool itself with a no-op
 * {@code close()}; for {@code DirectBufferPool} it is a fresh child arena freed per transaction. So
 * the same four assertions hold for both: scopes opened are closed, scopes do not pile up, and the
 * shared pool is borrowed only for the constant Sail-level scratch — never per statement (the
 * leak's signature).
 */
class BufferPoolWritePathScopeParityTest {

    static Stream<Arguments> pools() {
        return Stream.of(
                Arguments.of(
                        "HeapBufferPool (production)", (Supplier<BufferPool>) HeapBufferPool::new),
                Arguments.of("DirectBufferPool", (Supplier<BufferPool>) DirectBufferPool::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pools")
    void writePathScopeDisciplineHoldsForBothPools(String name, Supplier<BufferPool> poolFactory)
            throws Exception {
        int rounds = 10;
        int perRound = 2000;

        Path dir = Files.createTempDirectory("pool-parity-");
        ScopeTrackingPool pool = new ScopeTrackingPool(poolFactory.get());
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
            long created = pool.scopesCreated.get();
            long closed = pool.scopesClosed.get();
            assertTrue(
                    created >= rounds,
                    name
                            + ": expected >= one per-transaction scope per commit; created="
                            + created);
            assertEquals(
                    created,
                    closed,
                    name
                            + ": every per-transaction scope must be CLOSED after the connection"
                            + " closes (off-heap arena freed) — created="
                            + created
                            + " closed="
                            + closed);
            assertTrue(
                    pool.peakLiveScopes.get() <= 2,
                    name
                            + ": per-transaction scopes must not pile up (bounded off-heap) — peakLive="
                            + pool.peakLiveScopes.get());
            long sharedBorrows = pool.sharedBorrows.get();
            assertTrue(
                    sharedBorrows < perRound,
                    name
                            + ": shared-pool borrows must be bounded Sail-level scratch, NOT scale"
                            + " per-statement (a per-statement leak would be >= rounds*perRound="
                            + ((long) rounds * perRound)
                            + "). sharedBorrows="
                            + sharedBorrows);
        } finally {
            repo.shutDown();
            pool.close();
            deleteTree(dir);
        }
    }

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
