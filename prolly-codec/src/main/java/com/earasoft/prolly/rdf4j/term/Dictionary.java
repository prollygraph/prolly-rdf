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
package com.earasoft.prolly.rdf4j.term;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;

/**
 * Content-addressed map from {@link TermId} to encoded-term bytes.
 *
 * <p>The dictionary is the source of truth for every term in the store. Indexes reference terms
 * only by their {@code TermId}, so the dictionary is on the read path for any operation that needs
 * to materialize a term value (e.g. {@link com.earasoft.prolly.rdf4j.value.ProllyValue}
 * construction from a tuple).
 *
 * <p>Schema: a single Prolly tree with key column {@code Int64} (the {@code TermId.value()}) and a
 * value blob holding the encoded-term bytes produced by {@link TermCodec}.
 *
 * <h2>Collision handling — salted rehash</h2>
 *
 * <p>Hash collisions are handled by a deterministic salted-rehash loop. Salt 0 places the term in
 * the <em>natural</em> address space (top bit of TermId clear). On collision (occupied slot,
 * byte-different term) the encoder tries salt 1, 2, ... — each salt produces a hash over {@code
 * [salt-BE-4B][term]} and a TermId in the <em>extension</em> address space (top bit set). The salt
 * sequence is reproducible: re-encoding the same bytes traverses the same chain and finds the same
 * slot.
 *
 * <p>The dictionary uses a single Prolly tree; natural and extension entries coexist, distinguished
 * only by the top bit of their TermId. Storage is sparse — collisions on a 64-bit hash are
 * vanishingly rare.
 *
 * <p>If {@link #MAX_SALT} consecutive collisions are seen (degenerate hash function or pathological
 * adversarial input), {@link CollisionChainExhausted} is thrown.
 *
 * <p>Not thread-safe. The Sail layer holds one Dictionary per open connection and serializes
 * writes.
 *
 * @implNote <b>Collaborators:</b> {@link TermCodec} (the encoded-term bytes), and a single prolly
 *     tree ({@link com.dolthub.prolly.StaticMap}/{@link com.dolthub.prolly.MutableMap} over a
 *     {@link com.dolthub.prolly.NodeStore}) keyed by {@link TermId}. <b>Dependents:</b> every index
 *     (they hold only {@link TermId}s) and the tuple-to-{@code ProllyValue} materialization path —
 *     so the dictionary sits on the read path of any operation that turns an identifier back into a
 *     term value.
 */
public final class Dictionary {

    /** Maximum salt retries before declaring the collision chain exhausted. */
    public static final int MAX_SALT = 64;

    private final NodeStore store;
    private final BufferPool pool;
    private final HashFunction hashFn;
    private final TupleDescriptor keySchema;
    private final int maxSalt;
    private final EncoderMetrics metrics;
    private MutableMap buffer;

    public Dictionary(NodeStore store, BufferPool pool, HashFunction hashFn) {
        this(store, pool, hashFn, MAX_SALT, EncoderMetrics.noop());
    }

    /**
     * Test-friendly constructor: lets tests use a small {@code maxSalt} to trigger the exhaustion
     * path without inserting thousands of terms.
     */
    public Dictionary(NodeStore store, BufferPool pool, HashFunction hashFn, int maxSalt) {
        this(store, pool, hashFn, maxSalt, EncoderMetrics.noop());
    }

    public Dictionary(
            NodeStore store,
            BufferPool pool,
            HashFunction hashFn,
            int maxSalt,
            EncoderMetrics metrics) {
        this.store = store;
        this.pool = pool;
        this.hashFn = hashFn;
        this.maxSalt = maxSalt;
        this.metrics = metrics;
        // 1-column key schema: Int64 (the TermId.value()). Values are raw bytes
        // (no schema imposed on values — they are encoded-term blobs).
        this.keySchema = new TupleDescriptor(List.of(new Type(Encoding.Int64, false)));
        StaticMap emptyBase = new StaticMap(store, null, keySchema);
        this.buffer = newBuffer(emptyBase);
    }

    /**
     * Per-dictionary spill threshold override ({@code prolly.tx.dict.spill.bytes}; {@code <= 0}
     * keeps the normal {@code prolly.tx.spill.bytes} default). HISTORICALLY this was the only
     * escape from the build-once encode wall — {@link #encode}'s per-term dedup {@code get} was
     * {@code O(runs)} once the {@code SpillableSortedBuffer} spilled (plans/prolly-bulk-load.md
     * Step 4h / ADR-0061 D-3) — so bulk loads set it HIGH to keep the buffer in-heap at the cost of
     * heap proportional to distinct terms. Since {@link #newBuffer} enables the buffer's presence
     * index, a SPILLED dictionary also encodes in amortized {@code O(1)} per term, and this knob
     * demotes to ordinary heap-vs-disk tuning. Only the dictionary carries either concern — the
     * index buffers are insert-only (no per-key dedup get), so they spill harmlessly at the
     * default.
     */
    private static final long DICT_SPILL_BYTES = Long.getLong("prolly.tx.dict.spill.bytes", -1L);

    private MutableMap newBuffer(StaticMap base) {
        // presenceIndex=true, unconditionally: encode's dedup get() is the
        // measured quadratic wall once this buffer spills (each absent
        // first-encounter term walked every run file), and the Int64 keys
        // built by Int64Key.toTupleSegment are canonical — equal values are
        // byte-identical tuples — which is exactly the index's contract.
        // With the index, a spilled dictionary encodes in amortized O(1) per
        // term, so DICT_SPILL_BYTES (keeping the buffer in-heap) becomes a
        // tuning knob rather than the only escape from O(runs) per term.
        return DICT_SPILL_BYTES > 0
                ? new MutableMap(
                        base, store, keySchema, pool, Int64Key.COMPARATOR, DICT_SPILL_BYTES, true)
                : new MutableMap(base, store, keySchema, pool, Int64Key.COMPARATOR, true);
    }

    /** Re-open against an existing committed root. */
    public Dictionary(NodeStore store, BufferPool pool, HashFunction hashFn, StaticMap committed) {
        this(store, pool, hashFn, committed, EncoderMetrics.noop());
    }

    public Dictionary(
            NodeStore store,
            BufferPool pool,
            HashFunction hashFn,
            StaticMap committed,
            EncoderMetrics metrics) {
        this.store = store;
        this.pool = pool;
        this.hashFn = hashFn;
        this.maxSalt = MAX_SALT;
        this.metrics = metrics;
        this.keySchema = committed.descriptor();
        this.buffer = newBuffer(committed);
    }

    /** The hash function this dictionary uses — exposed for diagnostics / tests. */
    public HashFunction hashFunction() {
        return hashFn;
    }

    /**
     * Encode an already-encoded term into the dictionary, returning its TermId.
     *
     * <ul>
     *   <li>Salt 0 → natural address space. Empty slot → insert; byte-match → dedupe.
     *   <li>Salt 1..maxSalt-1 → extension address space, only attempted on collision.
     *   <li>If all {@link #MAX_SALT} salts collide → {@link CollisionChainExhausted}.
     * </ul>
     */
    public TermId encode(MemorySegment encodedTerm) {
        for (int salt = 0; salt < maxSalt; salt++) {
            long h = saltedHash(encodedTerm, salt);
            TermId tid =
                    (salt == 0)
                            ? TermId.ofNatural(h)
                            : TermId.ofExtensionSlot(h & TermId.NATURAL_MASK);
            MemorySegment keyTuple = buildKeyTuple(tid);
            Optional<MemorySegment> existing = buffer.get(keyTuple);
            if (existing.isEmpty()) {
                byte[] copy = encodedTerm.toArray(com.earasoft.prolly.rdf4j.term.Layouts.BYTE);
                buffer.put(keyTuple, MemorySegment.ofArray(copy));
                metrics.increment(
                        salt == 0 ? "dict.encode.insert" : "dict.encode.insert.extension");
                if (salt > 0) metrics.increment("dict.encode.collision");
                return tid;
            }
            if (Compare.compareUnsigned(existing.get(), encodedTerm) == 0) {
                metrics.increment(salt == 0 ? "dict.encode.hit" : "dict.encode.hit.extension");
                return tid; // dedupe
            }
            // Real byte-different collision at this salt — try next salt.
            metrics.increment("dict.encode.collision.retry");
        }
        throw new CollisionChainExhausted(maxSalt, encodedTerm);
    }

    private long saltedHash(MemorySegment term, int salt) {
        if (salt == 0) return hashFn.hash(term);
        int termLen = (int) term.byteSize();
        byte[] buf = new byte[4 + termLen];
        buf[0] = (byte) (salt >>> 24);
        buf[1] = (byte) (salt >>> 16);
        buf[2] = (byte) (salt >>> 8);
        buf[3] = (byte) salt;
        if (termLen > 0) {
            MemorySegment.copy(term, 0, MemorySegment.ofArray(buf), 4, termLen);
        }
        return hashFn.hash(buf);
    }

    /**
     * Read-only encode — walks the same salt chain as {@link #encode(MemorySegment)} but never
     * writes. Returns the stored {@link TermId} when the encoded term is already in the dictionary,
     * or {@link Optional#empty()} otherwise.
     *
     * <p>Used by snapshot-mode lookups (provenance, etc.) where the caller has a snapshot {@link
     * StaticMap} and wants to ask "is this term known" without mutating the dictionary.
     */
    public Optional<TermId> findTermId(MemorySegment encodedTerm) {
        for (int salt = 0; salt < maxSalt; salt++) {
            long h = saltedHash(encodedTerm, salt);
            TermId tid =
                    (salt == 0)
                            ? TermId.ofNatural(h)
                            : TermId.ofExtensionSlot(h & TermId.NATURAL_MASK);
            // Transient lookup key (ADR-0062 D-3/D-4): probe into a block we own, then recycle it —
            // this
            // is a get only (no put on this path), so MutableMap never retains the key. Release the
            // block,
            // not the slice. A no-op under HeapBufferPool.
            MemorySegment keyBlock = pool.borrow(Int64Key.TUPLE_SIZE);
            Optional<MemorySegment> existing =
                    buffer.get(Int64Key.writeInto(keyBlock, tid.value()));
            pool.release(keyBlock);
            if (existing.isEmpty()) {
                // Empty slot at this salt means the term hasn't been inserted at
                // this hash address. It might exist at a later salt (only if an
                // earlier write produced a collision and walked there) — keep
                // looking.
                continue;
            }
            if (Compare.compareUnsigned(existing.get(), encodedTerm) == 0) {
                return Optional.of(tid);
            }
            // byte-different collision; the term might still be at a later salt.
        }
        return Optional.empty();
    }

    /**
     * Decode a TermId back to its encoded-term bytes.
     *
     * @return the stored encoded-term bytes, or empty if the id is unknown
     */
    public Optional<MemorySegment> decode(TermId id) {
        // Transient lookup key (ADR-0062 D-3/D-4): probe into a block we own, recycle it, return
        // the
        // value. The returned value segment is independent of the key block (it comes from the
        // buffer's
        // stored value, a heap copy), so releasing the key does not touch it. A no-op under
        // HeapBufferPool.
        MemorySegment keyBlock = pool.borrow(Int64Key.TUPLE_SIZE);
        Optional<MemorySegment> v = buffer.get(Int64Key.writeInto(keyBlock, id.value()));
        pool.release(keyBlock);
        metrics.increment(v.isPresent() ? "dict.decode.hit" : "dict.decode.miss");
        return v;
    }

    /**
     * Flush buffered inserts to a new committed {@link StaticMap} and reset the write buffer.
     *
     * @return the new committed root
     */
    public StaticMap commit() {
        StaticMap next = buffer.flush();
        // Through newBuffer, not a bare construction: the rebase buffer must
        // carry the same presence-index + spill tuning as every other dict
        // buffer, or the post-flush dictionary silently loses the very
        // properties the constructor chain establishes.
        this.buffer = newBuffer(next);
        return next;
    }

    /** Build a 1-column Int64 tuple key for the given TermId. */
    private MemorySegment buildKeyTuple(TermId id) {
        return Int64Key.toTupleSegment(pool, id.value());
    }

    /**
     * Thrown when the entire {@link #MAX_SALT}-deep collision chain is occupied by byte-different
     * terms. Indicates either a pathological hash function or adversarial input designed to force
     * collisions.
     */
    public static final class CollisionChainExhausted extends RuntimeException {
        public final int triedSalts;
        public final MemorySegment incoming;

        public CollisionChainExhausted(int triedSalts, MemorySegment incoming) {
            super(
                    "collision chain exhausted after "
                            + triedSalts
                            + " salts; incoming="
                            + incoming.byteSize()
                            + "B"
                            + ". Either a degenerate HashFunction or adversarial input.");
            this.triedSalts = triedSalts;
            this.incoming = incoming;
        }
    }
}
