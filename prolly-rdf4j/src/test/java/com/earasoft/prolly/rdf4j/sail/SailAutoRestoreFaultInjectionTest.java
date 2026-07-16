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
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fault-injection coverage for {@link ProllySail}'s auto-restore path — {@code initializeInternal}
 * → {@code restoreFromMetaTree} → {@code loadStaticMap}. A configured {@link RootMetaTreeStore}
 * makes {@code init()} rebuild the Sail roots from the persisted manifest; these tests drive two
 * failure modes into that path:
 *
 * <ul>
 *   <li>a manifest that references a chunk absent from the store (corrupt / partially-lost storage)
 *       — must fail loudly with a "missing chunk" diagnosis, not silently mis-restore;
 *   <li>a transient store read failure during the restore.
 * </ul>
 */
class SailAutoRestoreFaultInjectionTest {

    private static boolean messageChainContains(Throwable t, String needle) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(needle)) return true;
        }
        return false;
    }

    private static long count(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try (it) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    /** Commit one triple into a fresh Sail over {@code store}, sidecars beside {@code dir}. */
    private static void seedOneCommit(NodeStore store, Path dir) {
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
                conn.commit();
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void init_throws_when_the_manifest_references_a_missing_chunk(@TempDir Path dir)
            throws Exception {
        // Hand-build a corrupt manifest: a RootMetaTree whose dict entry points
        // at a chunk that was never written. The manifest chunk itself is
        // present, so auto-restore reaches loadStaticMap — which must fail
        // loudly rather than silently mis-restore.
        NodeStore store = new InMemoryNodeStore();
        byte[] danglingHash = new byte[20];
        for (int i = 0; i < 20; i++) danglingHash[i] = (byte) (i + 1);
        RootMetaTree corrupt = new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, danglingHash));
        byte[] rmtHash = corrupt.writeTo(store); // the manifest chunk IS present

        RootMetaTreeStore rmts = RootMetaTreeStore.beside(dir);
        rmts.put(rmtHash);

        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        rmts,
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        Throwable ex =
                assertThrows(
                        Throwable.class,
                        sail::init,
                        "auto-restore must fail when the manifest references a missing chunk");
        assertTrue(
                messageChainContains(ex, "missing chunk"),
                "the failure must name the missing-chunk cause, got: " + ex);
    }

    @Test
    void init_surfaces_a_store_read_failure_during_auto_restore(@TempDir Path dir)
            throws Exception {
        // A real prior commit, then re-open over a fault-injecting store and
        // fail a read during the restore — init() must surface it, not start
        // fresh and silently drop the prior data.
        NodeStore backing = new InMemoryNodeStore();
        seedOneCommit(backing, dir);

        SailFaultInjector injector =
                SailFaultInjector.failNth(SailFaultInjector.FaultPoint.STORE_READ, 1);
        ProllySail sail =
                new ProllySail(
                        new FaultInjectingNodeStore(backing, injector),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        // Pre-armed to fail the first STORE_READ — the first read of the auto-restore.
        Throwable ex =
                assertThrows(
                        Throwable.class,
                        sail::init,
                        "a store read failure during auto-restore must abort init");
        assertTrue(
                messageChainContains(ex, FaultInjectingNodeStore.INJECTED),
                "the injected failure must surface in the init exception chain, got: " + ex);
    }

    @Test
    void clean_auto_restore_reopens_the_committed_data(@TempDir Path dir) throws Exception {
        // Control: with no injection, re-opening over the same store + sidecars
        // restores the prior commit — isolating the failures above.
        NodeStore backing = new InMemoryNodeStore();
        seedOneCommit(backing, dir);

        ProllySail reopened =
                new ProllySail(
                        backing,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        reopened.init();
        try {
            try (SailConnection conn = reopened.getConnection()) {
                assertEquals(
                        1,
                        count(conn.getStatements(null, null, null, false)),
                        "the prior commit must be restored on a clean re-open");
            }
        } finally {
            reopened.shutDown();
        }
    }
}
