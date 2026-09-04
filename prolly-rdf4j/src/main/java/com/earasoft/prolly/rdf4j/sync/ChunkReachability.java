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
package com.earasoft.prolly.rdf4j.sync;

import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.gc.PackedChunkSet;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.sync.DataTreeReachability;
import java.util.Optional;

/**
 * The RootMetaTree-aware Merkle reachability walk shared across the sync layer — given a commit
 * hash, the set of every chunk that commit transitively needs: the RootMetaTree chunk itself plus
 * every node of every table tree it names.
 *
 * <p>The remote uses it to build a fetch pack (walk {@code want}, prune what the client already
 * has); the client uses it to verify a fetched tree is <em>complete</em> — the walk reads every
 * referenced chunk, so a missing one surfaces as an {@link IllegalStateException}. See {@code
 * plans/distributed-sync.md}.
 */
public final class ChunkReachability {

    private ChunkReachability() {}

    /**
     * Hex hashes of every chunk reachable from commit {@code commitHash}, minus anything in {@code
     * excluded} (a Merkle skip — an excluded hash prunes its whole subtree). Returns empty if the
     * commit's RootMetaTree is not in {@code store}.
     *
     * @throws IllegalStateException if a chunk the tree <em>references</em> is absent — i.e. the
     *     store is torn or a fetch was incomplete
     */
    public static ChunkSet from(NodeStore store, byte[] commitHash, ChunkSet excluded) {
        ChunkSet out = new PackedChunkSet();
        if (excluded.contains(commitHash)) {
            return out;
        }
        // readFrom returns empty for an absent root AND for a commit-id root (ADR-0073: commits are
        // chunks too — a commit contributes no tree chunks at its own hash; its tree is reached via
        // the commit's metaTreeHash elsewhere in the walk).
        Optional<RootMetaTree> rmt = RootMetaTree.readFrom(store, commitHash);
        if (rmt.isEmpty()) {
            return out; // a commit this store does not hold — contributes nothing
        }
        out.add(commitHash);
        for (byte[] tableRoot : rmt.get().entries().values()) {
            DataTreeReachability.collectInto(store, tableRoot, out, excluded);
        }
        return out;
    }
}
