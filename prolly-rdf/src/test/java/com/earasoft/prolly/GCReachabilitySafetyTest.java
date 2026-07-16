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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 *
 * <h3>GC Reachability Safety Test</h3>
 *
 * <p>Asserts the safety invariant of {@link GarbageCollector}: <b>no node reachable from any live
 * branch ref may be deleted</b>, and any node that remains after a sweep must still hash-verify.
 *
 * <p><b>The Gap:</b> the prior {@code GCTest} (34 lines) only proved GC ran to completion on a
 * trivial two-commit history; it did not pin the post-GC reachable set or verify that anything
 * survived intact. The danger mode for a mark-and-sweep walker is collecting a node that some live
 * ref still depends on — the deletion is silent and only surfaces later when a reader hits a
 * missing hash. This test pins that case directly.
 *
 * <p><b>Bug uncovered &amp; fixed:</b> the original {@code GarbageCollector#collect()} walked
 * reachability starting from {@code db.getBranch(branch).root()} — the <i>data tree</i> root. The
 * {@link Commit} object the manifest actually points at (also a 20-byte hash key) was never marked,
 * so the sweeper deleted it. After {@code collect()} returned, the manifest still claimed {@code
 * heads/{branch} -> commitHash}, but {@code store.read(commitHash)} was empty, and every subsequent
 * {@code db.getBranch(name)} silently degraded to an empty {@link StaticMap}. This test caught the
 * regression on its first run; the fix walks the commit DAG from each branch head, marking the
 * commit hash (and its ancestors) before recursing into the data tree. See the mark-phase loop in
 * {@link GarbageCollector#collect()}.
 *
 * <p><b>Topology:</b>
 *
 * <ul>
 *   <li>Branch {@code A}: 4000 keys with prefix "shared-" (large overlap surface).
 *   <li>Branch {@code B}: forks from {@code A}, then mutates 500 keys — intentionally leaving most
 *       chunks shared with {@code A}.
 *   <li>Branch {@code C}: forks from {@code A}, adds 1000 disjoint keys — creating chunks unique to
 *       {@code C} interleaved with shared ones.
 *   <li>An "orphan" tree of 200 keys is built and its root persisted, but no branch references it.
 *       GC must delete every one of its chunks.
 * </ul>
 *
 * <p><b>Oracle:</b> after {@link GarbageCollector#collect()}:
 *
 * <ol>
 *   <li>Every branch's commit hash (the manifest target) must still resolve in the store — this is
 *       the assertion that fails if the bug above reappears.
 *   <li>For each branch, walking from the data root via a hash-verifying decorator must succeed for
 *       every node and produce the exact key set that was committed.
 *   <li>Every orphan chunk hash must be absent from the store, except where it coincidentally
 *       collides with a live, reachable hash.
 *   <li>Every tracked-reachable hash from before GC must still be present (no live node was swept).
 * </ol>
 */
public class GCReachabilitySafetyTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- Prolly Tree GC Reachability Safety Test ---");
        Path tempDir = Files.createTempDirectory("prolly-gc-safety");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            TupleDescriptor desc = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
            Database db = new Database(store, "gc-safety-repo", desc, pool);

            // Branch A: base content
            db.createBranch("A", "EMPTY");
            Set<String> aKeys = new HashSet<>();
            {
                MutableMap mm = new MutableMap(db.getBranch("A"), store, desc, pool);
                for (int i = 0; i < 4000; i++) {
                    String k = String.format("shared-%05d", i);
                    aKeys.add(k);
                    mm.put(buildKey(pool, k), MemorySegment.ofArray(("a-" + i).getBytes()));
                }
                db.commit("A", mm.flush(), null, "tester", "A v1");
            }

            // Branch B: fork A, modify 500 keys
            db.createBranch("B", "A");
            Set<String> bKeys = new HashSet<>(aKeys);
            {
                MutableMap mm = new MutableMap(db.getBranch("B"), store, desc, pool);
                for (int i = 0; i < 500; i++) {
                    String k = String.format("shared-%05d", i);
                    mm.put(
                            buildKey(pool, k),
                            MemorySegment.ofArray(("b-overwrite-" + i).getBytes()));
                }
                byte[] parent = db.getHeadHash("B").orElseThrow();
                db.commit("B", mm.flush(), parent, "tester", "B v1");
            }

            // Branch C: fork A, add 1000 disjoint keys
            db.createBranch("C", "A");
            Set<String> cKeys = new HashSet<>(aKeys);
            {
                MutableMap mm = new MutableMap(db.getBranch("C"), store, desc, pool);
                for (int i = 0; i < 1000; i++) {
                    String k = String.format("c-only-%05d", i);
                    cKeys.add(k);
                    mm.put(buildKey(pool, k), MemorySegment.ofArray(("c-" + i).getBytes()));
                }
                byte[] parent = db.getHeadHash("C").orElseThrow();
                db.commit("C", mm.flush(), parent, "tester", "C v1");
            }

            // Orphan: build a tree, persist nodes, but never reference it.
            Set<String> orphanHashes = new HashSet<>();
            {
                TreeMutator mutator = new TreeMutator(store, desc, pool);
                List<TreeMutator.Mutation> orphan = new ArrayList<>();
                for (int i = 0; i < 200; i++) {
                    orphan.add(
                            new TreeMutator.Mutation(
                                    // 0-padded so byte-order matches numeric order.
                                    buildKey(pool, String.format("orphan-%03d", i)),
                                    MemorySegment.ofArray(("o-" + i).getBytes())));
                }
                Node orphanRoot = mutator.applyMutations(null, orphan.iterator());
                byte[] orphanRootHash = store.write(orphanRoot.segment());
                collectAllReachable(store, orphanRootHash, orphanHashes);
                System.out.println(
                        "Orphan tree persisted: "
                                + orphanHashes.size()
                                + " chunks expected to be swept.");
            }

            // Snapshot the live-reachable hashes (Commit objects + every node reachable
            // from each branch's data root). The Commit objects sit at 20-byte hash keys
            // exactly like Merkle nodes, so they are *also* candidates for the sweeper —
            // if GC's walker never marks them, the sweep will delete them and the
            // manifest will point at vanished hashes after collect() returns.
            Set<String> liveBefore = new HashSet<>();
            java.util.Map<String, byte[]> commitHashByBranch = new java.util.HashMap<>();
            for (String name : List.of("A", "B", "C")) {
                byte[] commitHash = db.getHeadHash(name).orElseThrow();
                commitHashByBranch.put(name, commitHash);
                liveBefore.add(toHex(commitHash));
                StaticMap sm = db.getBranch(name);
                if (sm.root() != null) {
                    byte[] rootHash = store.write(sm.root().segment());
                    collectAllReachable(store, rootHash, liveBefore);
                }
            }
            System.out.println(
                    "Live reachable chunks before GC: "
                            + liveBefore.size()
                            + " (incl. "
                            + commitHashByBranch.size()
                            + " commit objects)");

            new GarbageCollector(db, store).collect();

            // Pre-flight: the Commit objects the manifest still references must resolve.
            // If GC swept them, every subsequent branch read silently degrades to empty.
            for (var e : commitHashByBranch.entrySet()) {
                if (store.read(e.getValue()).isEmpty()) {
                    throw new RuntimeException(
                            "GC SAFETY VIOLATION: branch "
                                    + e.getKey()
                                    + " still references commit hash "
                                    + toHex(e.getValue())
                                    + " in the manifest, but that commit was swept."
                                    + " GarbageCollector.collect() walks from the data root"
                                    + " (sm.root()) but never marks the Commit object itself"
                                    + " as reachable — the manifest's branch->commit pointer"
                                    + " becomes a dangling reference.");
                }
            }

            // Oracle 1 + 3: every branch walk succeeds + every previously-live hash is still
            // present.
            IntegrityVerifyingNodeStore verifier = new IntegrityVerifyingNodeStore(store);
            for (var pair :
                    List.of(
                            new Object[] {"A", aKeys},
                            new Object[] {"B", bKeys},
                            new Object[] {"C", cKeys})) {
                String name = (String) pair[0];
                @SuppressWarnings("unchecked")
                Set<String> expected = (Set<String>) pair[1];
                StaticMap sm = new StaticMap(verifier, db.getBranch(name).root(), desc);
                Set<String> got = new HashSet<>();
                MapIterator it = sm.iter();
                while (it.next()) {
                    got.add(new String(new Tuple(it.key()).getField(0)));
                }
                if (!got.equals(expected)) {
                    throw new RuntimeException(
                            "Branch "
                                    + name
                                    + " key set diverges after GC: missing="
                                    + diff(expected, got).size()
                                    + " extra="
                                    + diff(got, expected).size());
                }
            }
            for (String h : liveBefore) {
                if (store.read(fromHex(h)).isEmpty()) {
                    throw new RuntimeException("GC swept a live node! hash=" + h);
                }
            }
            System.out.println("Branch walks: all 3 verified, all live nodes present.");

            // Oracle 2: orphan chunks must be gone.
            int survivedOrphans = 0;
            for (String h : orphanHashes) {
                if (store.read(fromHex(h)).isPresent()) survivedOrphans++;
            }
            // Some orphan chunks may collide with branch chunks (deduplication),
            // so allow an orphan to survive iff its hash is also reachable.
            int unjustifiedSurvivors = 0;
            for (String h : orphanHashes) {
                if (store.read(fromHex(h)).isPresent() && !liveBefore.contains(h)) {
                    unjustifiedSurvivors++;
                }
            }
            System.out.println(
                    "Orphan chunks: "
                            + orphanHashes.size()
                            + " total, "
                            + survivedOrphans
                            + " survived ("
                            + unjustifiedSurvivors
                            + " unjustified).");
            if (unjustifiedSurvivors > 0) {
                throw new RuntimeException(
                        "GC failed to sweep "
                                + unjustifiedSurvivors
                                + " unreachable orphan chunks");
            }

            System.out.println("--- GC Reachability Safety Test PASSED ---");
        }
    }

    private static void collectAllReachable(NodeStore store, byte[] hash, Set<String> out) {
        if (hash == null) return;
        String hex = toHex(hash);
        if (!out.add(hex)) return;
        store.read(hash)
                .ifPresent(
                        seg -> {
                            Node n = Node.fromBytes(seg);
                            if (!n.isLeaf()) {
                                for (int i = 0; i < n.count(); i++)
                                    collectAllReachable(store, n.getValue(i), out);
                            }
                        });
    }

    private static MemorySegment buildKey(DirectBufferPool pool, String k) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, k.getBytes());
        return tb.build().segment();
    }

    private static Set<String> diff(Set<String> a, Set<String> b) {
        Set<String> r = new HashSet<>(a);
        r.removeAll(b);
        return r;
    }

    private static String toHex(byte[] bytes) {
        return HashUtils.toHex(bytes);
    }

    private static byte[] fromHex(String hex) {
        return HashUtils.fromHex(hex);
    }
}
