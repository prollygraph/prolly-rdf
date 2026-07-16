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
package com.earasoft.prolly;

import com.dolthub.prolly.*;
import com.earasoft.prolly.indexing.*;
import com.earasoft.prolly.monitor.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.semantic.*;
import com.earasoft.prolly.storage.*;
import com.earasoft.prolly.sync.*;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 *
 *
 * <h3>Crash Recovery & Manifest Atomicity Test</h3>
 *
 * <p>Verifies the durability invariant of the storage layer: the <b>{@link Manifest} is the single
 * source of truth for what is "committed"</b>. Even if the writer aborts after persisting some
 * intermediate Merkle nodes, a re-opened {@link Database} must still observe the previous,
 * fully-reachable root — never a half-updated DAG.
 *
 * <p><b>The Gap:</b> {@code FaultInjectionTest} verifies that a mid-flight exception leaves the
 * manifest unchanged, but it never reopens RocksDB. That leaves the harder question untested: after
 * process death, do the Merkle DAG nodes referenced by the manifest still resolve and hash-verify,
 * given that orphaned nodes from the failed write are sitting next to them on disk?
 *
 * <p><b>Two Crash Modes Exercised:</b>
 *
 * <ol>
 *   <li><b>Pre-commit abort:</b> a second write completes its chunk writes but is killed before
 *       {@link Database#commit(String, StaticMap, byte[], String, String)}. The manifest never
 *       advances.
 *   <li><b>Mid-write abort:</b> an {@link ErrorInjectingNodeStore} throws partway through {@code
 *       applyMutations}, leaving an arbitrary subset of chunk nodes persisted but no usable root.
 * </ol>
 *
 * <p><b>Oracle:</b> after each crash mode, close + reopen the RocksDB store, read the manifest's
 * head, and walk the recovered tree under a {@code HashVerifyingNodeStore}. Every node must
 * resolve, every hash must match its content, and the recovered key set must exactly equal the
 * pre-crash key set. Any deviation is durability corruption.
 */
public class CrashRecoveryAtomicityTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree Crash Recovery & Manifest Atomicity Test ---");
        Path tempDir = Files.createTempDirectory("prolly-crash-recovery");

        TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        DirectBufferPool pool = new DirectBufferPool();

        // ---- Phase 1: Build C0 normally ----
        byte[] c0HeadHash;
        Set<String> c0Keys = new HashSet<>();

        try (RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            Database db = new Database(store, "crash-repo", desc, pool);
            db.createBranch("main", "EMPTY");
            MutableMap mm = new MutableMap(db.getBranch("main"), store, desc, pool);
            for (int i = 0; i < 2000; i++) {
                String k = String.format("c0-%05d", i);
                c0Keys.add(k);
                mm.put(buildKey(pool, k), MemorySegment.ofArray(("v0-" + i).getBytes()));
            }
            if (!db.commit("main", mm.flush(), null, "tester", "C0")) {
                throw new RuntimeException("Initial commit failed");
            }
            c0HeadHash = db.getHeadHash("main").orElseThrow();
            System.out.println("C0 committed; head = " + toHex(c0HeadHash));
        }

        // ---- Crash Mode 1: pre-commit abort ----
        System.out.print("Crash Mode 1 (pre-commit abort): ");
        try (RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            Database db = new Database(store, "crash-repo", desc, pool);
            MutableMap mm = new MutableMap(db.getBranch("main"), store, desc, pool);
            for (int i = 0; i < 2000; i++) {
                mm.put(
                        buildKey(pool, String.format("c1-%05d", i)),
                        MemorySegment.ofArray(("v1-" + i).getBytes()));
            }
            // Build the next StaticMap — this writes intermediate chunk nodes to RocksDB.
            StaticMap next = mm.flush();
            // Persist the new root node so it is *reachable* by hash if anyone tried —
            // emulating the worst case where everything except the manifest update succeeded.
            if (next.root() != null) store.write(next.root().segment());
            // SIMULATED CRASH: never call db.commit(), never advance the manifest.
        }
        System.out.println("aborted before manifest update.");
        verifyHeadAndReachability(tempDir, desc, pool, c0HeadHash, c0Keys);

        // ---- Crash Mode 2: mid-write abort via injected I/O failure ----
        System.out.print("Crash Mode 2 (mid-write abort): ");
        try (RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {
            Database db = new Database(store, "crash-repo", desc, pool);
            ErrorInjectingNodeStore injected = new ErrorInjectingNodeStore(store);
            // Trip after a handful of chunk writes — well before the root is produced.
            injected.injectErrorAfter(5);
            TreeMutator mutator = new TreeMutator(injected, desc, pool);

            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (int i = 0; i < 2000; i++) {
                edits.add(
                        new TreeMutator.Mutation(
                                buildKey(pool, String.format("c2-%05d", i)),
                                MemorySegment.ofArray(("v2-" + i).getBytes())));
            }
            try {
                mutator.applyMutations(db.getBranch("main").root(), edits.iterator());
                throw new RuntimeException("Expected injected failure");
            } catch (RuntimeException expected) {
                if (!"Injected IO Failure".equals(expected.getMessage())) throw expected;
            }
        }
        System.out.println("aborted mid-flight.");
        verifyHeadAndReachability(tempDir, desc, pool, c0HeadHash, c0Keys);

        pool.close();
        System.out.println("--- Crash Recovery & Manifest Atomicity Test PASSED ---");
    }

    /**
     * Reopens RocksDB and asserts that:
     *
     * <ul>
     *   <li>the manifest still points at {@code expectedHead};
     *   <li>every node reachable from that head resolves and hash-verifies;
     *   <li>the recovered key set equals {@code expectedKeys} exactly.
     * </ul>
     */
    private static void verifyHeadAndReachability(
            Path dir,
            TupleDescriptor desc,
            DirectBufferPool pool,
            byte[] expectedHead,
            Set<String> expectedKeys)
            throws Exception {
        try (RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Database db = new Database(store, "crash-repo", desc, pool);
            byte[] head = db.getHeadHash("main").orElseThrow();
            if (!Arrays.equals(head, expectedHead)) {
                throw new RuntimeException(
                        "Manifest advanced unexpectedly: "
                                + toHex(head)
                                + " (expected "
                                + toHex(expectedHead)
                                + ")");
            }

            IntegrityVerifyingNodeStore verifier = new IntegrityVerifyingNodeStore(store);
            Commit commit =
                    Commit.deserialize(
                            verifier.read(head)
                                    .orElseThrow()
                                    .toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
            byte[] rootHash = commit.getRootValueHash();
            walkReachable(verifier, rootHash);

            Node root = verifier.read(rootHash).map(Node::fromBytes).orElseThrow();
            StaticMap recovered = new StaticMap(verifier, root, desc);
            Set<String> recoveredKeys = new HashSet<>();
            MapIterator it = recovered.iter();
            while (it.next()) {
                Tuple kt = new Tuple(it.key());
                recoveredKeys.add(new String(kt.getField(0)));
            }
            if (!recoveredKeys.equals(expectedKeys)) {
                throw new RuntimeException(
                        "Recovered key set diverges from C0: "
                                + " missing="
                                + diffSize(expectedKeys, recoveredKeys)
                                + " unexpected="
                                + diffSize(recoveredKeys, expectedKeys));
            }
            System.out.println(
                    "  Recovered " + recoveredKeys.size() + " keys; every node hash-verified.");
        }
    }

    private static void walkReachable(NodeStore store, byte[] hash) {
        if (hash == null) return;
        Optional<MemorySegment> seg = store.read(hash);
        if (seg.isEmpty()) {
            throw new RuntimeException("Reachable node missing from store: " + toHex(hash));
        }
        Node n = Node.fromBytes(seg.get());
        if (!n.isLeaf()) {
            for (int i = 0; i < n.count(); i++) walkReachable(store, n.getValue(i));
        }
    }

    private static int diffSize(Set<String> a, Set<String> b) {
        Set<String> copy = new HashSet<>(a);
        copy.removeAll(b);
        return copy.size();
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }

    private static String toHex(byte[] bytes) {
        return HashUtils.toHex(bytes);
    }
}
