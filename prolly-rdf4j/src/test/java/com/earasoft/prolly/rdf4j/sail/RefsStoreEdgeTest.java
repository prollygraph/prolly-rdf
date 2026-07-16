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

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Edge-case coverage for {@link RefsStore}. Existing tests cover the happy-path file-backed store;
 * this file pins the in-memory factory, {@code exists()}, the public constants, and the
 * dot-traversal edge cases that bypass NAME_PATTERN.
 */
class RefsStoreEdgeTest {

    // ---- public constants ----

    @Test
    void dirname_constant_pinned() {
        assertEquals("refs", RefsStore.DIRNAME, "DIRNAME drift would orphan existing on-disk refs");
    }

    @Test
    void default_branch_constant_pinned() {
        assertEquals(
                "main",
                RefsStore.DEFAULT_BRANCH,
                "DEFAULT_BRANCH is part of every API response — drift is a breaking change");
    }

    @Test
    void name_pattern_pinned() {
        assertEquals(
                "[A-Za-z0-9_./-]{1,128}",
                RefsStore.NAME_PATTERN.pattern(),
                "NAME_PATTERN drift would let bad names slip into refs/");
    }

    // ---- in-memory factory ----

    @Test
    void inMemory_starts_empty() throws IOException {
        RefsStore s = RefsStore.inMemory();
        assertFalse(s.exists("main"));
        assertTrue(s.get("main").isEmpty());
        assertNull(s.dir(), "in-memory store has no backing directory");
    }

    @Test
    void inMemory_put_then_get_roundtrip() throws IOException {
        RefsStore s = RefsStore.inMemory();
        byte[] hash = new byte[] {0x01, 0x02, 0x03};
        s.put("main", hash);
        assertArrayEquals(hash, s.get("main").orElseThrow());
    }

    @Test
    void inMemory_get_returns_defensive_copy() throws IOException {
        // Mutating the returned hash must not corrupt internal state.
        RefsStore s = RefsStore.inMemory();
        byte[] original = new byte[] {1, 2, 3};
        s.put("main", original);
        byte[] fetched = s.get("main").orElseThrow();
        fetched[0] = (byte) 0x99;
        assertEquals(1, s.get("main").orElseThrow()[0], "get() must return a defensive copy");
    }

    @Test
    void inMemory_put_stores_defensive_copy() throws IOException {
        // Mutating the input after put() must not change stored value.
        RefsStore s = RefsStore.inMemory();
        byte[] original = new byte[] {1, 2};
        s.put("main", original);
        original[0] = (byte) 0xFF;
        assertEquals(
                1,
                s.get("main").orElseThrow()[0],
                "put() must defensively copy so callers can't mutate stored state");
    }

    @Test
    void inMemory_delete_returns_false_when_absent() throws IOException {
        RefsStore s = RefsStore.inMemory();
        assertFalse(s.delete("ghost"));
    }

    @Test
    void inMemory_delete_returns_true_when_present() throws IOException {
        RefsStore s = RefsStore.inMemory();
        s.put("main", new byte[] {1});
        assertTrue(s.delete("main"));
        assertFalse(s.exists("main"), "delete must actually remove the entry");
    }

    @Test
    void inMemory_put_overwrites() throws IOException {
        RefsStore s = RefsStore.inMemory();
        s.put("main", new byte[] {1});
        s.put("main", new byte[] {2, 3});
        assertArrayEquals(new byte[] {2, 3}, s.get("main").orElseThrow());
    }

    // ---- exists() ----

    @Test
    void exists_false_for_unset_branch_file_backed(@TempDir Path dir) throws IOException {
        RefsStore s = RefsStore.beside(dir);
        assertFalse(s.exists("missing"));
    }

    @Test
    void exists_true_after_put_file_backed(@TempDir Path dir) throws IOException {
        RefsStore s = RefsStore.beside(dir);
        s.put("main", new byte[] {0x01});
        assertTrue(s.exists("main"));
    }

    @Test
    void exists_false_after_delete_file_backed(@TempDir Path dir) throws IOException {
        RefsStore s = RefsStore.beside(dir);
        s.put("main", new byte[] {0x01});
        s.delete("main");
        assertFalse(s.exists("main"));
    }

    @Test
    void exists_validates_name_too() {
        RefsStore s = RefsStore.inMemory();
        assertThrows(
                IllegalArgumentException.class,
                () -> s.exists(".."),
                "exists() must validate name like get/put — drift would skip the safety net");
    }

    // ---- validateName edge cases ----

    @Test
    void validateName_rejects_null() {
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName(null));
    }

    @Test
    void validateName_rejects_empty() {
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName(""));
    }

    @Test
    void validateName_rejects_dot_dot_segments() {
        // NAME_PATTERN allows dots so '..' alone would match; the separate
        // dot-segment guard is what catches it.
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName(".."));
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("../main"));
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("feature/.."));
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("a/../b"));
    }

    @Test
    void validateName_allows_single_dot() {
        // Single dot in a name is fine — only '..' is a path-traversal risk.
        assertDoesNotThrow(() -> RefsStore.validateName("v1.0"));
        assertDoesNotThrow(() -> RefsStore.validateName("feature.x.y"));
    }

    @Test
    void validateName_allows_nested_paths() {
        assertDoesNotThrow(() -> RefsStore.validateName("feature/auth"));
        assertDoesNotThrow(() -> RefsStore.validateName("staging/alice"));
        assertDoesNotThrow(() -> RefsStore.validateName("releases/v1.0.0"));
    }

    @Test
    void validateName_max_128_chars() {
        String oneTwentyEight = "a".repeat(128);
        assertDoesNotThrow(() -> RefsStore.validateName(oneTwentyEight));
        assertThrows(
                IllegalArgumentException.class, () -> RefsStore.validateName(oneTwentyEight + "x"));
    }

    @Test
    void put_rejects_null_hash() {
        RefsStore s = RefsStore.inMemory();
        assertThrows(IllegalArgumentException.class, () -> s.put("main", null));
    }

    // ---- in-memory ↔ file-backed parity ----

    @Test
    void in_memory_and_file_backed_agree_on_unset_branch(@TempDir Path dir) throws IOException {
        Optional<byte[]> mem = RefsStore.inMemory().get("ghost");
        Optional<byte[]> file = RefsStore.beside(dir).get("ghost");
        assertEquals(
                mem.isPresent(),
                file.isPresent(),
                "in-memory and file-backed must agree on absent branches");
    }
}
