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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.DiffEngine;
import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleDescriptor;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocIndex;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Three-way merge driver for ProllySail commit chains.
 *
 * <h2>What it does</h2>
 *
 * <p>Given a {@code target} ProllySail (the live Sail, typically on {@code refs/main}) and a {@code
 * source} commit hash to merge in, the engine:
 *
 * <ol>
 *   <li>Walks the parent chain in {@link CommitLog} to find the lowest common ancestor (LCA) of
 *       {@code target.currentCommitHash} and {@code source}.
 *   <li>Opens a read-only snapshot of {@code source} via {@link ProllySail#openSnapshotAt}.
 *   <li>Identifies the triples present on source but not on target (the "incoming" set), and the
 *       triples present on the LCA but missing from source ("removed-by-source" — irrelevant to a
 *       pure additive merge but tracked here for future delete support).
 *   <li>Applies the incoming set to {@code target} inside a single RDF4J transaction. Just before
 *       commit the Sail is tagged with {@link ProllySail#setNextCommitMergeParent(byte[])} so the
 *       new commit-log entry records two parents.
 * </ol>
 *
 * <h2>Conflict policy: set-union + fail-fast</h2>
 *
 * <p>RDF triples form a mathematical set: adding the same triple from both sides is a no-op, so
 * most "conflicts" disappear at the data model. The remaining surface is application-level
 * functional predicates (e.g., {@code foaf:age} should have a single value per subject). This
 * implementation does not yet enforce such constraints; the {@code conflicts} field of {@link
 * MergeResult} is reserved for iter 44+ to populate.
 *
 * <h2>Performance</h2>
 *
 * <p>Iterating triples through RDF4J is O(n) over the source-side diff. For large stores a future
 * iteration could walk the prolly trees directly: content-addressed subtrees that match on both
 * sides are skipped wholesale, which is the whole point of prolly trees. For v2.0's expected
 * dataset sizes (millions of triples) the RDF4J path is fast enough and keeps the index consistency
 * invariants in one place.
 */
public final class MergeEngine {

    private static final Logger LOG = LoggerFactory.getLogger(MergeEngine.class);

    private MergeEngine() {
        // static entry points only
    }

    /**
     * Find the lowest common ancestor of two commits in {@code log}. Returns empty if either side
     * is unknown, or if there is no shared ancestor (disjoint chains — shouldn't happen in normal
     * operation).
     *
     * <p>Delegates to {@link CommitGraph#mergeBase} — the single shared ancestry implementation.
     * Kept as a static entry point for callers (and tests) that only need a one-shot merge base.
     */
    public static Optional<byte[]> findLCA(CommitLog log, byte @Nullable [] a, byte @Nullable [] b)
            throws IOException {
        if (log == null) throw new IllegalArgumentException("log must not be null");
        return new CommitGraph(log).mergeBase(a, b);
    }

    /**
     * Merge {@code source} commit into {@code target} (the live Sail). Mutates {@code target} by
     * adding incoming triples and committing with two parents in the commit log.
     *
     * <p>{@code target} must be wrapped in {@code targetRepo} (a normal {@link SailRepository});
     * the engine drives writes through that repository so all four indexes stay consistent.
     *
     * @return {@link MergeResult} indicating success (with the new commit's hash) or conflicts
     *     (none in the current set-union policy)
     */
    public static MergeResult merge(
            ProllySail target, SailRepository targetRepo, byte[] sourceCommit) throws IOException {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (sourceCommit == null)
            throw new IllegalArgumentException("sourceCommit must not be null");
        byte[] targetHead = target.currentCommitId();
        if (targetHead != null && java.util.Arrays.equals(targetHead, sourceCommit)) {
            LOG.info("merge: target and source already equal — no-op");
            return MergeResult.upToDate(targetHead);
        }

        CommitLog log =
                target.commitLog()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "merge requires a CommitLog on the target Sail"));

        Optional<byte[]> lcaOpt = findLCA(log, targetHead, sourceCommit);
        byte[] lca = lcaOpt.orElse(null);
        LOG.info(
                "merge: target={}, source={}, lca={}",
                targetHead == null ? "<empty>" : shortHex(targetHead),
                shortHex(sourceCommit),
                lca == null ? "<none>" : shortHex(lca));

        // If source is an ancestor of target (i.e., LCA == source), source has no new triples
        // to contribute — short-circuit as up-to-date without creating a merge commit.
        if (lca != null && java.util.Arrays.equals(lca, sourceCommit)) {
            LOG.info("merge: source is an ancestor of target — up-to-date");
            return MergeResult.upToDate(targetHead);
        }

        // Fast-forward: target ⊆ source's ancestry → just adopt source's tree.
        // (We still bring the data in via the normal commit path so index roots advance correctly.)
        if (targetHead != null && lca != null && java.util.Arrays.equals(targetHead, lca)) {
            LOG.info("merge: fast-forward — target is an ancestor of source");
        }

        // Snapshot the source so we can iterate its triples.
        ProllySail sourceSnap =
                ProllySail.openSnapshotAt(
                        target.store(),
                        target.pool(),
                        new CompositeMeterRegistry(),
                        target.treeHashOf(sourceCommit));
        SailRepository sourceRepo = new SailRepository(sourceSnap);
        sourceRepo.init();

        ProllySail lcaSnap = null;
        SailRepository lcaRepo = null;
        if (lca != null) {
            lcaSnap =
                    ProllySail.openSnapshotAt(
                            target.store(),
                            target.pool(),
                            new CompositeMeterRegistry(),
                            target.treeHashOf(lca));
            lcaRepo = new SailRepository(lcaSnap);
            lcaRepo.init();
        }

        try {
            // Collect triples from source and lca; "incoming" = source \ target's view of
            // lca-and-prior.
            // Set semantics: we just take every triple from source and add it to target. Target may
            // already have it (no-op on add). RDF4J's add is idempotent on the (s,p,o,c) key.
            List<Statement> incoming = new ArrayList<>();
            try (RepositoryConnection sourceConn = sourceRepo.getConnection();
                    var it = sourceConn.getStatements(null, null, null, false)) {
                while (it.hasNext()) {
                    incoming.add(it.next());
                }
            }

            // For future delete-aware merges: capture LCA triples missing from source = source-side
            // deletes.
            // We don't apply them in the current set-union policy — surface count for
            // observability.
            int sourceDeletes = 0;
            if (lcaRepo != null) {
                Set<String> sourceTripleKeys = keysOf(incoming);
                try (RepositoryConnection lcaConn = lcaRepo.getConnection();
                        var it = lcaConn.getStatements(null, null, null, false)) {
                    while (it.hasNext()) {
                        Statement st = it.next();
                        if (!sourceTripleKeys.contains(tripleKey(st))) sourceDeletes++;
                    }
                }
            }

            // Apply incoming triples to target, tagging the next commit as a merge.
            target.setNextCommitMergeParent(sourceCommit);

            // Iter F.6 — when both Sails record provenance, fold source's
            // provenance entries into target's with older-wins. Without this
            // the merge result is non-deterministic for triples added on both
            // branches: whichever side wrote its provIdxTx entry first wins.
            // The fold runs against target's in-flight provIdxTx, set up via
            // a one-shot the connection reads at commit time.
            if (target.provenanceEnabled()
                    && sourceSnap.provenanceEnabled()
                    && sourceSnap.provenanceRoot() != null) {
                Optional<CommitLog> logOpt = target.commitLog();
                if (logOpt.isPresent()) {
                    CommitLog foldLog = logOpt.get();
                    target.setNextCommitProvenanceFold(
                            sourceSnap.provenanceRoot(),
                            (otherParent, thisParent) ->
                                    isParentOlder(foldLog, otherParent, thisParent));
                }
            }

            try (RepositoryConnection conn = targetRepo.getConnection()) {
                conn.begin();
                for (Statement st : incoming) {
                    conn.add(st);
                }
                conn.commit();
            }
            byte[] newHead = Objects.requireNonNull(target.currentCommitId());
            LOG.info(
                    "merge: completed — newCommit={} incoming={} sourceDeletes={} (ignored under set-union)",
                    shortHex(newHead),
                    incoming.size(),
                    sourceDeletes);
            return MergeResult.ok(newHead, incoming.size(), sourceDeletes);
        } finally {
            sourceRepo.shutDown();
            if (lcaRepo != null) lcaRepo.shutDown();
        }
    }

    /**
     * ADR-0002 W.3 — squash-merge {@code sourceBranch} into the live {@code target} sail. Different
     * from {@link #merge}:
     *
     * <ul>
     *   <li>Applies both the net adds AND the net deletes from source — set-difference semantics,
     *       not set-union. A triple deleted on source disappears from target.
     *   <li>Produces a one-parent commit (target's previous head), not two. The source branch's
     *       intermediate history is discarded from the parent chain — the source ref is reset to
     *       point at the new target HEAD instead.
     *   <li>Caller passes the source <em>branch name</em>, not a commit hash, because the squash
     *       needs to reset the ref afterward.
     * </ul>
     *
     * <p>Conflict handling is the same loose set-difference as the ADR's §8: triples added on both
     * branches → no-op (set semantics); triples deleted on source that target has since changed →
     * removed; triples added on source that target has since deleted → re-added. No conflict
     * surface in v1.
     */
    public static SquashResult squashMerge(
            ProllySail target, SailRepository targetRepo, String sourceBranch, String message)
            throws IOException {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (sourceBranch == null || sourceBranch.isBlank()) {
            throw new IllegalArgumentException("sourceBranch must not be blank");
        }
        RefsStore refs =
                target.refsStore()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "squashMerge requires a RefsStore on the target Sail"));
        CommitLog log =
                target.commitLog()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "squashMerge requires a CommitLog on the target Sail"));

        byte[] sourceHead = refs.get(sourceBranch).orElse(null);
        if (sourceHead == null) {
            return SquashResult.empty();
        }
        byte[] targetHead = target.currentCommitId();
        if (targetHead != null && java.util.Arrays.equals(sourceHead, targetHead)) {
            // Source already at target — nothing to squash. Still reset to be tidy.
            // reset the source ref onto target's head; a no-op squash still has a target head.
            refs.put(sourceBranch, Objects.requireNonNull(targetHead));
            return SquashResult.empty();
        }

        Optional<byte[]> lcaOpt = findLCA(log, targetHead, sourceHead);
        byte[] lca = lcaOpt.orElse(null);
        LOG.info(
                "squash: source={} ({}) target={} lca={}",
                sourceBranch,
                shortHex(sourceHead),
                targetHead == null ? "<empty>" : shortHex(targetHead),
                lca == null ? "<none>" : shortHex(lca));

        // Open source + LCA snapshots and compute the net diff.
        ProllySail sourceSnap =
                ProllySail.openSnapshotAt(
                        target.store(),
                        target.pool(),
                        new CompositeMeterRegistry(),
                        target.treeHashOf(sourceHead));
        SailRepository sourceRepo = new SailRepository(sourceSnap);
        sourceRepo.init();

        ProllySail lcaSnap = null;
        SailRepository lcaRepo = null;
        if (lca != null) {
            lcaSnap =
                    ProllySail.openSnapshotAt(
                            target.store(),
                            target.pool(),
                            new CompositeMeterRegistry(),
                            target.treeHashOf(lca));
            lcaRepo = new SailRepository(lcaSnap);
            lcaRepo.init();
        }

        // Materialize values into SimpleValueFactory NOW, while the snapshot
        // connections are still open. The snapshot's ProllyStatement values
        // are tied to its arena + dict cache; if we delay resolve until after
        // the try-with-resources closes, we get "TermId not in dictionary"
        // (#138 root cause).
        org.eclipse.rdf4j.model.ValueFactory vf =
                org.eclipse.rdf4j.model.impl.SimpleValueFactory.getInstance();
        try {
            List<Statement> sourceTriples = new ArrayList<>();
            try (RepositoryConnection conn = sourceRepo.getConnection();
                    var it = conn.getStatements(null, null, null, false)) {
                while (it.hasNext()) sourceTriples.add(materializeStatement(it.next(), vf));
            }
            List<Statement> lcaTriples = new ArrayList<>();
            if (lcaRepo != null) {
                try (RepositoryConnection conn = lcaRepo.getConnection();
                        var it = conn.getStatements(null, null, null, false)) {
                    while (it.hasNext()) lcaTriples.add(materializeStatement(it.next(), vf));
                }
            }
            Set<String> sourceKeys = keysOf(sourceTriples);
            Set<String> lcaKeys = keysOf(lcaTriples);
            List<Statement> added = new ArrayList<>();
            for (Statement st : sourceTriples) {
                if (!lcaKeys.contains(tripleKey(st))) added.add(st);
            }
            List<Statement> removed = new ArrayList<>();
            for (Statement st : lcaTriples) {
                if (!sourceKeys.contains(tripleKey(st))) removed.add(st);
            }
            if (added.isEmpty() && removed.isEmpty()) {
                LOG.info("squash: empty net diff — nothing to commit");
                // reset the source ref onto target's head; a no-op squash still has a target head.
                refs.put(sourceBranch, Objects.requireNonNull(targetHead));
                return SquashResult.empty();
            }

            // Apply to target via the live sail's connection — produces a single
            // one-parent commit on the target branch. We deliberately do NOT
            // call setNextCommitMergeParent — squash is a fresh linear commit.
            // Statements were already materialized through SimpleValueFactory
            // at read time (#138), so the target sail re-encodes from plain
            // IRI strings and the lookup succeeds.
            target.setNextCommitMessage(message == null ? "" : message);
            try (RepositoryConnection conn = targetRepo.getConnection()) {
                conn.begin();
                for (Statement st : added) {
                    conn.add(st);
                }
                for (Statement st : removed) {
                    conn.remove(st);
                }
                conn.commit();
            }
            byte[] newHead = target.currentCommitId();
            if (newHead == null) {
                LOG.warn("squash: target sail produced null currentCommitHash after apply");
                return SquashResult.empty();
            }

            // Reset the source ref to point at the squash commit on target.
            refs.put(sourceBranch, newHead);
            LOG.info(
                    "squash: completed — newCommit={} added={} removed={}",
                    shortHex(newHead),
                    added.size(),
                    removed.size());
            return new SquashResult(newHead, added.size(), removed.size());
        } finally {
            sourceRepo.shutDown();
            if (lcaRepo != null) lcaRepo.shutDown();
        }
    }

    /** Outcome of {@link #squashMerge}. {@code newCommit} is null iff nothing was committed. */
    public record SquashResult(byte @Nullable [] newCommit, int added, int removed) {
        /**
         * Explicit canonical constructor so the {@code @Nullable newCommit} holds at every call.
         */
        public SquashResult(byte @Nullable [] newCommit, int added, int removed) {
            this.newCommit = newCommit;
            this.added = added;
            this.removed = removed;
        }

        public static SquashResult empty() {
            return new SquashResult(null, 0, 0);
        }

        public boolean isEmpty() {
            return newCommit == null;
        }
    }

    /**
     * Iter F.6 — decide which of two parent-commit hashes is older by walking the commit log for
     * each one's timestamp. Returns true iff {@code other} is older than {@code mine}. Genesis
     * sentinel (empty byte[]) is treated as the oldest possible — there's nothing older than
     * "before the first commit." Failures (log unreadable, hash not found) fall back to {@code
     * false} so we don't override on uncertainty.
     */
    private static boolean isParentOlder(CommitLog log, byte[] other, byte[] mine) {
        if (other.length == 0 && mine.length == 0) return false; // tie
        if (other.length == 0) return true; // genesis < anything
        if (mine.length == 0) return false; // anything > genesis
        try {
            Optional<CommitLog.Entry> oe = log.findById(other);
            Optional<CommitLog.Entry> me = log.findById(mine);
            if (oe.isEmpty() || me.isEmpty()) return false;
            return oe.get().timestamp().isBefore(me.get().timestamp());
        } catch (IOException ex) {
            LOG.debug("isParentOlder: commit log read failed", ex);
            return false;
        }
    }

    /**
     * #138 fix — re-root a Statement's values through {@code SimpleValueFactory} so they're backed
     * by plain strings, not the source/LCA snapshot's prefix-table-aware encodings. Used by
     * squashMerge before applying the net diff to the target sail; without this, the target's
     * encode path fails to look up TermIds for ProllyIRI/Literal/BNode bytes that reference the
     * wrong prefix-table state.
     */
    private static Statement materializeStatement(
            Statement st, org.eclipse.rdf4j.model.ValueFactory vf) {
        return vf.createStatement(
                (org.eclipse.rdf4j.model.Resource) materializeValue(st.getSubject(), vf),
                (org.eclipse.rdf4j.model.IRI) materializeValue(st.getPredicate(), vf),
                materializeValue(st.getObject(), vf));
    }

    private static org.eclipse.rdf4j.model.Value materializeValue(
            org.eclipse.rdf4j.model.Value v, org.eclipse.rdf4j.model.ValueFactory vf) {
        if (v instanceof org.eclipse.rdf4j.model.IRI iri) {
            return vf.createIRI(iri.stringValue());
        }
        if (v instanceof org.eclipse.rdf4j.model.BNode bnode) {
            return vf.createBNode(bnode.getID());
        }
        if (v instanceof org.eclipse.rdf4j.model.Literal lit) {
            if (lit.getLanguage().isPresent()) {
                return vf.createLiteral(lit.getLabel(), lit.getLanguage().get());
            }
            if (lit.getDatatype() != null) {
                return vf.createLiteral(lit.getLabel(), lit.getDatatype());
            }
            return vf.createLiteral(lit.getLabel());
        }
        return v;
    }

    /** Reduce a Statement to a stable string key. Used for set-difference accounting. */
    private static String tripleKey(Statement st) {
        return st.getSubject()
                + "|"
                + st.getPredicate()
                + "|"
                + st.getObject()
                + "|"
                + st.getContext();
    }

    private static Set<String> keysOf(List<Statement> stmts) {
        Set<String> out = new HashSet<>(stmts.size() * 2);
        for (Statement s : stmts) out.add(tripleKey(s));
        return out;
    }

    private static String shortHex(byte[] hash) {
        String hex = HashUtils.toHex(hash);
        return hex.length() > 12 ? hex.substring(0, 12) + "…" : hex;
    }

    // ================================================================
    // Structural merge (Phase 8) — drives com.dolthub.prolly.MergeEngine
    // per persisted tree instead of scanning + re-inserting triples.
    // Cost is O(leaf-nodes) on near-identical branches, not O(triples).
    // ================================================================

    /** True only when the JVM was started with {@code -ea}. */
    private static final boolean ASSERTIONS_ENABLED;

    static {
        boolean ea = false;
        assert ea = true;
        ASSERTIONS_ENABLED = ea;
    }

    /** The six persisted trees a structural merge has to reconcile. */
    private static final java.util.List<QuadOrder> INDEX_ORDERS =
            java.util.List.of(QuadOrder.SPOC, QuadOrder.POSC, QuadOrder.OSPC, QuadOrder.CSPO);

    /**
     * Structural three-way merge of {@code sourceCommit} into the live {@code target} Sail. Unlike
     * {@link #merge}, this never scans or re-inserts triples: it drives {@link
     * com.dolthub.prolly.MergeEngine} once per persisted tree (dict, the four quad indexes,
     * namespaces), each of which short-circuits content-identical subtrees.
     *
     * <p>On a clean merge the merged roots are installed and committed via {@code
     * persistMetaTreeIfConfigured} — a two-parent commit-log entry, same machinery {@code
     * ProllySailConnection.commitInternal} uses. On conflicts nothing is installed and {@link
     * MergeResult.Kind#CONFLICT} is returned.
     *
     * <p><b>Provenance:</b> a provenance-enabled Sail folds provenance through the RDF4J connection
     * commit path, which this method bypasses; it throws {@link UnsupportedOperationException} so
     * callers fall back to {@link #merge} (plan 08 §8.7). <b>Event sink:</b> structural merge also
     * bypasses per-triple mutation-event emission — acceptable because a merge is a structural
     * operation, but noted for callers that rely on the sink.
     */
    public static MergeResult mergeStructural(ProllySail target, byte[] sourceCommit)
            throws IOException {
        if (sourceCommit == null)
            throw new IllegalArgumentException("sourceCommit must not be null");
        return doStructuralMerge(target, sourceCommit, true, "Merge " + shortHex(sourceCommit));
    }

    /**
     * Structural variant of {@link #squashMerge} — applies {@code sourceBranch}'s net changes to
     * {@code target} as a single one-parent commit, then resets the source ref to the new head.
     * Reuses the same per-tree merge as {@link #mergeStructural}; the only difference is the commit
     * shape (one parent, no merge-parent tag) and the ref reset.
     */
    public static SquashResult squashMergeStructural(
            ProllySail target, String sourceBranch, String message) throws IOException {
        if (target == null) throw new IllegalArgumentException("target must not be null");
        if (sourceBranch == null || sourceBranch.isBlank()) {
            throw new IllegalArgumentException("sourceBranch must not be blank");
        }
        RefsStore refs =
                target.refsStore()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "squashMergeStructural requires a RefsStore"));
        byte[] sourceHead = refs.get(sourceBranch).orElse(null);
        if (sourceHead == null) return SquashResult.empty();
        byte[] targetHead = target.currentCommitId();
        if (targetHead != null && java.util.Arrays.equals(sourceHead, targetHead)) {
            // reset the source ref onto target's head; a no-op squash still has a target head.
            refs.put(sourceBranch, Objects.requireNonNull(targetHead));
            return SquashResult.empty();
        }
        MergeResult r =
                doStructuralMerge(target, sourceHead, false, message == null ? "" : message);
        if (r.kind() == MergeResult.Kind.CONFLICT) {
            throw new SailConflictException(
                    "squash merge of branch '"
                            + sourceBranch
                            + "' has "
                            + r.conflicts().size()
                            + " conflict(s)");
        }
        if (r.kind() == MergeResult.Kind.UP_TO_DATE || r.newCommit() == null) {
            // reset the source ref onto target's head; a no-op squash still has a target head.
            refs.put(sourceBranch, Objects.requireNonNull(targetHead));
            return SquashResult.empty();
        }
        refs.put(sourceBranch, r.newCommit());
        return new SquashResult(r.newCommit(), r.incomingCount(), r.sourceSideDeletes());
    }

    /**
     * Shared engine for {@link #mergeStructural} and {@link #squashMergeStructural}. {@code
     * recordMergeParent} true → two-parent merge commit; false → a one-parent (squash) commit.
     */
    private static MergeResult doStructuralMerge(
            ProllySail target, byte[] sourceCommit, boolean recordMergeParent, String message)
            throws IOException {
        if (target == null) throw new IllegalArgumentException("target must not be null");

        // §8.7 — provenance folding rides the connection commit path. This is
        // an immutable construction flag, so it is safe to check before the
        // lock (and lets us avoid acquiring it just to throw).
        if (target.provenanceEnabled()) {
            throw new UnsupportedOperationException(
                    "mergeStructural does not support provenance-enabled Sails; "
                            + "call merge() instead (plan 08 §8.7)");
        }

        CommitLog log =
                target.commitLog()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "structural merge requires a CommitLog on the target Sail"));

        MeterRegistry m = target.meterRegistry();
        long t0 = System.nanoTime();

        // Hold the single-writer lock for the whole merge so no connection
        // forks roots mid-merge — same invariant startTransactionInternal
        // relies on. CRITICAL: read currentCommitHash and compute the LCA
        // *inside* the lock. Reading them before acquiring it races a
        // concurrent commit — the head (and LCA) would be stale while the
        // roots merged below would be fresh, producing an inconsistent merge.
        target.acquireWriteLock();
        try {
            byte[] targetHead = target.currentCommitId();
            if (targetHead != null && java.util.Arrays.equals(targetHead, sourceCommit)) {
                return MergeResult.upToDate(targetHead);
            }
            Optional<byte[]> lcaOpt = findLCA(log, targetHead, sourceCommit);
            byte[] lca = lcaOpt.orElse(null);
            LOG.info(
                    "mergeStructural: target={} source={} lca={}",
                    targetHead == null ? "<empty>" : shortHex(targetHead),
                    shortHex(sourceCommit),
                    lca == null ? "<none>" : shortHex(lca));

            // Source already an ancestor of target → nothing to merge.
            if (lca != null && java.util.Arrays.equals(lca, sourceCommit)) {
                LOG.info("mergeStructural: source is an ancestor of target — up-to-date");
                return MergeResult.upToDate(targetHead);
            }

            NodeStore store = target.store();
            BufferPool pool = target.pool();

            ProllySail sourceSnap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new CompositeMeterRegistry(),
                            target.treeHashOf(sourceCommit));
            ProllySail lcaSnap =
                    lca == null
                            ? null
                            : ProllySail.openSnapshotAt(
                                    store,
                                    pool,
                                    new CompositeMeterRegistry(),
                                    target.treeHashOf(lca));

            StaticMap targetSpocBefore = target.indexRoot(QuadOrder.SPOC);
            List<Conflict> conflicts = new ArrayList<>();

            // dict
            TreeMergeResult dict =
                    timedMergeTree(
                            m,
                            "dict",
                            store,
                            pool,
                            lcaSnap == null ? null : lcaSnap.dictRoot(),
                            target.dictRoot(),
                            sourceSnap.dictRoot());
            conflicts.addAll(decodeConflicts("dict", dict.conflicts()));

            // four quad indexes
            java.util.EnumMap<QuadOrder, TreeMergeResult> indexes =
                    new java.util.EnumMap<>(QuadOrder.class);
            for (QuadOrder order : INDEX_ORDERS) {
                TreeMergeResult tr =
                        timedMergeTree(
                                m,
                                order.name().toLowerCase(),
                                store,
                                pool,
                                lcaSnap == null ? null : lcaSnap.indexRoot(order),
                                target.indexRoot(order),
                                sourceSnap.indexRoot(order));
                indexes.put(order, tr);
                conflicts.addAll(decodeConflicts(order.name().toLowerCase(), tr.conflicts()));
            }

            // namespaces
            TreeMergeResult namespaces =
                    timedMergeTree(
                            m,
                            "namespaces",
                            store,
                            pool,
                            lcaSnap == null ? null : lcaSnap.namespacesRoot(),
                            target.namespacesRoot(),
                            sourceSnap.namespacesRoot());
            conflicts.addAll(decodeConflicts("namespaces", namespaces.conflicts()));

            if (!conflicts.isEmpty()) {
                LOG.info("mergeStructural: {} conflict(s) — installing nothing", conflicts.size());
                m.counter("sail.merge.conflict").increment();
                return MergeResult.conflict(conflicts);
            }

            // §8.4 — debug-mode dictionary-consistency invariant.
            assertDictConsistency(
                    store,
                    pool,
                    dict.merged(),
                    Objects.requireNonNull(indexes.get(QuadOrder.SPOC)).merged());

            // §8.3 — install merged roots and commit through the meta-tree path.
            target.advanceDictRoot(dict.merged());
            for (QuadOrder order : INDEX_ORDERS) {
                target.advanceIndexRoot(order, Objects.requireNonNull(indexes.get(order)).merged());
            }
            target.advanceNamespacesRoot(namespaces.merged());
            // §8.6(a) — statsRoot deliberately left at target's value (planner
            // hints, correctness-neutral). Follow-up ticket: recompute post-merge.
            // Publish the merged core roots as one atomic snapshot (under the target's write lock,
            // acquired above) so a concurrent fork can't read a torn mix — the publication-race
            // fix.
            target.publishSnapshot();
            if (recordMergeParent) {
                target.setNextCommitMergeParent(sourceCommit);
            }
            target.setNextCommitMessage(message);
            target.persistMetaTreeIfConfigured();

            byte[] newHead = Objects.requireNonNull(target.currentCommitId());
            m.timer("sail.merge.structural").record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            m.counter("sail.merge.structural.count").increment();

            if (newHead == null || java.util.Arrays.equals(newHead, targetHead)) {
                // persistMetaTree's empty-diff guard fired — no data change.
                LOG.info("mergeStructural: no data change — up-to-date");
                return MergeResult.upToDate(targetHead);
            }
            int[] delta =
                    spocDelta(
                            store,
                            targetSpocBefore,
                            Objects.requireNonNull(indexes.get(QuadOrder.SPOC)).merged());
            LOG.info(
                    "mergeStructural: completed newCommit={} added={} removed={}",
                    shortHex(newHead),
                    delta[0],
                    delta[1]);
            return MergeResult.ok(newHead, delta[0], delta[1]);
        } finally {
            target.releaseWriteLock();
        }
    }

    /** {@link #mergeTree} wrapped with a per-tree duration metric. */
    private static TreeMergeResult timedMergeTree(
            MeterRegistry m,
            String name,
            NodeStore store,
            BufferPool pool,
            @Nullable StaticMap ancestor,
            @Nullable StaticMap ours,
            @Nullable StaticMap theirs) {
        long t = System.nanoTime();
        TreeMergeResult r = mergeTree(store, pool, ancestor, ours, theirs);
        m.timer("sail.merge.tree", "tree", name)
                .record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
        return r;
    }

    /**
     * §8.2 — three-way merge of one persisted tree. {@code ancestor} is the LCA root (null → all
     * entries are ADDs), {@code ours} the target root, {@code theirs} the source root. The core
     * engine writes the merged chunks to {@code store}; this wraps the returned {@link Node} in a
     * fresh {@link StaticMap}.
     */
    private static TreeMergeResult mergeTree(
            NodeStore store,
            BufferPool pool,
            @Nullable StaticMap ancestorMap,
            @Nullable StaticMap oursMap,
            @Nullable StaticMap theirsMap) {
        TupleDescriptor desc = descriptorOf(ancestorMap, oursMap, theirsMap);
        if (desc == null) {
            // tree absent on every branch — nothing to merge
            return new TreeMergeResult(null, Collections.emptyList());
        }
        Node ancestor = ancestorMap == null ? null : ancestorMap.root();
        Node ours = oursMap == null ? null : oursMap.root();
        Node theirs = theirsMap == null ? null : theirsMap.root();
        com.dolthub.prolly.MergeEngine engine =
                new com.dolthub.prolly.MergeEngine(store, desc, pool);
        com.dolthub.prolly.MergeEngine.MergeResult r = engine.merge(ancestor, ours, theirs);
        return new TreeMergeResult(new StaticMap(store, r.root(), desc), r.conflicts());
    }

    /** First non-null map's {@link TupleDescriptor}, or null if all are null. */
    private static @Nullable TupleDescriptor descriptorOf(@Nullable StaticMap... maps) {
        for (StaticMap map : maps) {
            if (map != null) return map.descriptor();
        }
        return null;
    }

    /**
     * Outcome of merging one persisted tree. {@code merged} may be null (tree absent everywhere).
     */
    private record TreeMergeResult(
            @Nullable StaticMap merged, List<com.dolthub.prolly.MergeEngine.Conflict> conflicts) {
        private TreeMergeResult(
                @Nullable StaticMap merged,
                List<com.dolthub.prolly.MergeEngine.Conflict> conflicts) {
            this.merged = merged;
            this.conflicts = conflicts;
        }
    }

    /**
     * §8.5 — turn tuple-level core conflicts into RDF-shaped {@link Conflict}s. Namespace conflicts
     * (prefix → URI remaps) are the realistic surface under set-union semantics and are decoded
     * fully; other trees decode best-effort as hex (quad-index conflicts are near-impossible — the
     * same triple on both sides yields the same key+value).
     */
    private static List<Conflict> decodeConflicts(
            String treeName, List<com.dolthub.prolly.MergeEngine.Conflict> raw) {
        if (raw.isEmpty()) return Collections.emptyList();
        List<Conflict> out = new ArrayList<>(raw.size());
        boolean ns = "namespaces".equals(treeName);
        for (com.dolthub.prolly.MergeEngine.Conflict c : raw) {
            if (ns) {
                String prefix =
                        new String(
                                new Tuple(c.key()).getField(0),
                                java.nio.charset.StandardCharsets.UTF_8);
                out.add(
                        new Conflict(
                                Term.literal(prefix),
                                Term.uri("urn:prolly:merge:namespace"),
                                c.ourVal() == null ? null : Term.uri(segToString(c.ourVal())),
                                c.theirVal() == null ? null : Term.uri(segToString(c.theirVal()))));
            } else {
                out.add(
                        new Conflict(
                                Term.literal(HashUtils.toHex(toBytes(c.key()))),
                                Term.uri("urn:prolly:merge:" + treeName),
                                c.ourVal() == null
                                        ? null
                                        : Term.literal(HashUtils.toHex(toBytes(c.ourVal()))),
                                c.theirVal() == null
                                        ? null
                                        : Term.literal(HashUtils.toHex(toBytes(c.theirVal())))));
            }
        }
        return out;
    }

    private static byte[] toBytes(MemorySegment seg) {
        return seg.toArray(ValueLayout.JAVA_BYTE);
    }

    private static String segToString(MemorySegment seg) {
        return new String(toBytes(seg), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Net change on the SPOC index — {@code [adds, removes]} — via a leaf-cursor diff. */
    private static int[] spocDelta(
            NodeStore store, @Nullable StaticMap before, @Nullable StaticMap after) {
        Node b = before == null ? null : before.root();
        Node a = after == null ? null : after.root();
        int[] counts = new int[2];
        new DiffEngine(store, SpocKey.DESCRIPTOR)
                .diff(
                        b,
                        a,
                        e -> {
                            if (e.type() == DiffEngine.DiffType.ADD) counts[0]++;
                            else if (e.type() == DiffEngine.DiffType.DEL) counts[1]++;
                            return true;
                        });
        return counts;
    }

    /**
     * §8.4 — debug-mode invariant: every {@link TermId} referenced by the merged SPOC index must
     * resolve in the merged dictionary. Holds by construction (a TermId is a content hash), but a
     * cross-branch extension-slot divergence could break it — fail loud rather than persist a
     * corrupt dataset. Gated behind {@code -ea}.
     */
    private static void assertDictConsistency(
            NodeStore store,
            BufferPool pool,
            @Nullable StaticMap mergedDict,
            @Nullable StaticMap mergedSpoc) {
        if (!ASSERTIONS_ENABLED) return;
        if (!dictConsistencyHolds(store, pool, mergedDict, mergedSpoc)) {
            throw new SailConflictException(
                    "structural merge produced a dictionary inconsistent with the quad "
                            + "indexes — a TermId in SPOC does not resolve in the merged dict");
        }
    }

    private static boolean dictConsistencyHolds(
            NodeStore store,
            BufferPool pool,
            @Nullable StaticMap mergedDict,
            @Nullable StaticMap mergedSpoc) {
        if (mergedSpoc == null || mergedSpoc.root() == null) return true;
        if (mergedDict == null || mergedDict.root() == null) return false;
        Dictionary dict = new Dictionary(store, pool, HashFunctions.defaultHash(), mergedDict);
        SpocIndex spoc = new SpocIndex(store, pool, mergedSpoc);
        java.util.Iterator<SpocKey> it = spoc.iter();
        while (it.hasNext()) {
            SpocKey k = it.next();
            for (TermId t : new TermId[] {k.col0(), k.col1(), k.col2(), k.col3()}) {
                if (t.equals(TermId.ZERO)) continue; // default-graph / unused slot
                if (dict.decode(t).isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * Outcome of a merge attempt.
     *
     * <ul>
     *   <li>{@link Kind#OK} — clean merge; {@code newCommit} is set.
     *   <li>{@link Kind#UP_TO_DATE} — source is already an ancestor of target (or equal); nothing
     *       changed.
     *   <li>{@link Kind#CONFLICT} — conflicts were detected; {@code conflicts} is populated and no
     *       commit was made. (Reserved for iter 44+; never emitted under set-union policy.)
     * </ul>
     */
    public record MergeResult(
            Kind kind,
            byte @Nullable [] newCommit,
            int incomingCount,
            int sourceSideDeletes,
            List<Conflict> conflicts) {
        /**
         * Explicit canonical constructor — {@code newCommit} is {@code @Nullable} (null for a
         * {@code CONFLICT} or an empty-target {@code UP_TO_DATE}), declared on the parameter so
         * NullAway honors it at every {@code new MergeResult(...)} site (a record's implicit
         * canonical-constructor parameter does not reliably inherit the component annotation).
         */
        public MergeResult(
                Kind kind,
                byte @Nullable [] newCommit,
                int incomingCount,
                int sourceSideDeletes,
                List<Conflict> conflicts) {
            this.kind = kind;
            this.newCommit = newCommit;
            this.incomingCount = incomingCount;
            this.sourceSideDeletes = sourceSideDeletes;
            this.conflicts = conflicts;
        }

        public enum Kind {
            OK,
            UP_TO_DATE,
            CONFLICT
        }

        public static MergeResult ok(byte[] newCommit, int incoming, int sourceDeletes) {
            return new MergeResult(
                    Kind.OK, newCommit, incoming, sourceDeletes, Collections.emptyList());
        }

        public static MergeResult upToDate(byte @Nullable [] head) {
            return new MergeResult(Kind.UP_TO_DATE, head, 0, 0, Collections.emptyList());
        }

        public static MergeResult conflict(List<Conflict> conflicts) {
            return new MergeResult(Kind.CONFLICT, null, 0, 0, List.copyOf(conflicts));
        }
    }

    /**
     * Description of one merge conflict. Structured term shape mirrors the SPARQL JSON results
     * bindings + the /sparql/diff response, so UI clients can render conflicts and diff rows
     * uniformly.
     *
     * <p>Reserved for the conflict-aware policy in a future iter; the current set-union policy
     * never produces these.
     */
    public record Conflict(
            Term subject, Term predicate, @Nullable Term targetValue, @Nullable Term sourceValue) {
        /**
         * Explicit canonical constructor — {@code targetValue}/{@code sourceValue} are
         * {@code @Nullable} (a one-sided namespace remap has no value on the other side), declared
         * on the parameters so NullAway honors it at every {@code new Conflict(...)} site.
         */
        public Conflict(
                Term subject,
                Term predicate,
                @Nullable Term targetValue,
                @Nullable Term sourceValue) {
            this.subject = subject;
            this.predicate = predicate;
            this.targetValue = targetValue;
            this.sourceValue = sourceValue;
        }
    }

    /** A single RDF term carrying its kind + value + optional metadata. */
    public record Term(
            String type, String value, @Nullable String datatype, @Nullable String lang) {
        /**
         * Explicit canonical constructor — {@code datatype}/{@code lang} are {@code @Nullable}
         * (absent for a URI / bnode / plain literal), declared on the parameters so NullAway honors
         * it at every factory below.
         */
        public Term(String type, String value, @Nullable String datatype, @Nullable String lang) {
            this.type = type;
            this.value = value;
            this.datatype = datatype;
            this.lang = lang;
        }

        /** IRI / URI factory. */
        public static Term uri(String iri) {
            return new Term("uri", iri, null, null);
        }

        /** Blank-node factory — {@code value} is the bnode label. */
        public static Term bnode(String label) {
            return new Term("bnode", label, null, null);
        }

        /** Plain literal factory. */
        public static Term literal(String lex) {
            return new Term("literal", lex, null, null);
        }

        /** Typed literal factory. */
        public static Term typedLiteral(String lex, String datatypeIri) {
            return new Term("literal", lex, datatypeIri, null);
        }

        /** Language-tagged literal factory. */
        public static Term langLiteral(String lex, String langTag) {
            return new Term("literal", lex, null, langTag);
        }
    }
}
