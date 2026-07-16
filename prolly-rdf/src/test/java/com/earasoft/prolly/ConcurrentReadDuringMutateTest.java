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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 *
 * <h3>Concurrent Read-During-Mutate Snapshot Isolation Test</h3>
 *
 * <p>Asserts the snapshot-isolation invariant of the content-addressed store: <b>readers walking an
 * immutable root {@code R0} are never affected by concurrent writers producing {@code R1, R2, ...}
 * on the same {@link RocksNodeStore} and {@link DirectBufferPool}</b>. Every node a reader
 * retrieves must hash-verify, and the reader's traversal must always see {@code R0}'s exact key
 * set.
 *
 * <p><b>The Gap:</b> {@code ConcurrencyTest} is 53 lines and does not pin what readers observe. The
 * Prolly Tree's correctness story rests on <i>structural immutability</i> + <i>content
 * addressing</i>: writers may only ever ADD nodes (never overwrite), so old roots stay reachable.
 * If a future change ever introduced in-place mutation of a node (e.g., to "fix up" subtree counts)
 * it would silently break this guarantee — and no existing test would notice.
 *
 * <p><b>Scenario:</b>
 *
 * <ul>
 *   <li>Build a 5000-key tree {@code R0}; capture its key set.
 *   <li>4 reader threads loop: walk all of {@code R0} via a hash-verifying decorator, then iterate
 *       {@code StaticMap.iter()} and confirm the key set is unchanged.
 *   <li>2 writer threads loop: build {@code R1, R2, ...} by applying random batches of 200
 *       mutations on top of {@code R0} on the <i>shared</i> store and pool.
 *   <li>Run for ~3 seconds; any verification failure or thrown exception fails the test.
 * </ul>
 *
 * <p><b>Oracle:</b> after the test window, every reader must have completed at least one full walk;
 * total reader exceptions must be zero; the key set observed by every reader must equal the
 * original {@code R0} key set exactly.
 */
public class ConcurrentReadDuringMutateTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Concurrent Read-During-Mutate Test ---");
        Path tempDir = Files.createTempDirectory("prolly-concurrent-rd-mut");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            TreeMutator mutator = new TreeMutator(store, desc, pool);

            int BASE = 5000;
            Set<String> r0Keys = new HashSet<>();
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < BASE; i++) {
                String k = String.format("base-%05d", i);
                r0Keys.add(k);
                TupleBuilder tb = new TupleBuilder(pool);
                tb.putField(0, k.getBytes());
                edits.add(
                        new TreeMutator.Mutation(
                                tb.build().segment(),
                                MemorySegment.ofArray(("v-" + i).getBytes())));
            }
            Node r0 = mutator.applyMutations(null, edits.iterator());
            store.write(r0.segment());
            System.out.println("R0 built: " + BASE + " keys.");

            int READERS = 4;
            int WRITERS = 2;
            long DURATION_MS = 3000;

            ExecutorService pool2 = Executors.newFixedThreadPool(READERS + WRITERS);
            CountDownLatch ready = new CountDownLatch(READERS + WRITERS);
            CountDownLatch start = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean(false);
            AtomicLong readWalks = new AtomicLong();
            AtomicLong writeBatches = new AtomicLong();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            // Readers
            for (int r = 0; r < READERS; r++) {
                pool2.submit(
                        () -> {
                            try {
                                ready.countDown();
                                start.await();
                                IntegrityVerifyingNodeStore verifier =
                                        new IntegrityVerifyingNodeStore(store);
                                Node localRoot =
                                        Node.fromBytes(
                                                verifier.read(
                                                                HashUtils.hash(
                                                                        r0.segment()
                                                                                .asByteBuffer()))
                                                        .orElseThrow());
                                StaticMap snapshot = new StaticMap(verifier, localRoot, desc);
                                while (!stop.get() && failure.get() == null) {
                                    walkAll(verifier, HashUtils.hash(r0.segment().asByteBuffer()));
                                    Set<String> seen = new HashSet<>();
                                    MapIterator it = snapshot.iter();
                                    while (it.next()) {
                                        seen.add(new String(new Tuple(it.key()).getField(0)));
                                    }
                                    if (!seen.equals(r0Keys)) {
                                        throw new RuntimeException(
                                                "Reader saw mutated key set: missing="
                                                        + missing(r0Keys, seen)
                                                        + " extra="
                                                        + missing(seen, r0Keys));
                                    }
                                    readWalks.incrementAndGet();
                                }
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            }
                        });
            }

            // Writers
            for (int w = 0; w < WRITERS; w++) {
                final int wid = w;
                pool2.submit(
                        () -> {
                            try {
                                ready.countDown();
                                start.await();
                                Random rng = new Random(0xBEEF + wid);
                                TreeMutator localMutator = new TreeMutator(store, desc, pool);
                                long generation = 0;
                                while (!stop.get() && failure.get() == null) {
                                    List<TreeMutator.Mutation> batch = new ArrayList<>();
                                    for (int j = 0; j < 200; j++) {
                                        String k =
                                                String.format(
                                                        "w%d-g%d-%d",
                                                        wid, generation, rng.nextInt(1_000_000));
                                        TupleBuilder tb = new TupleBuilder(pool);
                                        tb.putField(0, k.getBytes());
                                        batch.add(
                                                new TreeMutator.Mutation(
                                                        tb.build().segment(),
                                                        MemorySegment.ofArray(
                                                                ("p" + j).getBytes())));
                                    }
                                    // Sorted-stream contract: sort batch by tuple key.
                                    batch.sort(
                                            (a, b) ->
                                                    desc.compare(
                                                            new Tuple(a.key()),
                                                            new Tuple(b.key())));
                                    Node next = localMutator.applyMutations(r0, batch.iterator());
                                    if (next != null) store.write(next.segment());
                                    writeBatches.incrementAndGet();
                                    generation++;
                                }
                            } catch (Throwable t) {
                                failure.compareAndSet(null, t);
                            }
                        });
            }

            ready.await();
            start.countDown();
            Thread.sleep(DURATION_MS);
            stop.set(true);
            pool2.shutdown();
            if (!pool2.awaitTermination(15, TimeUnit.SECONDS)) {
                throw new RuntimeException("Threads did not terminate");
            }

            if (failure.get() != null) {
                throw new RuntimeException("Concurrent invariant violated", failure.get());
            }
            System.out.println(
                    "Reader walks: " + readWalks.get() + ", writer batches: " + writeBatches.get());
            if (readWalks.get() == 0) {
                throw new RuntimeException("Readers did not complete a single walk");
            }
            if (writeBatches.get() == 0) {
                throw new RuntimeException("Writers did not complete a single batch");
            }

            System.out.println("--- Concurrent Read-During-Mutate Test PASSED ---");
        }
    }

    private static void walkAll(NodeStore store, byte[] hash) {
        if (hash == null) return;
        Optional<MemorySegment> seg = store.read(hash);
        if (seg.isEmpty()) {
            throw new RuntimeException(
                    "Reader could not resolve hash " + toHex(hash) + " — node disappeared from R0");
        }
        Node n = Node.fromBytes(seg.get());
        if (!n.isLeaf()) {
            for (int i = 0; i < n.count(); i++) walkAll(store, n.getValue(i));
        }
    }

    private static String missing(Set<String> a, Set<String> b) {
        Set<String> diff = new HashSet<>(a);
        diff.removeAll(b);
        return diff.size() <= 5 ? diff.toString() : "(" + diff.size() + " keys)";
    }

    private static String toHex(byte[] bytes) {
        return HashUtils.toHex(bytes);
    }
}
