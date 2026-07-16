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

import com.dolthub.prolly.NodeStore;
import java.lang.foreign.MemorySegment;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A counting/timing {@link NodeStore} decorator — <b>Layer C</b> of the write-path throughput probe
 * (plans/prolly-bulk-load.md). It wraps the real store and tallies every node {@code read}/{@code
 * write}: call count, wall-nanoseconds, and bytes (plus empty-read misses). The whole point is to
 * answer the crux question the phase timers cannot — when commit time grows super-linearly with the
 * store size, is it because the build issues <em>more</em> node reads (count grows) or because each
 * read gets <em>slower</em> (nanos/read grows as the never-GC'd RocksDB store bloats)? Snapshotting
 * the counters around {@code conn.commit()} also splits commit-phase node I/O from encode-phase.
 *
 * <p>Bench-only and thread-safe via {@link AtomicLong} — it is NOT wired into any production path,
 * so the {@code System.nanoTime()} per node op (which would be a real hot-path tax) never touches
 * the server. Reads/writes still hit the wrapped store's {@link com.dolthub.prolly.NodeCache}
 * normally; the cache hit/miss split comes from the cache's own stats, this from the store's total.
 */
public final class CountingNodeStore implements NodeStore {
    private final NodeStore delegate;
    private final AtomicLong readCount = new AtomicLong();
    private final AtomicLong readNanos = new AtomicLong();
    private final AtomicLong readBytes = new AtomicLong();
    private final AtomicLong readMisses = new AtomicLong();
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong writeNanos = new AtomicLong();
    private final AtomicLong writeBytes = new AtomicLong();

    public CountingNodeStore(NodeStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<MemorySegment> read(byte[] hash) {
        long t = System.nanoTime();
        Optional<MemorySegment> r = delegate.read(hash);
        readNanos.addAndGet(System.nanoTime() - t);
        readCount.incrementAndGet();
        if (r.isPresent()) readBytes.addAndGet(r.get().byteSize());
        else readMisses.incrementAndGet();
        return r;
    }

    @Override
    public byte[] write(MemorySegment data) {
        long t = System.nanoTime();
        byte[] h = delegate.write(data);
        writeNanos.addAndGet(System.nanoTime() - t);
        writeCount.incrementAndGet();
        writeBytes.addAndGet(data.byteSize());
        return h;
    }

    @Override
    public byte[] write(byte[] data) {
        long t = System.nanoTime();
        byte[] h = delegate.write(data);
        writeNanos.addAndGet(System.nanoTime() - t);
        writeCount.incrementAndGet();
        writeBytes.addAndGet(data.length);
        return h;
    }

    @Override
    public void beginWriteBatch() {
        delegate.beginWriteBatch();
    }

    @Override
    public void endWriteBatch() {
        delegate.endWriteBatch();
    }

    public long readCount() {
        return readCount.get();
    }

    public long readNanos() {
        return readNanos.get();
    }

    public long readBytes() {
        return readBytes.get();
    }

    public long readMisses() {
        return readMisses.get();
    }

    public long writeCount() {
        return writeCount.get();
    }

    public long writeNanos() {
        return writeNanos.get();
    }

    public long writeBytes() {
        return writeBytes.get();
    }
}
