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

import java.util.Optional;
import org.eclipse.rdf4j.sail.Sail;
import org.jspecify.annotations.Nullable;

/**
 * The versioning capability of a {@link Sail} — the contract a PROLLY-backed repository satisfies
 * and a FLAT (plain key/value) repository does not.
 *
 * <p>Reading and writing triples is the plain RDF4J {@link Sail} contract, honoured identically by
 * both sail types. The verbs declared here — commits, branches, the commit log, the time-travel
 * sidecars — are the surface only a versioned sail ({@link ProllySail}) can offer. Splitting them
 * into their own interface lets a caller program to {@code Sail} for data and to {@code
 * VersionedSail} only where it versions, and makes "this repo has no versioning" a type-checkable
 * fact ({@code sail instanceof VersionedSail}) instead of a downcast that throws or returns null.
 *
 * @apiNote Obtain one through the capability accessor on the per-repo bundle ({@code
 *     PerRepoSail.versioning()} / {@code PerRequestSailResolver.Resolved.versioning()}), which
 *     returns {@link Optional#empty()} for a FLAT repo — never assume the cast succeeds. The {@code
 *     setNextCommit*} methods stage metadata onto the commit the next RDF4J transaction will
 *     record; they are consumed and cleared at {@code commit()}. (ADR-0045 replaces this staging
 *     with an immutable {@code CommitSpec}; until then the setters are the surface.)
 * @implNote The sole implementation is {@link ProllySail}; the methods here are its existing public
 *     versioning surface lifted verbatim into a contract. Engine-config, provenance, and event-sink
 *     methods are deliberately NOT part of it — no consumer reaches those through the capability
 *     accessor.
 */
public interface VersionedSail extends Sail {

    /** The branch commits land on. v2.0 always {@code "main"}. */
    String currentBranch();

    /**
     * RootMetaTree (tree) hash of the most recent commit, or {@code null} if none yet — the tree
     * <em>address</em> for opening the commit's data. Distinct from {@link #currentCommitId()}
     * since ADR-0071 (they coincided before, when a commit's id <em>was</em> its tree hash).
     */
    byte @Nullable [] currentCommitHash();

    /**
     * Identity of the most recent commit (ADR-0071), or {@code null} if none yet — {@code
     * hash(metaTreeHash ‖ parent-ids ‖ author ‖ message)}. This is the commit <em>handle</em>: what
     * refs store, what the commit graph + sync key on, and what a client passes back to name a
     * commit. Use this — not {@link #currentCommitHash()} — wherever a commit is being
     * <em>identified</em> rather than <em>opened</em>.
     */
    byte @Nullable [] currentCommitId();

    /**
     * Resolve a commit <em>id</em> (handle) to its RootMetaTree (tree) hash — the inverse direction
     * of the {@link #currentCommitId()} vs {@link #currentCommitHash()} split (ADR-0071). Use this
     * to bridge from a client-supplied commit id to the tree <em>address</em> {@link
     * #openSnapshotAt(byte[])} expects: {@code openSnapshotAt(treeHashOf(clientCommitId))}.
     *
     * @throws org.eclipse.rdf4j.sail.SailException if {@code commitId} is not present in the commit
     *     log
     */
    byte[] treeHashOf(byte[] commitId);

    /** Wall-clock instant of the most recent commit, or {@code null} if none yet. */
    java.time.@Nullable Instant currentCommitInstant();

    /** Whether the pending transaction would record a no-op (empty) commit. */
    boolean wouldBeNoOpCommit();

    /** The append-only commit log, when configured. */
    Optional<CommitLog> commitLog();

    /** The branch refs store, when configured. */
    Optional<RefsStore> refsStore();

    /**
     * The immutable tags store, when configured (null on a snapshot Sail). Drives the tag verbs
     * (ADR-0047).
     */
    Optional<TagStore> tagStore();

    /** The auto-restore pointer sidecar, when configured (drives snapshot reads). */
    Optional<RootMetaTreeStore> rootMetaTreeStore();

    /**
     * Open a read-only Sail viewing the committed tree <em>at</em> {@code commit} (time-travel).
     * The returned Sail must be {@code init()}-ed by the caller and {@code shutDown()} when done;
     * it shares this Sail's content-addressed store, so opening one is cheap.
     *
     * <p>This keeps the time-travel mechanism a capability of the versioned Sail itself, so callers
     * (the gRPC {@code Snapshot}/{@code Diff} verbs) never assemble it from concrete internals like
     * {@code store()} / {@code pool()} — they depend only on this interface (ADR-0048 D-4).
     *
     * @throws org.eclipse.rdf4j.sail.SailException if {@code commit} is not present in the store
     */
    VersionedSail openSnapshotAt(byte[] commit);

    /** Stage the next commit's message (consumed + cleared at {@code commit()}). */
    void setNextCommitMessage(String message);

    /** Stage the next commit's author (consumed + cleared at {@code commit()}). */
    void setNextCommitAuthor(String author);

    /** Tag the next commit as a merge with this second parent. */
    void setNextCommitMergeParent(byte[] sourceCommitHash);

    /**
     * Record a commit on a non-active branch (e.g. a staging branch) without disturbing the live
     * sail's state. The caller must have already written {@code mtHash} and its chunks to the
     * NodeStore. {@code parentHash} is a parent <b>commit id</b> (ADR-0071); the branch ref is
     * pointed at the new commit's id.
     *
     * @return the new commit's id (ADR-0071) — what the branch ref now points at
     * @throws IllegalStateException if the commit log or refs store is absent.
     */
    byte[] recordBranchCommit(String branchName, byte[] mtHash, byte[] parentHash, String message)
            throws java.io.IOException;

    /**
     * Atomically point {@code refs/<branchName>} at {@code commitHash} without appending a
     * commit-log entry.
     *
     * @throws IllegalStateException if the refs store is absent.
     */
    void resetBranchRef(String branchName, byte[] commitHash) throws java.io.IOException;
}
