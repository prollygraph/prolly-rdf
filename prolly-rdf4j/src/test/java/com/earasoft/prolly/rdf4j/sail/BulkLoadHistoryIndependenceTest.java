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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * History-independence oracle — plans/prolly-bulk-load.md <b>Step 10</b> / <b>ADR-0061</b> Goal 4.
 * Loading the SAME triples via ONE transaction (the build-once path a bulk writer uses) versus MANY
 * small batched commits must produce <b>byte-identical data roots</b> (the dictionary + the four
 * quad indexes), because prolly trees are <em>history-independent</em>: content-defined chunking
 * depends only on the final content, not the insertion or commit order. The dictionary's {@code
 * TermId} is <b>content-addressed</b> — derived from the term's hash ({@code Dictionary.encode}:
 * {@code TermId.ofNatural(hash(term))}), <em>not</em> a sequential counter — so the same term gets
 * the same id regardless of encode order; the rare hash-slot collision is resolved by a
 * deterministic salt-walk that both paths walk identically because they encode terms in the same
 * statement order. So the dictionary tree is identical too.
 *
 * <p>This pins the load-bearing premise of the separate-MVCC bulk writer (ADR-0061): a build-once
 * store <em>is</em> the same tree as the incrementally-built one, so the writer can swap its built
 * root in (or 3-way-merge it) and land a result identical to the normal path. If it ever breaks,
 * the whole bulk-load design is unsound — a release-blocker oracle. Real {@link ProllySail}s, no
 * mocks.
 */
class BulkLoadHistoryIndependenceTest {

    private static byte[] rootHash(StaticMap m) {
        return (m == null || m.root() == null) ? new byte[0] : HashUtils.hash(m.root().bytes());
    }

    private static ProllySail newSail(Path dir) throws Exception {
        Files.createDirectories(dir);
        NodeStore store = new InMemoryNodeStore();
        return new ProllySail(
                store,
                new HeapBufferPool(),
                RootMetaTreeStore.beside(dir),
                CommitLog.beside(dir),
                RefsStore.beside(dir));
    }

    /**
     * Adds {@code n} deterministic triples; commits once every {@code batch} ({@code 0} = single
     * tx).
     */
    private static void load(SailRepository repo, int n, int batch) {
        ValueFactory vf = SimpleValueFactory.getInstance();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (int i = 0; i < n; i++) {
                conn.add(
                        vf.createIRI("urn:s/" + i),
                        vf.createIRI("urn:p/" + (i % 7)),
                        vf.createIRI("urn:o/" + i));
                if (batch > 0 && (i + 1) % batch == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
    }

    @Test
    void build_once_yields_the_same_data_roots_as_batched_commits(@TempDir Path dir)
            throws Exception {
        final int n = 5000; // multi-level trees, so the equality is a real structural claim
        ProllySail single = newSail(dir.resolve("single"));
        ProllySail batched = newSail(dir.resolve("batched"));
        SailRepository singleRepo = new SailRepository(single);
        SailRepository batchedRepo = new SailRepository(batched);
        singleRepo.init();
        batchedRepo.init();
        try {
            load(singleRepo, n, 0); // ONE transaction — build once (what the bulk writer does)
            load(batchedRepo, n, 250); // 20 batched commits — the normal path

            // Byte-identical data roots despite 1 vs 20 commits == history-independence holds.
            assertArrayEquals(
                    rootHash(single.dictRoot()),
                    rootHash(batched.dictRoot()),
                    "dictionary root must be identical for build-once vs batched");
            for (QuadOrder order : QuadOrder.values()) {
                assertArrayEquals(
                        rootHash(single.indexRoot(order)),
                        rootHash(batched.indexRoot(order)),
                        order + " index root must be identical for build-once vs batched");
            }
        } finally {
            singleRepo.shutDown();
            batchedRepo.shutDown();
        }
    }
}
