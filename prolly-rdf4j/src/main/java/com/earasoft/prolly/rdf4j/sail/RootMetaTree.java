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

import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.UnsupportedFormatException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A single commit record bundling all the Sail's table root chunk hashes.
 *
 * <p>The Sail otherwise holds its table roots in volatile fields, which are process-local and lost
 * on restart. This record is the durable alternative: it bundles every table's root hash into one
 * deterministically-serialized chunk, so the whole Sail state is named by a single hash. A sidecar
 * pointer file records the most recent such chunk's hash; the longer-term plan is to commit it
 * atomically via {@code Database}'s compare-and-set manifest machinery instead of a sidecar file.
 *
 * <p>Format on disk (deterministic so identical workloads produce identical chunks → identical
 * hashes). A self-describing header (ADR-0067) precedes the body:
 *
 * <pre>
 *   [4-byte magic "PRMT"][u8 format-version]    (header — verified before any field read)
 *   [u32 BE entry-count]
 *   per entry (sorted by name lex order):
 *     [u8  name-length-in-bytes]
 *     [N   UTF-8 name bytes]
 *     [u8  hash-length-in-bytes]
 *     [M   hash bytes]
 * </pre>
 *
 * <h2>Standard names</h2>
 *
 * <pre>
 *   "dict"          — Dictionary tree root
 *   "spoc","posc","ospc","cspo" — index roots
 *   "namespaces"    — SparqlNamespaces root
 *   "stats"         — TermStats root
 *   "prefixes"      — PrefixTable root
 *   "provenance"    — ProvenanceIndex root (only when the Sail is
 *                     provenance-enabled; see ADR-0001)
 * </pre>
 *
 * Missing names are treated as "empty tree". The presence/absence of {@code provenance} is the
 * marker for whether a commit has provenance tracking enabled.
 *
 * @apiNote Serialize to / deserialize from bytes via this class; the byte layout is deterministic
 *     (entries sorted by name), so an identical set of roots always produces an identical chunk
 *     hash — that determinism is what lets this root-of-roots act as a content address for the
 *     entire Sail. A name absent from the record means "empty tree"; the presence of {@code
 *     "provenance"} is itself the flag for whether a commit tracked provenance.
 * @implNote <b>Collaborators:</b> {@link NodeStore} (stores/loads the serialized record like any
 *     other chunk) and the canonical table-name constants below. <b>Dependents:</b> {@code
 *     ProllySail} (writes one per commit when a {@code RootMetaTreeStore} is configured, reads it
 *     at init to rehydrate roots) and — critically — {@code GarbageCollector}: these auxiliary
 *     roots are <em>not</em> on the commit-to-data-tree mark walk, so a collector that is not
 *     handed them explicitly would sweep them as unreachable (see the garbage collector's
 *     reachability-contract warning).
 */
public final class RootMetaTree {

    /** Map names in canonical order. */
    public static final String NAME_DICT = "dict";

    public static final String NAME_SPOC = "spoc";
    public static final String NAME_POSC = "posc";
    public static final String NAME_OSPC = "ospc";
    public static final String NAME_CSPO = "cspo";
    public static final String NAME_NAMESPACES = "namespaces";
    public static final String NAME_STATS = "stats";
    public static final String NAME_PREFIXES = "prefixes";

    /** Iter F sidecar — root of the {@code ProvenanceIndex} tree, when enabled. */
    public static final String NAME_PROVENANCE = "provenance";

    /**
     * ADR-0003 sidecar — root of the {@code EventLogIndex} tree. Records every INSERT/DELETE event
     * per triple, enabling {@code git log -- <triple>}-style mutation history. Independent of
     * {@link #NAME_PROVENANCE}; either may be enabled alone.
     */
    public static final String NAME_PROVENANCE_EVENTS = "provenance-events";

    /**
     * Format header (ADR-0067): a {@code [4-byte magic][1-byte version]} prefix, verified before
     * any field read so a foreign / pre-versioning / future-version blob fails closed with {@link
     * UnsupportedFormatException} rather than being mis-parsed. The version is <b>local</b> to this
     * Sail-layer type — independent of the core {@code FormatVersion.CORE_FORMAT_VERSION} — so a
     * core format bump does not invalidate metatrees and vice versa (ADR-0067 D-2).
     */
    private static final byte[] ROOT_META_MAGIC = {'P', 'R', 'M', 'T'};

    private static final int FORMAT_VERSION = 1;

    /** Header length: 4-byte magic + 1-byte version. */
    private static final int HEADER_SIZE = ROOT_META_MAGIC.length + 1;

    /** Sorted (name → root-chunk-hash). Null hashes are not stored (empty tree). */
    private final Map<String, byte[]> entries;

    public RootMetaTree(Map<String, byte[]> entries) {
        // Defensive copy + skip null-hash entries (== "empty tree" semantics).
        TreeMap<String, byte[]> copy = new TreeMap<>();
        for (var e : entries.entrySet()) {
            if (e.getValue() != null) {
                copy.put(e.getKey(), e.getValue().clone());
            }
        }
        this.entries = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * @return immutable view of (name → hash) entries, sorted by name.
     */
    public Map<String, byte[]> entries() {
        return entries;
    }

    /**
     * @return the hash for {@code name}, or empty if not present (treat as "empty tree").
     */
    public Optional<byte[]> hashOf(String name) {
        byte[] h = entries.get(name);
        return h == null ? Optional.empty() : Optional.of(h.clone());
    }

    /**
     * @return true iff this RootMetaTree contains no entries (a fresh Sail).
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @return canonical UTF-8 byte serialization (deterministic, hash-stable).
     */
    public byte[] serialize() {
        // Compute size first.
        int size = HEADER_SIZE + 4; // format header + entry-count
        for (var e : entries.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            if (name.length > 255) {
                throw new IllegalStateException("name too long: " + e.getKey());
            }
            byte[] hash = e.getValue();
            if (hash.length > 255) {
                throw new IllegalStateException(
                        "hash too long for " + e.getKey() + ": " + hash.length);
            }
            size += 1 + name.length + 1 + hash.length;
        }

        byte[] out = new byte[size];
        MemorySegment seg = MemorySegment.ofArray(out);
        // [4-byte magic][1-byte version] header (ADR-0067).
        MemorySegment.copy(
                MemorySegment.ofArray(ROOT_META_MAGIC), 0, seg, 0, ROOT_META_MAGIC.length);
        seg.set(ValueLayout.JAVA_BYTE, ROOT_META_MAGIC.length, (byte) FORMAT_VERSION);
        int n = entries.size();
        seg.set(
                ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN),
                HEADER_SIZE,
                n);
        long off = HEADER_SIZE + 4;
        for (var e : entries.entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] hash = e.getValue();
            seg.set(ValueLayout.JAVA_BYTE, off, (byte) name.length);
            off++;
            MemorySegment.copy(MemorySegment.ofArray(name), 0, seg, off, name.length);
            off += name.length;
            seg.set(ValueLayout.JAVA_BYTE, off, (byte) hash.length);
            off++;
            MemorySegment.copy(MemorySegment.ofArray(hash), 0, seg, off, hash.length);
            off += hash.length;
        }
        return out;
    }

    /** Parse a RootMetaTree from its serialized byte form. Inverse of {@link #serialize}. */
    public static RootMetaTree deserialize(byte[] bytes) {
        // ADR-0067 — verify the [4-byte magic][1-byte version] header before any field read, so a
        // foreign / pre-versioning / future-version blob fails closed with
        // UnsupportedFormatException
        // instead of mis-parsing its leading bytes as an entry count (mirrors Commit.deserialize).
        verifyHeader(bytes);
        // untrusted-input-boundary-hardening Step 2 — after the header, the sidecar is still a
        // trust
        // boundary: bound the entry count against the most entries the buffer could physically hold
        // (each entry is >= 2 bytes — a zero-length name byte + a zero-length hash byte), so a
        // hostile
        // count can't drive the loop past the buffer or, when negative, silently yield an empty
        // tree.
        // Per-entry lengths are single bytes (<= 255), already allocation-bounded.
        if (bytes.length < HEADER_SIZE + 4) {
            throw new IllegalArgumentException(
                    "RootMetaTree: buffer too short ("
                            + bytes.length
                            + " bytes) for the entry-count field after the format header —"
                            + " truncated or malformed");
        }
        MemorySegment seg = MemorySegment.ofArray(bytes);
        int n =
                seg.get(
                        ValueLayout.JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.BIG_ENDIAN),
                        HEADER_SIZE);
        long maxPossibleEntries = (bytes.length - (HEADER_SIZE + 4L)) / 2;
        if (n < 0 || n > maxPossibleEntries) {
            throw new IllegalArgumentException(
                    "RootMetaTree: entry count "
                            + n
                            + " out of bounds (buffer of "
                            + bytes.length
                            + " bytes holds at most "
                            + maxPossibleEntries
                            + " entries) — truncated or malformed");
        }
        long off = HEADER_SIZE + 4;
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int nameLen = seg.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
            off++;
            byte[] name = new byte[nameLen];
            MemorySegment.copy(seg, off, MemorySegment.ofArray(name), 0, nameLen);
            off += nameLen;
            int hashLen = seg.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
            off++;
            byte[] hash = new byte[hashLen];
            MemorySegment.copy(seg, off, MemorySegment.ofArray(hash), 0, hashLen);
            off += hashLen;
            entries.put(new String(name, StandardCharsets.UTF_8), hash);
        }
        return new RootMetaTree(entries);
    }

    /**
     * Verify the {@code [4-byte magic][1-byte version]} header (ADR-0067) before any field is read.
     * A blob too short for the header, with the wrong magic (a pre-versioning or foreign blob), or
     * with an unsupported version fails closed with {@link UnsupportedFormatException} — never a
     * silent mis-parse of arbitrary bytes as an entry count.
     */
    private static void verifyHeader(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) {
            throw new UnsupportedFormatException(
                    "malformed RootMetaTree: too short to carry the format header (magic + version)");
        }
        for (int i = 0; i < ROOT_META_MAGIC.length; i++) {
            if (bytes[i] != ROOT_META_MAGIC[i]) {
                throw new UnsupportedFormatException(
                        "unsupported RootMetaTree format: bad magic — not a versioned prolly"
                                + " root-meta-tree (a pre-versioning or foreign blob?)");
            }
        }
        int version = bytes[ROOT_META_MAGIC.length] & 0xFF;
        if (version != FORMAT_VERSION) {
            throw new UnsupportedFormatException(
                    "unsupported RootMetaTree format version "
                            + version
                            + " (this engine reads/writes version "
                            + FORMAT_VERSION
                            + ")");
        }
    }

    /** Write this RootMetaTree to {@code store} and return the chunk's hash. */
    public byte[] writeTo(NodeStore store) {
        return store.write(serialize());
    }

    /**
     * Read a RootMetaTree from {@code store} by its chunk hash.
     *
     * <p>Returns empty when the hash is absent <em>or</em> resolves to a {@link CommitObject}
     * rather than a tree: since <a
     * href="../../../../../../../../docs/adr/0073-commit-objects-in-the-nodestore.md">ADR-0073</a>
     * stores commit objects as content-addressed chunks too, a commit id passed where a tree hash
     * is expected (the sync layer's reachability walks over the receiver's {@code have} / {@code
     * want} refs) has no RootMetaTree at its own hash — so it reads as empty, exactly as it did
     * when commit ids were not stored. A chunk that is <em>neither</em> a RootMetaTree nor a
     * well-formed commit object is genuine corruption and still throws {@link
     * UnsupportedFormatException} (a real metaTreeHash can never point at a bad-magic chunk under
     * content-addressing).
     */
    public static Optional<RootMetaTree> readFrom(NodeStore store, byte[] chunkHash) {
        Optional<MemorySegment> chunk = store.read(chunkHash);
        if (chunk.isEmpty()) {
            return Optional.empty();
        }
        byte[] bytes = chunk.get().toArray(ValueLayout.JAVA_BYTE);
        try {
            return Optional.of(deserialize(bytes));
        } catch (UnsupportedFormatException notARootMetaTree) {
            if (isCommitObject(bytes)) {
                return Optional.empty();
            }
            throw notARootMetaTree;
        }
    }

    /**
     * True if {@code bytes} parse as a {@link CommitObject} (a commit id used where a tree hash was
     * expected — ADR-0073), distinguishing that from genuine corruption.
     */
    private static boolean isCommitObject(byte[] bytes) {
        try {
            CommitObject.deserialize(bytes);
            return true;
        } catch (RuntimeException notACommitObject) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof RootMetaTree mt)) return false;
        if (!entries.keySet().equals(mt.entries.keySet())) return false;
        for (var e : entries.entrySet()) {
            if (!Arrays.equals(e.getValue(), mt.entries.get(e.getKey()))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = 0;
        for (var e : entries.entrySet()) {
            h = 31 * h + e.getKey().hashCode();
            h = 31 * h + Arrays.hashCode(e.getValue());
        }
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("RootMetaTree{");
        boolean first = true;
        for (var e : entries.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            // Short hash prefix for readability — clamp, since toString must
            // never throw even if a hash is shorter than the 8-hex prefix.
            String hex = com.dolthub.prolly.HashUtils.toHex(e.getValue());
            sb.append(e.getKey()).append('=').append(hex, 0, Math.min(8, hex.length()));
        }
        return sb.append('}').toString();
    }
}
