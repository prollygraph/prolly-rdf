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

import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.Sail;
import org.eclipse.rdf4j.sail.lmdb.LmdbStore;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;
import org.eclipse.rdf4j.sail.nativerdf.NativeStore;

/**
 *
 *
 * <h3>Batched-commit edge-list ingest into a disk-backed store — the bounded-memory + index-count
 * measurement.</h3>
 *
 * <p>Ingests an edge list into a fresh disk-backed store with batched commits ({@code -Dbatch},
 * default 200k) and reports time / throughput / peak (via the harness) / <b>on-disk size</b>
 * (logical {@code Files.walk} sum — the fair cross-engine footprint). No triangle query.
 *
 * <p><b>Engines</b> (arg 1, default {@code flatsail}): {@code flatsail} ({@link RocksDbFlatSail}, 4
 * fixed permutation indexes), {@code prolly} (RocksDB-backed {@link ProllySail}, 4 fixed indexes +
 * WCOJ), {@code native} ({@link NativeStore}) and {@code lmdb} ({@link LmdbStore}) — the latter two
 * take a configurable index spec via {@code -Dindexes} (default {@code spoc,posc,ospc,cspo}, i.e.
 * 4, to match the prolly sails; pass {@code -Dindexes=spoc,posc} for their RDF4J default of 2).
 * This makes ingest/on-disk an <b>apples-to-apples, index-matched</b> comparison (see {@code
 * newcomer-docs/advanced-topics/engine-comparison.md}).
 *
 * <p>Run: {@code java … -Dgraph.zip=… [-Dbatch=200000] [-Dindexes=spoc,posc,ospc,cspo]
 * GraphIngestBench [flatsail|prolly|native|lmdb]}.
 */
public final class GraphIngestBench {

    public static void main(String[] args) throws Exception {
        String engine = args.length > 0 ? args[0] : "flatsail";
        int batch = Integer.getInteger("batch", 200_000);
        String indexes = System.getProperty("indexes", "spoc,posc,ospc,cspo");
        Set<Long> edges = RealGraphTriangleBench.loadEdges(false);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        Path dir = Files.createTempDirectory(tmp, "ingest-" + engine);

        Sail sail;
        int idxN; // index count actually maintained
        switch (engine) {
            case "prolly" -> {
                // -Dpool=heap|direct (default direct). The A/B for the web-Google off-heap OOM:
                // DirectBufferPool (off-heap, manual free) vs HeapBufferPool (on-heap,
                // GC-reclaimed).
                // If heap bounds an ingest that direct OOMs, the DirectBufferPool isn't freeing
                // released segments; if heap also OOMs, the buffers are genuinely live (working
                // set).
                com.dolthub.prolly.BufferPool pool =
                        "heap".equals(System.getProperty("pool"))
                                ? new com.dolthub.prolly.HeapBufferPool()
                                : new DirectBufferPool();
                sail = new ProllySail(new RocksNodeStore(dir.resolve("rocks").toString()), pool);
                idxN = 4;
            }
            case "native" -> {
                sail = new NativeStore(dir.toFile(), indexes);
                idxN = indexes.split(",").length;
            }
            case "lmdb" -> {
                sail = new LmdbStore(dir.toFile(), new LmdbStoreConfig(indexes));
                idxN = indexes.split(",").length;
            }
            default -> {
                sail = new RocksDbFlatSail(dir);
                idxN = 4;
            } // flatsail
        }
        SailRepository repo = new SailRepository(sail);
        repo.init();

        // Graceful shutdown on SIGTERM (the bench runs under `timeout`). Without this, a kill
        // mid-ingest never closes RocksDB, so a background-compaction thread SIGSEGVs during
        // process
        // teardown (rocksdb::BlockBasedTable::Open on a CompactionJob thread). The hook only
        // signals;
        // THIS thread stays the sole DB toucher and closes the store below. See
        // BenchGracefulShutdown.
        BenchGracefulShutdown shutdown = new BenchGracefulShutdown("ingest-bench-shutdown", 30);

        // RSS visibility during ingest — so a slow or timed-out run still answers "is memory
        // bounded?"
        // (this bench otherwise prints only on completion, so a killed run says nothing). Reuses
        // StreamingNcitIngest's sampler (RSS + heap; the RocksDB-internal columns are blank since
        // the
        // store handle isn't wired here).
        var sampler = StreamingNcitIngest.MemSampler.start(engine);
        long n = 0, t0 = System.nanoTime();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(RealGraphTriangleBench.EDGE);
            conn.begin();
            for (long enc : edges) {
                if (shutdown.stopRequested()) break; // SIGTERM: stop cleanly, then close below
                conn.add(
                        vf.createIRI(RealGraphTriangleBench.vIri(enc >>> 32)),
                        e,
                        vf.createIRI(RealGraphTriangleBench.vIri(enc & 0xFFFFFFFFL)));
                if (++n % batch == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
        double sec = (System.nanoTime() - t0) / 1e9;
        sampler.stop();
        try {
            repo.shutDown(); // drains RocksDB compactions (db.close()) before native handles free
        } catch (Exception ignore) {
            /* cleanup only; timing already captured */
        } finally {
            shutdown.done(); // release the SIGTERM hook: the store is now closed, safe to halt
        }

        long diskBytes = 0;
        try (Stream<Path> w = Files.walk(dir)) {
            diskBytes =
                    w.filter(Files::isRegularFile)
                            .mapToLong(
                                    p -> {
                                        try {
                                            return Files.size(p);
                                        } catch (Exception ex) {
                                            return 0L;
                                        }
                                    })
                            .sum();
        }

        System.out.printf(
                "[%s ingest] %,d edges  indexes=%d  batch=%,d  %.1f s  %,.0f stmts/s  on-disk %.1f MB%n",
                engine, n, idxN, batch, sec, n / sec, diskBytes / 1e6);
    }

    private GraphIngestBench() {}
}
