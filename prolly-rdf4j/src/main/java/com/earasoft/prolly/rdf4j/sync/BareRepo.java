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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RemotesStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * A <b>bare</b> ProllySail repository — chunk store + {@link RefsStore} + {@link CommitLog} +
 * {@link RemotesStore} + {@link RootMetaTreeStore} with <b>no serving Sail attached</b> (D-9). The
 * recommended deployment for a sync remote and the target of {@link #cloneInto(String, Path,
 * NodeStore, BufferPool, String) clone}.
 *
 * <p>Bareness is a deployment choice, not a data format — the sidecars here are exactly the files a
 * non-bare {@link ProllySail} writes, so a non-bare Sail can be opened on the same {@code dir}
 * later. {@link #cloneInto} persists the fetched head through {@link RootMetaTreeStore} so a later
 * {@code ProllySail.init()} restores at that commit.
 */
public record BareRepo(
        Path dir,
        NodeStore store,
        RootMetaTreeStore rootMetaTreeStore,
        RefsStore refs,
        CommitLog commitLog,
        RemotesStore remotes) {

    public BareRepo {
        Objects.requireNonNull(dir, "dir");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(rootMetaTreeStore, "rootMetaTreeStore");
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(commitLog, "commitLog");
        Objects.requireNonNull(remotes, "remotes");
    }

    /**
     * Open (or initialize) the bare repo at {@code dir}, creating the directory if needed. The
     * plan's {@code openBare} / {@code init --bare} — equivalent, because the sidecars are lazy and
     * write-on-first-use.
     */
    public static BareRepo open(Path dir, NodeStore store) throws IOException {
        Files.createDirectories(dir);
        return new BareRepo(
                dir,
                store,
                RootMetaTreeStore.beside(dir),
                RefsStore.beside(dir),
                CommitLog.beside(dir),
                RemotesStore.beside(dir));
    }

    /**
     * Clone {@code remoteUrl} into a fresh bare repo at {@code dir} over HTTP: fetch {@code
     * branch}, register {@code origin}, set the local branch literally at the fetched head, and
     * persist the head through {@link RootMetaTreeStore} so a later non-bare {@link ProllySail}
     * restores at it.
     */
    public static BareRepo cloneInto(
            String remoteUrl, Path dir, NodeStore store, BufferPool pool, String branch)
            throws IOException {
        return cloneInto(new HttpRemoteRepository(remoteUrl), remoteUrl, dir, store, pool, branch);
    }

    /**
     * As {@link #cloneInto(String, Path, NodeStore, BufferPool, String)} but takes a pre-built
     * {@link RemoteRepository} — used by tests and same-JVM cloning. The {@code originUrl} is what
     * gets recorded in the remotes registry; the {@code remote} does the actual transfer.
     */
    public static BareRepo cloneInto(
            RemoteRepository remote,
            String originUrl,
            Path dir,
            NodeStore store,
            BufferPool pool,
            String branch)
            throws IOException {
        BareRepo bare = open(dir, store);
        if (!bare.refs.list().isEmpty()) {
            throw new IllegalStateException("clone target is not empty (existing refs): " + dir);
        }
        bare.remotes.put("origin", originUrl);

        // Drive the fetch through a temporary ProllySail wrapping the bare's
        // sidecars — RepoSync needs a Sail. The bare's durable state is what
        // the caller will use afterward.
        ProllySail tempSail =
                new ProllySail(
                        store, pool, bare.rootMetaTreeStore, bare.commitLog, bare.refs, false);
        new SailRepository(tempSail).init();
        byte[] head = new RepoSync(tempSail, bare.remotes).fetch(remote, "origin", branch);

        // Clone semantics: the local branch points *literally* at the fetched
        // head (no re-commit through merge — see Phase 5 for why pull into an
        // empty repo produces a genesis instead). And the meta-head pointer is
        // updated so a later ProllySail open restores in-memory at this commit.
        bare.refs.put(branch, head);
        bare.rootMetaTreeStore.put(head);
        return bare;
    }
}
