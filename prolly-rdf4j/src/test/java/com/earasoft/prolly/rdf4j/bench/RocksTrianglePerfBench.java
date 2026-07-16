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
import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.flatsail.RocksFlatStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.rocksdb.RocksDB;

/**
 * Phase 1 of {@code plans/rocksdb-perf-instrumentation.md} — the payoff: settle the session's
 * central but never-measured hypothesis (ProllySail amortizes the RocksDB/JNI boundary <b>per
 * chunk</b>; flatsail pays <b>per key</b>) with hard {@code PerfContext} counters, not inference.
 * Runs the same cyclic-triangle SPARQL over a disk-backed flatsail and a disk-backed ProllySail
 * (bind-join + triejoin), and reports each arm's per-query RocksDB ops. Reports both a <b>cold</b>
 * first query (caches empty → real RocksDB access) and a <b>warm</b> steady-state query. {@code
 * main()} tool; point {@code -Djava.io.tmpdir} at real disk.
 */
public final class RocksTrianglePerfBench {

    public static void main(String[] args) throws Exception {
        int size = args.length > 0 ? Integer.parseInt(args[0]) : 380;
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        String ed = "<" + TriejoinVsRdf4jBenchmark.EDGE + ">";
        String q =
                "SELECT ?x ?y ?z WHERE { ?x " + ed + " ?y . ?y " + ed + " ?z . ?z " + ed + " ?x }";

        System.out.printf("[RocksDB ops per cyclic-triangle query @N=%d edges]%n", size);
        run("flatsail (bind) ", flat(tmp, size), q);
        run("prolly bind-join", prolly(tmp, false, size), q);
        run("prolly triejoin ", prolly(tmp, true, size), q);
    }

    private record Arm(SailRepository repo, RocksDB db) {}

    private static void run(String label, Arm arm, String q) {
        try (RepositoryConnection conn = arm.repo().getConnection()) {
            Runnable drain =
                    () -> {
                        try (TupleQueryResult r =
                                conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
                            while (r.hasNext()) r.next();
                        }
                    };
            RocksPerfProbe.Counters cold = RocksPerfProbe.measure(arm.db(), drain); // caches empty
            for (int i = 0; i < 6; i++) drain.run(); // warm
            RocksPerfProbe.Counters warm = RocksPerfProbe.measure(arm.db(), drain);
            RocksPerfProbe.print(label + " COLD", cold);
            RocksPerfProbe.print(label + " WARM", warm);
        } finally {
            arm.repo().shutDown();
        }
    }

    private static Arm flat(Path tmp, int size) throws Exception {
        RocksFlatStore store =
                RocksFlatStore.open(Files.createTempDirectory(tmp, "flat").toString());
        SailRepository repo = new SailRepository(new RocksDbFlatSail(store));
        repo.init();
        load(repo, size);
        return new Arm(repo, store.db());
    }

    private static Arm prolly(Path tmp, boolean triejoin, int size) throws Exception {
        Path dir = Files.createTempDirectory(tmp, "prolly");
        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        sail.setTriejoinEnabled(triejoin);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        load(repo, size);
        return new Arm(repo, store.db());
    }

    private static void load(SailRepository repo, int size) {
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI e = vf.createIRI(TriejoinVsRdf4jBenchmark.EDGE);
            conn.begin();
            long c = 0;
            for (TriejoinVsRdf4jBenchmark.Edge edge : TriejoinVsRdf4jBenchmark.denseCore(size)) {
                conn.add(
                        vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.from())),
                        e,
                        vf.createIRI(TriejoinVsRdf4jBenchmark.vIri(edge.to())));
                // Batched commits bound memory — never a single-tx mega-transaction (OOMs at
                // scale).
                if (++c % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
    }

    private RocksTrianglePerfBench() {}
}
