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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity coverage (plan 08 §8.13): for a battery of branch pairs, {@link
 * MergeEngine#mergeStructural} and the legacy {@link MergeEngine#merge} must produce the
 * <em>same</em> final triple set and the <em>same</em> data-root hashes (dict + four quad indexes).
 *
 * <p>This is the release-blocker guard: two merge paths only stay trustworthy if a parity failure
 * breaks CI. Prolly trees are history-independent (content-defined chunking), so identical final
 * content must yield byte-identical roots regardless of whether the triples arrived by re-insertion
 * ({@code merge}) or structural tree merge ({@code mergeStructural}).
 *
 * <p>The battery deliberately spans single-leaf trees and multi-level trees with a tiny divergence
 * — the case structural merge is built to win. Real {@link ProllySail}s, no mocks.
 */
class StructuralMergeParityTest {

    /** A live Sail with a divergent {@code feature} branch already forked. */
    private static final class Scenario implements AutoCloseable {
        final NodeStore store = new InMemoryNodeStore();
        final HeapBufferPool pool = new HeapBufferPool();
        final ProllySail sail;
        final SailRepository repo;
        byte[] featureHead;

        Scenario(Path dir) throws Exception {
            Files.createDirectories(dir);
            sail =
                    new ProllySail(
                            store,
                            pool,
                            RootMetaTreeStore.beside(dir),
                            CommitLog.beside(dir),
                            RefsStore.beside(dir));
            repo = new SailRepository(sail);
            repo.init();
        }

        /** base triples, then main-only triples, then a feature branch off base. */
        void build(int baseCount, int mainCount, int featureCount) throws Exception {
            byte[] base = commit(repo, "b", 0, baseCount);
            if (mainCount > 0) commit(repo, "m", 0, mainCount);

            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            base);
            SailRepository snapRepo = new SailRepository(snap);
            snapRepo.init();
            try {
                commit(snapRepo, "f", 0, featureCount);
                featureHead = snap.currentCommitHash();
            } finally {
                snapRepo.shutDown();
            }
            sail.recordBranchCommit("feature", featureHead, base, "feature");
        }

        @Override
        public void close() {
            repo.shutDown();
        }
    }

    /** Commit {@code count} triples (prefix-NNN, p, prefix-o-NNN); return new head. */
    private static byte[] commit(SailRepository repo, String prefix, int from, int count) {
        try (RepositoryConnection c = repo.getConnection()) {
            ValueFactory vf = c.getValueFactory();
            c.begin();
            for (int i = from; i < from + count; i++) {
                c.add(
                        vf.createIRI("urn:t:" + prefix + "-" + i),
                        vf.createIRI("urn:t:p"),
                        vf.createIRI("urn:t:" + prefix + "-o-" + i));
            }
            c.commit();
        }
        return repo.getSail() instanceof ProllySail ps ? ps.currentCommitHash() : null;
    }

    private static Set<String> triples(SailRepository repo) {
        Set<String> out = new HashSet<>();
        try (RepositoryConnection c = repo.getConnection();
                var it = c.getStatements(null, null, null, false)) {
            while (it.hasNext()) {
                Statement st = it.next();
                out.add(st.getSubject() + "|" + st.getPredicate() + "|" + st.getObject());
            }
        }
        return out;
    }

    private static byte[] rootHash(StaticMap m) {
        return (m == null || m.root() == null) ? new byte[0] : HashUtils.hash(m.root().bytes());
    }

    private record Battery(String name, int base, int main, int feature) {}

    @Test
    void structural_and_legacy_merge_agree_across_a_branch_battery(@TempDir Path dir)
            throws Exception {
        List<Battery> battery =
                List.of(
                        new Battery("single-leaf", 5, 3, 3),
                        new Battery("medium", 300, 50, 50),
                        new Battery("multi-level-tiny", 2000, 10, 10),
                        new Battery("lopsided", 1, 200, 5));

        for (Battery b : battery) {
            // Build two byte-identical Sails; merge one each way.
            try (Scenario legacy = new Scenario(dir.resolve(b.name() + "-legacy"));
                    Scenario structural = new Scenario(dir.resolve(b.name() + "-structural"))) {

                legacy.build(b.base(), b.main(), b.feature());
                structural.build(b.base(), b.main(), b.feature());

                // Deterministic content → identical commit hashes across the two.
                assertArrayEquals(
                        legacy.featureHead,
                        structural.featureHead,
                        b.name() + ": deterministic build must yield identical feature hashes");

                MergeEngine.MergeResult lr =
                        MergeEngine.merge(legacy.sail, legacy.repo, legacy.featureHead);
                MergeEngine.MergeResult sr =
                        MergeEngine.mergeStructural(structural.sail, structural.featureHead);

                assertNotEquals(MergeEngine.MergeResult.Kind.CONFLICT, lr.kind(), b.name());
                assertNotEquals(MergeEngine.MergeResult.Kind.CONFLICT, sr.kind(), b.name());

                // (1) same final triple set
                assertEquals(
                        triples(legacy.repo),
                        triples(structural.repo),
                        b.name() + ": merged triple sets must match");

                // (2) same data-root hashes — dict + four quad indexes
                assertArrayEquals(
                        rootHash(legacy.sail.dictRoot()),
                        rootHash(structural.sail.dictRoot()),
                        b.name() + ": merged dict root must be byte-identical");
                for (QuadOrder order : QuadOrder.values()) {
                    assertArrayEquals(
                            rootHash(legacy.sail.indexRoot(order)),
                            rootHash(structural.sail.indexRoot(order)),
                            b.name() + ": merged " + order + " index root must be byte-identical");
                }
            }
        }
    }
}
