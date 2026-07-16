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

import com.dolthub.prolly.InMemoryNodeStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link CommitLog} — the append-only sidecar that drives the Memento-Datetime
 * header and the TimeMap endpoint.
 *
 * <p>ADR-0073: a file-backed log persists a thin {@code "<datetime> <id>"} row + the commit's
 * content-addressed chunk, and reconstructs each {@link CommitLog.Entry}'s content from that chunk
 * on read — so every file-backed log needs a {@code NodeStore} attached ({@link
 * CommitLog#beside(Path, com.dolthub.prolly.NodeStore)}). The content the tests append round-trips
 * through the chunk (the {@code metaTreeHash} bytes need not name a real tree — a commit chunk
 * merely records them).
 */
class CommitLogTest {

    private static byte[] hash(int seed) {
        byte[] out = new byte[20];
        out[0] = (byte) seed;
        return out;
    }

    @Test
    void cache_serves_repeated_reads_from_memory(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01));
        log.append(Instant.parse("2026-05-12T23:14:49Z"), hash(0x02));

        CommitLog.CachedEntries first = log.cache();
        CommitLog.CachedEntries second = log.cache();
        // memoized: the SAME instance comes back — no file re-read, no chunk re-fetch
        assertSame(first, second);
        // Entry is a record over byte[] fields (reference equality) — compare by id
        assertEquals(
                log.entries().stream().map(CommitLog.Entry::hashHex).toList(),
                first.entries().stream().map(CommitLog.Entry::hashHex).toList());
        assertEquals(2, first.size());
        // O(1) lookups agree with the list
        String oldest = first.entries().get(0).hashHex();
        String newest = first.entries().get(1).hashHex();
        assertEquals(0, first.seqOf(oldest));
        assertEquals(1, first.seqOf(newest));
        assertSame(first.entries().get(1), first.byHash(newest));
        assertEquals(-1, first.seqOf("deadbeef"));
        assertNull(first.byHash("deadbeef"));
    }

    @Test
    void cache_invalidates_on_append(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01));
        CommitLog.CachedEntries before = log.cache();
        assertEquals(1, before.size());

        log.append(Instant.parse("2026-05-12T23:14:49Z"), hash(0x02));
        CommitLog.CachedEntries after = log.cache();
        assertNotSame(before, after);
        assertEquals(2, after.size());
        assertEquals(1, after.seqOf(after.entries().get(1).hashHex()));
    }

    @Test
    void cache_invalidates_on_append_in_memory_mode() throws Exception {
        CommitLog log = CommitLog.inMemory();
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01));
        CommitLog.CachedEntries before = log.cache();
        log.append(Instant.parse("2026-05-12T23:14:49Z"), hash(0x02));
        assertNotSame(before, log.cache());
        assertEquals(2, log.cache().size());
    }

    @Test
    void empty_log_has_no_entries(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        assertTrue(log.entries().isEmpty());
        assertTrue(log.latest().isEmpty());
    }

    @Test
    void appending_round_trips_to_disk(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        Instant t1 = Instant.parse("2026-05-12T23:14:48Z");
        byte[] h1 = hash(0x6b);
        log.append(t1, h1);

        List<CommitLog.Entry> back = log.entries();
        assertEquals(1, back.size());
        assertEquals(t1, back.get(0).timestamp());
        assertArrayEquals(h1, back.get(0).metaTreeHash());
    }

    @Test
    void appending_preserves_chronological_order(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        Instant t1 = Instant.parse("2026-05-12T23:14:48Z");
        Instant t2 = Instant.parse("2026-05-13T08:30:00Z");
        Instant t3 = Instant.parse("2026-05-14T12:00:00Z");
        log.append(t1, hash(0x01));
        log.append(t2, hash(0x02));
        log.append(t3, hash(0x03));

        List<CommitLog.Entry> back = log.entries();
        assertEquals(3, back.size());
        assertEquals(t1, back.get(0).timestamp());
        assertEquals(t2, back.get(1).timestamp());
        assertEquals(t3, back.get(2).timestamp());
    }

    @Test
    void latest_returns_most_recent(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01));
        log.append(Instant.parse("2026-05-13T08:30:00Z"), hash(0x02));
        log.append(Instant.parse("2026-05-14T12:00:00Z"), hash(0x03));

        CommitLog.Entry latest = log.latest().orElseThrow();
        assertArrayEquals(hash(0x03), latest.metaTreeHash());
    }

    @Test
    void findById_returns_matching_entry(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01));
        log.append(Instant.parse("2026-05-13T08:30:00Z"), hash(0x02));

        // The id is computed from the entry's content (ADR-0071); look it up by that id.
        byte[] id2 = log.entries().get(1).id();
        CommitLog.Entry found = log.findById(id2).orElseThrow();
        assertEquals(Instant.parse("2026-05-13T08:30:00Z"), found.timestamp());
    }

    @Test
    void findById_unknown_returns_empty(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01));
        assertTrue(log.findById(hash(0xFF)).isEmpty());
    }

    @Test
    void entry_rfc1123_round_trip(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        // Pick an Instant that has a stable RFC 1123 representation.
        Instant when = Instant.parse("2026-05-12T23:14:48Z");
        log.append(when, hash(0x7f));

        CommitLog.Entry back = log.latest().orElseThrow();
        assertEquals("Tue, 12 May 2026 23:14:48 GMT", back.rfc1123());
    }

    @Test
    void parse_rejects_malformed_line(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("commits.log");
        java.nio.file.Files.writeString(file, "not a real line\n");
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        // Wrong token count for a "<datetime> <id>" row → parseThinRow rejects it loudly.
        assertThrows(IllegalStateException.class, log::entries);
    }

    @Test
    void parse_rejects_a_line_with_a_bad_hex_id(@TempDir Path dir) throws Exception {
        // Right token count (6 datetime + 1 id) so the length check passes, but the id is not valid
        // hex — exercises parseThinRow's catch-and-rethrow branch.
        Path file = dir.resolve("commits.log");
        java.nio.file.Files.writeString(file, "Tue, 12 May 2026 23:14:48 GMT not-a-hex-id\n");
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        assertThrows(IllegalStateException.class, log::entries);
    }

    @Test
    void entry_two_arg_convenience_ctor_defaults_parents_and_message() {
        CommitLog.Entry e = new CommitLog.Entry(Instant.parse("2026-05-12T23:14:48Z"), hash(0x11));
        assertTrue(e.parents().isEmpty(), "two-arg ctor → genesis (no parents)");
        assertEquals("", e.message(), "two-arg ctor → empty message");
    }

    @Test
    void entry_three_arg_convenience_ctor_defaults_message() {
        CommitLog.Entry e =
                new CommitLog.Entry(
                        Instant.parse("2026-05-12T23:14:48Z"), hash(0x22), List.of(hash(0x21)));
        assertEquals(1, e.parents().size());
        assertEquals("", e.message(), "three-arg ctor → empty message");
    }

    @Test
    void entry_compact_ctor_rejects_null_timestamp() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommitLog.Entry(null, hash(0x33), List.of(), ""));
    }

    @Test
    void entry_compact_ctor_rejects_null_metatree_hash() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CommitLog.Entry(Instant.now(), null, List.of(), ""));
    }

    @Test
    void entry_hashHex_is_the_commit_id_and_treeHashHex_is_the_tree() {
        CommitLog.Entry e = new CommitLog.Entry(Instant.now(), hash(0x44));
        assertEquals(
                com.dolthub.prolly.HashUtils.toHex(e.id()),
                e.hashHex(),
                "hashHex is the commit id (ADR-0071), not the tree hash");
        assertEquals(
                com.dolthub.prolly.HashUtils.toHex(hash(0x44)),
                e.treeHashHex(),
                "treeHashHex is the RootMetaTree (tree) hash");
    }

    @Test
    void file_accessor_returns_expected_path(@TempDir Path dir) {
        CommitLog log = CommitLog.beside(dir);
        assertEquals(dir.resolve("commits.log"), log.file());
    }

    @Test
    void single_parent_round_trips(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] parent = hash(0x10);
        byte[] commit = hash(0x20);
        log.append(Instant.parse("2026-05-12T23:14:48Z"), commit, List.of(parent));

        CommitLog.Entry e = log.latest().orElseThrow();
        assertEquals(1, e.parents().size());
        assertArrayEquals(parent, e.parents().get(0));
        assertArrayEquals(commit, e.metaTreeHash());
    }

    @Test
    void two_parents_round_trip_for_merge_commits(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        byte[] p1 = hash(0x10);
        byte[] p2 = hash(0x11);
        byte[] mergeCommit = hash(0x42);
        log.append(Instant.parse("2026-05-12T23:14:48Z"), mergeCommit, List.of(p1, p2));

        CommitLog.Entry e = log.latest().orElseThrow();
        assertEquals(2, e.parents().size());
        assertArrayEquals(p1, e.parents().get(0));
        assertArrayEquals(p2, e.parents().get(1));
    }

    @Test
    void zero_parents_means_genesis(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01), Collections.emptyList());

        CommitLog.Entry e = log.latest().orElseThrow();
        assertEquals(0, e.parents().size());
    }

    @Test
    void parents_hex_lists_align_with_byte_lists(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(
                Instant.parse("2026-05-12T23:14:48Z"), hash(0x42), List.of(hash(0x10), hash(0x11)));
        CommitLog.Entry e = log.latest().orElseThrow();
        List<String> hex = e.parentsHex();
        assertEquals(2, hex.size());
        assertEquals(com.dolthub.prolly.HashUtils.toHex(hash(0x10)), hex.get(0));
        assertEquals(com.dolthub.prolly.HashUtils.toHex(hash(0x11)), hex.get(1));
    }

    @Test
    void author_round_trips_to_disk(@TempDir Path dir) throws Exception {
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(
                Instant.parse("2026-05-12T23:14:48Z"),
                hash(0x55),
                List.of(hash(0x10)),
                "ingest sbom v2",
                "alice");

        CommitLog.Entry e = log.latest().orElseThrow();
        assertEquals("ingest sbom v2", e.message());
        assertEquals("alice", e.author());
    }

    @Test
    void thin_row_is_datetime_and_id_only(@TempDir Path dir) throws Exception {
        // ADR-0073 thin format: the disk row is "<RFC 1123 datetime> <hex commit id>" — the content
        // (tree / parents / author / message) lives in the commit chunk, so there are no
        // m=/a=/tree/parent tokens on the row. The content still round-trips via the chunk.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(Instant.parse("2026-05-12T23:14:48Z"), hash(0x01), List.of(), "msg", "");
        String raw = java.nio.file.Files.readString(dir.resolve("commits.log")).trim();
        assertFalse(raw.contains(" m="), "no message token on the thin row");
        assertFalse(raw.contains(" a="), "no author token on the thin row");
        assertEquals(7, raw.split("\\s+").length, "row is 6 datetime tokens + the commit id");

        CommitLog.Entry e = log.latest().orElseThrow();
        assertEquals("msg", e.message(), "message reconstructs from the chunk");
        assertEquals("", e.author(), "empty author reconstructs as empty");
    }

    @Test
    void author_with_unicode_and_spaces_round_trips(@TempDir Path dir) throws Exception {
        // The chunk carries the author verbatim, so spaces / unicode survive without any
        // line-format
        // escaping.
        CommitLog log = CommitLog.beside(dir, new InMemoryNodeStore());
        log.append(
                Instant.parse("2026-05-12T23:14:48Z"),
                hash(0x02),
                List.of(),
                "",
                "Renée O'Brien <r@x>");

        CommitLog.Entry e = log.latest().orElseThrow();
        assertEquals("Renée O'Brien <r@x>", e.author());
        assertEquals("", e.message(), "message stays empty independently of author");
    }

    @Test
    void entry_four_arg_ctor_defaults_author() {
        CommitLog.Entry e =
                new CommitLog.Entry(
                        Instant.parse("2026-05-12T23:14:48Z"),
                        hash(0x22),
                        List.of(hash(0x21)),
                        "m");
        assertEquals("m", e.message());
        assertEquals("", e.author(), "four-arg ctor → empty author");
    }
}
