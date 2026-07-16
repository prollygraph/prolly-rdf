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

import com.dolthub.prolly.HeapBufferPool;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Resource/memory-leak <b>soak</b>: a long-lived, <b>production-config</b> process ({@link
 * ProllySail} over {@link RocksNodeStore} + {@link HeapBufferPool} — the default the Sail factory
 * wires) that churns the per-transaction + connection-lifecycle write path for a target duration,
 * sampling RSS / live-heap / file descriptors / threads. A <b>plateau</b> across the run means
 * leak-free; a <b>sustained climb</b> (or an OOM-kill under the {@code ts_scope_run} memory cap)
 * means a leak.
 *
 * <h2>Why ROLLBACK churn (the leak regime)</h2>
 *
 * <p>The signal must let a leak <i>act</i> while staying distinguishable from legitimate growth. A
 * <i>commit</i> soak is confounded: the content-addressed store retains every commit's chunks + a
 * log entry (garbage collection is deferred), so it grows <b>by design</b>, not by leak. {@code
 * rollback} instead exercises the leak-prone path — each {@code add} borrows pool scratch + buffers
 * a tuple; the rollback forks a fresh transaction scope and frees the prior one (the exact class
 * the DirectBufferPool write-path leak belonged to) — while persisting <b>nothing</b>. So the
 * persistent state is bounded, and any sustained growth in RSS / live-heap / descriptors / threads
 * is a real leak. (The commit/flush path's resource lifecycle is a separate concern, bounded by the
 * garbage collector + the per-tx scope, not soaked here.)
 *
 * <p><b>Modes</b> ({@code -Dsoak.mode}): {@code rollback} (default) adds IRIs then rolls back;
 * {@code mixed} rotates the object through every value-factory create* path (IRI / string /
 * lang-string / long / double) and runs an in-transaction SELECT over the buffered batch before
 * rolling back — same bounded-state guarantee, far wider code coverage ("catch more issues").
 *
 * <p>Run via {@code test-support/soak-bench.sh}; not a unit test (no {@code @Test}), driven only as
 * a main.
 */
public final class SoakLeakDriver {

    public static void main(String[] args) throws Exception {
        long minutes = Long.getLong("soak.minutes", 60);
        int batch = Integer.getInteger("soak.batch", 5000);
        int roundsPerConn = Integer.getInteger("soak.rounds", 20);
        // Vocabulary bound. A BOUNDED keyspace (reuse a fixed set of terms) is the clean leak
        // signal: the
        // term universe is finite, so any sustained heap growth is a genuine per-tx/rollback
        // retention
        // leak, not the cache cost of an ever-growing distinct vocabulary. soak.keyspace=0 restores
        // the
        // unbounded-distinct workload (which OOM'd at ~9 min — but that conflates a leak with vocab
        // growth).
        long keyspace = Long.getLong("soak.keyspace", 200_000);
        // Workload mode. "rollback" (default): add IRIs, then roll back — the original clean
        // bounded-state leak signal. "mixed": rotate the object through EVERY ProllyValueFactory
        // create* path (IRI / xsd:string / rdf:langString / xsd:long / xsd:double — the whole
        // value-factory surface where the 2026-06-15 leak lived, ADR-0063) AND run an
        // in-transaction
        // SELECT over the buffered batch (the read/query/value-materialization path, via
        // read-your-writes) before rolling back. Mixed still persists NOTHING, so the bounded-state
        // signal stays clean while soaking far more code — to "catch more issues" over a long run.
        String mode = System.getProperty("soak.mode", "rollback");
        boolean mixed = "mixed".equals(mode);
        Path dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "soak");

        Thread sampler = startSampler();
        long deadline = System.nanoTime() + minutes * 60_000_000_000L;
        long cycles = 0;

        ProllySail sail =
                new ProllySail(
                        new RocksNodeStore(dir.resolve("rocks").toString()), new HeapBufferPool());
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            while (System.nanoTime() < deadline) {
                try (RepositoryConnection conn = repo.getConnection()) {
                    ValueFactory vf = conn.getValueFactory();
                    IRI p = vf.createIRI("urn:p");
                    for (int r = 0; r < roundsPerConn; r++) {
                        conn.begin();
                        long base = cycles * (long) batch;
                        for (int i = 0; i < batch; i++) {
                            // Bounded vocabulary (keyspace>0): ids wrap, so the distinct term
                            // universe saturates after one pass over the keyspace and any FURTHER
                            // sustained heap growth is a genuine per-tx/rollback retention leak —
                            // not the cache cost of an ever-growing vocabulary. A contiguous block
                            // of `batch` consecutive ids stays distinct mod keyspace (batch <
                            // keyspace), so each round still adds `batch` distinct triples — real
                            // add work, no dedup short-circuit. keyspace=0 = the unbounded
                            // workload.
                            long raw = base + i;
                            long id = keyspace > 0 ? Math.floorMod(raw, keyspace) : raw;
                            conn.add(vf.createIRI("urn:s/" + id), p, objectFor(vf, id, mixed));
                        }
                        if (mixed) {
                            // Read-your-writes: a SELECT in the SAME tx scans the buffered (still
                            // uncommitted) batch — SPARQL parse -> plan -> iterate -> materialize
                            // values through the Sail's shared value factory (the read side of the
                            // value-creation path). Discarded on rollback, so state stays bounded.
                            try (org.eclipse.rdf4j.query.TupleQueryResult res =
                                    conn.prepareTupleQuery("SELECT ?s ?o WHERE { ?s <urn:p> ?o }")
                                            .evaluate()) {
                                while (res.hasNext()) {
                                    org.eclipse.rdf4j.model.Value o = res.next().getValue("o");
                                    if (o != null) o.stringValue(); // force segment decode
                                }
                            }
                        }
                        conn.rollback(); // discard: exercises add (+ read) + per-tx-scope free,
                        // persists nothing
                        cycles++;
                    }
                }
            }
        } finally {
            sampler.interrupt();
            try {
                repo.shutDown();
            } catch (Exception ignore) {
                /* cleanup only; the sample trace is the result */
            }
        }
        System.out.printf(
                "[soak] DONE minutes=%d cycles=%,d batch=%d mode=%s (bounded persistent state)%n",
                minutes, cycles, batch, mode);
    }

    /**
     * The object term for one add. In {@code rollback} mode, always an IRI (the original workload).
     * In {@code mixed} mode, rotate through every {@link ProllyValueFactory} create* path by {@code
     * id % 5} — IRI, xsd:string, rdf:langString, xsd:long, xsd:double — so the soak exercises the
     * WHOLE value-factory surface (each create* allocates from a per-value arena since ADR-0063),
     * not just createIRI where the leak first surfaced.
     */
    private static org.eclipse.rdf4j.model.Value objectFor(
            ValueFactory vf, long id, boolean mixed) {
        if (!mixed) return vf.createIRI("urn:o/" + id);
        return switch ((int) Math.floorMod(id, 5)) {
            case 0 -> vf.createIRI("urn:o/" + id);
            case 1 -> vf.createLiteral("str-" + id);
            case 2 -> vf.createLiteral("lang-" + id, "en");
            case 3 -> vf.createLiteral(id); // xsd:long
            default -> vf.createLiteral((double) id); // xsd:double
        };
    }

    /**
     * Samples RSS / peak-RSS / heap-used / live-heap (post-GC, every ~60s) / FD count / thread
     * count.
     */
    private static Thread startSampler() {
        Thread t =
                new Thread(
                        () -> {
                            long t0 = System.nanoTime();
                            int tick = 0;
                            Runtime rt = Runtime.getRuntime();
                            while (!Thread.currentThread().isInterrupted()) {
                                long sec = (System.nanoTime() - t0) / 1_000_000_000L;
                                long heapLive = -1;
                                if (tick % 12 == 0) { // ~every 60s: a GC reads the live-heap trough
                                    System.gc();
                                    heapLive = (rt.totalMemory() - rt.freeMemory()) >> 20;
                                }
                                System.out.printf(
                                        "[soak t=%5ds rssMiB=%,7d hwmMiB=%,7d heapUsedMiB=%,6d"
                                                + " heapLiveMiB=%,6d fds=%,6d threads=%,4d]%n",
                                        sec,
                                        procStatusKb("VmRSS") >> 10,
                                        procStatusKb("VmHWM") >> 10,
                                        (rt.totalMemory() - rt.freeMemory()) >> 20,
                                        heapLive,
                                        fdCount(),
                                        ManagementFactory.getThreadMXBean().getThreadCount());
                                tick++;
                                try {
                                    Thread.sleep(5000);
                                } catch (InterruptedException e) {
                                    return;
                                }
                            }
                        },
                        "soak-sampler");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static long procStatusKb(String field) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith(field + ":")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception ignore) {
            /* /proc absent (non-Linux) or transient read failure — sampling is best-effort */
        }
        return 0;
    }

    private static long fdCount() {
        try (var s = Files.list(Path.of("/proc/self/fd"))) {
            return s.count();
        } catch (Exception ignore) {
            return -1;
        }
    }

    private SoakLeakDriver() {}
}
