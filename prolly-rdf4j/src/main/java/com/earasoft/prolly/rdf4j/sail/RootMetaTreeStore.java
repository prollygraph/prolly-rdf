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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent pointer to the most-recent {@link RootMetaTree} commit record.
 *
 * <p>Phase 3 bridge: stores the meta-tree's chunk hash in a small sidecar file next to the
 * NodeStore directory. Phase 4 will replace this with a proper {@code Manifest} entry routed
 * through {@code Database.commit}, gaining cross-process CAS semantics. The on-disk format here is
 * a single text line (hex of the chunk hash) so a human can read it.
 *
 * <h2>Why the sidecar approach</h2>
 *
 * <p>The Sail needs a way to discover its "current commit" at open time. We have three options:
 *
 * <ol>
 *   <li>Embed in the NodeStore as a well-known key (e.g., {@code "head"}) — requires NodeStore API
 *       extension or a reserved hash collision.
 *   <li>Use the {@code Manifest} interface from prolly-port-core — the right long-term answer, but
 *       requires wiring {@code Database} into the Sail.
 *   <li>A sidecar file (this class) — simple, visible, no API changes. Bridge solution until Phase
 *       4 lands.
 * </ol>
 *
 * <p>The bridge approach is safe for v2.0 single-writer; it's not safe for concurrent processes
 * because filesystem write+rename is not atomic relative to NodeStore writes. Phase 4 Manifest
 * integration fixes this.
 *
 * <h2>Atomicity</h2>
 *
 * <p>{@link #put} writes to a temp file then renames over the target. POSIX rename is atomic on the
 * same filesystem. Crashing mid-commit leaves either the old pointer or the new one — never a torn
 * write.
 */
public final class RootMetaTreeStore {

    private static final Logger LOG = LoggerFactory.getLogger(RootMetaTreeStore.class);

    /** File name suffix used relative to the {@link NodeStore} directory. */
    public static final String FILENAME = "root-head";

    private final Path file;

    public RootMetaTreeStore(Path file) {
        this.file = file;
    }

    /**
     * Static factory: build a {@link RootMetaTreeStore} pointing at the sidecar inside {@code
     * storeDir}. Caller is responsible for ensuring storeDir exists (typically true because the
     * NodeStore created it).
     */
    public static RootMetaTreeStore beside(Path storeDir) {
        return new RootMetaTreeStore(storeDir.resolve(FILENAME));
    }

    /**
     * File this pointer is persisted to — exposed for diagnostics and to derive the {@link
     * CommitLog} path.
     */
    public Path file() {
        return file;
    }

    /** Read the current meta-tree hash, if any. */
    public Optional<byte[]> get() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        String hex = Files.readString(file).trim();
        if (hex.isEmpty()) return Optional.empty();
        // A corrupt head file (bit rot, partial write on a non-atomic FS,
        // manual edit) makes HashUtils.fromHex throw an *unchecked*
        // NumberFormatException / IllegalArgumentException — which would
        // escape this throws-IOException method uncaught and fail Sail init
        // with a cryptic stack trace. Translate it to a clear IOException,
        // matching CommitLog.Entry.parse's handling of a malformed line.
        try {
            return Optional.of(com.dolthub.prolly.HashUtils.fromHex(hex));
        } catch (RuntimeException corrupt) {
            throw new IOException(
                    "corrupt RootMetaTree head pointer "
                            + file
                            + " — content is not a valid hex hash",
                    corrupt);
        }
    }

    /** Atomically replace the current meta-tree hash. */
    public void put(byte[] hash) throws IOException {
        if (hash == null) throw new IllegalArgumentException("hash must not be null");
        Path tmp = file.resolveSibling(FILENAME + ".tmp");
        Files.writeString(tmp, com.dolthub.prolly.HashUtils.toHex(hash) + "\n");
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (LOG.isDebugEnabled()) {
            String hex = com.dolthub.prolly.HashUtils.toHex(hash);
            LOG.debug(
                    "RootMetaTreeStore.put {} -> chunk {}",
                    file,
                    hex.length() > 12 ? hex.substring(0, 12) + "…" : hex);
        }
    }

    /** Load the RootMetaTree pointed at by this store, if any. */
    public Optional<RootMetaTree> load(NodeStore nodeStore) throws IOException {
        Optional<byte[]> hash = get();
        if (hash.isEmpty()) return Optional.empty();
        return RootMetaTree.readFrom(nodeStore, hash.get());
    }
}
