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
package com.earasoft.prolly.rdf4j.persistence;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Test-only helper: capture the on-disk identity of a {@link StaticMap} so it can be re-loaded
 * after a close/reopen cycle.
 *
 * <p>The Sail does not yet auto-persist its root references — that's part of the Phase 4
 * Manifest/Database integration. Until then, tests that want to exercise close+reopen capture the
 * roots externally via this class and restore them manually with {@link #reload}.
 *
 * <p>What gets saved: the hash of the StaticMap's root chunk (RocksDB key) plus the TupleDescriptor
 * (in-process schema). What gets loaded: read the chunk by hash from the new NodeStore, parse via
 * {@link Node#fromBytes}, wrap as a new StaticMap.
 */
public final class PersistedRoot {

    private final byte[] rootHash;
    private final TupleDescriptor schema;

    /**
     * Capture a StaticMap by computing its root chunk's content hash. The upstream {@code
     * TreeMutator} now always writes the final root chunk to the store (was a bug in earlier
     * versions; see the {@code treemutator-root-write-gap} memory entry).
     *
     * <p>The {@code store} parameter is retained for defensive double-write: if the chunk was
     * already written by TreeMutator, this is a no-op (the store is content-addressed). If a
     * hypothetical future code path regresses, this re-write keeps the tests honest.
     *
     * @param map a committed StaticMap; {@code null} is treated as "empty tree" (no root → null
     *     hash)
     * @param store the NodeStore to write the root chunk into (defensive)
     */
    public PersistedRoot(StaticMap map, NodeStore store) {
        if (map == null || map.root() == null) {
            this.rootHash = null;
            this.schema = null;
        } else {
            byte[] bytes = map.root().bytes();
            byte[] expected = HashUtils.hash(bytes);
            // Idempotent write — if the chunk is already there (it should be
            // post-TreeMutator-fix), this is a no-op.
            byte[] written = store.write(bytes);
            if (!java.util.Arrays.equals(expected, written)) {
                throw new IllegalStateException(
                        "store.write returned a different hash than HashUtils.hash — store contract broken");
            }
            this.rootHash = expected;
            this.schema = map.descriptor();
        }
    }

    /**
     * Read-only capture: hash without writing. Caller must guarantee the chunk is already in the
     * store (e.g., for an interior root, which TreeMutator writes).
     */
    public static PersistedRoot capture(StaticMap map) {
        if (map == null || map.root() == null) {
            return new PersistedRoot(null, null, null);
        }
        return new PersistedRoot(HashUtils.hash(map.root().bytes()), map.descriptor(), null);
    }

    private PersistedRoot(byte[] rootHash, TupleDescriptor schema, Object unused) {
        this.rootHash = rootHash;
        this.schema = schema;
    }

    /** Was this captured from an empty tree? */
    public boolean isEmpty() {
        return rootHash == null;
    }

    /** The root chunk's content hash. For tests / sidecar serialization. */
    public byte[] rootHash() {
        return rootHash;
    }

    /**
     * Reload the StaticMap by reading the root chunk from {@code store}.
     *
     * @throws IllegalStateException if the chunk is not present in the store (which indicates the
     *     persistence story is broken — that's what these tests are meant to surface)
     */
    public StaticMap reload(NodeStore store) {
        if (isEmpty()) return new StaticMap(store, null, schema);
        Optional<MemorySegment> chunk = store.read(rootHash);
        if (chunk.isEmpty()) {
            throw new IllegalStateException(
                    "root chunk " + HashUtils.toHex(rootHash) + " missing from store after reopen");
        }
        Node root = Node.fromBytes(chunk.get());
        return new StaticMap(store, root, schema);
    }
}
