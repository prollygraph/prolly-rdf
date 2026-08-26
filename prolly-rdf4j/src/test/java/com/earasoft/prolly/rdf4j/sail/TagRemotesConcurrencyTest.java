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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-JVM mutation races for {@link TagStore} and {@link RemotesStore} (roadmap T16), mirroring
 * {@code RefsStoreConcurrencyTest} for the two sibling sidecar stores it left uncovered. All three
 * share the same file-backed hazard its javadoc names — {@code FileChannel.lock()} throws {@code
 * OverlappingFileLockException} for a second same-JVM thread unless the monitor serializes them
 * first — so a store that got the locking subtly wrong would fail here and nowhere else.
 *
 * <p>The two stores are raced on DIFFERENT invariants, because their APIs promise different things
 * and pinning the wrong one would be decoration:
 *
 * <ul>
 *   <li>{@link TagStore#create} is <b>create-if-absent</b> and returns whether it won. Under N
 *       threads racing for one name, <b>exactly one</b> may return true. Anything else is either a
 *       lost tag or a silently overwritten one, and tags are recovery handles (ADR-0003) — the
 *       thing you reach for when something has already gone wrong.
 *   <li>{@link RemotesStore#put} is <b>last-write-wins</b> with no CAS, so "exactly one winner" is
 *       not the contract and asserting it would be wrong. What must hold is that the survivor is
 *       one of the values actually written, never a mixture of two — the store hand-encodes its
 *       flat JSON, so a torn interleave is a real failure mode rather than a theoretical one.
 * </ul>
 *
 * <p>Both backends are raced, because {@code inMemory()} and {@code beside()} are different
 * implementations of the same contract and only the file-backed one takes the lock.
 */
class TagRemotesConcurrencyTest {

    private static final int THREADS = 8;

    private static byte[] commitHash(int thread) {
        byte[] h = new byte[32];
        h[0] = (byte) thread;
        h[31] = (byte) 0xC0;
        return h;
    }

    // ── TagStore: create-if-absent must have exactly one winner ─────────────────────────────

    private void raceTagCreate(TagStore tags) throws Exception {
        String name = "v1.0-release";
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final int id = i;
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    if (tags.create(name, commitHash(id), "from thread " + id)) {
                                        winners.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        assertTrue(
                failures.isEmpty(),
                "no thread may fail outright — a same-JVM lock collision would surface here: "
                        + failures);
        for (Thread t : threads) {
            assertEquals(Thread.State.TERMINATED, t.getState(), "no wedge");
        }
        assertEquals(
                1,
                winners.get(),
                "create-if-absent must have EXACTLY one winner among "
                        + THREADS
                        + " racers; "
                        + winners.get()
                        + " threads believed they created the tag");

        Optional<TagStore.Entry> survivor = tags.get(name);
        assertTrue(survivor.isPresent(), "the tag survives the race");
        assertEquals(32, survivor.get().commit().length, "no torn commit hash");
        assertEquals((byte) 0xC0, survivor.get().commit()[31], "the surviving hash is well-formed");
        assertTrue(
                survivor.get().message().startsWith("from thread "),
                "the message belongs to one writer, not a mixture: " + survivor.get().message());
        assertEquals(1, tags.list().size(), "exactly one tag exists after the race");
    }

    @Test
    void fileBackedTagCreateHasExactlyOneWinner(@TempDir Path dir) throws Exception {
        raceTagCreate(TagStore.beside(dir));
    }

    @Test
    void inMemoryTagCreateHasExactlyOneWinner() throws Exception {
        raceTagCreate(TagStore.inMemory());
    }

    /** Deleting one tag from N threads: exactly one delete may report success. */
    private void raceTagDelete(TagStore tags) throws Exception {
        String name = "doomed";
        assertTrue(tags.create(name, commitHash(1), "to be deleted"));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger deleters = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    if (tags.delete(name)) {
                                        deleters.incrementAndGet();
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        assertTrue(failures.isEmpty(), "no thread may fail outright: " + failures);
        assertEquals(
                1,
                deleters.get(),
                "exactly one delete may report success — a second true means two callers both "
                        + "believe they removed it, got "
                        + deleters.get());
        assertTrue(tags.get(name).isEmpty(), "the tag is gone");
    }

    @Test
    void fileBackedTagDeleteHasExactlyOneWinner(@TempDir Path dir) throws Exception {
        raceTagDelete(TagStore.beside(dir));
    }

    @Test
    void inMemoryTagDeleteHasExactlyOneWinner() throws Exception {
        raceTagDelete(TagStore.inMemory());
    }

    // ── RemotesStore: last-write-wins, but never a torn value ───────────────────────────────

    private void raceRemotesPut(RemotesStore remotes) throws Exception {
        String name = "origin";
        CountDownLatch start = new CountDownLatch(1);
        Set<String> written = ConcurrentHashMap.newKeySet();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final String url = "https://example.org/repo-" + i;
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    for (int rep = 0; rep < 10; rep++) {
                                        remotes.put(name, url);
                                        written.add(url);
                                    }
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        assertTrue(
                failures.isEmpty(),
                "concurrent put must not throw — the store hand-encodes its bindings file, so a "
                        + "lock or parse failure surfaces as an exception here: "
                        + failures);
        Optional<String> survivor = remotes.get(name);
        assertTrue(survivor.isPresent(), "the binding survives the race");
        assertTrue(
                written.contains(survivor.get()),
                "last-write-wins is fine; a value nobody wrote is not. Survivor '"
                        + survivor.get()
                        + "' is not among the "
                        + written.size()
                        + " written values — the flat-JSON encoding tore under concurrency");
    }

    @Test
    void fileBackedRemotesPutNeverTears(@TempDir Path dir) throws Exception {
        raceRemotesPut(RemotesStore.beside(dir));
    }

    @Test
    void inMemoryRemotesPutNeverTears() throws Exception {
        raceRemotesPut(RemotesStore.inMemory());
    }

    /**
     * Concurrent writes to DIFFERENT names must not lose each other. This is the failure mode a
     * whole-file rewrite invites: two threads each read the map, add their own key, and write the
     * file back — and one of the two keys vanishes with no error anywhere.
     */
    private void raceRemotesDistinctNames(RemotesStore remotes) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        Thread[] threads = new Thread[THREADS];

        for (int i = 0; i < THREADS; i++) {
            final String name = "remote-" + i;
            final String url = "https://example.org/" + i;
            threads[i] =
                    new Thread(
                            () -> {
                                try {
                                    start.await();
                                    remotes.put(name, url);
                                } catch (Throwable t) {
                                    failures.add(t);
                                }
                            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(30_000);
        }

        assertTrue(failures.isEmpty(), "no thread may fail outright: " + failures);
        Map<String, String> present = new java.util.HashMap<>();
        for (int i = 0; i < THREADS; i++) {
            remotes.get("remote-" + i).ifPresent(u -> present.put(u, u));
        }
        assertEquals(
                THREADS,
                present.size(),
                "every distinct remote written concurrently must survive — "
                        + present.size()
                        + " of "
                        + THREADS
                        + " are present, so a whole-file rewrite dropped keys "
                        + "written by another thread");
    }

    @Test
    void fileBackedRemotesKeepEveryDistinctName(@TempDir Path dir) throws Exception {
        raceRemotesDistinctNames(RemotesStore.beside(dir));
    }

    @Test
    void inMemoryRemotesKeepEveryDistinctName() throws Exception {
        raceRemotesDistinctNames(RemotesStore.inMemory());
    }
}
