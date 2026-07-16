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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.index.QuadIndex;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocIndex;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.persistence.PersistedRoot;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.SparqlNamespaces;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.term.TermStats;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SplittableRandom;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end disk persistence tests. Each test opens a real {@code RocksNodeStore} in a temp
 * directory, mutates + commits some state, closes the store, reopens it, and verifies the data is
 * intact.
 *
 * <p>Three test layers:
 *
 * <ul>
 *   <li><b>Substrate</b>: raw chunks survive close/reopen via NodeStore.read/write.
 *   <li><b>Table</b>: each table type (Dictionary, QuadIndex, SparqlNamespaces, TermStats,
 *       PrefixTable) can be re-opened against its committed root.
 *   <li><b>Sail</b>: full ProllySail close/reopen with manual root restore (the Sail doesn't
 *       auto-persist roots yet — that's Phase 4).
 * </ul>
 */
class DiskPersistenceTest {

    private static BufferPool pool() {
        return new HeapBufferPool();
    }

    private static long drain(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        } finally {
            it.close();
        }
        return n;
    }

    // ==================================================================
    // Substrate level: chunks survive close/reopen
    // ==================================================================
    @Nested
    class SubstrateLayer {

        @Test
        void chunk_round_trip_across_close(@TempDir Path dir) throws Exception {
            byte[] data = "hello, disk!".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] hash;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                hash = store.write(data);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                Optional<MemorySegment> back = store.read(hash);
                assertTrue(back.isPresent(), "chunk missing after reopen");
                byte[] bytes = back.get().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                assertArrayEquals(data, bytes);
            }
        }

        @Test
        void many_chunks_round_trip_across_close(@TempDir Path dir) throws Exception {
            SplittableRandom r = new SplittableRandom(42);
            byte[][] data = new byte[200][];
            byte[][] hashes = new byte[200][];
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                for (int i = 0; i < data.length; i++) {
                    data[i] = new byte[1 + r.nextInt(512)];
                    r.nextBytes(data[i]);
                    hashes[i] = store.write(data[i]);
                }
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                for (int i = 0; i < data.length; i++) {
                    byte[] back =
                            store.read(hashes[i])
                                    .orElseThrow()
                                    .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
                    assertArrayEquals(data[i], back, "chunk " + i + " mismatch");
                }
            }
        }

        @Test
        void same_content_same_hash_across_reopen(@TempDir Path dir) throws Exception {
            byte[] data = "deterministic content".getBytes();
            byte[] h1, h2;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                h1 = store.write(data);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                h2 = store.write(data); // same content, expect same hash
            }
            assertArrayEquals(
                    h1,
                    h2,
                    "content-addressed: same bytes → same hash regardless of store instance");
        }
    }

    // ==================================================================
    // Table level: each persistent table reopens at its committed root
    // ==================================================================
    @Nested
    class TableLayer {

        @Test
        void dictionary_persists(@TempDir Path dir) throws Exception {
            TermId aliceId, bobId, oldIntegerId;
            PersistedRoot saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString());
                    Arena arena = Arena.ofConfined()) {
                Dictionary d = new Dictionary(store, pool(), HashFunctions.defaultHash());
                aliceId = d.encode(TermCodec.encodeFullIri("http://example/alice", arena));
                bobId = d.encode(TermCodec.encodeFullIri("http://example/bob", arena));
                oldIntegerId = d.encode(TermCodec.encodeInteger(42L, arena));
                saved = new PersistedRoot(d.commit(), store);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString());
                    Arena arena = Arena.ofConfined()) {
                Dictionary d =
                        new Dictionary(
                                store, pool(), HashFunctions.defaultHash(), saved.reload(store));
                // Decode all three TermIds — bytes should match originals
                MemorySegment alice = d.decode(aliceId).orElseThrow();
                MemorySegment bob = d.decode(bobId).orElseThrow();
                MemorySegment forty2 = d.decode(oldIntegerId).orElseThrow();
                assertEquals(
                        "http://example/alice",
                        TermCodec.decodeFullIri(TermCodec.payloadOf(alice)));
                assertEquals(
                        "http://example/bob", TermCodec.decodeFullIri(TermCodec.payloadOf(bob)));
                assertEquals("42", TermCodec.decodeLexical(TermCodec.payloadOf(forty2)));
                // Re-encoding the same term returns the same TermId (dedup)
                TermId aliceAgain =
                        d.encode(TermCodec.encodeFullIri("http://example/alice", arena));
                assertEquals(aliceId, aliceAgain);
            }
        }

        @Test
        void spoc_index_persists(@TempDir Path dir) throws Exception {
            SpocKey k1 = new SpocKey(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
            SpocKey k2 = new SpocKey(TermId.of(5L), TermId.of(6L), TermId.of(7L), TermId.of(8L));
            SpocKey ghost =
                    new SpocKey(TermId.of(99L), TermId.of(99L), TermId.of(99L), TermId.of(99L));
            PersistedRoot saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                SpocIndex idx = new SpocIndex(store, pool());
                idx.insert(k1);
                idx.insert(k2);
                saved = new PersistedRoot(idx.commit(), store);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                SpocIndex idx = new SpocIndex(store, pool(), saved.reload(store));
                assertTrue(idx.contains(k1), "k1 lost across reopen");
                assertTrue(idx.contains(k2), "k2 lost across reopen");
                assertFalse(idx.contains(ghost), "phantom key appeared");
            }
        }

        @Test
        void quad_index_all_orders_persist(@TempDir Path dir) throws Exception {
            // Save one root per QuadOrder.
            PersistedRoot[] saved = new PersistedRoot[QuadOrder.values().length];
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                for (QuadOrder order : QuadOrder.values()) {
                    QuadIndex idx = new QuadIndex(order, store, pool());
                    idx.insert(TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L));
                    saved[order.ordinal()] = new PersistedRoot(idx.commit(), store);
                }
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                for (QuadOrder order : QuadOrder.values()) {
                    QuadIndex idx =
                            new QuadIndex(
                                    order, store, pool(), saved[order.ordinal()].reload(store));
                    assertTrue(
                            idx.contains(
                                    TermId.of(1L), TermId.of(2L), TermId.of(3L), TermId.of(4L)),
                            order + " lost the quad");
                }
            }
        }

        @Test
        void sparql_namespaces_persists(@TempDir Path dir) throws Exception {
            PersistedRoot saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                SparqlNamespaces ns = new SparqlNamespaces(store, pool());
                ns.set("ex", "http://example.com/");
                ns.set("foaf", "http://xmlns.com/foaf/0.1/");
                ns.set("日本", "https://例え.jp/");
                saved = new PersistedRoot(ns.commit(), store);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                SparqlNamespaces ns = new SparqlNamespaces(store, pool(), saved.reload(store));
                assertEquals("http://example.com/", ns.get("ex").orElseThrow());
                assertEquals("http://xmlns.com/foaf/0.1/", ns.get("foaf").orElseThrow());
                assertEquals("https://例え.jp/", ns.get("日本").orElseThrow());
                assertEquals(3, ns.snapshot().size());
            }
        }

        @Test
        void term_stats_persists(@TempDir Path dir) throws Exception {
            TermId t1 = TermId.of(1L);
            TermId t2 = TermId.of(2L);
            TermId t3 = TermId.ofExtensionSlot(42L); // exercise extension-bit path
            PersistedRoot saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                TermStats stats = new TermStats(store, pool());
                stats.increment(t1, 100L);
                stats.increment(t2, 1L);
                stats.increment(t3, Long.MAX_VALUE / 2);
                saved = new PersistedRoot(stats.commit(), store);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                TermStats stats = new TermStats(store, pool(), saved.reload(store));
                assertEquals(100L, stats.frequency(t1));
                assertEquals(1L, stats.frequency(t2));
                assertEquals(Long.MAX_VALUE / 2, stats.frequency(t3));
                assertEquals(
                        0L, stats.frequency(TermId.of(999L)), "unwritten term has zero frequency");
            }
        }

        @Test
        void prefix_table_persists_with_bootstrap_and_runtime(@TempDir Path dir) throws Exception {
            int customId;
            PersistedRoot saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                PrefixTable pt = new PrefixTable(store, pool());
                customId = pt.register("http://my.example/ns#");
                saved = new PersistedRoot(pt.commit(), store);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                PrefixTable pt = new PrefixTable(store, pool(), saved.reload(store));
                // Bootstrap entries survive
                assertEquals(
                        "http://www.w3.org/2001/XMLSchema#",
                        pt.lookupNamespaceAsString(PrefixTable.ID_XSD).orElseThrow());
                // Custom entry survives
                assertEquals(
                        "http://my.example/ns#",
                        pt.lookupNamespaceAsString(customId).orElseThrow());
                // Runtime sequence continues from the persisted highest id
                assertEquals(customId + 1, pt.register("http://other.example/"));
            }
        }

        @Test
        void dictionary_collision_extension_persists(@TempDir Path dir) throws Exception {
            // Force a collision via FixedHash so both natural + extension entries
            // end up on disk; verify both are recoverable.
            com.earasoft.prolly.rdf4j.term.HashFunction fixed =
                    new com.earasoft.prolly.rdf4j.term.HashFunction() {
                        @Override
                        public long hash(MemorySegment data) {
                            return 0L;
                        }

                        @Override
                        public String name() {
                            return "fixed-0";
                        }
                    };
            TermId natId, extId;
            PersistedRoot saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString());
                    Arena arena = Arena.ofConfined()) {
                Dictionary d = new Dictionary(store, pool(), fixed);
                natId = d.encode(TermCodec.encodeInteger(1L, arena));
                extId = d.encode(TermCodec.encodeInteger(2L, arena)); // forced extension
                assertFalse(natId.isExtension());
                assertTrue(extId.isExtension(), "second term should escalate");
                saved = new PersistedRoot(d.commit(), store);
                store.flushDurable();
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                Dictionary d = new Dictionary(store, pool(), fixed, saved.reload(store));
                assertEquals(
                        "1",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(d.decode(natId).orElseThrow())));
                assertEquals(
                        "2",
                        TermCodec.decodeLexical(
                                TermCodec.payloadOf(d.decode(extId).orElseThrow())));
            }
        }
    }

    // ==================================================================
    // Sail level: full Sail close/reopen with manual root restore
    // ==================================================================
    @Nested
    class SailLayer {

        /** Snapshot all roots Phase 4 will eventually persist into the Manifest. */
        private SavedSailRoots saveRoots(ProllySail sail, NodeStore store) {
            java.util.EnumMap<QuadOrder, PersistedRoot> idxRoots =
                    new java.util.EnumMap<>(QuadOrder.class);
            for (QuadOrder order : QuadOrder.values()) {
                idxRoots.put(order, new PersistedRoot(sail.indexRoot(order), store));
            }
            return new SavedSailRoots(
                    new PersistedRoot(sail.dictRoot(), store),
                    idxRoots,
                    new PersistedRoot(sail.namespacesRoot(), store),
                    new PersistedRoot(sail.statsRoot(), store));
        }

        /** Restore via the Sail's package-private advance accessors. */
        private void restoreRoots(ProllySail sail, SavedSailRoots saved, NodeStore store) {
            sail.advanceDictRoot(saved.dict.reload(store));
            for (QuadOrder order : QuadOrder.values()) {
                sail.advanceIndexRoot(order, saved.indexes.get(order).reload(store));
            }
            sail.advanceNamespacesRoot(saved.namespaces.reload(store));
            sail.advanceStatsRoot(saved.stats.reload(store));
            // Manual restore mirrors production restore: after setting the core roots, publish the
            // atomic snapshot a fork reads (the publication-race invariant — see
            // publishSnapshot()).
            sail.publishSnapshot();
        }

        @Test
        void sail_e2e_one_commit_survives_close_reopen(@TempDir Path dir) throws Exception {
            SavedSailRoots saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    ValueFactory vf = sail.getValueFactory();
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        conn.addStatement(
                                vf.createIRI("http://example/alice"),
                                vf.createIRI("http://example/knows"),
                                vf.createIRI("http://example/bob"));
                        conn.commit();
                    }
                    saved = saveRoots(sail, store);
                    store.flushDurable();
                } finally {
                    sail.shutDown();
                }
            }

            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    restoreRoots(sail, saved, store);
                    try (SailConnection conn = sail.getConnection()) {
                        long n = drain(conn.getStatements(null, null, null, false));
                        assertEquals(1L, n);
                    }
                } finally {
                    sail.shutDown();
                }
            }
        }

        @Test
        void sail_e2e_many_commits_persist(@TempDir Path dir) throws Exception {
            SavedSailRoots saved;
            int N = 100;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    ValueFactory vf = sail.getValueFactory();
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        for (int i = 0; i < N; i++) {
                            conn.addStatement(
                                    vf.createIRI("http://example/s" + i),
                                    vf.createIRI("http://example/p"),
                                    vf.createLiteral(i));
                        }
                        conn.commit();
                    }
                    saved = saveRoots(sail, store);
                    store.flushDurable();
                } finally {
                    sail.shutDown();
                }
            }

            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    restoreRoots(sail, saved, store);
                    try (SailConnection conn = sail.getConnection()) {
                        assertEquals(N, conn.size());
                        // Spot-check a specific statement
                        long matched =
                                drain(
                                        conn.getStatements(
                                                sail.getValueFactory()
                                                        .createIRI("http://example/s50"),
                                                null,
                                                null,
                                                false));
                        assertEquals(1L, matched);
                    }
                } finally {
                    sail.shutDown();
                }
            }
        }

        @Test
        void sail_e2e_round_trip_preserves_values(@TempDir Path dir) throws Exception {
            // Persist a varied set of literal types and verify round-trip integrity.
            SavedSailRoots saved;
            IRI subject;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    ValueFactory vf = sail.getValueFactory();
                    subject = vf.createIRI("http://example/measurement");
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        conn.addStatement(
                                subject,
                                vf.createIRI("http://example/intVal"),
                                vf.createLiteral(42L));
                        conn.addStatement(
                                subject,
                                vf.createIRI("http://example/doubleVal"),
                                vf.createLiteral(3.14159));
                        conn.addStatement(
                                subject,
                                vf.createIRI("http://example/stringVal"),
                                vf.createLiteral("hello"));
                        conn.addStatement(
                                subject,
                                vf.createIRI("http://example/langStr"),
                                vf.createLiteral("Hello", "en"));
                        conn.addStatement(
                                subject,
                                vf.createIRI("http://example/boolVal"),
                                vf.createLiteral(true));
                        conn.commit();
                    }
                    saved = saveRoots(sail, store);
                    store.flushDurable();
                } finally {
                    sail.shutDown();
                }
            }

            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    restoreRoots(sail, saved, store);
                    try (SailConnection conn = sail.getConnection()) {
                        long n = drain(conn.getStatements(subject, null, null, false));
                        assertEquals(5L, n, "all 5 typed literals survive");
                    }
                } finally {
                    sail.shutDown();
                }
            }
        }

        @Test
        void sail_e2e_writes_after_reopen_compose_with_prior(@TempDir Path dir) throws Exception {
            // Write 1, close. Reopen, restore, write 2 more. Verify all 3 visible.
            SavedSailRoots saved;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    ValueFactory vf = sail.getValueFactory();
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        conn.addStatement(
                                vf.createIRI("http://example/s1"),
                                vf.createIRI("http://example/p"),
                                vf.createIRI("http://example/o1"));
                        conn.commit();
                    }
                    saved = saveRoots(sail, store);
                    store.flushDurable();
                } finally {
                    sail.shutDown();
                }
            }

            SavedSailRoots savedFinal;
            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    restoreRoots(sail, saved, store);
                    ValueFactory vf = sail.getValueFactory();
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        conn.addStatement(
                                vf.createIRI("http://example/s2"),
                                vf.createIRI("http://example/p"),
                                vf.createIRI("http://example/o2"));
                        conn.addStatement(
                                vf.createIRI("http://example/s3"),
                                vf.createIRI("http://example/p"),
                                vf.createIRI("http://example/o3"));
                        conn.commit();
                    }
                    savedFinal = saveRoots(sail, store);
                    store.flushDurable();
                } finally {
                    sail.shutDown();
                }
            }

            try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    restoreRoots(sail, savedFinal, store);
                    try (SailConnection conn = sail.getConnection()) {
                        assertEquals(
                                3L,
                                conn.size(),
                                "all three rows (1 from first session + 2 from second) visible");
                    }
                } finally {
                    sail.shutDown();
                }
            }
        }

        @Test
        void sail_e2e_chunk_hash_stable_across_reopen(@TempDir Path dir) throws Exception {
            // Run the same workload twice on independent stores; confirm the
            // committed root chunks are byte-identical. Proves the encoding is
            // deterministic.
            PersistedRoot rootA, rootB;
            try (RocksNodeStore store = new RocksNodeStore(dir.resolve("a").toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    ValueFactory vf = sail.getValueFactory();
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        conn.addStatement(
                                vf.createIRI("http://example/x"),
                                vf.createIRI("http://example/y"),
                                vf.createIRI("http://example/z"));
                        conn.commit();
                    }
                    rootA = new PersistedRoot(sail.dictRoot(), store);
                } finally {
                    sail.shutDown();
                }
            }
            try (RocksNodeStore store = new RocksNodeStore(dir.resolve("b").toString())) {
                ProllySail sail = new ProllySail(store, pool());
                sail.init();
                try {
                    ValueFactory vf = sail.getValueFactory();
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin();
                        conn.addStatement(
                                vf.createIRI("http://example/x"),
                                vf.createIRI("http://example/y"),
                                vf.createIRI("http://example/z"));
                        conn.commit();
                    }
                    rootB = new PersistedRoot(sail.dictRoot(), store);
                } finally {
                    sail.shutDown();
                }
            }
            assertArrayEquals(
                    rootA.rootHash(),
                    rootB.rootHash(),
                    "same workload, different stores → same dict root hash (deterministic encoding)");
        }
    }

    /** Bundle of all the per-table roots needed to restore a Sail. */
    private static record SavedSailRoots(
            PersistedRoot dict,
            java.util.Map<QuadOrder, PersistedRoot> indexes,
            PersistedRoot namespaces,
            PersistedRoot stats) {}
}
