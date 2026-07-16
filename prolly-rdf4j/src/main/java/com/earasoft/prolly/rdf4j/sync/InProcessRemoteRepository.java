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
package com.earasoft.prolly.rdf4j.sync;

import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An in-process {@link RemoteRepository} wrapping another {@link ProllySail} — the test (and
 * same-JVM) transport. The HTTP implementation (plan Step 13) runs the identical pack walk on the
 * far side of a wire; this one runs it directly via {@link PackBuilder}.
 *
 * <p>{@link #setLatency} injects a per-call delay so latency-sensitive behaviour can be exercised
 * without a network — the same simulation {@code prolly-rdf}'s {@code RemoteNodeStoreClient}
 * provides at the {@code NodeStore} level.
 */
public final class InProcessRemoteRepository implements RemoteRepository {

    private final ProllySail remote;
    private volatile long latencyMs;

    public InProcessRemoteRepository(ProllySail remote) {
        this.remote = Objects.requireNonNull(remote, "remote");
    }

    /** Inject a simulated per-call network delay, in milliseconds (0 = none). */
    public void setLatency(long ms) {
        this.latencyMs = Math.max(0, ms);
    }

    @Override
    public Map<String, byte[]> advertiseRefs() throws IOException {
        simulateLatency();
        return refs().list();
    }

    @Override
    public SyncPack fetchPack(byte[] want, Collection<byte[]> have) throws IOException {
        return fetchPack(want, have, java.util.Set.of());
    }

    /**
     * Graph-aware fetch — delegates to the 5-arg {@link PackBuilder#build} overload (Step 3 of
     * plans/auth-graph-syncpack-filter.md). Used by {@code SyncController} when the host runs
     * {@code auth.backend=sparql} to omit auth-graph CSPO leaves from the pack by default.
     *
     * <p>Not on the {@link RemoteRepository} interface — that stays the protocol surface and any
     * RemoteRepository implementation that doesn't host its own local data (HttpRemoteRepository,
     * the future gRPC client) has no filter to apply. The in-process variant is the only sender.
     */
    public SyncPack fetchPack(
            byte[] want, Collection<byte[]> have, java.util.Set<Long> excludedContextTermIds)
            throws IOException {
        simulateLatency();
        return PackBuilder.build(remote.store(), commitLog(), want, have, excludedContextTermIds);
    }

    /**
     * Expose the underlying {@link ProllySail} for adapters that need dictionary access — e.g.
     * {@link GraphIriResolver}. Not part of the {@link RemoteRepository} interface.
     */
    public ProllySail sail() {
        return remote;
    }

    @Override
    public void receivePack(SyncPack pack) throws IOException {
        simulateLatency();
        NodeStore store = remote.store();
        for (byte[] chunk : pack.chunks()) {
            store.write(chunk); // content-addressed: re-hashed to its own address, idempotent
        }
        CommitLogSync.mergeInto(commitLog(), pack.commits());
    }

    @Override
    public boolean compareAndSetRef(String branch, byte @Nullable [] expected, byte[] desired)
            throws IOException {
        simulateLatency();
        if (desired == null) {
            throw new IllegalArgumentException("desired must not be null");
        }
        // Atomic via RefsStore.compareAndSet (plan Step 21) — single CAS, no
        // TOCTOU window between the read and the swap. Concurrent pushes
        // racing on the same branch now serialize to exactly one winner.
        return refs().compareAndSet(branch, expected, desired);
    }

    private RefsStore refs() {
        return remote.refsStore()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "remote ProllySail has no RefsStore configured"));
    }

    private CommitLog commitLog() {
        return remote.commitLog()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "remote ProllySail has no CommitLog configured"));
    }

    private void simulateLatency() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
