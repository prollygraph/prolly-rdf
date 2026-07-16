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
import com.dolthub.prolly.UnsupportedFormatException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RootMetaTreeTest {

    @Test
    void empty_metatree_round_trips() {
        RootMetaTree mt = new RootMetaTree(Map.of());
        byte[] serialized = mt.serialize();
        RootMetaTree back = RootMetaTree.deserialize(serialized);
        assertEquals(mt, back);
        assertTrue(back.isEmpty());
    }

    @Test
    void single_entry_round_trip() {
        Map<String, byte[]> entries =
                Map.of(RootMetaTree.NAME_DICT, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        RootMetaTree mt = new RootMetaTree(entries);
        assertEquals(mt, RootMetaTree.deserialize(mt.serialize()));
    }

    @Test
    void all_canonical_entries_round_trip() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(RootMetaTree.NAME_DICT, new byte[] {0x10, 0x10, 0x10});
        entries.put(RootMetaTree.NAME_SPOC, new byte[] {0x20, 0x20, 0x20});
        entries.put(RootMetaTree.NAME_POSC, new byte[] {0x21, 0x21, 0x21});
        entries.put(RootMetaTree.NAME_OSPC, new byte[] {0x22, 0x22, 0x22});
        entries.put(RootMetaTree.NAME_CSPO, new byte[] {0x23, 0x23, 0x23});
        entries.put(RootMetaTree.NAME_NAMESPACES, new byte[] {0x30, 0x30, 0x30});
        entries.put(RootMetaTree.NAME_STATS, new byte[] {0x40, 0x40, 0x40});
        entries.put(RootMetaTree.NAME_PREFIXES, new byte[] {0x50, 0x50, 0x50});

        RootMetaTree mt = new RootMetaTree(entries);
        RootMetaTree back = RootMetaTree.deserialize(mt.serialize());
        assertEquals(mt, back);
        for (var e : entries.entrySet()) {
            assertArrayEquals(e.getValue(), back.hashOf(e.getKey()).orElseThrow());
        }
    }

    @Test
    void entries_sorted_by_name() {
        Map<String, byte[]> input = new LinkedHashMap<>();
        // Insert in non-sorted order
        input.put(RootMetaTree.NAME_STATS, new byte[] {1});
        input.put(RootMetaTree.NAME_DICT, new byte[] {2});
        input.put(RootMetaTree.NAME_SPOC, new byte[] {3});
        RootMetaTree mt = new RootMetaTree(input);
        var iter = mt.entries().keySet().iterator();
        assertEquals(RootMetaTree.NAME_DICT, iter.next());
        assertEquals(RootMetaTree.NAME_SPOC, iter.next());
        assertEquals(RootMetaTree.NAME_STATS, iter.next());
    }

    @Test
    void null_hashes_skipped() {
        Map<String, byte[]> input = new LinkedHashMap<>();
        input.put(RootMetaTree.NAME_DICT, new byte[] {1});
        input.put(RootMetaTree.NAME_SPOC, null); // skipped
        RootMetaTree mt = new RootMetaTree(input);
        assertTrue(mt.hashOf(RootMetaTree.NAME_DICT).isPresent());
        assertTrue(mt.hashOf(RootMetaTree.NAME_SPOC).isEmpty());
    }

    @Test
    void unknown_name_returns_empty() {
        RootMetaTree mt = new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[] {1}));
        assertTrue(mt.hashOf("future-table-name").isEmpty());
    }

    @Test
    void deterministic_serialization_same_content_same_bytes() {
        Map<String, byte[]> a = new LinkedHashMap<>();
        a.put(RootMetaTree.NAME_SPOC, new byte[] {1, 2, 3});
        a.put(RootMetaTree.NAME_DICT, new byte[] {4, 5, 6});
        Map<String, byte[]> b = new LinkedHashMap<>();
        b.put(RootMetaTree.NAME_DICT, new byte[] {4, 5, 6});
        b.put(RootMetaTree.NAME_SPOC, new byte[] {1, 2, 3});
        // Different insertion orders, same content → same bytes (sorted ser).
        assertArrayEquals(new RootMetaTree(a).serialize(), new RootMetaTree(b).serialize());
    }

    @Test
    void writeTo_and_readFrom_round_trip() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        RootMetaTree mt =
                new RootMetaTree(
                        Map.of(
                                RootMetaTree.NAME_DICT,
                                        new byte[] {
                                            (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef
                                        },
                                RootMetaTree.NAME_SPOC,
                                        new byte[] {
                                            (byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe
                                        }));
        byte[] hash = mt.writeTo(store);
        RootMetaTree back = RootMetaTree.readFrom(store, hash).orElseThrow();
        assertEquals(mt, back);
    }

    @Test
    void readFrom_missing_returns_empty() {
        InMemoryNodeStore store = new InMemoryNodeStore();
        assertTrue(RootMetaTree.readFrom(store, new byte[] {1, 2, 3}).isEmpty());
    }

    @Test
    void name_too_long_throws() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 256; i++) sb.append('a');
        RootMetaTree mt = new RootMetaTree(Map.of(sb.toString(), new byte[] {1}));
        assertThrows(IllegalStateException.class, mt::serialize);
    }

    @Test
    void hash_too_long_throws() {
        // The name/hash length prefixes are single bytes — a hash over 255
        // bytes can't be length-encoded, so serialize must reject it.
        RootMetaTree mt = new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[256]));
        IllegalStateException ex = assertThrows(IllegalStateException.class, mt::serialize);
        assertTrue(
                ex.getMessage().contains("hash too long"),
                "message should name the offending field");
    }

    @Test
    void max_length_name_and_hash_serialize_ok() {
        // 255 is the boundary — the guard is strictly > 255, so 255 must work
        // and round-trip (length stored as a byte, read back via & 0xFF).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 255; i++) sb.append('x');
        RootMetaTree mt = new RootMetaTree(Map.of(sb.toString(), new byte[255]));
        RootMetaTree back = RootMetaTree.deserialize(mt.serialize());
        assertEquals(mt, back);
        assertEquals(255, back.hashOf(sb.toString()).orElseThrow().length);
    }

    // ---- format header (ADR-0067) + malformed-input hardening (Step 2) ----------------------

    private static final byte[] MAGIC = {'P', 'R', 'M', 'T'};

    /**
     * Prepend the valid [magic][version] header to a body so a test reaches the post-header parse.
     */
    private static byte[] withHeader(int version, int... body) {
        byte[] out = new byte[MAGIC.length + 1 + body.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        out[MAGIC.length] = (byte) version;
        for (int i = 0; i < body.length; i++) {
            out[MAGIC.length + 1 + i] = (byte) body[i];
        }
        return out;
    }

    @Test
    void serialize_emits_the_magic_and_version_header() {
        byte[] bytes = new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[] {1})).serialize();
        assertArrayEquals(MAGIC, java.util.Arrays.copyOfRange(bytes, 0, 4), "magic prefix");
        assertEquals(1, bytes[4] & 0xFF, "format version byte");
    }

    @Test
    void deserialize_rejects_blob_too_short_for_the_header() {
        // Fewer than the 5 header bytes (4 magic + 1 version) → a typed UnsupportedFormatException,
        // not a mis-parse of the leading bytes as an entry count.
        assertThrows(
                UnsupportedFormatException.class,
                () -> RootMetaTree.deserialize(new byte[] {0x00, 0x01}));
    }

    @Test
    void deserialize_rejects_bad_magic() {
        // Right length, wrong magic (a pre-versioning / foreign blob) → fail closed.
        byte[] notMagic = {0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00};
        assertThrows(UnsupportedFormatException.class, () -> RootMetaTree.deserialize(notMagic));
    }

    @Test
    void deserialize_rejects_unsupported_version() {
        // Valid magic, future/unknown version → fail closed (no defensive multi-version reader).
        byte[] futureVersion = withHeader(99, 0x00, 0x00, 0x00, 0x00);
        assertThrows(
                UnsupportedFormatException.class, () -> RootMetaTree.deserialize(futureVersion));
    }

    @Test
    void deserialize_rejects_buffer_shorter_than_the_count_field() {
        // Valid header but fewer than the 4 count bytes after it → a controlled
        // IllegalArgumentException (truncated body), not an IndexOutOfBoundsException.
        assertThrows(
                IllegalArgumentException.class,
                () -> RootMetaTree.deserialize(withHeader(1, 0x00, 0x01)));
    }

    @Test
    void deserialize_rejects_absurd_entry_count_not_oom() {
        // Valid header, then a count claiming Integer.MAX_VALUE entries. Bounded against the most
        // the
        // buffer could hold (0 here), so it rejects up front rather than running the loop off the
        // end.
        byte[] malformed = withHeader(1, 0x7F, 0xFF, 0xFF, 0xFF); // count = Integer.MAX_VALUE
        assertThrows(IllegalArgumentException.class, () -> RootMetaTree.deserialize(malformed));
    }

    @Test
    void deserialize_rejects_negative_entry_count_not_silent_empty() {
        // A negative count previously skipped the loop and silently returned an empty tree (a
        // mis-parse). With a valid header it must now be a controlled rejection.
        byte[] malformed = withHeader(1, 0xFF, 0xFF, 0xFF, 0xFF); // count = -1
        assertThrows(IllegalArgumentException.class, () -> RootMetaTree.deserialize(malformed));
    }

    @Test
    void equals_handles_self_null_and_foreign_types() {
        RootMetaTree mt = new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[] {1}));
        assertEquals(mt, mt, "a metatree equals itself");
        assertNotEquals(mt, null);
        assertNotEquals(mt, "not a metatree");
    }

    @Test
    void equals_distinguishes_key_sets_and_values() {
        RootMetaTree a = new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[] {1, 2}));
        RootMetaTree differentValue =
                new RootMetaTree(Map.of(RootMetaTree.NAME_DICT, new byte[] {9, 9}));
        RootMetaTree differentKey =
                new RootMetaTree(Map.of(RootMetaTree.NAME_SPOC, new byte[] {1, 2}));
        RootMetaTree extraKey =
                new RootMetaTree(
                        Map.of(
                                RootMetaTree.NAME_DICT, new byte[] {1, 2},
                                RootMetaTree.NAME_SPOC, new byte[] {3, 4}));
        assertNotEquals(a, differentValue, "same key, different hash → not equal");
        assertNotEquals(a, differentKey, "different key name → not equal");
        assertNotEquals(a, extraKey, "an extra entry → not equal");
    }

    @Test
    void equal_metatrees_have_equal_hashcodes() {
        Map<String, byte[]> e =
                Map.of(
                        RootMetaTree.NAME_DICT, new byte[] {1, 2, 3},
                        RootMetaTree.NAME_STATS, new byte[] {4, 5, 6});
        assertEquals(
                new RootMetaTree(e).hashCode(),
                new RootMetaTree(e).hashCode(),
                "equal metatrees must hash equally");
    }

    @Test
    void toString_names_the_entries() {
        RootMetaTree mt =
                new RootMetaTree(
                        Map.of(
                                RootMetaTree.NAME_DICT, new byte[] {1},
                                RootMetaTree.NAME_SPOC, new byte[] {2}));
        String s = mt.toString();
        assertTrue(s.contains("RootMetaTree"));
        assertTrue(s.contains(RootMetaTree.NAME_DICT));
        assertTrue(s.contains(RootMetaTree.NAME_SPOC));
    }
}
