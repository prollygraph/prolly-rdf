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

import com.dolthub.prolly.BufferPool;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test instrument: wraps a supplied underlying {@link BufferPool} (Heap or Direct) and tracks the
 * per-transaction scope discipline — scopes opened / closed / peak-live, and top-level ("shared")
 * borrows. Delegates all real work to the underlying pool so the write path runs unchanged on
 * whichever primitive.
 *
 * <p>Shared by {@link BufferPoolWritePathScopeParityTest} (fixed workload) and {@link
 * BufferPoolWritePathScopeProperty} (random workloads) so both measure the scope discipline with
 * the <em>same</em> instrument — the off-heap-leak net across production {@code HeapBufferPool} and
 * {@code DirectBufferPool}, the divergence that hid {@code
 * bugs/direct-buffer-pool-write-path-leak.md}. The per-transaction scope itself lives in {@code
 * ProllySailConnection.forkTables()}, not the pool: for {@code HeapBufferPool} the scope is the
 * pool with a no-op {@code close()}; for {@code DirectBufferPool} it is a fresh child arena freed
 * per transaction. So the same accounting holds for both, and any divergence is the leak's
 * signature.
 */
final class ScopeTrackingPool implements BufferPool {
    final AtomicInteger scopesCreated = new AtomicInteger();
    final AtomicInteger scopesClosed = new AtomicInteger();
    final AtomicInteger liveScopes = new AtomicInteger();
    final AtomicInteger peakLiveScopes = new AtomicInteger();
    final AtomicLong sharedBorrows = new AtomicLong();
    private final BufferPool underlying;

    ScopeTrackingPool(BufferPool underlying) {
        this.underlying = underlying;
    }

    @Override
    public MemorySegment borrow(int size) {
        sharedBorrows.incrementAndGet();
        return underlying.borrow(size);
    }

    // Forwarded, not defaulted: the interface default routes borrowRetained
    // through THIS decorator's borrow -> underlying.borrow, silently swapping
    // the production pool's exact-size retained allocation for the bucketed
    // scratch path — the parity instrument would then exercise a non-production
    // shape, the precise failure mode the parity registry exists to prevent.
    @Override
    public MemorySegment borrowRetained(int size) {
        sharedBorrows.incrementAndGet();
        return underlying.borrowRetained(size);
    }

    @Override
    public BufferPool newTransactionScope() {
        scopesCreated.incrementAndGet();
        int live = liveScopes.incrementAndGet();
        peakLiveScopes.accumulateAndGet(live, Math::max);
        BufferPool child = underlying.newTransactionScope();
        return new BufferPool() {
            @Override
            public MemorySegment borrow(int size) {
                return child.borrow(size);
            }

            @Override
            public MemorySegment borrowRetained(int size) {
                return child.borrowRetained(size);
            }

            @Override
            public void release(MemorySegment segment) {
                child.release(segment);
            }

            @Override
            public void close() {
                child.close();
                scopesClosed.incrementAndGet();
                liveScopes.decrementAndGet();
            }
        };
    }

    @Override
    public void close() {
        underlying.close();
    }
}
