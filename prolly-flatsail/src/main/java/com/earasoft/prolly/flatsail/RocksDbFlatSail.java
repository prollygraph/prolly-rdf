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

import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.AbstractSail;
import org.jspecify.annotations.Nullable;
import org.rocksdb.RocksDBException;

/**
 * An <strong>unversioned</strong> RDF4J {@link org.eclipse.rdf4j.sail.Sail} storing quads as plain
 * sorted RocksDB keys — the fast, simple sibling of the versioned {@code ProllySail}.
 *
 * <p>Construct it one of two ways:
 *
 * <ul>
 *   <li>{@link #RocksDbFlatSail(Path)} — the Sail opens (and owns) a {@link RocksFlatStore} at the
 *       given directory on {@code init()} and closes it on {@code shutDown()};
 *   <li>{@link #RocksDbFlatSail(RocksFlatStore)} — the caller supplies an already-open store (e.g.
 *       one sharing a RocksDB instance with the versioned chunk store); the Sail uses but does not
 *       close it.
 * </ul>
 *
 * <p>This class is the Sail shell — lifecycle, value factory, connection factory. The data path
 * lives in {@link RocksDbFlatSailConnection}.
 *
 * <p><b>Concurrency — why single-writer.</b> Writes are serialized by a fair single-writer {@link
 * java.util.concurrent.Semaphore} ({@code writeLock}); readers never take it, so reads stay fully
 * concurrent. RocksDB itself is <em>not</em> single-writer — the gate exists because the shared
 * {@link FlatDictionary} assigns a monotonic id to each new term: two concurrent writers would
 * intern the <em>same</em> new term to <em>different</em> ids (each sees only committed state plus
 * its own {@code WriteBatchWithIndex}), corrupting the term-to-id mapping and the persisted id
 * counter. The gate stands in for the write-write conflict detection a RocksDB {@code
 * OptimisticTransactionDB} would provide — FlatSail deliberately uses a plain {@code
 * WriteBatchWithIndex} + {@code db.write(batch)} instead, trading concurrent writers for
 * simplicity. (The versioned {@code ProllySail} is single-writer for the same dictionary reason,
 * plus its copy-on-write roots.)
 *
 * @apiNote Use this when you want RDF4J-compatible quad storage <em>without</em> history — no
 *     commits, branches, or time travel, just sorted key/value storage that is faster and simpler
 *     than the versioned {@code ProllySail}. It extends {@link AbstractSail} (not the notifying
 *     variant): there are no per-statement listeners, hence no streaming-progress hook. Writes are
 *     serialized by a fair single-writer {@link java.util.concurrent.Semaphore}; readers never take
 *     it.
 * @implNote <b>Collaborators:</b> {@link RocksFlatStore} (the RocksDB column families holding the
 *     four index permutations plus the dictionary), {@link FlatDictionary} (term-to-id interning,
 *     kept race-free by the single-writer gate), and RDF4J's {@link ValueFactory}.
 *     <b>Dependents:</b> {@link RocksDbFlatSailConnection} (every read/write runs through it) and
 *     the platform, which can host a flat Sail as a non-owning co-tenant alongside the versioned
 *     chunk store in one shared RocksDB engine (see {@code SharedRocksDb}).
 */
public class RocksDbFlatSail extends AbstractSail {

    private final ValueFactory valueFactory = SimpleValueFactory.getInstance();

    /** Non-null iff this Sail opens and owns its own store (the {@link Path} constructor). */
    private final @Nullable Path dataDir;

    /** True iff {@link #shutDownInternal()} must close {@link #store}. */
    private final boolean ownsStore;

    // @Nullable: lazily set in initializeInternal() (or, in the store ctor, store is set there);
    // both
    // are valid only between init() and shutDown(), which store()/dictionary() assert.
    private volatile @Nullable RocksFlatStore store;
    private volatile @Nullable FlatDictionary dictionary;

    /**
     * Single-writer gate. A write transaction acquires this on its first mutation and releases it
     * at commit/rollback, so only one transaction mutates at a time — readers never take it. A fair
     * {@link Semaphore} (not a {@code ReentrantLock}) so acquire and release need not happen on the
     * same thread. Serializing writers is what keeps {@code FlatDictionary} intern race-free: two
     * writers interning the same new term concurrently would otherwise assign it two TermIds.
     */
    private final java.util.concurrent.Semaphore writeLock =
            new java.util.concurrent.Semaphore(1, true);

    /** Open and own a RocksDB-backed flat Sail rooted at {@code dataDir}. */
    public RocksDbFlatSail(Path dataDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.ownsStore = true;
    }

    /**
     * Build a flat Sail over an already-open {@code store}. The Sail uses the store but does not
     * own it — {@code shutDown()} leaves it open for the caller (or co-tenant) to close.
     */
    public RocksDbFlatSail(RocksFlatStore store) {
        this.dataDir = null;
        this.store = Objects.requireNonNull(store, "store");
        this.ownsStore = false;
    }

    @Override
    protected void initializeInternal() throws SailException {
        super.initializeInternal();
        if (ownsStore) {
            // ownsStore implies the Path ctor ran, so dataDir is non-null.
            try {
                this.store = RocksFlatStore.open(Objects.requireNonNull(dataDir).toString());
            } catch (RocksDBException e) {
                throw new SailException("RocksDbFlatSail: failed to open RocksDB at " + dataDir, e);
            }
        }
        // store is non-null here: set just above when ownsStore, else by the RocksFlatStore ctor.
        this.dictionary = new FlatDictionary(Objects.requireNonNull(store));
    }

    @Override
    protected void shutDownInternal() throws SailException {
        // Only close the store this Sail opened itself; a caller-supplied store
        // (shared-instance mode) is the caller's to close.
        if (ownsStore && store != null) {
            store.close();
        }
    }

    @Override
    protected SailConnection getConnectionInternal() throws SailException {
        return new RocksDbFlatSailConnection(this);
    }

    @Override
    public ValueFactory getValueFactory() {
        return valueFactory;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    /** The backing store. Valid only between {@code init()} and {@code shutDown()}. */
    RocksFlatStore store() {
        return Objects.requireNonNull(store, "store() before init() / after shutDown()");
    }

    /**
     * The RDF Value ↔ TermId dictionary. Valid only between {@code init()} and {@code shutDown()}.
     */
    FlatDictionary dictionary() {
        return Objects.requireNonNull(dictionary, "dictionary() before init() / after shutDown()");
    }

    /** The single-writer gate — a connection holds it for the life of a write transaction. */
    java.util.concurrent.Semaphore writeLock() {
        return writeLock;
    }
}
