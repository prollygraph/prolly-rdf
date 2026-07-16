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
package com.earasoft.prolly.rdf4j.index;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Sidecar prolly tree mapping a quad <code>(s, p, o, c)</code> to the <em>parent commit hash</em>
 * at the moment the triple was first added.
 *
 * <h2>Why a sidecar (and not a SPOC value extension)</h2>
 *
 * <p>Keeping provenance in a separate tree leaves the four quad indexes (SPOC / POSC / OSPC / CSPO)
 * byte-for-byte unchanged. Provenance is opt-in (see ADR-0001), so a store that never enables it
 * carries no provenance cost, and a reader that does not understand the sidecar simply ignores it.
 * (This separation originally also preserved bit-level compatibility with Dolt's Go prolly-tree
 * port; that compatibility is now <b>optional and deferred</b> — see CLAUDE.md — so the clean
 * separation, not Dolt parity, is what justifies the sidecar today.)
 *
 * <h2>Why parent hash, not "this commit's" hash</h2>
 *
 * <p>A commit's RootMetaTree hash is determined by its data, including the provenance root.
 * Recording <em>"this commit's"</em> hash inside the provenance tree would be circular — the hash
 * of the tree containing the hash. So we record the <em>parent</em> commit hash; the server
 * resolves "first appeared after parent X" by walking the CommitLog forward to find X's successor.
 *
 * <p>Genesis-commit triples record an empty byte array as the parent hash; the resolver maps that
 * to the first commit-log entry.
 *
 * <h2>Idempotence</h2>
 *
 * <p>{@link #recordFirstSeen} is idempotent: if an entry already exists for <code>(s,p,o,c)</code>,
 * it is not overwritten. This preserves the <em>first</em>-seen semantics even when the same triple
 * is re-added after a delete.
 *
 * <p>Not thread-safe.
 *
 * @implNote <b>Collaborators:</b> {@link NodeStore} + {@link BufferPool} (chunk storage / scratch),
 *     {@link MutableMap} / {@link StaticMap} (buffer then commit the sidecar tree), {@link
 *     TupleBuilder} + {@link TermId} (encode the (s, p, o, c) key), and the {@code CommitLog} the
 *     resolver walks forward to turn a recorded parent hash into a successor commit.
 *     <b>Dependents:</b> {@code ProllySail} / {@code ProllySailConnection} when provenance is
 *     enabled, and the server's provenance / time-travel endpoints.
 */
public final class ProvenanceIndex {

    /**
     * Sentinel value used as the "parent hash" for triples added in the very first (genesis)
     * commit. The resolver maps empty bytes to the first entry of the {@code CommitLog}.
     */
    public static final byte[] GENESIS_PARENT = new byte[0];

    private final NodeStore store;
    private final BufferPool pool;
    private final TupleDescriptor keySchema;
    private MutableMap base;

    /**
     * Pending writes not yet committed. We don't read these back during the same transaction
     * (provenance reads at HEAD ignore in-flight writes), which matches how the other indexes batch
     * writes.
     */
    private final Map<SpocKey, byte[]> pending = new HashMap<>();

    public ProvenanceIndex(NodeStore store, BufferPool pool) {
        this.store = store;
        this.pool = pool;
        // Same key schema as the SPOC index — content-addressed key shape
        // means future Dolt-side readers can deserialize the entries.
        this.keySchema = SpocKey.DESCRIPTOR;
        StaticMap empty = new StaticMap(store, null, keySchema);
        this.base = new MutableMap(empty, store, keySchema, pool);
    }

    public ProvenanceIndex(NodeStore store, BufferPool pool, StaticMap committed) {
        this.store = store;
        this.pool = pool;
        this.keySchema = committed.descriptor();
        this.base = new MutableMap(committed, store, keySchema, pool);
    }

    /**
     * Record the first appearance of a triple. Idempotent — if any prior commit already recorded
     * this triple, the existing entry is kept.
     *
     * @param parentCommitHash the commit hash that was HEAD <em>before</em> this triple landed
     *     (i.e., the parent of the commit being built). Pass {@link #GENESIS_PARENT} (empty array)
     *     for the genesis commit.
     */
    public void recordFirstSeen(SpocKey key, byte[] parentCommitHash) {
        recordFirstSeen(key, parentCommitHash, EMPTY);
    }

    /**
     * Three-arg overload (ADR-0001 §9 axis 5) — record the first appearance along with the repo
     * identifier (typically the genesis commit hash). The {@code repoId} is folded into the stored
     * value so CAS dedup at the metadata layer is broken across repos even when the underlying
     * triples are identical.
     *
     * @param repoId the repo's stable identifier; pass {@code byte[0]} for "unscoped" (legacy /
     *     single-repo mode). Stored as-is in the entry.
     */
    public void recordFirstSeen(SpocKey key, byte[] parentCommitHash, byte[] repoId) {
        if (parentCommitHash == null) {
            throw new IllegalArgumentException(
                    "parentCommitHash must not be null (use GENESIS_PARENT for empty)");
        }
        if (repoId == null) {
            throw new IllegalArgumentException(
                    "repoId must not be null (use byte[0] for unscoped)");
        }
        if (pending.containsKey(key)) return;
        if (readCommitted(key).isPresent()) return;
        pending.put(key, encodeValue(parentCommitHash, repoId));
    }

    /** Look up the parent commit hash for a triple. Reads committed + pending. */
    public Optional<byte[]> firstSeen(SpocKey key) {
        return firstSeenEntry(key).map(Entry::parent);
    }

    /**
     * Look up the full entry (parent + repoId) for a triple. Returns empty when the triple has no
     * provenance record. Used by callers that care about which repo introduced the triple — e.g., a
     * federated lookup disambiguating "who knew this first" across stores.
     */
    public Optional<Entry> firstSeenEntry(SpocKey key) {
        byte[] p = pending.get(key);
        if (p != null) return Optional.of(decodeValue(p));
        Optional<byte[]> raw = readCommitted(key);
        return raw.map(ProvenanceIndex::decodeValue);
    }

    /** One stored provenance entry — parent commit + repo identifier. */
    public record Entry(byte[] parent, byte[] repoId) {}

    private static final byte[] EMPTY = new byte[0];

    /**
     * On-disk value layout (ADR-0001 §9 axis 5).
     *
     * <p>Every entry carries the four-byte magic {@code 0x70 0x76 0x32 0x00} (ASCII "pv2\0")
     * followed by {@code [1 byte parentLen][parent][1 byte repoIdLen][repoId]}. Either field may
     * have length 0 — empty parent = genesis, empty repoId = unscoped.
     *
     * <p>Pre-1.0 we do not read pre-axis-5 entries. The magic is mandatory: a missing magic throws
     * {@link IllegalStateException} so corrupt or stale-format data fails loudly rather than
     * misinterpreting a real hash whose first bytes happen to look meaningful. {@link
     * com.dolthub.prolly} store dirs from older iters must be wiped before upgrading.
     */
    private static final byte[] V2_MAGIC = new byte[] {0x70, 0x76, 0x32, 0x00};

    private static byte[] encodeValue(byte[] parent, byte[] repoId) {
        byte[] out = new byte[V2_MAGIC.length + 1 + parent.length + 1 + repoId.length];
        System.arraycopy(V2_MAGIC, 0, out, 0, V2_MAGIC.length);
        int o = V2_MAGIC.length;
        out[o++] = (byte) parent.length;
        System.arraycopy(parent, 0, out, o, parent.length);
        o += parent.length;
        out[o++] = (byte) repoId.length;
        System.arraycopy(repoId, 0, out, o, repoId.length);
        return out;
    }

    private static Entry decodeValue(byte[] raw) {
        if (raw.length == 0) return new Entry(EMPTY, EMPTY);
        if (raw.length < V2_MAGIC.length || !hasMagic(raw)) {
            throw new IllegalStateException(
                    "ProvenanceIndex entry is not v2 format (missing magic prefix). "
                            + "Older on-disk formats are not supported; wipe the store-dir and rebuild.");
        }
        int o = V2_MAGIC.length;
        int parentLen = raw[o++] & 0xFF;
        byte[] parent = new byte[parentLen];
        System.arraycopy(raw, o, parent, 0, parentLen);
        o += parentLen;
        int repoIdLen = raw[o++] & 0xFF;
        byte[] repoId = new byte[repoIdLen];
        System.arraycopy(raw, o, repoId, 0, repoIdLen);
        return new Entry(parent, repoId);
    }

    private static boolean hasMagic(byte[] raw) {
        for (int i = 0; i < V2_MAGIC.length; i++) {
            if (raw[i] != V2_MAGIC[i]) return false;
        }
        return true;
    }

    /** True iff this index has any entry for {@code key} (committed or pending). */
    public boolean contains(SpocKey key) {
        return firstSeen(key).isPresent();
    }

    /** Flush pending writes; return the new committed root. */
    public StaticMap commit() {
        flushPendingIntoBase();
        StaticMap next = base.flush();
        this.base = new MutableMap(next, store, keySchema, pool);
        return next;
    }

    /**
     * Drain {@link #pending} into {@link #base}'s edit buffer (without flushing to a new root).
     * Shared by {@link #commit()} and {@link #mergeFrom}: the latter calls it up front so its
     * older-wins fold compares against — and writes over — this transaction's own {@code
     * recordFirstSeen} entries, instead of those pending entries clobbering the fold during the
     * subsequent {@code commit()}.
     */
    private void flushPendingIntoBase() {
        for (Map.Entry<SpocKey, byte[]> e : pending.entrySet()) {
            // Idempotence was enforced at recordFirstSeen time: pending never
            // holds a key already committed. Single-writer → no race here.
            base.put(buildKeyTuple(e.getKey()), MemorySegment.ofArray(e.getValue()));
        }
        pending.clear();
    }

    /** Drop pending writes (rollback). */
    public void discard() {
        pending.clear();
    }

    /** Number of in-flight writes — exposed for diagnostics. */
    public int pendingCount() {
        return pending.size();
    }

    // ---- private ------------------------------------------------------

    private Optional<byte[]> readCommitted(SpocKey key) {
        Optional<MemorySegment> v = base.get(buildKeyTuple(key));
        if (v.isEmpty()) return Optional.empty();
        // Copy the bytes out — the MemorySegment may be backed by a buffer
        // that gets reused.
        MemorySegment seg = v.get();
        byte[] out = new byte[(int) seg.byteSize()];
        MemorySegment.copy(seg, 0, MemorySegment.ofArray(out), 0, out.length);
        return Optional.of(out);
    }

    private MemorySegment buildKeyTuple(SpocKey key) {
        TupleBuilder tb = new TupleBuilder(pool, keySchema);
        tb.putInt64(0, key.col0().value());
        tb.putInt64(1, key.col1().value());
        tb.putInt64(2, key.col2().value());
        tb.putInt64(3, key.col3().value());
        return tb.build().segment();
    }

    /**
     * The currently-committed root — exposed so other indexes can iterate our entries (e.g., the
     * merge fold in {@link #mergeFrom}).
     */
    public StaticMap committedRoot() {
        return base.base();
    }

    /**
     * Merge another ProvenanceIndex's entries into this one with "older wins" semantics — the entry
     * pointing to the earlier parent commit is kept. The caller supplies a predicate that decides
     * whether {@code other}'s parent is older than {@code this}'s (typically via {@code
     * CommitLog.findByHash().timestamp()} comparison).
     *
     * <p>Used by {@code MergeEngine} when reconciling two branches' provenance trees. Both sides
     * may have entries for the same triple if the triple was added on both branches independently
     * of a shared ancestor.
     *
     * <p><b>Iter F.6 — correctness fix.</b> Without this fold, a merge with overlapping provenance
     * entries leaves the result non-deterministic — whichever side wrote into the in-flight
     * provIdxTx first wins. With it, the result is canonical: the older first-seen-at commit is
     * preserved.
     *
     * @param other entries to fold in (committed side)
     * @param parentOlder predicate: true iff {@code other}'s parent (1st arg) is older than {@code
     *     this}'s parent (2nd arg). Free function so {@link ProvenanceIndex} stays decoupled from
     *     {@code CommitLog}.
     */
    public void mergeFrom(
            ProvenanceIndex other, java.util.function.BiPredicate<byte[], byte[]> parentOlder) {
        // Drain THIS index's pending writes into base first, so the fold below
        // resolves against this transaction's own recordFirstSeen entries.
        // Without it, a key present in both `pending` and `other` takes the
        // "adopt" branch below (pending is invisible to lookupRaw), and the
        // subsequent commit() flush re-applies the pending value over the
        // fold — defeating older-wins exactly for merge-introduced triples.
        flushPendingIntoBase();

        // Walk other's committed entries directly. We deliberately ignore
        // other.pending — the contract is "fold committed state from a peer
        // index"; if peer has unflushed writes, that's its problem to commit
        // before calling us.
        StaticMap otherCommitted = other.committedRoot();
        if (otherCommitted == null) return;
        com.dolthub.prolly.MapIterator it = otherCommitted.iter();
        while (it.next()) {
            MemorySegment otherKeySeg = it.key();
            MemorySegment otherValueSeg = it.value();
            byte[] otherRaw = new byte[(int) otherValueSeg.byteSize()];
            MemorySegment.copy(
                    otherValueSeg, 0, MemorySegment.ofArray(otherRaw), 0, otherRaw.length);

            // Look up the same key in our pending + committed.
            byte[] ourRaw = lookupRaw(otherKeySeg);
            if (ourRaw == null) {
                // Not present here — adopt other's entry (full encoded value,
                // including its repoId if present, so axis-5 scoping survives the merge).
                base.put(copySegment(otherKeySeg), MemorySegment.ofArray(otherRaw));
                continue;
            }
            // Both sides have an entry — decode just the parent portion of each
            // for the older-wins comparison; the value-format wrapping (axis 5
            // repoId tagging) is irrelevant to which one is older.
            byte[] otherParent = decodeValue(otherRaw).parent();
            byte[] ourParent = decodeValue(ourRaw).parent();
            if (parentOlder.test(otherParent, ourParent)) {
                // Other's parent is older — adopt it. `pending` was drained into
                // base at the top of this method, so this base.put is the final
                // write for the key; commit()'s flush has nothing left to undo.
                base.put(copySegment(otherKeySeg), MemorySegment.ofArray(otherRaw));
            }
            // else: ours is older or equal — keep it.
        }
    }

    /**
     * Read the raw stored bytes for a key, checking pending first then committed; null if absent.
     */
    private byte @Nullable [] lookupRaw(MemorySegment keySeg) {
        // We can't reverse-decode keySeg → SpocKey cheaply; lookups by raw key
        // need to go through the committed map directly.
        Optional<MemorySegment> existing = base.get(keySeg);
        if (existing.isEmpty()) return null;
        byte[] out = new byte[(int) existing.get().byteSize()];
        MemorySegment.copy(existing.get(), 0, MemorySegment.ofArray(out), 0, out.length);
        return out;
    }

    /** Defensive copy of a MemorySegment to a stable backing array. */
    private static MemorySegment copySegment(MemorySegment src) {
        byte[] arr = new byte[(int) src.byteSize()];
        MemorySegment.copy(src, 0, MemorySegment.ofArray(arr), 0, arr.length);
        return MemorySegment.ofArray(arr);
    }

    /** Equality on the byte content of a hash — handy for tests. */
    public static boolean parentEquals(byte[] a, byte[] b) {
        return Arrays.equals(a, b);
    }
}
