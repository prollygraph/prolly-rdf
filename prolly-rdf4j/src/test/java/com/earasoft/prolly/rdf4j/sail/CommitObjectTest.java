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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HashUtils;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CommitObject} — the commit chunk's wire format + its trust-boundary parse (ADR-0073 Phase
 * 0). Two things are pinned here: the <b>identity contract</b> (a commit object's hash is exactly
 * the {@link CommitId} of the same fields, so {@code store.write(serialize())} stores a commit
 * under its own id, D-1) and the <b>deserialize trust boundary</b> (a malformed chunk throws {@link
 * IllegalArgumentException}, never over-reads or over-allocates). The round-trip + id invariants
 * over generated inputs live in {@code CommitObjectProperty}; this file pins the exact edge cases.
 */
class CommitObjectTest {

    private static final byte[] TAG = "prolly-commit-id-v1".getBytes(StandardCharsets.UTF_8);

    private static byte[] hash(String s) {
        return HashUtils.hash(s.getBytes(StandardCharsets.UTF_8));
    }

    // ---- identity contract: the object's hash IS the commit id ------------------------------

    @Test
    void id_equals_CommitId_of_and_hash_of_serialize() {
        byte[] mth = hash("tree");
        List<byte[]> parents = List.of(hash("p0"), hash("p1"));
        CommitObject c = CommitObject.of(mth, parents, "alice", "a merge");

        assertArrayEquals(
                CommitId.of(mth, parents, "alice", "a merge"),
                c.id(),
                "a commit object's id must equal CommitId.of over the same fields (D-1)");
        assertArrayEquals(
                c.id(),
                HashUtils.hash(c.serialize()),
                "id() must be exactly the content hash of serialize() — so store.write(serialize())"
                        + " returns the id");
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    void round_trip_ordinary_genesis_and_merge() {
        assertRoundTrips(CommitObject.of(hash("t"), List.of(), "", "")); // genesis, unattributed
        assertRoundTrips(CommitObject.of(hash("t"), List.of(hash("p")), "bob", "ordinary"));
        assertRoundTrips(
                CommitObject.of(
                        hash("t"), List.of(hash("a"), hash("b")), "carol", "merge b into a"));
    }

    @Test
    void round_trip_preserves_parent_order() {
        List<byte[]> ab = List.of(hash("a"), hash("b"));
        CommitObject c =
                CommitObject.deserialize(CommitObject.of(hash("t"), ab, "", "").serialize());
        assertArrayEquals(hash("a"), c.parents().get(0), "parents[0] preserved");
        assertArrayEquals(hash("b"), c.parents().get(1), "parents[1] preserved");
    }

    private static void assertRoundTrips(CommitObject c) {
        CommitObject back = CommitObject.deserialize(c.serialize());
        assertEquals(c, back, "deserialize(serialize(c)) must equal c");
        assertArrayEquals(c.metaTreeHash(), back.metaTreeHash());
        assertEquals(c.author(), back.author());
        assertEquals(c.message(), back.message());
        assertArrayEquals(c.id(), back.id(), "the id survives a round trip");
    }

    // ---- of() coercion mirrors CommitId.of ---------------------------------------------------

    @Test
    void of_coerces_and_validates_like_CommitId() {
        // null author/message -> "" ; null parent list -> empty (same id as explicit empties)
        assertArrayEquals(
                CommitObject.of(hash("t"), List.of(), "", "").id(),
                CommitObject.of(hash("t"), null, null, null).id());
        assertThrows(
                IllegalArgumentException.class, () -> CommitObject.of(null, List.of(), "", ""));
        List<byte[]> withNull = java.util.Arrays.asList(hash("p"), null);
        assertThrows(
                IllegalArgumentException.class, () -> CommitObject.of(hash("t"), withNull, "", ""));
    }

    // ---- deserialize trust boundary (Phase 0 Step 2) -----------------------------------------

    @Test
    void deserialize_rejects_a_bad_tag() {
        byte[] b = CommitObject.of(hash("t"), List.of(), "", "").serialize();
        b[4] ^= (byte) 0xFF; // first byte of the tag content (after its 4-byte length)
        assertThrows(IllegalArgumentException.class, () -> CommitObject.deserialize(b));
    }

    @Test
    void deserialize_rejects_truncation() {
        byte[] b = CommitObject.of(hash("t"), List.of(hash("p")), "x", "y").serialize();
        assertThrows(
                IllegalArgumentException.class,
                () -> CommitObject.deserialize(java.util.Arrays.copyOf(b, b.length - 3)));
    }

    @Test
    void deserialize_rejects_trailing_bytes() {
        byte[] b = CommitObject.of(hash("t"), List.of(), "", "").serialize();
        byte[] extra = java.util.Arrays.copyOf(b, b.length + 1); // one stray trailing byte
        assertThrows(IllegalArgumentException.class, () -> CommitObject.deserialize(extra));
    }

    @Test
    void deserialize_rejects_an_oversized_length_field_without_allocating() {
        byte[] b = CommitObject.of(hash("t"), List.of(), "", "").serialize();
        b[0] = 0x7F; // blow up the very first (tag) length to ~2^31 — must be rejected, not OOM
        b[1] = (byte) 0xFF;
        b[2] = (byte) 0xFF;
        b[3] = (byte) 0xFF;
        assertThrows(IllegalArgumentException.class, () -> CommitObject.deserialize(b));
    }

    @Test
    void deserialize_rejects_a_hostile_parent_count_before_allocating() {
        // A well-formed tag + metaTreeHash, then a parent count of Integer.MAX_VALUE and no
        // parents.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeLp(buf, TAG);
        writeLp(buf, hash("t"));
        writeInt(buf, Integer.MAX_VALUE); // hostile count — must be rejected, never new byte[2^31]
        assertThrows(
                IllegalArgumentException.class, () -> CommitObject.deserialize(buf.toByteArray()));
    }

    private static void writeLp(ByteArrayOutputStream buf, byte[] bytes) {
        writeInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static void writeInt(ByteArrayOutputStream buf, int v) {
        buf.write((v >>> 24) & 0xFF);
        buf.write((v >>> 16) & 0xFF);
        buf.write((v >>> 8) & 0xFF);
        buf.write(v & 0xFF);
    }
}
