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
 * Phase 2 of {@code prolly-rdf4j/plans/join-approaches-benchmark.md} — the real-Sail A/B for the
 * bind-join inner-re-probe memo. Phase 1 ({@code JoinBindingRecurrenceProbe}) predicted, from the
 * data, a 62.4% memo hit rate on the {@code subClassOf} join. This measures whether that recurrence
 * translates to a wall-clock win <b>over the warm node-cache baseline</b> (D-3 — the node cache is
 * shipped, so the honest question is what the memo adds on top, not over a cacheless engine).
 *
 * <p>One Sail, node cache on and warmed; the only variable toggled between arms is the memo.
 * Reports median per-query wall-clock memo-off vs memo-on, the speedup, and the <b>realized</b> hit
 * rate (from the Micrometer counters) — which should match Phase 1's data-level prediction, closing
 * the loop. Run: {@code mvn -pl prolly-rdf4j test -Dtest=BindJoinMemoExperiment
 * -Dncit.zip=/path/to/ncit.zip [-Dexp.sample=50000] [-Dexp.cache.mib=64]}.
 */
public class BindJoinMemoExperiment {

    private static final int WARM = 40;
    private static final int RUNS = 120;

    @Test
    public void measureMemoMarginalWinOverNodeCache() throws Exception {
        String zip = System.getProperty("ncit.zip");
        Assumptions.assumeTrue(
                zip != null && Files.exists(Path.of(zip)), "set -Dncit.zip=/path/to/ncit.zip");
        int sample = Integer.getInteger("exp.sample", 50_000);
        long cacheBytes = Long.getLong("exp.cache.mib", 64L) * 1024 * 1024;

        Path dir = Files.createTempDirectory("ncit-memo-ab");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
        store.setNodeCache(new NodeCache(cacheBytes)); // the shipped, warm baseline (D-3)
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

            // Warm the node cache + JIT on the memo-OFF path, then measure memo-OFF.
            sail.setBindJoinMemoEnabled(false);
            long rows = 0;
            for (int i = 0; i < WARM; i++) rows = runJoin(repo, q);
            long[] off = times(repo, q);

            // Warm the memo-ON path (same warm node cache), then measure memo-ON + capture realized
            // hits.
            sail.setBindJoinMemoEnabled(true);
            for (int i = 0; i < WARM; i++) runJoin(repo, q);
            double hits0 = registry.get("prolly.bindjoinmemo.hits").counter().count();
            double miss0 = registry.get("prolly.bindjoinmemo.misses").counter().count();
            long[] on = times(repo, q);
            double hits = registry.get("prolly.bindjoinmemo.hits").counter().count() - hits0;
            double miss = registry.get("prolly.bindjoinmemo.misses").counter().count() - miss0;

            double offMs = median(off) / 1e6, onMs = median(on) / 1e6;
            System.out.println(
                    "\n========= BIND-JOIN MEMO A/B (NCIt sample="
                            + sample
                            + ", node-cache="
                            + (cacheBytes / 1024 / 1024)
                            + " MiB, warm) =========");
            System.out.printf("join rows=%d   runs=%d%n", rows, RUNS);
            System.out.printf("memo OFF median = %.3f ms%n", offMs);
            System.out.printf("memo ON  median = %.3f ms   speedup = %.2fx%n", onMs, offMs / onMs);
            System.out.printf(
                    "realized memo hit rate = %.1f%% (%.0f hits / %.0f lookups per arm)%n",
                    100.0 * hits / (hits + miss), hits / RUNS, (hits + miss) / RUNS);
            System.out.println("Phase-1 data-level prediction was 62.4% for this join.");
            System.out.println(
                    "==================================================================================\n");
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
