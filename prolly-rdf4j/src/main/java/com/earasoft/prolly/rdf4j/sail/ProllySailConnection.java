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
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.IndexPlanner;
import com.earasoft.prolly.rdf4j.index.QuadIndex;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.QuadRole;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.SparqlNamespaces;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.term.TermStats;
import com.earasoft.prolly.rdf4j.value.DictionaryTermEncoder;
import com.earasoft.prolly.rdf4j.value.DictionaryTermResolver;
import com.earasoft.prolly.rdf4j.value.ProllyStatement;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.foreign.Arena;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.UpdateContext;
import org.eclipse.rdf4j.sail.helpers.AbstractNotifyingSailConnection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link org.eclipse.rdf4j.sail.SailConnection} for {@link ProllySail} — one transaction's
 * worth of buffered reads and writes against a forked snapshot of the Sail's committed roots.
 *
 * <p>Each transaction operates on its own {@link Dictionary} / {@link QuadIndex} / {@link
 * SparqlNamespaces} / {@link TermStats} instances, forked from the Sail's currently-committed
 * {@link StaticMap} roots at {@code startTransaction}. That fork is what gives a transaction
 * read-your-writes over its own buffer while staying isolated from other connections.
 *
 * <ul>
 *   <li>{@code commit} flushes each per-transaction table to a new {@link StaticMap} and advances
 *       the Sail's root references.
 *   <li>{@code rollback} drops the per-transaction tables — Sail state is untouched.
 *   <li>Concurrent connections see a snapshot of their own start-of-transaction state. The first to
 *       commit wins; a later connection must re-begin to see the newer state (no compare-and-set
 *       rebase yet).
 * </ul>
 *
 * @apiNote Obtained from the owning {@link ProllySail}; not safe to share across threads. Reads
 *     route to the four permutation indexes through {@link IndexPlanner} (which picks the index
 *     whose key prefix matches the bound columns); a cyclic basic graph pattern is handed to the
 *     worst-case-optimal join engine. Writes buffer into the per-transaction tables and only become
 *     visible to other connections at commit.
 * @implNote <b>Collaborators:</b> {@link ProllySail} (the committed roots it forks and later
 *     advances), the per-transaction {@link Dictionary} / {@link QuadIndex} / {@link
 *     SparqlNamespaces} / {@link TermStats}, {@link IndexPlanner} + {@link QuadOrder} (index
 *     selection), {@link TermEncoder} / {@link TermId} / {@link DictionaryTermResolver} (term-to-id
 *     resolution), and RDF4J's {@link EvaluationStrategy} for the query algebra. <b>Dependents:</b>
 *     RDF4J's query/update machinery and the REST SPARQL endpoints, which drive every operation
 *     through this connection.
 */
public class ProllySailConnection extends AbstractNotifyingSailConnection {

    private static final Logger LOG = LoggerFactory.getLogger(ProllySailConnection.class);

    /** Monotonic per-process counter, used to tag log lines for a given connection's lifecycle. */
    private static final java.util.concurrent.atomic.AtomicLong CONN_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Entry cap for the per-transaction term-encode memo ({@link #termCacheTx}). Overridable via
     * the system property {@code prolly.tx.term-cache-size}; default {@code 1_000_000} entries (≈
     * 150 MiB at a ~150-byte Value+TermId+overhead estimate — order-of-magnitude, not measured). At
     * this cap a bulk load keeps the hot terms resident while bounding heap; for batched loads the
     * per-batch distinct-term count stays well under it, so the bound never engages.
     */
    private static final int TERM_CACHE_MAX = resolveTermCacheMax();

    private static int resolveTermCacheMax() {
        String prop = System.getProperty("prolly.tx.term-cache-size");
        if (prop != null) {
            try {
                int v = Integer.parseInt(prop.trim());
                if (v > 0) return v;
            } catch (NumberFormatException ignore) {
                // fall through to default
            }
        }
        return 1_000_000;
    }

    /**
     * Upper bound on how many of a commit's independent table builds (dict, the four quad indexes,
     * namespaces, stats) run concurrently. From the system property {@code
     * prolly.tx.commit.concurrency}; default {@code 0} = unbounded (the common-pool fan-out —
     * current behavior). A positive value routes the builds through a fixed pool of that size;
     * {@code 1} is fully sequential.
     *
     * <p>The memory-for-time lever of Step 4c-2 (plans/prolly-bulk-load.md, D-7): each concurrent
     * table build holds its transient allocations, so the commit peak scales with how many run at
     * once. Capping concurrency caps that per-commit build spike at the cost of commit wall-time —
     * the "slow is better than blow up" knob for the single un-batched-transaction path.
     */
    private static final int COMMIT_CONCURRENCY = resolveCommitConcurrency();

    private static int resolveCommitConcurrency() {
        String prop = System.getProperty("prolly.tx.commit.concurrency");
        if (prop != null) {
            try {
                int v = Integer.parseInt(prop.trim());
                if (v >= 1) return v;
            } catch (NumberFormatException ignore) {
                // fall through to default
            }
        }
        return 0; // unbounded — common-pool fan-out (current default behavior)
    }

    private final ProllySail sail;

    /** Short stable id (cnNNN) for grep-ability in customer logs. */
    private final String connId;

    /**
     * Per-transaction buffer-pool scope, obtained from {@code sail.pool().newTransactionScope()} in
     * {@link #forkTables()} and used for ALL per-tx table scratch (dict, the four quad indexes,
     * namespaces, stats, provenance, event-sink). Freed wholesale at the NEXT fork (begin/rollback)
     * and at {@link #closeInternal()} — NOT at commit, so reads after a commit (before the next
     * begin) still find a live scope.
     *
     * @implNote The fix for the off-heap write-path leak ({@code
     *     bugs/direct-buffer-pool-write-path-leak.md}). For the on-heap default ({@link
     *     com.dolthub.prolly.HeapBufferPool}, production) {@code newTransactionScope()} returns the
     *     shared pool itself and {@code close()} is a no-op — behaviour is byte-identical to
     *     before. For an arena-backed {@code DirectBufferPool} it returns a fresh child arena that
     *     this connection frees per transaction, bounding the off-heap footprint to one
     *     transaction.
     */
    // @Nullable: live only between begin() and close()/rollback (null otherwise).
    private @Nullable BufferPool poolTx;

    /** Mutation counters since last commit/rollback — surfaced in commit log lines. */
    private long addedSinceFlush;

    private long removedSinceFlush;

    /**
     * Whether THIS transaction added/removed any statement — the flags behind the sail-level {@code
     * SailChangedEvent} fired on commit ({@code SailChangedListener}s registered on the sail, e.g.
     * RDF4J's notifying-store contract, hear about data changes through it; the per-statement
     * {@code notifyStatementAdded/Removed} connection-listener channel is separate and already
     * wired). Reset on begin/rollback and after the commit-time fire.
     */
    private boolean txStatementsAdded;

    private boolean txStatementsRemoved;

    /**
     * Whether this connection currently holds the Sail write lock (acquired in {@link
     * #startTransactionInternal}). {@code volatile} because the release may run on a different
     * thread than the acquire — e.g. {@code Sail.shutDown()} closes tracked connections from the
     * shutdown thread. The write lock is a non-thread-owned {@link java.util.concurrent.Semaphore}
     * precisely so that hand-off is legal; this flag makes the release idempotent and exactly-once.
     */
    private volatile boolean writeLockHeld;

    // Per-transaction tables — initialized in startTransactionInternal, nulled on rollback.
    private Dictionary dictTx;

    /**
     * Per-transaction memo of {@code Value → TermId}. Within one transaction a term always encodes
     * to the same TermId (the dictionary is deterministic), so this skips the repeated {@code
     * TermEncoder.encode} + dictionary lookup for terms that recur — predicates, {@code rdf:type},
     * shared resources — which in real RDF is the overwhelming majority of {@code encodeTerm}
     * calls. Re-created per transaction by {@link #forkTables()}.
     *
     * <p><b>Bounded</b> (Caffeine Window-TinyLFU, cap {@link #TERM_CACHE_MAX} entries). A pure
     * {@code HashMap} here was the last unbounded write-path heap wall: a single un-batched
     * transaction loading millions of <em>distinct</em> terms grew this map without limit and
     * out-of-memoried before the spillable staging buffer ever bound. Eviction is safe precisely
     * <em>because</em> the dictionary is deterministic — a term already inserted re-encodes to the
     * same TermId via {@code Dictionary.encode}'s salt-walk dedupe path, so an evicted entry just
     * costs a re-walk of the (spillable) dict buffer, never a wrong id. The eviction tax falls on
     * the rare long-tail term; Caffeine's frequency-aware admission keeps the hot terms
     * (predicates, types) resident under RDF's heavy term skew — strictly better than plain LRU for
     * the scan-plus-skew access pattern of a bulk load.
     */
    private Cache<Value, TermId> termCacheTx;

    private Map<QuadOrder, QuadIndex> indexesTx;
    private IndexPlanner plannerTx;
    private SparqlNamespaces namespacesTx;
    private TermStats statsTx;
    private DictionaryTermResolver resolverTx;

    /**
     * Optional provenance buffer — non-null iff the Sail was constructed with provenance enabled.
     * Each {@code addStatement} records the parent commit hash so a future {@code
     * /sparql/provenance} lookup can resolve "when did this triple first appear?" (ADR-0001).
     */
    private com.earasoft.prolly.rdf4j.index.@Nullable ProvenanceIndex provIdxTx;

    /**
     * Optional per-tx mutation-event sink — non-null iff the Sail has a {@link
     * com.earasoft.prolly.rdf4j.sail.spi.MutationEventSinkFactory} bound. Each insert + delete is
     * forwarded; the sink owns its own prolly tree. Independent of {@link #provIdxTx} — either may
     * be on alone. The OSS distribution never binds a factory so this is always null there.
     */
    private com.earasoft.prolly.rdf4j.sail.spi.@Nullable MutationEventSink eventSinkTx;

    /**
     * The Sail's {@code currentCommitHash} captured at fork time. Stored on provenance entries so
     * the resolver can map "first appeared after X" to X's successor in the commit log. Empty array
     * means genesis (this transaction will produce the first ever commit). Also used by the
     * event-log path: events are keyed by parent (we don't know the new mtHash until the event-log
     * tree is closed; readers walk forward to resolve to the introducing commit).
     */
    private byte @Nullable [] parentCommitAtFork;

    /**
     * Snapshot of the Sail's committed roots at the moment this connection forked its per-tx
     * tables. Phase 4 will use this as the {@code expected} argument to {@code Database.commit}'s
     * CAS check. v2.0 single-writer captures it but doesn't yet act on it.
     */
    private Snapshot forkSnapshot;

    public ProllySailConnection(ProllySail sail) {
        super(sail);
        this.sail = sail;
        this.connId = "cn" + CONN_SEQ.incrementAndGet();
        forkTables(); // initial fork — also opens the per-tx pool scope + arena
        LOG.debug("[{}] connection opened", connId);
    }

    /**
     * Fork the Sail's current committed roots into fresh per-connection tables. Called once at
     * construction and again on rollback (to discard buffered mutations while picking up any
     * commits that landed in the meantime — though for v2.0 single-writer, the sail roots are
     * unchanged between the connection's start-of-tx and rollback).
     */
    private void forkTables() {
        // Reset the per-transaction buffer-pool scope: free the PRIOR transaction's scope wholesale
        // (an arena-backed pool frees its arena here; an on-heap pool's close is a no-op) and open
        // a
        // fresh scope for the tables built below. Done here — at every fork (construction, begin,
        // rollback) — rather than at commit so a read AFTER a commit, before the next begin, still
        // finds a live scope. See the poolTx field javadoc +
        // bugs/direct-buffer-pool-write-path-leak.md.
        closeTxScratch();
        BufferPool tx = sail.pool().newTransactionScope();
        poolTx = tx;
        // One atomic read of the four core roots — they always belong to the same commit, so a
        // connection opened concurrently with a commit can't fork a torn mix of two commits' roots
        // (the publication-race fix). Provenance / event-sink sidecar roots are still read
        // individually below (a smaller, off-by-default residual).
        Snapshot snap = sail.publishedSnapshot();
        StaticMap dictRoot = snap.dictRoot();
        dictTx =
                (dictRoot == null)
                        ? new Dictionary(
                                sail.store(),
                                tx,
                                sail.hashFn(),
                                Dictionary.MAX_SALT,
                                sail.encoderMetrics())
                        : new Dictionary(
                                sail.store(), tx, sail.hashFn(), dictRoot, sail.encoderMetrics());
        // Fresh term memo per transaction — tied to dictTx's lifetime.
        // Bounded Caffeine cache: caps heap on a single un-batched load of
        // millions of distinct terms (see the field javadoc for why eviction
        // is safe). For batched loads the per-batch term count never reaches the
        // cap, so the bound is inert and behavior is unchanged.
        termCacheTx = Caffeine.newBuilder().maximumSize(TERM_CACHE_MAX).build();

        indexesTx = new EnumMap<>(QuadOrder.class);
        for (QuadOrder order : QuadOrder.values()) {
            StaticMap root = snap.indexRoots().get(order);
            QuadIndex idx =
                    (root == null)
                            ? new QuadIndex(order, sail.store(), tx, sail.boundarySplitterFactory())
                            : new QuadIndex(
                                    order, sail.store(), tx, root, sail.boundarySplitterFactory());
            indexesTx.put(order, idx);
        }
        plannerTx =
                new IndexPlanner(
                        indexesTx, sail.meterRegistry(), null); // statsTx isn't initialized yet
        // Stats-aware planner re-bound after statsTx is initialized below.

        StaticMap nsRoot = snap.namespacesRoot();
        namespacesTx =
                (nsRoot == null)
                        ? new SparqlNamespaces(sail.store(), tx)
                        : new SparqlNamespaces(sail.store(), tx, nsRoot);

        StaticMap statsRoot = snap.statsRoot();
        statsTx =
                (statsRoot == null)
                        ? new TermStats(sail.store(), tx)
                        : new TermStats(sail.store(), tx, statsRoot);

        // Re-create planner with stats now that statsTx exists.
        plannerTx = new IndexPlanner(indexesTx, sail.meterRegistry(), statsTx);

        resolverTx = new DictionaryTermResolver(dictTx, sail.prefixes(), sail.termCacheSize());

        // Both ADR-0001 (provenance) and ADR-0003 (event log) need the
        // parent commit hash captured at fork time. Capture once if either
        // opt-in is on; leave null otherwise so the metrics fast-path can
        // skip the bookkeeping branch entirely.
        if (sail.provenanceEnabled() || sail.eventSinkActive()) {
            // The parent is the previous head's commit ID (ADR-0071) — it MUST match the id the
            // provenance rebuild records (`e.parents().get(0)`) and the id readers key on (the
            // event
            // log + lookupProvenance walk the commit log by id). currentCommitHash (the tree)
            // silently
            // broke that match (EventLogControllerTest). Genesis (no head yet) stays
            // GENESIS_PARENT.
            byte[] head = sail.currentCommitId();
            parentCommitAtFork =
                    head == null
                            ? com.earasoft.prolly.rdf4j.index.ProvenanceIndex.GENESIS_PARENT
                            : head;
        } else {
            parentCommitAtFork = null;
        }

        if (sail.provenanceEnabled()) {
            StaticMap provRoot = sail.provenanceRoot();
            provIdxTx =
                    (provRoot == null)
                            ? new com.earasoft.prolly.rdf4j.index.ProvenanceIndex(sail.store(), tx)
                            : new com.earasoft.prolly.rdf4j.index.ProvenanceIndex(
                                    sail.store(), tx, provRoot);
        } else {
            provIdxTx = null;
        }

        // Mutation-event sink — open via the bound factory, passing the current
        // committed root (null = empty / never written). When the factory is
        // unbound OR the runtime flag is off, leave the tx null and skip in the
        // hot path.
        if (sail.eventSinkActive()) {
            // eventSinkActive() == (eventSinkEnabled && factory != null), so the factory is
            // present.
            eventSinkTx =
                    Objects.requireNonNull(sail.eventSinkFactory())
                            .open(sail.store(), tx, sail.eventSinkRoot());
        } else {
            eventSinkTx = null;
        }

        forkSnapshot = snap; // the same atomic snapshot the tables were forked from
    }

    /** Package-private accessor for tests. */
    Snapshot forkSnapshotForTesting() {
        return forkSnapshot;
    }

    // ---------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------

    @Override
    protected void closeInternal() throws SailException {
        LOG.debug("[{}] connection closed", connId);
        // If the caller closed without a commit/rollback (or the Sail is
        // shutting down and closing us from another thread), don't leak the
        // write lock. releaseWriteLockIfHeld is idempotent and thread-agnostic.
        releaseWriteLockIfHeld();
        closeTxScratch();
    }

    /**
     * Release the Sail write lock iff this connection holds it. Idempotent and safe to call from
     * any thread (the lock is a {@link java.util.concurrent.Semaphore}, not a thread-owned {@code
     * ReentrantLock}). The flag is cleared before the release so a concurrent caller cannot
     * double-release.
     */
    private void releaseWriteLockIfHeld() {
        if (writeLockHeld) {
            writeLockHeld = false;
            sail.releaseWriteLock();
        }
    }

    /**
     * Free the current transaction's buffer-pool scope — if any (idempotent). For an arena-backed
     * pool scope this releases its arena wholesale; for the on-heap default pool the scope close is
     * a no-op. {@code BufferPool.close()} throws nothing, so no checked-exception handling is
     * needed.
     *
     * @implNote The per-term encode scratch is no longer freed here. It is a per-operation {@code
     *     Arena.ofConfined()} created and closed inside {@link #encodeTerm} (the read-capacity fix,
     *     {@code plans/tx-scratch-arena-confined.md} D-1a) — so there is no per-transaction encode
     *     arena to release, and the per-request close that forced a JVM-wide thread handshake (77%
     *     of read wall) is gone.
     */
    private void closeTxScratch() {
        if (poolTx != null) {
            poolTx.close();
            poolTx = null;
        }
    }

    @Override
    protected void startTransactionInternal() throws SailException {
        txStatementsAdded = false;
        txStatementsRemoved = false;
        // #143 — acquire the sail's write lock to enforce v2.0 single-writer.
        // Two concurrent /sparql/update connections must serialize end-to-end,
        // otherwise both fork from the same dictRoot, both advance it on
        // commit, and last-writer-wins drops the first's TermIds. Held until
        // commitInternal or rollbackInternal completes.
        sail.acquireWriteLock();
        writeLockHeld = true;
        // Re-fork now that we're under the lock — the dictRoot/indexRoots may
        // have advanced since the connection opened (another writer committed
        // while we were blocked).
        forkTables();
    }

    /**
     * Times one table build (Layer-B per-tree throughput attribution, plans/prolly-bulk-load.md):
     * records the supplier's wall time under {@code sail.commit.tree.<name>} so a probe can see
     * WHICH of the seven trees dominates and degrades — the scatter hypothesis (POSC/OSPC inserts
     * land across the whole tree as it grows, SPOC mostly appends). Cheap: one Micrometer timer,
     * the same idiom the surrounding commit already uses; no behavioural change.
     */
    private static StaticMap timedTreeCommit(
            MeterRegistry m, String name, java.util.function.Supplier<StaticMap> build) {
        long t = System.nanoTime();
        try {
            return build.get();
        } finally {
            m.timer(name).record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    protected void commitInternal() throws SailException {
        MeterRegistry m = sail.meterRegistry();
        long t0 = System.nanoTime();
        long t = System.nanoTime();
        try {
            // The per-transaction table commits — dict, the 4 quad-order
            // indexes, namespaces, stats — are independent tree builds:
            // distinct objects sharing only the (thread-safe) NodeStore and
            // BufferPool. Build them in parallel on the common pool, then
            // advance the Sail roots sequentially (advance*Root mutates shared
            // Sail state). The write lock guarantees no *other* commit runs
            // concurrently, so the only concurrency is these tasks within
            // this one commit.
            // COMMIT_CONCURRENCY bounds how many table builds run at once
            // (Step 4c-2). Default (0) keeps the common-pool fan-out; a positive
            // value uses a fixed pool of that size — 1 = sequential, lowest peak.
            // A single-thread pool serialises naturally: the tasks queue and run
            // one at a time, so at most one build's transient is live.
            java.util.concurrent.ExecutorService ownedPool =
                    (COMMIT_CONCURRENCY > 0)
                            ? java.util.concurrent.Executors.newFixedThreadPool(COMMIT_CONCURRENCY)
                            : null;
            java.util.concurrent.Executor pool =
                    (ownedPool != null)
                            ? ownedPool
                            : java.util.concurrent.ForkJoinPool.commonPool();
            try {
                CompletableFuture<StaticMap> dictF =
                        CompletableFuture.supplyAsync(
                                () -> timedTreeCommit(m, "sail.commit.tree.dict", dictTx::commit),
                                pool);
                CompletableFuture<StaticMap> nsF =
                        CompletableFuture.supplyAsync(
                                () ->
                                        timedTreeCommit(
                                                m, "sail.commit.tree.ns", namespacesTx::commit),
                                pool);
                CompletableFuture<StaticMap> statsF =
                        CompletableFuture.supplyAsync(
                                () -> timedTreeCommit(m, "sail.commit.tree.stats", statsTx::commit),
                                pool);
                EnumMap<QuadOrder, CompletableFuture<StaticMap>> idxF =
                        new EnumMap<>(QuadOrder.class);
                for (Map.Entry<QuadOrder, QuadIndex> e : indexesTx.entrySet()) {
                    QuadIndex idx = e.getValue();
                    String treeName =
                            "sail.commit.tree."
                                    + e.getKey().name().toLowerCase(java.util.Locale.ROOT);
                    idxF.put(
                            e.getKey(),
                            CompletableFuture.supplyAsync(
                                    () -> timedTreeCommit(m, treeName, idx::commit), pool));
                }
                try {
                    sail.advanceDictRoot(dictF.join());
                    for (Map.Entry<QuadOrder, CompletableFuture<StaticMap>> e : idxF.entrySet()) {
                        sail.advanceIndexRoot(e.getKey(), e.getValue().join());
                    }
                    sail.advanceNamespacesRoot(nsF.join());
                    sail.advanceStatsRoot(statsF.join());
                    // Publish the four core roots as ONE atomic snapshot for lock-free forks.
                    // Placed
                    // after all four advances succeed: a mid-sequence join() failure throws before
                    // this, leaving the previous (consistent) snapshot published — never a torn
                    // one.
                    sail.publishSnapshot();
                } catch (CompletionException ce) {
                    // Surface a table-commit failure as itself, not wrapped.
                    Throwable cause = ce.getCause() != null ? ce.getCause() : ce;
                    if (cause instanceof RuntimeException re) throw re;
                    if (cause instanceof Error err) throw err;
                    throw new SailException(cause);
                }
            } finally {
                if (ownedPool != null) ownedPool.shutdown();
            }
            m.timer("sail.commit.tables").record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
            // PrefixTable stays Sail-level (shared, not per-tx); commit it
            // sequentially here for v2.0.
            t = System.nanoTime();
            sail.prefixes().commit();
            m.timer("sail.commit.prefixes").record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
            // Provenance commit — gated by the opt-in flag (see ADR-0001).
            // Must run BEFORE persistMetaTreeIfConfigured so the new root is
            // baked into the RootMetaTree this commit produces.
            if (provIdxTx != null) {
                // Shadow into a local: the takeNext* calls below clear NullAway's field narrowing.
                com.earasoft.prolly.rdf4j.index.ProvenanceIndex provIdx = provIdxTx;
                t = System.nanoTime();
                // Iter F.6 — fold a peer ProvenanceIndex with older-wins before
                // committing. Set by MergeEngine via setNextCommitProvenanceFold.
                com.dolthub.prolly.StaticMap foldSource = sail.takeNextProvenanceFoldSource();
                java.util.function.BiPredicate<byte[], byte[]> foldPred =
                        sail.takeNextProvenanceFoldPredicate();
                if (foldSource != null && foldPred != null) {
                    com.earasoft.prolly.rdf4j.index.ProvenanceIndex peer =
                            new com.earasoft.prolly.rdf4j.index.ProvenanceIndex(
                                    sail.store(), Objects.requireNonNull(poolTx), foldSource);
                    provIdx.mergeFrom(peer, foldPred);
                }
                sail.advanceProvenanceRoot(provIdx.commit());
                m.timer("sail.commit.provenance")
                        .record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
            }
            // Event-sink commit — same lifecycle as provenance, but with a
            // pre-check so we don't orphan events on no-op transactions (#126).
            // The data roots have all been advanced above; if those collectively
            // produce a no-op vs the previous commit, persistMetaTreeIfConfigured
            // will skip the commit-log append. Discard the pending sink state
            // instead of committing — otherwise the sink tree advances with
            // events that have no corresponding /sparql/commits entry.
            if (eventSinkTx != null) {
                t = System.nanoTime();
                if (sail.wouldBeNoOpCommit()) {
                    eventSinkTx.discard();
                    LOG.debug(
                            "[{}] commit no-op detected — discarding {} pending event(s)",
                            connId,
                            "<sink>");
                } else {
                    sail.advanceEventSinkRoot(eventSinkTx.commit());
                }
                m.timer("sail.commit.event-sink")
                        .record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
            }
            t = System.nanoTime();
            sail.persistMetaTreeIfConfigured();
            m.timer("sail.commit.metatree").record(System.nanoTime() - t, TimeUnit.NANOSECONDS);
            long totalNs = System.nanoTime() - t0;
            m.timer("sail.commit.total").record(totalNs, TimeUnit.NANOSECONDS);
            m.counter("sail.commit").increment();
            // Mutations (added + deleted) in this commit — the distribution tells an operator
            // whether
            // commits are too large (→ batch them; the spill/crater work). Recorded before the
            // reset below.
            m.summary("prolly.commit.mutations").record(addedSinceFlush + removedSinceFlush);
            if (LOG.isInfoEnabled()) {
                long ms = totalNs / 1_000_000L;
                if (ms >= 250) {
                    // Slow commits get an explicit WARN so they stand out in customer logs.
                    LOG.warn(
                            "[{}] commit slow: added={} removed={} duration={}ms",
                            connId,
                            addedSinceFlush,
                            removedSinceFlush,
                            ms);
                } else if (addedSinceFlush + removedSinceFlush > 0) {
                    LOG.info(
                            "[{}] commit: added={} removed={} duration={}ms",
                            connId,
                            addedSinceFlush,
                            removedSinceFlush,
                            ms);
                } else {
                    LOG.debug("[{}] commit (no-op): duration={}ms", connId, ms);
                }
            }
            addedSinceFlush = 0;
            removedSinceFlush = 0;
            if (txStatementsAdded || txStatementsRemoved) {
                org.eclipse.rdf4j.sail.helpers.DefaultSailChangedEvent changed =
                        new org.eclipse.rdf4j.sail.helpers.DefaultSailChangedEvent(sail);
                changed.setStatementsAdded(txStatementsAdded);
                changed.setStatementsRemoved(txStatementsRemoved);
                txStatementsAdded = false;
                txStatementsRemoved = false;
                sail.fireSailChanged(changed);
            }
        } catch (RuntimeException e) {
            LOG.error(
                    "[{}] commit failed after {} added / {} removed",
                    connId,
                    addedSinceFlush,
                    removedSinceFlush,
                    e);
            throw e;
        } finally {
            // #143 — always release the write lock acquired in startTransactionInternal.
            releaseWriteLockIfHeld();
        }
    }

    @Override
    protected void rollbackInternal() throws SailException {
        txStatementsAdded = false;
        txStatementsRemoved = false;
        // Re-fork from sail's current committed roots. Buffered mutations are
        // discarded; Sail-level state was never touched.
        LOG.info(
                "[{}] rollback: discarding added={} removed={}",
                connId,
                addedSinceFlush,
                removedSinceFlush);
        try {
            forkTables();
            addedSinceFlush = 0;
            removedSinceFlush = 0;
            sail.meterRegistry().counter("sail.rollback").increment();
        } finally {
            releaseWriteLockIfHeld();
        }
    }

    // ---------------------------------------------------------------
    // Add / Remove
    // ---------------------------------------------------------------

    /**
     * Reject an RDF-star {@link org.eclipse.rdf4j.model.Triple} used as a context. A triple term is
     * not a legal graph name; the RDF4J Sail contract requires a {@link SailException} here (see
     * RDFStoreTest {@code testAddTripleContext}).
     *
     * <p>This must run synchronously on the {@code addStatement} / {@code removeStatement} call —
     * {@code AbstractSailConnection} buffers those into a pending set and only invokes {@code
     * *Internal} at flush — so the guard lives in the overridable {@code (UpdateContext, ...)}
     * methods below, not in {@code addStatementInternal}.
     */
    private static void rejectTripleContexts(Resource[] ctxs) throws SailException {
        for (Resource ctx : ctxs) {
            if (ctx instanceof org.eclipse.rdf4j.model.Triple) {
                throw new SailException("context argument can not be of type Triple: " + ctx);
            }
        }
    }

    @Override
    public void addStatement(
            UpdateContext op, Resource subj, IRI pred, Value obj, Resource... contexts)
            throws SailException {
        rejectTripleContexts(contexts);
        super.addStatement(op, subj, pred, obj, contexts);
    }

    @Override
    public void removeStatement(
            UpdateContext op, Resource subj, IRI pred, Value obj, Resource... contexts)
            throws SailException {
        rejectTripleContexts(contexts);
        super.removeStatement(op, subj, pred, obj, contexts);
    }

    @Override
    protected void addStatementInternal(Resource s, IRI p, Value o, Resource... ctxs)
            throws SailException {
        MeterRegistry m = sail.meterRegistry();
        m.counter("sail.add").increment();
        // Per-statement timers — term encoding vs index insertion. Off the
        // hot path under the noop() metrics default; used to attribute the
        // addStatement cost when profiling ingest.
        long tEnc = System.nanoTime();
        TermId sId = encodeTerm(s);
        TermId pId = encodeTerm(p);
        TermId oId = encodeTerm(o);
        m.timer("sail.add.encode").record(System.nanoTime() - tEnc, TimeUnit.NANOSECONDS);
        // notifyStatementAdded only allocates the Statement when a
        // listener is actually registered (the cheap hasConnection
        // Listeners() check guards the per-statement allocation in
        // the listener-less common case).
        // Connection-listener notifications must be CHANGE-ACCURATE (the
        // NotifyingSail contract RDF4J's stores implement: no event for a
        // no-op re-add — surfaced by RDFNotifyingStoreTest, 2026-08-25). The
        // presence probe runs ONLY when a listener is registered, so the
        // listener-less hot path pays exactly the boolean check it always did.
        boolean notify = hasConnectionListeners();
        if (ctxs.length == 0 || (ctxs.length == 1 && ctxs[0] == null)) {
            boolean fresh = !notify || !statementPresent(s, p, o, null);
            long tIns = System.nanoTime();
            insertEverywhere(sId, pId, oId, TermId.ZERO);
            m.timer("sail.add.insert").record(System.nanoTime() - tIns, TimeUnit.NANOSECONDS);
            addedSinceFlush++;
            txStatementsAdded = true;
            if (notify && fresh) {
                notifyStatementAdded(sail.getValueFactory().createStatement(s, p, o));
            }
        } else {
            for (Resource ctx : ctxs) {
                boolean fresh = !notify || !statementPresent(s, p, o, ctx);
                TermId cId = ctx == null ? TermId.ZERO : encodeTerm(ctx);
                long tIns = System.nanoTime();
                insertEverywhere(sId, pId, oId, cId);
                m.timer("sail.add.insert").record(System.nanoTime() - tIns, TimeUnit.NANOSECONDS);
                addedSinceFlush++;
                txStatementsAdded = true;
                if (notify && fresh) {
                    notifyStatementAdded(sail.getValueFactory().createStatement(s, p, o, ctx));
                }
            }
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace("[{}] add s={} p={} o={} ctxs={}", connId, s, p, o, ctxs.length);
        }
    }

    private void insertEverywhere(TermId sId, TermId pId, TermId oId, TermId cId) {
        for (QuadIndex idx : indexesTx.values()) {
            idx.insert(sId, pId, oId, cId);
            sail.meterRegistry().counter(idx.order().insertMetricKey()).increment();
        }
        // Provenance: idempotent — first add wins. Re-adding a triple after a
        // delete keeps the original parent-commit pointer, which is the
        // intentional first-seen semantics from ADR-0001 §2.3. The repoId
        // (ADR-0001 §9 axis 5) is the Sail's genesis hash, included in the
        // value so CAS dedup at the metadata layer is broken across repos
        // even when triples are byte-identical.
        if (provIdxTx != null) {
            provIdxTx.recordFirstSeen(
                    new com.earasoft.prolly.rdf4j.index.SpocKey(sId, pId, oId, cId),
                    Objects.requireNonNull(parentCommitAtFork),
                    sail.repoId());
        }
        // Mutation-event sink (ADR-0003 event log via enterprise jar, etc).
        // Keyed by parent commit because the new commit hash isn't known until
        // the meta tree is sealed; readers walk forward in the commit log to
        // resolve to the introducing commit (mirrors the ProvenanceIndex
        // resolve path).
        if (eventSinkTx != null) {
            eventSinkTx.recordInsert(
                    new com.earasoft.prolly.rdf4j.index.SpocKey(sId, pId, oId, cId),
                    Objects.requireNonNull(parentCommitAtFork));
        }
        statsTx.increment(sId);
        statsTx.increment(pId);
        statsTx.increment(oId);
        if (!cId.equals(TermId.ZERO)) statsTx.increment(cId);
    }

    private void deleteEverywhere(TermId sId, TermId pId, TermId oId, TermId cId) {
        for (QuadIndex idx : indexesTx.values()) {
            idx.delete(sId, pId, oId, cId);
            sail.meterRegistry().counter(idx.order().deleteMetricKey()).increment();
        }
        // Mutation-event sink: matching DELETE event. Same key/value rules as insert.
        if (eventSinkTx != null) {
            eventSinkTx.recordDelete(
                    new com.earasoft.prolly.rdf4j.index.SpocKey(sId, pId, oId, cId),
                    Objects.requireNonNull(parentCommitAtFork));
        }
        statsTx.decrement(sId, 1L);
        statsTx.decrement(pId, 1L);
        statsTx.decrement(oId, 1L);
        if (!cId.equals(TermId.ZERO)) statsTx.decrement(cId, 1L);
    }

    @Override
    protected void removeStatementsInternal(Resource s, IRI p, Value o, Resource... ctxs)
            throws SailException {
        sail.meterRegistry().counter("sail.remove").increment();
        boolean notify = hasConnectionListeners();
        // An all-contexts remove (empty ctxs) of a fully-bound (s,p,o) must reach
        // EVERY graph the triple is in, not just the default graph — the same
        // contract the read path documents in wantedContexts (empty ctxs ⇒ match
        // every graph; {null} ⇒ the default graph only). We can't know which graphs
        // hold the triple without looking, so the all-contexts case routes through
        // the scan-and-delete branch, which deletes each match in its ACTUAL context.
        // (Bug: a bound (s,p,o) all-contexts remove previously deleted only the
        // default-graph copy — TermId.ZERO — silently leaving named-graph copies.
        // Surfaced by SailReadYourWritesProperty's forced-collision regime, which the
        // large-term-space SailDifferentialProperty structurally never reached.)
        if (s == null || p == null || o == null || ctxs.length == 0) {
            try (var it = getStatementsInternal(s, p, o, false, ctxs)) {
                while (it.hasNext()) {
                    Statement st = it.next();
                    TermId sId = encodeTerm(st.getSubject());
                    TermId pId = encodeTerm(st.getPredicate());
                    TermId oId = encodeTerm(st.getObject());
                    TermId cId =
                            st.getContext() == null ? TermId.ZERO : encodeTerm(st.getContext());
                    deleteEverywhere(sId, pId, oId, cId);
                    removedSinceFlush++;
                    txStatementsRemoved = true;
                    if (notify) notifyStatementRemoved(st);
                }
            }
            return;
        }
        TermId sId = encodeTerm(s);
        TermId pId = encodeTerm(p);
        TermId oId = encodeTerm(o);
        // Reaching here, s/p/o are all bound and ctxs is non-empty — the
        // all-contexts case (ctxs.length == 0) already returned via the scan
        // branch above. So the only default-graph fast path left is an explicit
        // single {null}; do NOT re-add `ctxs.length == 0` here (that was the bug).
        if (ctxs.length == 1 && ctxs[0] == null) {
            boolean existed = notify && statementPresent(s, p, o, null);
            deleteEverywhere(sId, pId, oId, TermId.ZERO);
            removedSinceFlush++;
            txStatementsRemoved = true;
            if (existed) {
                notifyStatementRemoved(sail.getValueFactory().createStatement(s, p, o));
            }
        } else {
            for (Resource ctx : ctxs) {
                boolean existed = notify && statementPresent(s, p, o, ctx);
                TermId cId = ctx == null ? TermId.ZERO : encodeTerm(ctx);
                deleteEverywhere(sId, pId, oId, cId);
                removedSinceFlush++;
                txStatementsRemoved = true;
                if (existed) {
                    notifyStatementRemoved(sail.getValueFactory().createStatement(s, p, o, ctx));
                }
            }
        }
    }

    /**
     * Is the exact statement visible to THIS transaction right now ({@code ctx == null} = the
     * default graph only)? The change-accuracy probe behind listener notifications — called only
     * when a connection listener is registered.
     */
    private boolean statementPresent(Resource s, IRI p, Value o, @Nullable Resource ctx) {
        try (var it = getStatementsInternal(s, p, o, false, new Resource[] {ctx})) {
            return it.hasNext();
        }
    }

    @Override
    protected void clearInternal(Resource... ctxs) throws SailException {
        // clear() removes statements: the commit-time SailChangedEvent must say so,
        // and registered connection listeners hear each actually-removed statement
        // (change-accurate by construction — the scan only yields what exists).
        txStatementsRemoved = true;
        boolean notify = hasConnectionListeners();
        try (var it = getStatementsInternal(null, null, null, false, ctxs)) {
            while (it.hasNext()) {
                Statement st = it.next();
                TermId sId = encodeTerm(st.getSubject());
                TermId pId = encodeTerm(st.getPredicate());
                TermId oId = encodeTerm(st.getObject());
                TermId cId = st.getContext() == null ? TermId.ZERO : encodeTerm(st.getContext());
                deleteEverywhere(sId, pId, oId, cId);
                if (notify) notifyStatementRemoved(st);
            }
        }
    }

    private TermId encodeTerm(Value v) {
        TermId cached = termCacheTx.getIfPresent(v);
        if (cached != null) return cached;
        // DictionaryTermEncoder routes a custom-datatype literal through encodeCustomLiteral
        // (interning
        // its datatype IRI in dictTx); built-in datatypes delegate to TermEncoder.encode. (ADR-0043
        // DTYPE-2.)
        //
        // Per-operation confined arena (plans/tx-scratch-arena-confined.md D-1a, the read-capacity
        // fix). The encode scratch lives only for this one call, on this one thread: encodeForWrite
        // allocates the term's bytes here, and dictTx.encode copies them out to a heap array
        // (Dictionary.encode -> toArray, Dictionary.java:168 — verified) before this arena closes,
        // so
        // nothing references arena memory past the try block. Confined (not shared) so the close is
        // thread-local — no JVM-wide handshake (closing a shared arena was 77% of read wall).
        // Per-call
        // (not a persistent per-tx field) so there is no arena to be used or closed on a thread
        // other
        // than the one driving this call — which is what refuted the per-tx variant against
        // ProllySailConcurrencyStressTest (cross-thread allocate -> WrongThreadException).
        TermId id;
        try (Arena enc = Arena.ofConfined()) {
            id = dictTx.encode(DictionaryTermEncoder.encodeForWrite(v, dictTx, enc));
        }
        termCacheTx.put(v, id);
        return id;
    }

    // ---------------------------------------------------------------
    // Reads
    // ---------------------------------------------------------------

    @Override
    protected CloseableIteration<? extends Statement> getStatementsInternal(
            @Nullable Resource s,
            @Nullable IRI p,
            @Nullable Value o,
            boolean includeInferred,
            Resource... ctxs)
            throws SailException {
        sail.meterRegistry().counter("sail.get").increment();
        TermId sId = s == null ? null : encodeTerm(s);
        TermId pId = p == null ? null : encodeTerm(p);
        TermId oId = o == null ? null : encodeTerm(o);

        QuadIndex chosen = plannerTx.choose(sId, pId, oId, null);
        // Read-your-own-writes: the scan path reads the index's committed
        // StaticMap, not its in-transaction MutableMap buffer. Flush the
        // pending buffer first so this read reflects same-transaction
        // addStatement/removeStatement calls. flush() returns the base
        // unchanged when nothing is pending, so read-only transactions
        // pay nothing.
        chosen.commit();
        Iterator<SpocKey> base = chosen.scan(sId, pId, oId, null);

        Set<TermId> wantedCtx = wantedContexts(ctxs);
        Iterator<SpocKey> filtered =
                filterByLogical(base, chosen.order(), sId, pId, oId, wantedCtx);

        return new SpocStatementIteration(filtered, chosen.order());
    }

    private @Nullable Set<TermId> wantedContexts(Resource[] ctxs) {
        // No contexts at all (empty varargs) → no filter: match every graph.
        // A single null context is NOT the same thing — per the RDF4J
        // SailConnection contract a null context denotes the DEFAULT GRAPH
        // specifically, which this store keeps under the TermId.ZERO sentinel
        // (see addStatementInternal). Returning null here for a single-null
        // context leaked every named graph into default-graph reads — the bug
        // behind ~60 W3C SPARQL 1.1 update-suite failures (per-graph compares).
        if (ctxs.length == 0) return null;
        Set<TermId> out = new LinkedHashSet<>();
        for (Resource c : ctxs) {
            out.add(c == null ? TermId.ZERO : encodeTerm(c));
        }
        return out;
    }

    private Iterator<SpocKey> filterByLogical(
            Iterator<SpocKey> base,
            QuadOrder order,
            @Nullable TermId sId,
            @Nullable TermId pId,
            @Nullable TermId oId,
            @Nullable Set<TermId> wantedCtx) {
        QuadRole role = order.role();
        return new Iterator<>() {
            @Nullable SpocKey nextKey;
            long examined = 0;
            long emitted = 0;

            @Override
            public boolean hasNext() {
                if (nextKey != null) return true;
                while (base.hasNext()) {
                    SpocKey k = base.next();
                    examined++;
                    if (sId != null && !sId.equals(role.col(k, 0))) continue;
                    if (pId != null && !pId.equals(role.col(k, 1))) continue;
                    if (oId != null && !oId.equals(role.col(k, 2))) continue;
                    if (wantedCtx != null && !wantedCtx.contains(role.col(k, 3))) continue;
                    nextKey = k;
                    emitted++;
                    return true;
                }
                sail.meterRegistry()
                        .counter("index", "name", order.name().toLowerCase() + ".scan.examined")
                        .increment(examined);
                sail.meterRegistry()
                        .counter("index", "name", order.name().toLowerCase() + ".scan.emitted")
                        .increment(emitted);
                examined = 0;
                emitted = 0;
                return false;
            }

            @Override
            public SpocKey next() {
                if (!hasNext()) throw new NoSuchElementException();
                SpocKey r = Objects.requireNonNull(nextKey); // hasNext()==true guarantees it
                nextKey = null;
                return r;
            }
        };
    }

    @Override
    protected long sizeInternal(Resource... ctxs) throws SailException {
        long count = 0;
        try (var it = getStatementsInternal(null, null, null, false, ctxs)) {
            while (it.hasNext()) {
                it.next();
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------
    // Contexts
    // ---------------------------------------------------------------

    @Override
    protected CloseableIteration<? extends Resource> getContextIDsInternal() throws SailException {
        Set<Resource> ctxs = new LinkedHashSet<>();
        QuadIndex spoc = Objects.requireNonNull(indexesTx.get(QuadOrder.SPOC));
        // Flush pending in-transaction writes so context discovery is
        // read-your-own-writes consistent (see getStatementsInternal).
        spoc.commit();
        Iterator<SpocKey> it = spoc.iter();
        while (it.hasNext()) {
            SpocKey k = it.next();
            TermId cId = k.col3();
            if (cId.equals(TermId.ZERO)) continue;
            Value v = resolverTx.resolve(cId);
            if (v instanceof Resource r) ctxs.add(r);
        }
        return new SimpleCloseableIteration<>(ctxs.iterator());
    }

    // ---------------------------------------------------------------
    // Namespaces (persistent, per-tx-isolated)
    // ---------------------------------------------------------------

    @Override
    protected void setNamespaceInternal(String prefix, String name) throws SailException {
        namespacesTx.set(prefix, name);
    }

    @Override
    protected void removeNamespaceInternal(String prefix) throws SailException {
        namespacesTx.remove(prefix);
    }

    @Override
    protected void clearNamespacesInternal() throws SailException {
        namespacesTx.clear();
    }

    @Override
    protected @Nullable String getNamespaceInternal(String prefix) throws SailException {
        return namespacesTx.get(prefix).orElse(null);
    }

    @Override
    protected CloseableIteration<? extends Namespace> getNamespacesInternal() throws SailException {
        Set<Namespace> nss = new LinkedHashSet<>();
        for (var e : namespacesTx.snapshot().entrySet()) {
            nss.add(new org.eclipse.rdf4j.model.impl.SimpleNamespace(e.getKey(), e.getValue()));
        }
        return new SimpleCloseableIteration<>(nss.iterator());
    }

    // ---------------------------------------------------------------
    // SPARQL evaluation (delegate to RDF4J default — calls back through getStatements)
    // ---------------------------------------------------------------

    // ---- triejoin-routing evaluation support (plans/triejoin-evaluation-wiring.md, Phase 2) ----
    // Package-private accessors for ProllyEvaluationStrategy (same package). triejoinIndexRoot
    // flushes the
    // in-tx buffer (QuadIndex.commit) so the triejoin reads same-transaction writes —
    // read-your-writes
    // (D-5), exactly as getStatementsInternal does before scanning.
    Dictionary triejoinDict() {
        return dictTx;
    }

    DictionaryTermResolver triejoinResolver() {
        return resolverTx;
    }

    com.dolthub.prolly.StaticMap triejoinIndexRoot(QuadOrder order) {
        return Objects.requireNonNull(indexesTx.get(order)).commit();
    }

    /**
     * Whether the routed triejoin should order variables by cardinality (baseline plan); read-time.
     */
    boolean triejoinCardinalityOrder() {
        return sail.triejoinCardinalityOrder();
    }

    @Override
    protected CloseableIteration<? extends BindingSet> evaluateInternal(
            TupleExpr expr, Dataset dataset, BindingSet bindings, boolean includeInferred)
            throws SailException {
        // Wrap the parsed TupleExpr so the optimizer can replace the root if needed.
        TupleExpr root = (expr instanceof QueryRoot) ? expr : new QueryRoot(expr);
        // Clone before optimizing: the pipeline mutates the tree, and a caller
        // may reuse its TupleExpr across evaluate() calls (RDFStoreTest's
        // testQueryBindings does exactly that).
        root = root.clone();
        TripleSource tripleSource =
                new SailConnectionTripleSource(this, sail.getValueFactory(), includeInferred);
        // Read-path opt-in (plans/join-approaches-benchmark.md): memoize the acyclic bind-join's
        // recurring
        // s+p-bound inner re-probe. Per-query, snapshot-immutable, freed with this evaluation.
        if (sail.bindJoinMemoEnabled()) {
            // perEntryCap 4096 (skip a fat fan-out), maxEntries 100k, global budget 256k statements
            // (the hard memory ceiling — productionize-the-cache.md D-3).
            tripleSource =
                    new MemoizingTripleSource(
                            tripleSource, sail.meterRegistry(), 4096, 100_000, 256_000L);
        }
        // Phase 0 seam (plans/triejoin-evaluation-wiring.md): when the flag is on, run through
        // ProllySail's own strategy (cyclic-BGP → WCOJ-triejoin routing); otherwise the shared
        // Prolly default. BOTH extend ProllyDefaultEvaluationStrategy, so this store's
        // conformance corrections (the graph-scoped zero-length-path walk, W3C pp35) apply
        // identically with the flag on or off.
        EvaluationStrategy strategy =
                sail.triejoinEnabled()
                        ? new ProllyEvaluationStrategy(tripleSource, dataset, this)
                        : new ProllyDefaultEvaluationStrategy(tripleSource, dataset, this);
        // Honor the sail-level QueryEvaluationMode exactly as the stock
        // SailSourceConnection does. AbstractSail defaults to STRICT while
        // DefaultEvaluationStrategy's own constructor defaults to STANDARD, so
        // omitting this silently evaluated in the WRONG compliance mode —
        // incomparable-literal comparisons returned false instead of raising
        // the type error SPARQL requires (W3C date-3/open-cmp-01/02 and the
        // CascadeValueException contract, all caught by the 2026-08-25
        // gap-wiring round).
        strategy.setQueryEvaluationMode(sail.getDefaultQueryEvaluationMode());
        try {
            // Run RDF4J's STANDARD optimizer pipeline exactly as the stock
            // SailSourceConnection does (BindingAssigner, ConstantOptimizer,
            // QueryJoinOptimizer, ...). Skipping it did not just cost speed —
            // it evaluated algebra shapes no stock store ever executes raw,
            // and some are latently broken upstream: Join(GRAPH ?g {..},
            // {..} UNION {..}) drops the union's rows unless the join
            // optimizer reorders it (W3C join-combo-1/-2, caught by the
            // 2026-08-25 gap-wiring round; the reordered plan every other
            // RDF4J store runs is correct). Uniform-cost statistics: this
            // store keeps no cardinality estimates yet.
            root =
                    strategy.optimize(
                            root,
                            new org.eclipse.rdf4j.query.algebra.evaluation.impl
                                    .EvaluationStatistics(),
                            bindings);
            return strategy.evaluate(root, bindings);
        } catch (org.eclipse.rdf4j.query.QueryEvaluationException e) {
            throw new SailException(e);
        }
    }

    // ---------------------------------------------------------------
    // Iteration helpers
    // ---------------------------------------------------------------

    private final class SpocStatementIteration implements CloseableIteration<Statement> {
        private final Iterator<SpocKey> inner;
        private final QuadRole role;
        private boolean closed;

        SpocStatementIteration(Iterator<SpocKey> inner, QuadOrder order) {
            this.inner = inner;
            this.role = order.role();
        }

        @Override
        public boolean hasNext() {
            return !closed && inner.hasNext();
        }

        @Override
        public Statement next() {
            if (closed) throw new NoSuchElementException();
            SpocKey k = inner.next();
            return new ProllyStatement(k, role, resolverTx, TermId.ZERO);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class SimpleCloseableIteration<E> implements CloseableIteration<E> {
        private final Iterator<E> inner;
        private boolean closed;

        SimpleCloseableIteration(Iterator<E> inner) {
            this.inner = inner;
        }

        @Override
        public boolean hasNext() {
            return !closed && inner.hasNext();
        }

        @Override
        public E next() {
            if (closed) throw new NoSuchElementException();
            return inner.next();
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
