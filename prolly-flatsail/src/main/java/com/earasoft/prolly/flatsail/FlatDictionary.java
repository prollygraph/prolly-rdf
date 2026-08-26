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
package com.earasoft.prolly.flatsail;

import com.earasoft.prolly.rdf4j.term.TermId;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.rdf4j.model.Value;
import org.jspecify.annotations.Nullable;
import org.rocksdb.AbstractWriteBatch;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatchWithIndex;

/**
 * Bidirectional RDF {@link Value} ↔ {@link TermId} dictionary, backed by two RocksDB column
 * families of a {@link RocksFlatStore}:
 *
 * <ul>
 *   <li>{@code dict-fwd} — 8-byte {@code TermId} → encoded term bytes;
 *   <li>{@code dict-rev} — encoded term bytes → 8-byte {@code TermId}.
 * </ul>
 *
 * <p>TermIds are assigned <strong>sequentially from 1</strong>; id 0 ({@link TermId#ZERO}) is
 * reserved as the default-graph sentinel. The next id is persisted in {@code dict-fwd} under a
 * reserved 15-byte key that cannot collide with an 8-byte TermId key.
 *
 * <h3>Within-transaction consistency</h3>
 *
 * <p>{@link #intern} stages new mappings into a caller-supplied {@link AbstractWriteBatch} that is
 * not yet committed, so a plain {@code dict-rev} read would not see a term interned earlier in the
 * same batch — and the same term (a recurring predicate, say) would be assigned a fresh id on every
 * occurrence. The caller therefore passes a per-transaction {@code pending} map; {@code intern}
 * consults and updates it so each distinct term gets exactly one id per transaction. The map is
 * keyed by the encoded term bytes.
 *
 * <p>{@link #find} and {@link #lookup} take an optional open transaction's {@link
 * WriteBatchWithIndex}: when given, they merge its uncommitted entries over committed state
 * (read-your-writes); when {@code null} they read committed state only. Both are safe for
 * concurrent use; {@code intern} is single-writer like the Sail.
 */
public final class FlatDictionary {

    /** Default read options, shared — immutable in use, so safe across threads. */
    private static final ReadOptions READ_OPTIONS = new ReadOptions();

    /** Upper bound on the {@link #termCache}; ~15&nbsp;MB worst case. */
    private static final int MAX_CACHED_TERMS = 100_000;

    /** Reserved {@code dict-fwd} key holding the next sequential id — never an 8-byte TermId. */
    private static final byte[] NEXT_ID_KEY =
            new byte[] {0, '_', 'f', 'l', 'a', 't', '_', 'n', 'e', 'x', 't', '_', 'i', 'd'};

    /** First assignable id; 0 is reserved ({@link TermId#ZERO} = default-graph sentinel). */
    private static final long FIRST_ID = 1L;

    private final RocksDB db;
    private final ColumnFamilyHandle fwd;
    private final ColumnFamilyHandle rev;
    private final AtomicLong nextId;

    /**
     * Bounded LRU cache of {@code TermId → Value}. The dictionary is append-only — once a TermId is
     * assigned, its term never changes — so cached entries can never go stale and need no
     * invalidation. Shared across this Sail's connections so hot terms (recurring predicates,
     * vocabulary IRIs) stay warm. Without it, a scan issues a RocksDB point-read per term per
     * statement; with it, a warm scan issues none.
     */
    private final Map<TermId, Value> termCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<TermId, Value>(1024, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<TermId, Value> eldest) {
                            return size() > MAX_CACHED_TERMS;
                        }
                    });

    public FlatDictionary(RocksFlatStore store) {
        this.db = store.db();
        this.fwd = store.dictForward();
        this.rev = store.dictReverse();
        this.nextId = new AtomicLong(loadNextId());
    }

    private long loadNextId() {
        try {
            byte[] raw = db.get(fwd, NEXT_ID_KEY);
            return raw == null ? FIRST_ID : longFromBytes(raw);
        } catch (RocksDBException e) {
            throw new IllegalStateException("FlatDictionary: failed to read the id counter", e);
        }
    }

    /**
     * Return the {@link TermId} for {@code value}, assigning a new one — staged into {@code batch}
     * — if the term is not already known.
     *
     * @param value the RDF term to intern
     * @param batch the open transaction's write batch; new mappings are staged here
     * @param pending per-transaction cache of terms interned in {@code batch} so far (keyed by
     *     encoded term bytes); created empty at {@code begin}, discarded at commit/rollback
     */
    public TermId intern(Value value, AbstractWriteBatch batch, Map<ByteBuffer, TermId> pending) {
        byte[] term = FlatTermCodec.encode(value);
        ByteBuffer cacheKey = ByteBuffer.wrap(term);
        TermId staged = pending.get(cacheKey);
        if (staged != null) {
            return staged;
        }
        try {
            byte[] committed = db.get(rev, term);
            if (committed != null) {
                TermId tid = TermId.of(longFromBytes(committed));
                pending.put(cacheKey, tid);
                return tid;
            }
            long id = nextId.getAndIncrement();
            byte[] idBytes = bytesFromLong(id);
            batch.put(fwd, idBytes, term);
            batch.put(rev, term, idBytes);
            batch.put(fwd, NEXT_ID_KEY, bytesFromLong(nextId.get()));
            TermId tid = TermId.of(id);
            pending.put(cacheKey, tid);
            return tid;
        } catch (RocksDBException e) {
            throw new IllegalStateException("FlatDictionary.intern failed", e);
        }
    }

    /** The {@link TermId} for {@code value} in committed state, else empty. */
    public Optional<TermId> find(Value value) {
        return find(value, null);
    }

    /**
     * The {@link TermId} for {@code value}, or empty. When {@code tx} is non-null its uncommitted
     * writes are merged over committed state, so a term interned earlier in the same transaction
     * resolves.
     */
    public Optional<TermId> find(Value value, @Nullable WriteBatchWithIndex tx) {
        byte[] term = FlatTermCodec.encode(value);
        try {
            byte[] id =
                    (tx != null)
                            ? tx.getFromBatchAndDB(db, rev, READ_OPTIONS, term)
                            : db.get(rev, term);
            return id == null ? Optional.empty() : Optional.of(TermId.of(longFromBytes(id)));
        } catch (RocksDBException e) {
            throw new IllegalStateException("FlatDictionary.find failed", e);
        }
    }

    /** The RDF {@link Value} for {@code id} in committed state, else empty. */
    public Optional<Value> lookup(TermId id) {
        return lookup(id, null);
    }

    /**
     * The RDF {@link Value} for {@code id}, or empty. When {@code tx} is non-null its uncommitted
     * writes are merged over committed state, so a TermId minted earlier in the same transaction
     * resolves.
     */
    public Optional<Value> lookup(TermId id, @Nullable WriteBatchWithIndex tx) {
        Value cached = termCache.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        byte[] idBytes = bytesFromLong(id.value());
        try {
            byte[] term =
                    (tx != null)
                            ? tx.getFromBatchAndDB(db, fwd, READ_OPTIONS, idBytes)
                            : db.get(fwd, idBytes);
            if (term == null) {
                return Optional.empty();
            }
            Value value = FlatTermCodec.decode(term);
            termCache.put(id, value);
            return Optional.of(value);
        } catch (RocksDBException e) {
            throw new IllegalStateException("FlatDictionary.lookup failed", e);
        }
    }

    /**
     * Resolve several TermIds to Values at once — the batched form of {@link #lookup}. Cached ids
     * are served from the cache; the cache misses are fetched in a <strong>single RocksDB {@code
     * multiGet}</strong> when no transaction is open, collapsing one JNI point-read per term into
     * one round-trip per call. That is the cold-scan win: a freshly opened store's first scan
     * resolves each statement's terms in a single batched fetch instead of three or four separate
     * reads.
     *
     * <p>With a transaction open it falls back to per-id transaction-merged reads — {@code
     * WriteBatchWithIndex} has no batched read.
     *
     * @return values positionally aligned with {@code ids}; a slot is {@code null} if that id has
     *     no dictionary entry
     */
    public Value[] lookupAll(TermId[] ids, @Nullable WriteBatchWithIndex tx) {
        Value[] out = new Value[ids.length];
        List<Integer> misses = null;
        for (int i = 0; i < ids.length; i++) {
            Value cached = termCache.get(ids[i]);
            if (cached != null) {
                out[i] = cached;
            } else {
                if (misses == null) {
                    misses = new ArrayList<>(ids.length);
                }
                misses.add(i);
            }
        }
        if (misses == null) {
            return out; // every id was already cached
        }
        try {
            if (tx != null) {
                for (int i : misses) {
                    resolveMiss(
                            out,
                            ids,
                            i,
                            tx.getFromBatchAndDB(
                                    db, fwd, READ_OPTIONS, bytesFromLong(ids[i].value())));
                }
            } else {
                List<ColumnFamilyHandle> cfs = new ArrayList<>(misses.size());
                List<byte[]> keys = new ArrayList<>(misses.size());
                for (int i : misses) {
                    cfs.add(fwd);
                    keys.add(bytesFromLong(ids[i].value()));
                }
                List<byte[]> terms = db.multiGetAsList(cfs, keys);
                for (int k = 0; k < misses.size(); k++) {
                    resolveMiss(out, ids, misses.get(k), terms.get(k));
                }
            }
        } catch (RocksDBException e) {
            throw new IllegalStateException("FlatDictionary.lookupAll failed", e);
        }
        return out;
    }

    /**
     * Decode one fetched term into {@code out[i]} and cache it; {@code null} term → leave the slot
     * null.
     */
    private void resolveMiss(Value[] out, TermId[] ids, int i, byte[] term) {
        if (term != null) {
            Value value = FlatTermCodec.decode(term);
            termCache.put(ids[i], value);
            out[i] = value;
        }
    }

    // ---- 8-byte big-endian long <-> bytes -------------------------------

    private static byte[] bytesFromLong(long v) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) v;
            v >>>= 8;
        }
        return out;
    }

    private static long longFromBytes(byte[] b) {
        long v = 0L;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[i] & 0xFFL);
        }
        return v;
    }
}
