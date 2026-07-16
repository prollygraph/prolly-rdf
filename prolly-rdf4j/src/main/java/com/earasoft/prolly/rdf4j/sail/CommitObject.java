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

import com.dolthub.prolly.HashUtils;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The content-addressed <b>commit object</b>: the logically-meaningful content of a commit — {@code
 * {metaTreeHash, ordered parent-ids, author, message}} — with a canonical, injective serialization
 * whose content hash <em>is</em> the commit id.
 *
 * <p>This is the storage counterpart to {@link CommitId}. The two share <b>one</b> byte definition:
 * {@link CommitId#of} is exactly {@code HashUtils.hash(CommitObject.of(...).serialize())}, so a
 * commit written to a {@code NodeStore} as {@code store.write(commitObject.serialize())} comes back
 * under precisely its commit id — identity and storage location are the same fact (see <a
 * href="../../../../../../../../docs/adr/0073-commit-objects-in-the-nodestore.md">ADR-0073</a>
 * D-1). The wall-clock timestamp is <b>not</b> a field here: it is deliberately excluded from the
 * id (ADR-0071 D-2, for deterministic cross-peer convergence) and lives in the {@code commits.log}
 * time-index sidecar instead.
 *
 * @apiNote Immutable value type. {@link #of} coerces exactly as {@link CommitId#of} does (a null
 *     author/message becomes the empty string; a null parent list becomes empty; a null
 *     metaTreeHash or a null parent throws). {@link #id()} returns the 20-byte commit id.
 * @implNote The serialization is <b>injective</b>: a leading domain-separation tag ({@code
 *     prolly-commit-id-v1} — the same tag {@link CommitId} used, kept byte-identical so the id is
 *     unchanged) then each field length-prefixed (4-byte big-endian length ‖ bytes), with a 4-byte
 *     parent count. No two distinct inputs can produce the same stream. {@link #deserialize} is a
 *     <b>trust boundary</b>: every length is bounds-checked before a read, the tag is verified, an
 *     implausible parent count is rejected before allocation, and trailing bytes are refused — a
 *     malformed chunk throws {@link IllegalArgumentException}, never over-reads or over-allocates.
 *     <b>Collaborators:</b> {@link HashUtils#hash} (the content-addressing primitive); {@link
 *     CommitId} (delegates its id computation here). <b>Dependents:</b> the {@code ProllySail}
 *     write path (writes the object as a chunk) and the read/sync/garbage-collection layer (reads a
 *     commit by id via {@code store.read(id)}).
 */
public final class CommitObject {

    /**
     * Domain-separation + format-version tag — byte-identical to the tag {@link CommitId} hashed
     * before this type existed, so extracting the serialization here does not change any commit id.
     */
    private static final byte[] TAG = "prolly-commit-id-v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] metaTreeHash;
    private final List<byte[]> parents;
    private final String author;
    private final String message;

    private CommitObject(byte[] metaTreeHash, List<byte[]> parents, String author, String message) {
        this.metaTreeHash = metaTreeHash;
        this.parents = parents;
        this.author = author;
        this.message = message;
    }

    /**
     * Builds a commit object from its content, coercing exactly as {@link CommitId#of}.
     *
     * @param metaTreeHash the RootMetaTree chunk hash (the commit's tree); must not be null
     * @param parentIds the parent commit ids in <b>recorded order</b> (order is significant); null
     *     or empty denotes a genesis commit
     * @param author the commit author; null becomes the empty string
     * @param message the commit message; null becomes the empty string
     * @throws IllegalArgumentException if {@code metaTreeHash} is null, or any parent id is null
     */
    public static CommitObject of(
            byte[] metaTreeHash, List<byte[]> parentIds, String author, String message) {
        if (metaTreeHash == null) {
            throw new IllegalArgumentException("metaTreeHash must not be null");
        }
        List<byte[]> src = parentIds == null ? Collections.emptyList() : parentIds;
        List<byte[]> copy = new ArrayList<>(src.size());
        for (byte[] p : src) {
            if (p == null) {
                throw new IllegalArgumentException("parent ids must not be null");
            }
            copy.add(p.clone());
        }
        return new CommitObject(
                metaTreeHash.clone(),
                copy,
                author == null ? "" : author,
                message == null ? "" : message);
    }

    /** The 20-byte content-addressed commit id — {@code HashUtils.hash(serialize())}. */
    public byte[] id() {
        return HashUtils.hash(serialize());
    }

    /** The RootMetaTree hash (the commit's data-tree root). */
    public byte[] metaTreeHash() {
        return metaTreeHash.clone();
    }

    /** The parent commit ids, in recorded order (defensive copies). */
    public List<byte[]> parents() {
        List<byte[]> out = new ArrayList<>(parents.size());
        for (byte[] p : parents) {
            out.add(p.clone());
        }
        return Collections.unmodifiableList(out);
    }

    /** The commit author (never null; the empty string when unattributed). */
    public String author() {
        return author;
    }

    /** The commit message (never null; the empty string when absent). */
    public String message() {
        return message;
    }

    /**
     * The canonical injective serialization — the exact bytes whose hash is the commit id. Writing
     * these to a content-addressed {@code NodeStore} stores the commit under its own id.
     */
    public byte[] serialize() {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(64);
        writeLenPrefixed(buf, TAG);
        writeLenPrefixed(buf, metaTreeHash);
        writeInt(buf, parents.size());
        for (byte[] p : parents) {
            writeLenPrefixed(buf, p);
        }
        writeLenPrefixed(buf, author.getBytes(StandardCharsets.UTF_8));
        writeLenPrefixed(buf, message.getBytes(StandardCharsets.UTF_8));
        return buf.toByteArray();
    }

    /**
     * Parses a commit object from its serialized chunk bytes. A <b>trust boundary</b>:
     * bounds-checks every length, verifies the tag, rejects an implausible parent count before
     * allocating, and refuses trailing bytes.
     *
     * @throws IllegalArgumentException if {@code data} is not a well-formed commit object
     */
    public static CommitObject deserialize(byte[] data) {
        Objects.requireNonNull(data, "data must not be null");
        Cursor c = new Cursor(data);
        byte[] tag = c.readLenPrefixed("tag");
        if (!Arrays.equals(tag, TAG)) {
            throw new IllegalArgumentException("not a commit object: bad domain tag");
        }
        byte[] mth = c.readLenPrefixed("metaTreeHash");
        int count = c.readInt("parent count");
        if (count < 0 || count > c.remaining()) {
            // Each parent needs >= 4 bytes (its length prefix), so a count exceeding the remaining
            // byte count is impossible — reject before allocating a list sized to a hostile count.
            throw new IllegalArgumentException("implausible parent count: " + count);
        }
        List<byte[]> parents = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            parents.add(c.readLenPrefixed("parent " + i));
        }
        String author = new String(c.readLenPrefixed("author"), StandardCharsets.UTF_8);
        String message = new String(c.readLenPrefixed("message"), StandardCharsets.UTF_8);
        if (c.remaining() != 0) {
            throw new IllegalArgumentException("trailing bytes after commit object");
        }
        return new CommitObject(mth, parents, author, message);
    }

    private static void writeLenPrefixed(ByteArrayOutputStream buf, byte[] bytes) {
        writeInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    private static void writeInt(ByteArrayOutputStream buf, int v) {
        buf.write((v >>> 24) & 0xFF);
        buf.write((v >>> 16) & 0xFF);
        buf.write((v >>> 8) & 0xFF);
        buf.write(v & 0xFF);
    }

    /** A bounds-checking read cursor over the serialized bytes (the untrusted-input boundary). */
    private static final class Cursor {
        private final byte[] data;
        private int pos;

        Cursor(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - pos;
        }

        int readInt(String what) {
            if (remaining() < 4) {
                throw new IllegalArgumentException("truncated " + what);
            }
            int v =
                    ((data[pos] & 0xFF) << 24)
                            | ((data[pos + 1] & 0xFF) << 16)
                            | ((data[pos + 2] & 0xFF) << 8)
                            | (data[pos + 3] & 0xFF);
            pos += 4;
            return v;
        }

        byte[] readLenPrefixed(String what) {
            int len = readInt(what + " length");
            if (len < 0 || len > remaining()) {
                throw new IllegalArgumentException("bad " + what + " length: " + len);
            }
            byte[] out = Arrays.copyOfRange(data, pos, pos + len);
            pos += len;
            return out;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommitObject other)) {
            return false;
        }
        if (!Arrays.equals(metaTreeHash, other.metaTreeHash)
                || parents.size() != other.parents.size()
                || !author.equals(other.author)
                || !message.equals(other.message)) {
            return false;
        }
        for (int i = 0; i < parents.size(); i++) {
            if (!Arrays.equals(parents.get(i), other.parents.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = Arrays.hashCode(metaTreeHash);
        for (byte[] p : parents) {
            h = 31 * h + Arrays.hashCode(p);
        }
        h = 31 * h + author.hashCode();
        return 31 * h + message.hashCode();
    }
}
