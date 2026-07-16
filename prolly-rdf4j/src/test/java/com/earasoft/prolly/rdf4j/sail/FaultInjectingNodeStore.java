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

import com.dolthub.prolly.NodeStore;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Phase 6 Step 22 of {@code prolly-rdf4j-test-strategy.md} (S-8) — the {@link NodeStore} seam that
 * turns a {@link SailFaultInjector} decision into a thrown storage failure. It wraps a real {@code
 * NodeStore} and, before delegating, asks the injector whether <i>this</i> operation should fail
 * ({@link SailFaultInjector.FaultPoint#STORE_WRITE} on writes — the commit-flush path; {@link
 * SailFaultInjector.FaultPoint#STORE_READ} on reads — the auto-restore path).
 *
 * <p>This is the seeded successor to {@code ErrorInjectingNodeStore}'s bare countdown: same
 * decorator shape, but the decision (and its recording, for replay) lives in the injector. The
 * thrown message is the literal {@value #INJECTED} so the migrated fault tests' cause-chain
 * assertions are unchanged.
 *
 * <p>The other two fault points ({@code COMMIT_LOG_APPEND}, {@code ROOT_META_PERSIST}) have no
 * decorator here because their backing classes are {@code final} — see {@link SailFaultInjector}'s
 * class note.
 */
public final class FaultInjectingNodeStore implements NodeStore {

    /** The failure message; matches the cause-chain check in the migrated fault tests. */
    public static final String INJECTED = "Injected IO Failure";

    private final NodeStore inner;
    private final SailFaultInjector injector;

    public FaultInjectingNodeStore(NodeStore inner, SailFaultInjector injector) {
        this.inner = inner;
        this.injector = injector;
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        if (injector.shouldFail(SailFaultInjector.FaultPoint.STORE_READ)) {
            throw new RuntimeException(INJECTED);
        }
        return inner.read(hash);
    }

    @Override
    public byte[] write(MemorySegment data) {
        if (injector.shouldFail(SailFaultInjector.FaultPoint.STORE_WRITE)) {
            throw new RuntimeException(INJECTED);
        }
        return inner.write(data);
    }

    @Override
    public byte[] write(byte[] data) {
        if (injector.shouldFail(SailFaultInjector.FaultPoint.STORE_WRITE)) {
            throw new RuntimeException(INJECTED);
        }
        return inner.write(data);
    }

    // Delegate the write-batch lifecycle so the decorator is fully transparent under none()
    // (a no-op default would silently change the inner store's batching behaviour).
    @Override
    public void beginWriteBatch() {
        inner.beginWriteBatch();
    }

    @Override
    public void endWriteBatch() {
        inner.endWriteBatch();
    }
}
