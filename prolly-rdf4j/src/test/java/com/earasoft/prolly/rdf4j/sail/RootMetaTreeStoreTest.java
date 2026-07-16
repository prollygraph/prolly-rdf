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
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * SQLite-grade coverage for {@link RootMetaTreeStore}. The sidecar head pointer is what lets a Sail
 * rediscover its "current commit" on restart — torn writes or drift in the FILENAME constant orphan
 * the Sail state.
 */
class RootMetaTreeStoreTest {

    @Test
    void filename_constant_pinned() {
        assertEquals(
                "root-head",
                RootMetaTreeStore.FILENAME,
                "FILENAME drift would leave existing Sails unable to find their head");
    }

    @Test
    void empty_when_file_absent(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        assertTrue(s.get().isEmpty(), "absent file = empty Optional, not exception");
        assertEquals(dir.resolve("root-head"), s.file());
    }

    @Test
    void put_then_get_round_trips(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        byte[] hash = new byte[] {0x42, (byte) 0xCA, (byte) 0xFE};
        s.put(hash);
        assertArrayEquals(hash, s.get().orElseThrow());
    }

    @Test
    void survives_reopen(@TempDir Path dir) throws IOException {
        byte[] hash = new byte[] {1, 2, 3, 4, 5};
        RootMetaTreeStore s1 = RootMetaTreeStore.beside(dir);
        s1.put(hash);

        // New store instance at the same path must see the persisted value.
        RootMetaTreeStore s2 = RootMetaTreeStore.beside(dir);
        assertArrayEquals(hash, s2.get().orElseThrow(), "Sail head must survive close + reopen");
    }

    @Test
    void empty_file_returns_empty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("root-head"), "");
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        assertTrue(
                s.get().isEmpty(), "empty file must be treated as 'never set', not a parse error");
    }

    @Test
    void whitespace_only_file_returns_empty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("root-head"), "  \n\t\n");
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        assertTrue(s.get().isEmpty());
    }

    @Test
    void a_stale_tmp_file_from_a_crashed_put_does_not_break_get_or_a_later_put(@TempDir Path dir)
            throws IOException {
        // put() writes root-head.tmp then atomically renames it onto root-head.
        // A crash between those two steps leaves a stale .tmp behind; get()
        // must ignore it (it reads root-head), and a later put() must still
        // succeed by overwriting the orphaned .tmp.
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        byte[] first = new byte[20];
        first[0] = 0x5a;
        s.put(first);

        Files.writeString(dir.resolve("root-head.tmp"), "orphaned partial write");
        assertArrayEquals(
                first,
                s.get().orElseThrow(),
                "get() reads root-head and ignores a stale .tmp from a crashed put");

        byte[] second = new byte[20];
        second[0] = 0x6b;
        s.put(second);
        assertArrayEquals(
                second,
                s.get().orElseThrow(),
                "a later put() overwrites the orphaned .tmp and still commits atomically");
    }

    @Test
    void corrupt_hex_file_surfaces_a_clear_IOException(@TempDir Path dir) throws IOException {
        // Bit rot / a manual edit leaves non-hex content. HashUtils.fromHex
        // throws an *unchecked* exception; get() must catch it and re-surface
        // an IOException so Sail init fails actionably, not with a raw
        // NumberFormatException escaping a throws-IOException method.
        Files.writeString(dir.resolve("root-head"), "not-a-valid-hex-hash-zzzz");
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        IOException e = assertThrows(IOException.class, s::get);
        assertTrue(
                e.getMessage().contains("corrupt"),
                "the failure must name the corrupt head pointer");
    }

    @Test
    void trailing_newline_trimmed_on_read(@TempDir Path dir) throws IOException {
        // put() always writes a trailing newline; pin that get() trims it.
        Files.writeString(dir.resolve("root-head"), "0102030405060708090a0b0c0d0e0f1011121314\n");
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        byte[] got = s.get().orElseThrow();
        assertEquals(20, got.length, "trailing whitespace must be trimmed before hex decode");
    }

    @Test
    void file_contents_are_hex_encoded(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        s.put(new byte[] {0x01, 0x02, 0x03});
        String raw = Files.readString(dir.resolve("root-head"), StandardCharsets.UTF_8);
        assertTrue(
                raw.startsWith("010203"), "file must store hex-encoded hash for human-readability");
    }

    @Test
    void put_uses_atomic_temp_rename(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        s.put(new byte[] {1});
        s.put(new byte[] {2, 3});
        Path tmp = dir.resolve("root-head.tmp");
        assertFalse(Files.exists(tmp), "atomic rename must remove the .tmp file after success");
    }

    @Test
    void put_replaces_existing_value(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        s.put(new byte[] {1, 2});
        s.put(new byte[] {3, 4, 5});
        assertArrayEquals(new byte[] {3, 4, 5}, s.get().orElseThrow());
    }

    @Test
    void put_null_rejected(@TempDir Path dir) {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> s.put(null));
    }

    @Test
    void file_accessor_returns_expected_path(@TempDir Path dir) {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        assertEquals(dir.resolve("root-head"), s.file());
    }

    @Test
    void constructor_with_explicit_path(@TempDir Path dir) throws IOException {
        // The direct constructor lets callers point at any path, not just <dir>/root-head.
        Path custom = dir.resolve("custom-head-file");
        RootMetaTreeStore s = new RootMetaTreeStore(custom);
        s.put(new byte[] {0x42});
        assertTrue(Files.exists(custom), "explicit-path constructor must write to that path");
        assertEquals(custom, s.file());
    }

    // ---- load() ----

    @Test
    void load_returns_empty_when_no_head_set(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        NodeStore store = new InMemoryNodeStore();
        assertTrue(s.load(store).isEmpty(), "load() with no head must return Optional.empty");
    }

    @Test
    void load_round_trips_through_node_store(@TempDir Path dir) throws IOException {
        NodeStore store = new InMemoryNodeStore();
        RootMetaTree original =
                new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[] {0x01, 0x02, 0x03}));
        byte[] chunkHash = original.writeTo(store);

        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        s.put(chunkHash);

        Optional<RootMetaTree> loaded = s.load(store);
        assertTrue(loaded.isPresent());
        assertEquals(
                original,
                loaded.get(),
                "load() must read back the RootMetaTree pointed at by the sidecar");
    }

    @Test
    void load_returns_empty_when_chunk_missing_from_store(@TempDir Path dir) throws IOException {
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        // Point at a hash that doesn't exist in the store.
        byte[] phantom = new byte[20];
        phantom[0] = 0x42;
        s.put(phantom);

        NodeStore store = new InMemoryNodeStore();
        assertTrue(
                s.load(store).isEmpty(),
                "dangling sidecar (chunk gone) must surface as Optional.empty, not an exception");
    }

    @Test
    void put_creates_file_alongside_existing_node_store_dir(@TempDir Path dir) throws IOException {
        // Simulate: NodeStore dir already exists. Pin that put() doesn't blow up.
        Files.createDirectories(dir.resolve("rocksdb-data"));
        RootMetaTreeStore s = RootMetaTreeStore.beside(dir);
        s.put(new byte[] {(byte) 0x99});
        assertTrue(Files.exists(dir.resolve("root-head")));
    }

    @Test
    void hex_round_trips_match_HashUtils() {
        // Property: hash → hex → hash via HashUtils is identity.
        byte[] original =
                new byte[] {
                    0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF
                };
        String hex = HashUtils.toHex(original);
        assertArrayEquals(
                original,
                HashUtils.fromHex(hex),
                "the hex round-trip RootMetaTreeStore relies on must be lossless");
    }
}
