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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.bench.CountingNodeStore;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of {@code plans/streaming-commit-diff.md} — the bounded-work proof of Goal 3: a small
 * commit on a large repo does O(changes)-dominated work, not O(snapshot).
 *
 * <p><b>What the streaming rewrite delivers — measured on a one-triple commit over a 20k-triple
 * base (deterministic node-read counts via {@link CountingNodeStore} over an in-memory store):</b>
 *
 * <ul>
 *   <li><b>Heap (materialized objects): O(changes), not O(snapshot).</b> The old path buffered
 *       <em>both</em> commit snapshots into heap maps — O(snapshot) {@code Statement} objects, the
 *       out-of-memory hazard this plan split from {@code oom-hardening} to fix. The new path
 *       streams: the one-triple commit materializes exactly <b>1</b> event vs the old path's
 *       <b>~40,001</b> (both snapshots). ~40,000× fewer objects — asserted.
 *   <li><b>Reads: the per-triple decode drops to O(changes).</b> The old path decoded every triple
 *       of <em>both</em> snapshots (it keyed its maps by a stringified {@code (s,p,o,c)}, forcing a
 *       dictionary walk per term) — ~120k node reads <em>per</em> snapshot. The new path decodes
 *       only the changed triples: <b>~390</b> reads total. >100× fewer than even one snapshot's
 *       decode — asserted.
 * </ul>
 *
 * <p><b>The honest residual.</b> Those ~390 reads are almost entirely the SPOC tree <em>walk</em>:
 * {@code DiffEngine.diffIterator} has only a whole-tree short-circuit (identical roots → 0 reads)
 * and a per-leaf one (it <em>reads</em> each leaf, then skips its entries when byte-identical) —
 * <b>no internal-subtree skip</b> — so it walks every leaf of both trees. That walk is O(snapshot
 * SPOC nodes) = O(n / fanout): small under prolly's high fanout (~190 SPOC nodes for 20k triples)
 * but not strictly O(changes). An internal-node Merkle skip in {@code DiffEngine} would cut it to
 * O(log n + changes) — a separate prolly-port-core enhancement. The dominant old cost — decoding
 * every triple of both snapshots — is eliminated regardless.
 *
 * <p><b>Measurement note (instrument must match the workload).</b> An earlier version of this test
 * compared the diff against a snapshot scan that drained {@code getStatements} <em>without</em>
 * decoding (192 reads), making the diff (390) look worse. That control was unfaithful — the real
 * old path decoded every triple to key its map. {@link #readAll} (decode included) is the faithful
 * control (~120k reads/snapshot), and against it the diff reads two orders of magnitude fewer.
 */
class CommitDiffStreamBoundedWorkTest {

    private static final ValueFactory VF =
            org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();

    @Test
    void smallCommitDoesOChangesWorkNotOSnapshot() throws Exception {
        int base = 20_000;
        Path dir = Files.createTempDirectory("commit-diff-bound-");
        CountingNodeStore store = new CountingNodeStore(new InMemoryNodeStore());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                for (int i = 0; i < base; i++) {
                    c.add(
                            VF.createIRI("urn:b:s" + i),
                            VF.createIRI("urn:b:p"),
                            VF.createIRI("urn:b:o" + i));
                    if ((i + 1) % 2_000 == 0) {
                        c.commit();
                        c.begin();
                    }
                }
                c.commit();
            }
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                c.add(
                        VF.createIRI("urn:b:sNEW"),
                        VF.createIRI("urn:b:p"),
                        VF.createIRI("urn:b:oNEW"));
                c.commit();
            }

            List<CommitLog.Entry> entries = sail.commitLog().orElseThrow().entries();
            CommitLog.Entry head = entries.get(entries.size() - 1);
            // Post-ADR-0071 a commit's identity is its id (hash over tree + parents + author +
            // message), distinct from its tree hash. CommitDiffStream.stream takes commit IDS (it
            // resolves each to a tree hash via treeHashOf internally); readAll/openSnapshotAt take
            // a
            // TREE hash directly. So capture both handles for each side and feed each consumer the
            // one it needs — the bounded-work semantics the test checks are unchanged.
            byte[] hereId = head.id();
            byte[] hereTree = head.metaTreeHash();
            byte[] parentId = head.parents().get(0); // parents now hold parent commit IDS
            byte[] parentTree = sail.treeHashOf(parentId);

            // The OLD path materialized BOTH whole snapshots as maps — O(snapshot) Statements.
            int oldMaterialized = readAll(sail, hereTree).size() + readAll(sail, parentTree).size();

            // The NEW path streams — materializes only changed events; tally its node reads.
            int[] newMaterialized = {0};
            long readsBefore = store.readCount();
            new CommitDiffStream(sail).stream(hereId, parentId, ev -> newMaterialized[0]++);
            long diffReads = store.readCount() - readsBefore;

            // Faithful control: ONE snapshot read the old way (decode included, as the old map key
            // forced). The old path did this twice; the diff reads far fewer than even one.
            readsBefore = store.readCount();
            int scanned = readAll(sail, hereTree).size();
            long scanReads = store.readCount() - readsBefore;

            System.out.printf(
                    "[commit-diff-bound] base=%,d%n"
                            + "  materialized: old(two snapshots)=%,d  new(streamed)=%,d  -> %,dx"
                            + " fewer objects%n"
                            + "  node reads:   one snapshot decoded=%,d  streaming diff=%,d  -> %dx"
                            + " fewer (per-triple decode eliminated; residual is the SPOC walk)%n",
                    base,
                    oldMaterialized,
                    newMaterialized[0],
                    oldMaterialized / Math.max(1, newMaterialized[0]),
                    scanReads,
                    diffReads,
                    scanReads / Math.max(1, diffReads));

            assertEquals(
                    1,
                    newMaterialized[0],
                    "the streaming diff materializes exactly the change-set (one triple), not the"
                            + " snapshot");
            assertTrue(
                    (long) newMaterialized[0] * 1_000 < oldMaterialized,
                    "heap bound: the streaming diff must materialize orders of magnitude fewer"
                            + " objects than buffering both snapshots — new="
                            + newMaterialized[0]
                            + " old="
                            + oldMaterialized);
            assertTrue(
                    diffReads * 10 < scanReads,
                    "read bound: by decoding only changed triples, the streaming diff must read far"
                            + " fewer store nodes than one decoded snapshot — diff="
                            + diffReads
                            + " scan="
                            + scanReads);
            assertTrue(scanned > base, "sanity: the base snapshot scan saw the whole corpus");
        } finally {
            repo.shutDown();
            deleteTree(dir);
        }
    }

    /**
     * The old buffer-a-whole-snapshot read: open a snapshot Sail and drain a full {@code
     * getStatements}, keying each statement by a stringified {@code (s,p,o,c)} — which decodes
     * every term, exactly as the replaced {@code readAllTriples} did.
     */
    private static Map<String, Statement> readAll(ProllySail live, byte[] commit) {
        ProllySail snap =
                ProllySail.openSnapshotAt(
                        live.store(), live.pool(), new CompositeMeterRegistry(), commit);
        SailRepository repo = new SailRepository(snap);
        repo.init();
        try (RepositoryConnection c = repo.getConnection();
                var it = c.getStatements(null, null, null, false)) {
            Map<String, Statement> out = new LinkedHashMap<>();
            while (it.hasNext()) {
                Statement st = it.next();
                out.put(
                        st.getSubject()
                                + "|"
                                + st.getPredicate()
                                + "|"
                                + st.getObject()
                                + "|"
                                + st.getContext(),
                        st);
            }
            return out;
        } finally {
            repo.shutDown();
        }
    }

    private static void deleteTree(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignore) {
                                    // best-effort temp cleanup
                                }
                            });
        }
    }
}
