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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-JVM ref-update race the inventory flagged as untested (hardening round 1): {@code
 * RefsStore.compareAndSet} is the single primitive every concurrent commit serializes through, on
 * BOTH backends — the in-memory map and the file-backed path, whose own comment warns that {@code
 * FileChannel.lock()} throws {@code OverlappingFileLockException} for a second same-JVM thread
 * unless the monitor serializes them. This hammers exactly that: N threads CAS-advancing one ref
 * with retry-on-conflict, asserting NO lost update (every successful CAS observed the then-current
 * value, so the total success count and the per-thread counts reconcile exactly) and no torn read.
 */
class RefsStoreConcurrencyTest {

    private static final int THREADS = 8;
    private static final int ADVANCES_PER_THREAD = 20;

    private static byte[] hash(int thread, int step) {
        byte[] h = new byte[32];
        h[0] = (byte) thread;
        h[1] = (byte) step;
        h[31] = (byte) 0xAB;
        return h;
    }

    private void race(RefsStore refs) throws Exception {
        String name = "race-branch";
        byte[] genesis = hash(99, 0);
        refs.put(name, genesis);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger casAttempts = new AtomicInteger();
        // Every value ever successfully installed, keyed by its bytes — the
        // final value must be one of these (no torn/foreign bytes).
        ConcurrentHashMap<String, Boolean> installed = new ConcurrentHashMap<>();
        installed.put(Arrays.toString(genesis), true);
        // THE atomicity invariant: successful CASes form a single chain — no two
        // successes may have observed the SAME current value. Each success
        // registers its (observed -> installed) edge; a duplicate observed key
        // is a lost update, the exact bug a broken CAS produces.
        ConcurrentHashMap<String, String> chainEdges = new ConcurrentHashMap<>();
        java.util.List<String> lostUpdates =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        Thread[] ts =
                IntStream.range(0, THREADS)
                        .mapToObj(
                                t ->
                                        new Thread(
                                                () -> {
                                                    try {
                                                        start.await();
                                                        for (int i = 1;
                                                                i <= ADVANCES_PER_THREAD;
                                                                i++) {
                                                            byte[] mine = hash(t, i);
                                                            while (true) {
                                                                casAttempts.incrementAndGet();
                                                                byte[] cur =
                                                                        refs.get(name)
                                                                                .orElseThrow();
                                                                if (refs.compareAndSet(
                                                                        name, cur, mine)) {
                                                                    installed.put(
                                                                            Arrays.toString(mine),
                                                                            true);
                                                                    String clash =
                                                                            chainEdges.putIfAbsent(
                                                                                    Arrays.toString(
                                                                                            cur),
                                                                                    Arrays.toString(
                                                                                            mine));
                                                                    if (clash != null) {
                                                                        lostUpdates.add(
                                                                                "two successes from"
                                                                                        + " one expected"
                                                                                        + " value");
                                                                    }
                                                                    break;
                                                                }
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        throw new RuntimeException(e);
                                                    }
                                                }))
                        .toArray(Thread[]::new);
        for (Thread t : ts) t.start();
        start.countDown();
        for (Thread t : ts) t.join(60_000);
        for (Thread t : ts) assertEquals(Thread.State.TERMINATED, t.getState(), "no wedge");

        Optional<byte[]> fin = refs.get(name);
        assertTrue(fin.isPresent(), "the ref survives the race");
        assertEquals(32, fin.get().length, "no torn value");
        assertTrue(
                installed.containsKey(Arrays.toString(fin.get())),
                "the final value is one some thread actually installed");
        assertTrue(lostUpdates.isEmpty(), "CAS atomicity violated: " + lostUpdates);
        assertEquals(
                THREADS * ADVANCES_PER_THREAD,
                chainEdges.size(),
                "the successes form one chain: exactly one edge per successful advance");
        assertTrue(
                casAttempts.get() >= THREADS * ADVANCES_PER_THREAD,
                "every advance succeeded exactly once (attempts >= successes; successes = "
                        + THREADS * ADVANCES_PER_THREAD
                        + ", attempts = "
                        + casAttempts.get()
                        + ")");
    }

    @Test
    void fileBackedCasSurvivesInJvmContention(@TempDir Path dir) throws Exception {
        race(RefsStore.beside(dir));
    }

    @Test
    void inMemoryCasSurvivesInJvmContention() throws Exception {
        race(RefsStore.inMemory());
    }
}
