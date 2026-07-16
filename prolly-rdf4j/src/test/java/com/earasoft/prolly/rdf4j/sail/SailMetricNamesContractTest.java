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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 9 of {@code plans/sailmetrics-micrometer-revamp.md} — the metric-NAME contract.
 *
 * <p>Pins the Sail's Micrometer meter names (the plan's contracts table) on a {@link
 * SimpleMeterRegistry}, so a silent rename fails CI rather than a dashboard. This complements
 * {@code ProllySailMetricsTest} (which already pins {@code sail.add}/{@code sail.get}/{@code
 * sail.commit}/ {@code sail.commit.total}, {@code index.*}, {@code planner.choice}, and the
 * empty-registry near-noop) by covering the names that test does not reach: the add/remove/rollback
 * meters, the commit-phase timers, and the structural-merge meters. A structural merge is driven
 * with the same snapshot-fork mechanism {@code StructuralMergeTest} uses — against a real {@link
 * ProllySail}, no mocks.
 */
class SailMetricNamesContractTest {

    private static void add(RepositoryConnection c, String s, String p, String o) {
        ValueFactory vf = c.getValueFactory();
        c.add(vf.createIRI("urn:t:" + s), vf.createIRI("urn:t:" + p), vf.createIRI("urn:t:" + o));
    }

    private static void assertMeterPresent(SimpleMeterRegistry m, String name) {
        assertFalse(m.find(name).meters().isEmpty(), "metric name not found (renamed?): " + name);
    }

    /** A full-setup Sail (real meta-tree / commit-log / refs) wired to the given registry. */
    private static ProllySail fullSail(NodeStore store, HeapBufferPool pool, Path dir, Object reg) {
        return reg instanceof SimpleMeterRegistry r
                ? new ProllySail(
                        store,
                        pool,
                        r,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir))
                : new ProllySail(
                        store,
                        pool,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir)); // empty CompositeMeterRegistry default
    }

    @Test
    void hotPathWriteReadRemoveRollbackNamesArePinned(@TempDir Path dir) {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = fullSail(new InMemoryNodeStore(), new HeapBufferPool(), dir, m);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "b");
                add(c, "a", "p", "c");
                c.commit();
            }
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                ValueFactory vf = c.getValueFactory();
                c.remove(vf.createIRI("urn:t:a"), vf.createIRI("urn:t:p"), vf.createIRI("urn:t:b"));
                c.commit();
            }
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "x", "p", "y");
                c.rollback();
            }
            try (RepositoryConnection c = repo.getConnection();
                    var it = c.getStatements(null, null, null, false)) {
                while (it.hasNext()) it.next();
            }
            for (String name :
                    new String[] {
                        "sail.add",
                        "sail.add.encode",
                        "sail.add.insert",
                        "sail.remove",
                        "sail.rollback",
                        "sail.get",
                        "sail.commit",
                        "sail.commit.total",
                        "sail.commit.tables",
                        "sail.commit.prefixes",
                        "sail.commit.metatree",
                    }) {
                assertMeterPresent(m, name);
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void structuralMergeNamesArePinned(@TempDir Path dir) throws Exception {
        NodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = fullSail(store, pool, dir, m);
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "b");
                c.commit();
            }
            byte[] base = sail.currentCommitHash();
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "c"); // main diverges
                c.commit();
            }
            byte[] feature = forkBranch(store, pool, sail, base, c -> add(c, "a", "p", "d"));

            MergeEngine.MergeResult r = MergeEngine.mergeStructural(sail, feature);
            assertEquals(MergeEngine.MergeResult.Kind.OK, r.kind());

            assertMeterPresent(m, "sail.merge.structural");
            assertMeterPresent(m, "sail.merge.structural.count");
            assertTrue(
                    m.getMeters().stream()
                            .anyMatch(mt -> mt.getId().getName().startsWith("sail.merge.tree")),
                    "no sail.merge.tree.<tree> meter recorded by a structural merge");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void noActuatorEmptyCompositeRegistryMergeDoesNotThrow(@TempDir Path dir) throws Exception {
        // D-2: with no MeterRegistry bean the Sail runs on an empty CompositeMeterRegistry — meters
        // are created but record nothing, and NOTHING throws, even through a structural merge.
        NodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        ProllySail sail = fullSail(store, pool, dir, null); // empty composite default
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "b");
                c.commit();
            }
            byte[] base = sail.currentCommitHash();
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                add(c, "a", "p", "c");
                c.commit();
            }
            byte[] feature = forkBranch(store, pool, sail, base, c -> add(c, "a", "p", "d"));
            assertEquals(
                    MergeEngine.MergeResult.Kind.OK,
                    MergeEngine.mergeStructural(sail, feature).kind());
        } finally {
            repo.shutDown();
        }
    }

    /** Fork "feature" off {@code base}, apply {@code work}, register it in the live Sail's log. */
    private static byte[] forkBranch(
            NodeStore store,
            HeapBufferPool pool,
            ProllySail live,
            byte[] base,
            Consumer<RepositoryConnection> work)
            throws Exception {
        ProllySail snap =
                ProllySail.openSnapshotAt(store, pool, new CompositeMeterRegistry(), base);
        SailRepository snapRepo = new SailRepository(snap);
        snapRepo.init();
        byte[] head;
        try {
            try (RepositoryConnection c = snapRepo.getConnection()) {
                c.begin();
                work.accept(c);
                c.commit();
            }
            head = snap.currentCommitHash();
        } finally {
            snapRepo.shutDown();
        }
        live.recordBranchCommit("feature", head, base, "feature");
        return head;
    }
}
