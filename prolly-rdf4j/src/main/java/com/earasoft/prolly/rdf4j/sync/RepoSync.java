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
import com.earasoft.prolly.rdf4j.sail.CommitGraph;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.MergeEngine;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RemotesStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.jspecify.annotations.Nullable;

/**
 * The client-side distributed-sync engine for a local {@link ProllySail}. See {@code
 * plans/distributed-sync.md} Phase 2.
 *
 * <ul>
 *   <li>{@link #fetch} — pure transfer: download a remote branch into the local store + commit log
 *       and record a remote-tracking ref; local branches untouched.
 *   <li>{@link #push} — the reverse: send the local branch's missing data + history to the remote
 *       and advance the remote ref, but only as a fast-forward.
 * </ul>
 */
public final class RepoSync {

    private final ProllySail local;

    /** Optional name→URL registry; required only by the {@code (remoteName, …)} overloads. */
    private final @Nullable RemotesStore remotes;

    /** Receive-side resource bounds (plan Step 23) — applied to every inbound pack. */
    private final SyncLimits limits;

    /**
     * Strategy for turning a remote URL into a {@link RemoteRepository} — defaults to {@link
     * HttpOnlyRemoteRepositoryFactory}. A scheme-dispatching variant ({@code prolly-rdf4j-rest}'s
     * {@code SchemeDispatchingRemoteRepositoryFactory}) is what lets a {@code grpc://} URL reach
     * the gRPC binding (plan sync-ui.md Step 15).
     */
    private final RemoteRepositoryFactory factory;

    public RepoSync(ProllySail local) {
        this(local, null, SyncLimits.defaults(), new HttpOnlyRemoteRepositoryFactory());
    }

    /**
     * Construct a {@link RepoSync} with a {@link RemotesStore} so the {@code (remoteName, branch)}
     * convenience overloads can resolve a URL.
     */
    public RepoSync(ProllySail local, RemotesStore remotes) {
        this(local, remotes, SyncLimits.defaults(), new HttpOnlyRemoteRepositoryFactory());
    }

    /**
     * Construct a {@link RepoSync} with custom receive-side {@link SyncLimits}. Use the
     * default-limits constructors for normal callers; this overload is for tests that need to
     * exercise the limit boundaries and for callers with bespoke deployment constraints.
     */
    public RepoSync(ProllySail local, RemotesStore remotes, SyncLimits limits) {
        this(local, remotes, limits, new HttpOnlyRemoteRepositoryFactory());
    }

    /**
     * Construct a {@link RepoSync} with a custom {@link RemoteRepositoryFactory} — used by {@code
     * prolly-rdf4j-rest}'s scheme-dispatching wiring so the {@code (remoteName, branch)}
     * convenience overloads can route a {@code grpc://} URL to the gRPC binding.
     */
    public RepoSync(
            ProllySail local,
            @Nullable RemotesStore remotes,
            SyncLimits limits,
            RemoteRepositoryFactory factory) {
        this.local = Objects.requireNonNull(local, "local");
        this.remotes = remotes;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Fetch {@code branch} from {@code remote} into the local repository: advertise the remote's
     * refs, pull the chunk + commit-history delta the local side is missing, verify the fetched
     * tree is complete, and point the remote-tracking ref {@code
     * refs/remotes/<remoteName>/<branch>} at the fetched head. Local branches are left untouched.
     *
     * @return the fetched head commit hash (the remote branch's head)
     * @throws IllegalArgumentException if {@code remote} has no such branch
     * @throws IllegalStateException if the fetched tree is incomplete
     */
    public byte[] fetch(RemoteRepository remote, String remoteName, String branch)
            throws IOException {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(remoteName, "remoteName");
        Objects.requireNonNull(branch, "branch");

        Map<String, byte[]> remoteRefs = remote.advertiseRefs();
        byte[] want = remoteRefs.get(branch);
        if (want == null) {
            throw new IllegalArgumentException(
                    "remote '" + remoteName + "' has no branch '" + branch + "'");
        }

        RefsStore refs = refs();
        // The local ref heads are what this side already has — the remote uses
        // them to prune the pack down to the genuine delta (Merkle skip).
        SyncPack pack = remote.fetchPack(want, refs.list().values());

        // Resource bounds (plan Step 23) — apply BEFORE any chunk lands in the
        // store. A malicious or runaway remote cannot drain memory by sending
        // a multi-gigabyte pack; the receiver caps at SyncLimits.maxBytes /
        // maxChunks and refuses the rest of the transfer.
        limits.validate(pack);

        NodeStore store = local.store();
        for (byte[] chunk : pack.chunks()) {
            store.write(chunk); // content-addressed — each lands at its own hash
        }
        // Integrity-validating merge (plan Step 22): every fresh entry's
        // metaTreeHash must resolve to a present RootMetaTree in `store`.
        // Without this, a malicious pack could land phantom commits pointing
        // at trees the pack never delivered.
        CommitLogSync.mergeInto(commitLog(), pack.commits(), store);

        // `want` is a commit id (ADR-0071); resolve it to the head's tree hash for the integrity
        // reads below. mergeInto has just landed want's commit-log entry locally, so findById
        // resolves it.
        byte[] wantTree = local.treeHashOf(want);

        // The fetched tree must be fully present locally before the tracking
        // ref may point at it — otherwise a torn or partial pack would leave a
        // ref aimed at an unreadable commit.
        if (RootMetaTree.readFrom(store, wantTree).isEmpty()) {
            throw new IllegalStateException(
                    "fetch incomplete: the head RootMetaTree was not delivered");
        }
        ChunkReachability.from(store, wantTree, ChunkSet.EMPTY); // throws on any missing chunk

        refs.put(trackingRef(remoteName, branch), want);
        return want;
    }

    /**
     * Push the local {@code branch} to {@code remote} as a fast-forward. Shorthand for {@link
     * #push(RemoteRepository, String, String, boolean) push(remote, remoteName, branch, false)} —
     * the safe default.
     */
    public byte[] push(RemoteRepository remote, String remoteName, String branch)
            throws IOException {
        return push(remote, remoteName, branch, false);
    }

    /**
     * Push the local {@code branch} to {@code remote}: send the data + history the remote is
     * missing and advance the remote's {@code branch} ref.
     *
     * <p>By default ({@code force=false}) only a <b>fast-forward</b> is accepted — the remote's
     * current {@code branch} must be an ancestor of the local head, otherwise the push is rejected
     * with an actionable error.
     *
     * <p>{@code force=true} performs the git equivalent of {@code --force-with-lease}: the
     * fast-forward check is skipped, but the ref update is still a {@code compareAndSet} against
     * the remote head this client <i>observed</i> in the advertisement. That preserves race
     * protection — if the remote ref moved between the advertisement and the CAS, the force push
     * still fails with "moved concurrently — retry", so the operator never silently clobbers work
     * they didn't see.
     *
     * <p>(There is no plain {@code --force} that would unconditionally overwrite a moved remote;
     * that primitive would need a separate "force-set" entry on {@link RemoteRepository} and is
     * intentionally absent — the lease-style force is enough for the recovery scenarios {@code
     * --force} normally covers, and is harder to misuse.)
     *
     * @return the local head now published as the remote branch's head, or the local head if the
     *     remote was already at it (no-op)
     * @throws IllegalArgumentException if the local has no such branch
     * @throws IllegalStateException on a non-fast-forward push without {@code force}, or a lost CAS
     *     race
     */
    public byte[] push(RemoteRepository remote, String remoteName, String branch, boolean force)
            throws IOException {
        return push(remote, remoteName, branch, force, java.util.Set.of());
    }

    /**
     * As {@link #push(RemoteRepository, String, String, boolean)} but with the sender-side
     * auth-graph filter (plans/auth-graph-syncpack-filter.md Phase 1): {@code
     * excludedContextTermIds} — resolved TermIds of the graphs to keep OUT of the emitted pack —
     * flows into {@code PackBuilder}'s graph-aware build. The push twin of the {@code /sync/fetch}
     * default-DENY filter: a sparql-auth-backend host pushing to a remote must not ship its user
     * table by default. Empty set = the legacy unfiltered pack ({@code auth.backend=rocksdb}
     * resolves no TermIds, so the filter is structurally a no-op there).
     */
    public byte[] push(
            RemoteRepository remote,
            String remoteName,
            String branch,
            boolean force,
            java.util.Set<Long> excludedContextTermIds)
            throws IOException {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(remoteName, "remoteName");
        Objects.requireNonNull(branch, "branch");

        byte[] localHead =
                refs().get(branch)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "local has no branch '" + branch + "' to push"));

        Map<String, byte[]> remoteRefs = remote.advertiseRefs();
        byte[] remoteHead = remoteRefs.get(branch); // null → a new branch on the remote

        if (remoteHead != null && Arrays.equals(remoteHead, localHead)) {
            return localHead; // the remote is already at this head — nothing to do
        }
        // Fast-forward check: the remote head must be an ancestor of the local
        // head, walked over the local commit DAG (which holds localHead's full
        // history). A remote head this side has never seen is, by definition,
        // not an ancestor — the remote diverged. force=true bypasses this
        // check but still keeps the CAS lease below.
        if (!force
                && remoteHead != null
                && !new CommitGraph(commitLog()).isAncestor(remoteHead, localHead)) {
            throw new IllegalStateException(
                    "non-fast-forward push rejected: the remote '"
                            + branch
                            + "' is not an ancestor of the local head — fetch + merge first, "
                            + "or pass force=true to override (lease-protected: still fails if the remote moved)");
        }

        // Send the delta the remote lacks, then advance its ref. Chunks first,
        // ref last: a lost CAS race leaves only harmless orphan chunks.
        SyncPack pack =
                PackBuilder.build(
                        local.store(),
                        commitLog(),
                        localHead,
                        remoteRefs.values(),
                        excludedContextTermIds == null
                                ? java.util.Set.of()
                                : excludedContextTermIds);
        remote.receivePack(pack);
        if (!remote.compareAndSetRef(branch, remoteHead, localHead)) {
            throw new IllegalStateException(
                    "push rejected: the remote branch '" + branch + "' moved concurrently — retry");
        }
        return localHead;
    }

    /**
     * Pull {@code branch} from {@code remote}: {@link #fetch} the remote's data and history, then
     * <b>integrate</b> it into the local current branch.
     *
     * <p>Integration is delegated to {@link MergeEngine#merge}, which — over the now-complete local
     * commit log — fast-forwards if the local branch is behind, no-ops if it is already up to date
     * or ahead, and otherwise runs a 3-way merge producing a two-parent merge commit.
     *
     * <p>{@code branch} is expected to be the local Sail's active branch (the usual
     * pull-into-your-checkout case); the merge integrates into whatever branch the Sail currently
     * has checked out.
     *
     * @return the local head commit hash after integration
     * @throws IllegalArgumentException if {@code remote} has no such branch
     * @throws IllegalStateException if the merge produced conflicts
     */
    public byte @Nullable [] pull(RemoteRepository remote, String remoteName, String branch)
            throws IOException {
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(remoteName, "remoteName");
        Objects.requireNonNull(branch, "branch");

        byte[] want = fetch(remote, remoteName, branch); // data + history + tracking ref

        MergeEngine.MergeResult result = MergeEngine.merge(local, new SailRepository(local), want);
        if (result.kind() == MergeEngine.MergeResult.Kind.CONFLICT) {
            throw new IllegalStateException(
                    "pull of branch '"
                            + branch
                            + "' produced "
                            + result.conflicts().size()
                            + " conflict(s) — resolve them, commit, and retry");
        }
        return result.newCommit();
    }

    // ---- name-based convenience overloads (resolved via RemotesStore) -----

    /**
     * As {@link #fetch(RemoteRepository, String, String)} but resolves the remote URL through the
     * configured {@link RemotesStore}.
     */
    public byte[] fetch(String remoteName, String branch) throws IOException {
        return fetch(resolve(remoteName), remoteName, branch);
    }

    /**
     * As {@link #push(RemoteRepository, String, String)} but resolves the remote URL through the
     * configured {@link RemotesStore}.
     */
    public byte[] push(String remoteName, String branch) throws IOException {
        return push(resolve(remoteName), remoteName, branch, false);
    }

    /**
     * As {@link #push(RemoteRepository, String, String, boolean)} but resolves the remote URL
     * through the configured {@link RemotesStore}.
     */
    public byte[] push(String remoteName, String branch, boolean force) throws IOException {
        return push(resolve(remoteName), remoteName, branch, force);
    }

    /**
     * As {@link #pull(RemoteRepository, String, String)} but resolves the remote URL through the
     * configured {@link RemotesStore}.
     */
    public byte @Nullable [] pull(String remoteName, String branch) throws IOException {
        return pull(resolve(remoteName), remoteName, branch);
    }

    /** Register a remote — {@code remoteAdd("origin", "http://host")}. */
    public void remoteAdd(String name, String url) throws IOException {
        requireRemotes().put(name, url);
    }

    /** Every configured remote, name → URL. */
    public java.util.Map<String, String> remoteList() throws IOException {
        return requireRemotes().list();
    }

    /** Remove a remote; returns {@code true} if it existed. */
    public boolean remoteRemove(String name) throws IOException {
        return requireRemotes().delete(name);
    }

    /**
     * Look up {@code remoteName} in the {@link RemotesStore} and turn its URL into a {@link
     * RemoteRepository} via the configured {@link RemoteRepositoryFactory}. The default factory
     * accepts only {@code http(s)://}; scheme-dispatching factories (the {@code prolly-rdf4j-rest}
     * default) also accept {@code grpc://}.
     */
    private RemoteRepository resolve(String remoteName) throws IOException {
        String url =
                requireRemotes()
                        .get(remoteName)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "no remote named '"
                                                        + remoteName
                                                        + "' — add it with remoteAdd(name, url)"));
        return factory.fromUrl(url);
    }

    private RemotesStore requireRemotes() {
        if (remotes == null) {
            throw new IllegalStateException(
                    "no RemotesStore configured on this RepoSync — name-based remote "
                            + "operations require constructing it with the (ProllySail, RemotesStore) ctor");
        }
        return remotes;
    }

    /** The remote-tracking ref name for a {@code remote}/{@code branch} pair. */
    public static String trackingRef(String remoteName, String branch) {
        return "remotes/" + remoteName + "/" + branch;
    }

    /**
     * Every remote-tracking ref this {@code RepoSync} has fetched into the local {@link RefsStore},
     * keyed by {@code remoteName/branch}. A convenience over {@code refs.list()} that filters out
     * local branches.
     */
    public java.util.Map<String, byte[]> trackingRefs() throws IOException {
        java.util.Map<String, byte[]> out = new java.util.TreeMap<>();
        String prefix = "remotes/";
        for (var e : refs().list().entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    /**
     * Tracking refs for one remote, keyed by branch. Returns an empty map if no branch from {@code
     * remoteName} has been fetched yet.
     */
    public java.util.Map<String, byte[]> trackingRefs(String remoteName) throws IOException {
        Objects.requireNonNull(remoteName, "remoteName");
        java.util.Map<String, byte[]> out = new java.util.TreeMap<>();
        String prefix = "remotes/" + remoteName + "/";
        for (var e : refs().list().entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                out.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        return out;
    }

    private RefsStore refs() {
        return local.refsStore()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "local ProllySail has no RefsStore configured"));
    }

    private CommitLog commitLog() {
        return local.commitLog()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "local ProllySail has no CommitLog configured"));
    }
}
