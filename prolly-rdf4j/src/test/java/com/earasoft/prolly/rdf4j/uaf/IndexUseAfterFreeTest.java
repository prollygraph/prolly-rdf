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
package com.earasoft.prolly.rdf4j.uaf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.ArenaScopeProbe;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.PoisoningBufferPool;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.ProvenanceIndex;
import com.earasoft.prolly.rdf4j.index.QuadIndex;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocIndex;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the per-transaction quad indexes
 * (plans/off-heap-use-after-free-tests.md Phase 4 Step 14): {@link QuadIndex}, {@link
 * com.earasoft.prolly.rdf4j.index.SpocIndex}, and {@link ProvenanceIndex}.
 *
 * <p><b>Why these get a real poison differential (unlike the rdf query layer, Steps 11–12):</b> all
 * three take the {@code BufferPool} <em>interface</em>, not the concrete {@code DirectBufferPool} —
 * they only hold + pass the pool (to {@code MutableMap} / {@code TupleBuilder}). So the poison
 * harness (a separate {@code BufferPool} impl, shared via the port-core test-jar, D-5) can drive
 * them: building through the {@link PoisoningBufferPool} (genuinely off-heap, {@code
 * Arena.ofShared}) must produce the byte-identical committed tree it does through the {@link
 * com.dolthub.prolly.HeapBufferPool} — a read of freed/reused scratch would diverge or surface
 * poison (H2/H4).
 *
 * <p>{@code SpocIndex} (the key-only prolly map) is exercised through {@code QuadIndex(SPOC)},
 * which is a thin permutation wrapper that delegates insert / delete / commit / prefix-scan
 * straight to it.
 */
class IndexUseAfterFreeTest {

    private static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
    private static final int N = 80; // enough 4×Int64 keys to force a multi-level tree

    /**
     * The quad write-buffer path: insert N quads, delete two, commit (flush the {@code MutableMap}
     * buffer through {@code TreeMutator}), then full-scan the committed root AND a prefix-scan
     * (which drives {@code SpocIndex.buildPrefixTuple}'s own pool borrow). Byte-identical
     * serialized content through the poison and heap pools proves none of insert / delete / commit
     * / prefix-scan reads freed scratch.
     */
    @Test
    void quadIndexBuildCommitScanIsByteIdenticalThroughPoisonAndHeapPool() {
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    QuadIndex idx = new QuadIndex(QuadOrder.SPOC, store, pool);
                    TermId c = TermId.of(0);
                    for (int i = 0; i < N; i++) {
                        // 8 quads share each subject → a prefix scan on a subject returns a real
                        // range.
                        idx.insert(TermId.of(i / 8), TermId.of(i % 4), TermId.of(i), c);
                    }
                    // Buffered deletes (exercise MutableMap.delete over the pool) — neither is in
                    // the
                    // subject=2 range scanned below, so the scan stays stable across both pools.
                    idx.delete(TermId.of(0 / 8), TermId.of(0 % 4), TermId.of(0), c);
                    idx.delete(TermId.of(40 / 8), TermId.of(40 % 4), TermId.of(40), c);
                    StaticMap committed = idx.commit();

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    MapIterator it = committed.iter();
                    while (it.next()) {
                        out.writeBytes(it.key().toArray(BYTE)); // key-only index — value is empty
                    }
                    // Prefix scan on subject=2 (i in 16..23, none deleted) — drives
                    // buildPrefixTuple(pool).
                    Iterator<SpocKey> scan = idx.scan(TermId.of(2), null, null, null);
                    while (scan.hasNext()) {
                        writeKey(out, scan.next());
                    }
                    return out.toByteArray();
                });
    }

    /**
     * The provenance sidecar write path: record N first-seen entries (each {@code recordFirstSeen}
     * does a committed-state read via {@code buildKeyTuple(pool)} + {@code base.get}), then commit
     * (drains pending via {@code buildKeyTuple(pool)} + {@code base.put}, then flushes the tree
     * through the pool). Byte-identical committed keys+values through both pools proves the sidecar
     * build reads no freed scratch.
     */
    @Test
    void provenanceIndexBuildCommitIsByteIdenticalThroughPoisonAndHeapPool() {
        ArenaScopeProbe.assertSameThroughPoolAndHeap(
                pool -> {
                    InMemoryNodeStore store = new InMemoryNodeStore();
                    ProvenanceIndex prov = new ProvenanceIndex(store, pool);
                    for (int i = 0; i < N; i++) {
                        SpocKey k =
                                new SpocKey(
                                        TermId.of(i / 8),
                                        TermId.of(i % 4),
                                        TermId.of(i),
                                        TermId.of(0));
                        byte[] parent = ("parent-" + (i % 7)).getBytes(StandardCharsets.UTF_8);
                        prov.recordFirstSeen(k, parent);
                    }
                    StaticMap committed = prov.commit();

                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    MapIterator it = committed.iter();
                    while (it.next()) {
                        out.writeBytes(it.key().toArray(BYTE));
                        out.writeBytes(
                                it.value()
                                        .toArray(BYTE)); // the encoded (magic+parent+repoId) value
                    }
                    return out.toByteArray();
                });
    }

    /**
     * The provenance read path returns owned bytes, not a pool view (H4 / D-4): commit an entry
     * under the poison pool, reopen the index over the committed root (a fresh-transaction read of
     * HEAD), and read it back — {@code firstSeen} copies the value out ({@code readCommitted}'s
     * {@code MemorySegment.copy}), so it returns the real parent hash with no poison, even though
     * the lookup borrows a key tuple from the poisoning pool.
     */
    @Test
    void provenanceFirstSeenReadsOwnedBytesNotPoisonAfterCommitAndReopen() {
        try (PoisoningBufferPool pool = new PoisoningBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            SpocKey k = new SpocKey(TermId.of(7), TermId.of(1), TermId.of(99), TermId.of(0));
            byte[] parent = "the-parent-commit-hash".getBytes(StandardCharsets.UTF_8);

            ProvenanceIndex prov = new ProvenanceIndex(store, pool);
            prov.recordFirstSeen(k, parent);
            StaticMap committed = prov.commit();

            ProvenanceIndex reopened = new ProvenanceIndex(store, pool, committed);
            Optional<byte[]> got = reopened.firstSeen(k);

            assertTrue(got.isPresent(), "the committed entry must be readable after reopen");
            assertArrayEquals(
                    parent,
                    got.get(),
                    "firstSeen must return the real parent hash (copied out), not a poisoned pool view");
            assertFalse(
                    ArenaScopeProbe.containsPoisonRun(got.get(), 4),
                    "the returned hash must carry no poison run");
        }
    }

    /**
     * Recycling plan Step 4 (ADR-0062 D-3/D-4): {@code contains} builds a <em>transient</em> lookup
     * key (probe + discard) and recycles the borrowed block; {@code insert} <em>retains</em> its
     * key (it becomes a {@code MutableMap} key), so it must not release. The asymmetry — contains
     * releases, insert doesn't — is what proves only the proven-transient site is recycled.
     */
    @Test
    void spocIndexContainsRecyclesItsTransientLookupKey_insertRetains() {
        try (PoisoningBufferPool pool = new PoisoningBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            SpocIndex idx = new SpocIndex(store, pool);
            SpocKey k = new SpocKey(TermId.of(1), TermId.of(2), TermId.of(3), TermId.of(4));

            long r0 = pool.releasedCount();
            idx.insert(k); // retained — the key segment becomes a MutableMap key
            long afterInsert = pool.releasedCount();
            idx.contains(k); // transient — probe, then recycle the borrowed key block
            long afterContains = pool.releasedCount();

            assertEquals(
                    r0,
                    afterInsert,
                    "insert retains its key (stored as a MutableMap key) — no release");
            assertTrue(
                    afterContains > afterInsert,
                    "contains recycles its transient lookup-key block (D-3/D-4)");
        }
    }

    private static void writeKey(ByteArrayOutputStream out, SpocKey k) {
        writeLong(out, k.col0().value());
        writeLong(out, k.col1().value());
        writeLong(out, k.col2().value());
        writeLong(out, k.col3().value());
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (v >>> shift) & 0xFF);
        }
    }
}
