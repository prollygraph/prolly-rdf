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
import java.lang.foreign.ValueLayout;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads and writes {@link CommitObject}s as content-addressed chunks in a {@link NodeStore} — the
 * seam that makes a commit "just another chunk" (<a
 * href="../../../../../../../../docs/adr/0073-commit-objects-in-the-nodestore.md">ADR-0073</a>).
 *
 * <p>{@link #write} stores a commit's serialized bytes; because a commit object's hash <em>is</em>
 * its id, the returned address equals {@code commit.id()}. {@link #read} fetches a commit by that
 * id and parses it through the {@link CommitObject#deserialize} trust boundary.
 *
 * @apiNote A thin, stateless wrapper — cheap to construct per use. The {@code NodeStore} owns
 *     durability + garbage collection; this type only translates between {@code CommitObject} and
 *     its chunk bytes.
 * @implNote <b>Collaborators:</b> {@link NodeStore} (the chunk store), {@link CommitObject} (the
 *     wire format). <b>Dependents:</b> the {@code ProllySail} write path (persists a commit chunk)
 *     and — as later phases land — the read / sync / garbage-collection layer (addresses a commit
 *     by id).
 */
public final class CommitStore {

    private final NodeStore store;

    public CommitStore(NodeStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    /**
     * Stores {@code commit} as a content-addressed chunk and returns its address — which equals
     * {@code commit.id()} by construction (the id is the hash of the same serialized bytes).
     */
    public byte[] write(CommitObject commit) {
        return store.write(commit.serialize());
    }

    /**
     * Reads the commit stored under {@code id}, or empty if absent.
     *
     * @throws IllegalArgumentException if a chunk is present but is not a well-formed commit object
     *     (the {@link CommitObject#deserialize} trust boundary)
     */
    public Optional<CommitObject> read(byte[] id) {
        return store.read(id)
                .map(segment -> CommitObject.deserialize(segment.toArray(ValueLayout.JAVA_BYTE)));
    }

    /**
     * Reconstructs a full {@link CommitLog.Entry} for {@code id} from its chunk plus a {@code
     * timestamp} — the reconstruction primitive a thin {@code <datetime> <id>} commit log needs
     * (ADR-0073): the chunk supplies the content ({@code metaTreeHash}, parents, author, message),
     * the caller supplies the wall-clock time (which is deliberately excluded from the id, so it
     * cannot live in the chunk). Empty if no commit chunk is stored under {@code id}.
     */
    public Optional<CommitLog.Entry> readEntry(byte[] id, Instant timestamp) {
        return read(id).map(
                        commit ->
                                new CommitLog.Entry(
                                        timestamp,
                                        id,
                                        commit.metaTreeHash(),
                                        commit.parents(),
                                        commit.message(),
                                        commit.author()));
    }
}
