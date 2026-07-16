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
import com.dolthub.prolly.NodeCache;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Follow-on to {@link BindJoinMemoExperiment} — does the <b>node-cache size</b> make a difference,
 * and at what data scale? The methodology point this whole series turns on: a cache's <i>size</i>
 * can only matter in the regime where the <b>working set exceeds the cache</b> (otherwise it "holds
 * everything" and size is irrelevant). The earlier A/B fixed the cache at 64 MiB; this sweeps it (0
 * = off, then 2/8/32/128 MiB) over the {@code subClassOf} join and reports, per size: the
 * node-cache <b>hit rate</b> (does eviction kick in?), the warm <b>working-set bytes</b>, and the
 * bind-join-memo speedup (off vs on).
 *
 * <p>The hypothesis worth testing: the memo's marginal win should <b>grow as the node cache
 * shrinks</b> — when the cache is too small to hold the repeated keys' nodes, each re-probe pays a
 * node-cache miss (RocksDB block-cache read + re-parse), and the memo backstops exactly those
 * repeats. Run at each scale: {@code mvn -pl prolly-rdf4j test -Dtest=NodeCacheSizeSweepExperiment
 * -Dncit.zip=… -Dexp.sample=50000} (then 200000, then 1000000).
 */
public class NodeCacheSizeSweepExperiment {

    private static final int WARM = Integer.getInteger("exp.warm", 25);
    private static final int RUNS = Integer.getInteger("exp.runs", 80);
    private static final long[] CACHE_MIB = {0, 2, 8, 32, 128};

    @Test
    public void sweepNodeCacheSize() throws Exception {
        String zip = System.getProperty("ncit.zip");
        Assumptions.assumeTrue(
                zip != null && Files.exists(Path.of(zip)), "set -Dncit.zip=/path/to/ncit.zip");
        int sample = Integer.getInteger("exp.sample", 50_000);

        Path dir = Files.createTempDirectory("ncit-cache-sweep");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        registry,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            StreamingNcitIngest.streamLoad(repo, sample, 100_000);
            String q =
                    "SELECT ?c ?l WHERE { ?c <"
                            + RDFS.SUBCLASSOF
                            + "> ?s . ?s <"
                            + RDFS.LABEL
                            + "> ?l }";

            System.out.println(
                    "\n===== NODE-CACHE SIZE SWEEP — subClassOf join (NCIt sample="
                            + sample
                            + ") =====");
            System.out.printf(
                    "%8s  %10s  %12s  %10s  %10s  %9s%n",
                    "cacheMiB", "nodeHit%", "workingSet", "memoOFF ms", "memoON ms", "memoWin");
            for (long mib : CACHE_MIB) {
                NodeCache nc = new NodeCache(mib * 1024 * 1024); // fresh, empty; 0 → disabled
                store.setNodeCache(nc);

                sail.setBindJoinMemoEnabled(false);
                for (int i = 0; i < WARM; i++) runJoin(repo, q);
                long h0 = nc.hits(), m0 = nc.misses();
                long[] off = times(repo, q);
                long dh = nc.hits() - h0, dm = nc.misses() - m0;
                double nodeHit = (mib == 0 || dh + dm == 0) ? Double.NaN : 100.0 * dh / (dh + dm);
                long wsKiB = nc.bytes() / 1024;

                sail.setBindJoinMemoEnabled(true);
                for (int i = 0; i < 15; i++) runJoin(repo, q);
                long[] on = times(repo, q);

                double offMs = median(off) / 1e6, onMs = median(on) / 1e6;
                System.out.printf(
                        "%8d  %10s  %10d KiB  %10.3f  %10.3f  %8.2fx%n",
                        mib,
                        Double.isNaN(nodeHit) ? "off" : String.format("%.1f", nodeHit),
                        wsKiB,
                        offMs,
                        onMs,
                        offMs / onMs);
            }
            System.out.println(
                    "(workingSet = node-cache bytes held warm; if it stops growing below the cap, the cap binds → eviction.)");
            System.out.println(
                    "=====================================================================================\n");
        } finally {
            repo.shutDown();
            StreamingNcitIngest.deleteTree(dir);
        }
    }

    private static long runJoin(SailRepository repo, String q) {
        try (RepositoryConnection conn = repo.getConnection();
                TupleQueryResult r = conn.prepareTupleQuery(QueryLanguage.SPARQL, q).evaluate()) {
            long n = 0;
            while (r.hasNext()) {
                r.next();
                n++;
            }
            return n;
        }
    }

    private static long[] times(SailRepository repo, String q) {
        long[] t = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            runJoin(repo, q);
            t[i] = System.nanoTime() - start;
        }
        return t;
    }

    private static double median(long[] t) {
        long[] s = t.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }
}
