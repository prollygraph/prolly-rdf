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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TagStoreTest {

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        h[19] = (byte) (seed * 7);
        return h;
    }

    @Test
    void create_thenGet_roundTripsCommitAndMessage(@TempDir Path dir) throws Exception {
        TagStore tags = TagStore.beside(dir);
        assertTrue(tags.create("v1.0.0", hash(1), "release one"));
        Optional<TagStore.Entry> got = tags.get("v1.0.0");
        assertTrue(got.isPresent());
        assertArrayEquals(hash(1), got.get().commit());
        assertEquals("release one", got.get().message());
    }

    @Test
    void create_duplicate_returnsFalse(@TempDir Path dir) throws Exception {
        TagStore tags = TagStore.beside(dir);
        assertTrue(tags.create("v1", hash(1), ""));
        assertFalse(tags.create("v1", hash(2), "different")); // already exists -> ALREADY_EXISTS
        // the original is untouched
        assertArrayEquals(hash(1), tags.get("v1").orElseThrow().commit());
    }

    @Test
    void get_absent_isEmpty(@TempDir Path dir) throws Exception {
        assertTrue(TagStore.beside(dir).get("nope").isEmpty());
    }

    @Test
    void list_returnsAllSorted(@TempDir Path dir) throws Exception {
        TagStore tags = TagStore.beside(dir);
        tags.create("v2", hash(2), "two");
        tags.create("v1", hash(1), "one");
        Map<String, TagStore.Entry> all = tags.list();
        assertEquals(2, all.size());
        assertEquals("[v1, v2]", all.keySet().toString()); // sorted
        assertArrayEquals(hash(1), all.get("v1").commit());
    }

    @Test
    void delete_removesThenReportsAbsent(@TempDir Path dir) throws Exception {
        TagStore tags = TagStore.beside(dir);
        tags.create("v1", hash(1), "");
        assertTrue(tags.delete("v1")); // existed
        assertFalse(tags.delete("v1")); // already gone -> NOT_FOUND at the verb edge
        assertTrue(tags.get("v1").isEmpty());
    }

    @Test
    void create_pathTraversalName_throws(@TempDir Path dir) throws Exception {
        TagStore tags = TagStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> tags.create("../evil", hash(1), ""));
        assertThrows(IllegalArgumentException.class, () -> tags.create("/etc/passwd", hash(1), ""));
    }

    @Test
    void message_withNewlines_roundTrips(@TempDir Path dir) throws Exception {
        TagStore tags = TagStore.beside(dir);
        String msg = "line one\nline two\nline three";
        tags.create("annotated", hash(3), msg);
        assertEquals(msg, tags.get("annotated").orElseThrow().message());
    }

    @Test
    void inMemory_hasSameSemantics() throws Exception {
        TagStore tags = TagStore.inMemory();
        assertTrue(tags.create("v1", hash(1), "one"));
        assertFalse(tags.create("v1", hash(2), "dup"));
        assertArrayEquals(hash(1), tags.get("v1").orElseThrow().commit());
        assertEquals(1, tags.list().size());
        assertTrue(tags.delete("v1"));
        assertTrue(tags.get("v1").isEmpty());
    }
}
