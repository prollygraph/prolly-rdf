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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncCommitEntry;
import com.earasoft.prolly.sync.SyncPack;
import com.earasoft.prolly.sync.SyncPackCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for {@link CommitLogSync} (batch merge) + the pack commit-section wire round trip (owned
 * by {@link SyncPackCodec} since extract-prolly-sync-module D-1).
 *
 * <p>Post-ADR-0071 a commit's parents are parent <b>ids</b>, not tree hashes. {@link #commit}
 * builds a chain whose child references its parent's id (so the merge's parent-resolution sees a
 * connected DAG); {@link #entry} builds an entry with arbitrary tree-hash "parents" only for the
 * wire round-trip tests, where parent-resolution against a log is not exercised.
 */
class CommitLogSyncTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        return h;
    }

    private static List<byte[]> parentHashes(int... seeds) {
        List<byte[]> out = new ArrayList<>();
        for (int s : seeds) {
            out.add(hash(s));
        }
        return out;
    }

    /** The extract-plan D-1 adapter, test-side: sail entry → wire entry (id preserved). */
    private static SyncCommitEntry toSync(CommitLog.Entry e) {
        return new SyncCommitEntry(
                e.timestamp(), e.id(), e.metaTreeHash(), e.parents(), e.message(), e.author());
    }

    /** An entry with arbitrary tree-hash "parents" — for wire round-trip tests only. */
    private static SyncCommitEntry entry(int seed, String message, int... parentSeeds) {
        return toSync(
                new CommitLog.Entry(
                        Instant.ofEpochSecond(seed),
                        hash(seed),
                        parentHashes(parentSeeds),
                        message));
    }

    /** A commit chained by parent <b>id</b> (ADR-0071) — for merge tests with a connected DAG. */
    private static SyncCommitEntry commit(int seed, String message, SyncCommitEntry... parents) {
        List<byte[]> parentIds = new ArrayList<>();
        for (SyncCommitEntry p : parents) {
            parentIds.add(p.id());
        }
        return toSync(
                new CommitLog.Entry(Instant.ofEpochSecond(seed), hash(seed), parentIds, message));
    }

    /** Content equality — record equals() does not compare byte[] deeply. */
    private static void assertSameEntry(SyncCommitEntry expected, SyncCommitEntry actual) {
        assertEquals(expected.timestamp(), actual.timestamp(), "timestamp");
        assertEquals(expected.hashHex(), actual.hashHex(), "id");
        assertEquals(expected.treeHashHex(), actual.treeHashHex(), "metaTreeHash");
        assertEquals(expected.parentsHex(), actual.parentsHex(), "parents");
        assertEquals(expected.message(), actual.message(), "message");
        assertEquals(expected.author(), actual.author(), "author");
    }

    /** As above, against a stored sail-side entry (the merge tests' read-back). */
    private static void assertSameEntry(SyncCommitEntry expected, CommitLog.Entry actual) {
        assertSameEntry(expected, toSync(actual));
    }

    /** Wire round trip through the PUBLIC pack surface — the format now lives in the codec. */
    private static List<SyncCommitEntry> roundTrip(List<SyncCommitEntry> batch) {
        return SyncPackCodec.parse(SyncPackCodec.serialize(new SyncPack(List.of(), batch)))
                .commits();
    }

    private static List<Integer> seeds(List<CommitLog.Entry> entries) {
        return entries.stream().map(e -> e.metaTreeHash()[0] & 0xFF).toList();
    }

    @Test
    void serialize_parse_round_trips_entries() {
        List<SyncCommitEntry> batch =
                List.of(
                        entry(1, ""), // genesis, empty message
                        entry(2, "second commit", 1), // a message with a space
                        entry(3, "merge\nwith newline", 1, 2)); // 2 parents, awkward message
        List<SyncCommitEntry> back = roundTrip(batch);
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) {
            assertSameEntry(batch.get(i), back.get(i));
        }
    }

    @Test
    void serialize_parse_round_trips_the_commit_author() {
        // ADR-0037 follow-on: author now survives push/pull (the Step-2 author seam
        // was previously dropped at the sync boundary).
        List<SyncCommitEntry> batch =
                List.of(
                        toSync(
                                new CommitLog.Entry(
                                        Instant.ofEpochSecond(1),
                                        hash(1),
                                        parentHashes(),
                                        "genesis",
                                        "alice")),
                        toSync(
                                new CommitLog.Entry(
                                        Instant.ofEpochSecond(2),
                                        hash(2),
                                        parentHashes(1),
                                        "",
                                        "Renée O'Brien <r@x>")),
                        toSync(
                                new CommitLog.Entry(
                                        Instant.ofEpochSecond(3),
                                        hash(3),
                                        parentHashes(2),
                                        "msg",
                                        ""))); // empty author → '-'
        List<SyncCommitEntry> back = roundTrip(batch);
        assertEquals(3, back.size());
        for (int i = 0; i < 3; i++) {
            assertSameEntry(batch.get(i), back.get(i));
        }
    }

    @Test
    void merge_preserves_the_commit_author() throws IOException {
        CommitLog local = CommitLog.inMemory();
        CommitLogSync.mergeInto(
                local,
                List.of(
                        toSync(
                                new CommitLog.Entry(
                                        Instant.ofEpochSecond(1),
                                        hash(1),
                                        parentHashes(),
                                        "ingest v1",
                                        "bob"))));
        CommitLog.Entry stored = local.entries().get(0);
        assertEquals("bob", stored.author());
        assertEquals("ingest v1", stored.message());
    }

    @Test
    void merge_into_empty_log_appends_the_whole_batch() throws IOException {
        CommitLog local = CommitLog.inMemory();
        SyncCommitEntry e1 = commit(1, "");
        SyncCommitEntry e2 = commit(2, "", e1);
        SyncCommitEntry e3 = commit(3, "", e2);
        assertEquals(3, CommitLogSync.mergeInto(local, List.of(e1, e2, e3)));
        assertEquals(List.of(1, 2, 3), seeds(local.entries()));
    }

    @Test
    void merge_dedups_entries_the_log_already_has() throws IOException {
        CommitLog local = CommitLog.inMemory();
        SyncCommitEntry e1 = commit(1, "");
        SyncCommitEntry e2 = commit(2, "", e1);
        CommitLogSync.mergeInto(local, List.of(e1, e2)); // seed the log

        // Batch re-sends 1 and 2 and adds 3 — only 3 is new.
        SyncCommitEntry e3 = commit(3, "", e2);
        int appended = CommitLogSync.mergeInto(local, List.of(e1, e2, e3));
        assertEquals(1, appended);
        assertEquals(List.of(1, 2, 3), seeds(local.entries()));
    }

    @Test
    void merge_topologically_orders_an_out_of_order_batch() throws IOException {
        CommitLog local = CommitLog.inMemory();
        SyncCommitEntry e1 = commit(1, "");
        SyncCommitEntry e2 = commit(2, "", e1);
        SyncCommitEntry e3 = commit(3, "", e2);
        // Batch arrives children-first; merge must append parents first.
        int appended = CommitLogSync.mergeInto(local, List.of(e3, e2, e1));
        assertEquals(3, appended);
        assertEquals(List.of(1, 2, 3), seeds(local.entries()), "appended ancestors-first");
    }

    @Test
    void merge_rejects_a_dangling_parent_and_appends_nothing() throws IOException {
        CommitLog local = CommitLog.inMemory();
        // An entry whose parent id (hash(99)) matches no entry in the batch or the log.
        SyncCommitEntry orphan =
                toSync(
                        new CommitLog.Entry(
                                Instant.ofEpochSecond(2), hash(2), List.of(hash(99)), ""));
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitLogSync.mergeInto(local, List.of(orphan)));
        assertTrue(local.entries().isEmpty(), "a rejected batch appends nothing");
    }

    @Test
    void merge_rejects_a_cycle_and_appends_nothing() throws IOException {
        CommitLog local = CommitLog.inMemory();
        // A real id-cycle is unconstructable (a child's id depends on its parent's id), so a cycle
        // can only arrive as a tampered/corrupt batch with explicit ids: a.id=A parent=B, b.id=B
        // parent=A. mergeInto preserves received ids verbatim, so it must still detect the cycle.
        byte[] idA = hash(1);
        byte[] idB = hash(2);
        SyncCommitEntry a =
                new SyncCommitEntry(Instant.ofEpochSecond(1), idA, hash(10), List.of(idB), "", "");
        SyncCommitEntry b =
                new SyncCommitEntry(Instant.ofEpochSecond(2), idB, hash(11), List.of(idA), "", "");
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitLogSync.mergeInto(local, List.of(a, b)));
        assertTrue(local.entries().isEmpty(), "a rejected batch appends nothing");
    }

    @Test
    void merge_preserves_timestamps_parents_and_messages() throws IOException {
        CommitLog local = CommitLog.inMemory();
        SyncCommitEntry e1 = commit(1, "genesis");
        SyncCommitEntry e2 = commit(2, "branch", e1);
        SyncCommitEntry merge = commit(3, "the merge commit", e1, e2);
        CommitLogSync.mergeInto(local, List.of(e1, e2, merge));

        CommitLog.Entry stored = local.entries().get(2);
        assertSameEntry(merge, stored);
    }

    // ---- integrity & anti-tamper (plan Step 22) ---------------------------

    /** Build a ProllySail and commit two triples so its commit log has real metaTreeHashes. */
    private static ProllySail seededSail(Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        new SailRepository(sail).init();
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            conn.begin();
            conn.add(vf.createIRI("urn:a"), vf.createIRI("urn:p"), vf.createIRI("urn:b"));
            conn.commit();
            conn.begin();
            conn.add(vf.createIRI("urn:c"), vf.createIRI("urn:p"), vf.createIRI("urn:d"));
            conn.commit();
        }
        return sail;
    }

    @Test
    void merge_with_store_validates_metaTreeHash_against_chunks(@TempDir Path dir)
            throws IOException {
        // A real sail's commit log is what the validator must accept against
        // that same sail's NodeStore — the happy path. Two commits, both must
        // pass validation when merged into a fresh log against the live store.
        ProllySail source = seededSail(dir);
        List<SyncCommitEntry> entries =
                source.commitLog().orElseThrow().entries().stream()
                        .map(CommitLogSyncTest::toSync)
                        .toList();

        CommitLog target = CommitLog.inMemory();
        int appended = CommitLogSync.mergeInto(target, entries, source.store());
        assertEquals(2, appended, "both real commits validate against the source's store");
    }

    @Test
    void merge_rejects_an_entry_whose_metaTreeHash_is_missing_from_the_store(@TempDir Path dir)
            throws IOException {
        ProllySail source = seededSail(dir);
        List<SyncCommitEntry> real =
                source.commitLog().orElseThrow().entries().stream()
                        .map(CommitLogSyncTest::toSync)
                        .toList();
        SyncCommitEntry phantom =
                toSync(new CommitLog.Entry(Instant.now(), hash(0xee), parentHashes(), "forged"));

        CommitLog target = CommitLog.inMemory();
        // The phantom's metaTreeHash 0xee... is not in the source's store —
        // mergeInto with store must reject the whole batch.
        IllegalArgumentException ex =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                CommitLogSync.mergeInto(
                                        target,
                                        List.of(real.get(0), real.get(1), phantom),
                                        source.store()));
        assertTrue(ex.getMessage().contains("metaTreeHash"), ex.getMessage());
        assertTrue(
                target.entries().isEmpty(),
                "a rejected batch appends nothing — even the otherwise-valid real commits");
    }

    @Test
    void merge_without_store_skips_chunk_integrity_check(@TempDir Path dir) throws IOException {
        // The store==null overload preserves the original behavior — the
        // phantom is accepted because there's no integrity check to fail.
        // Unit tests that synthesize bogus entries depend on this.
        ProllySail source = seededSail(dir);
        SyncCommitEntry phantom =
                toSync(new CommitLog.Entry(Instant.now(), hash(0xee), parentHashes(), "forged"));

        CommitLog target = CommitLog.inMemory();
        int appended = CommitLogSync.mergeInto(target, List.of(phantom));
        assertEquals(1, appended);
        assertFalse(
                target.entries().isEmpty(),
                "without a store argument the legacy un-validated path runs");
    }
}
