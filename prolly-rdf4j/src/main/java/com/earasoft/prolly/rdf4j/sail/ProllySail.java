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
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.rdf4j.index.ProvenanceIndex;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.EncoderMetrics;
import com.earasoft.prolly.rdf4j.term.HashFunction;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.value.ProllyValueFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.eclipse.rdf4j.common.transaction.IsolationLevel;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.AbstractNotifyingSail;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Versioned RDF4J Sail backed by prolly trees — the production read/write surface for RDF data
 * stored as content-addressed trees.
 *
 * <p>The Sail holds only the <em>committed</em> {@link StaticMap} root for each persistent table
 * (the dictionary root, the four index roots, the namespaces root, the term-stats root). A
 * connection forks those roots into per-transaction tables at {@code startTransaction}; a
 * successful commit advances the Sail's roots to the new ones; rollback drops the per-transaction
 * tables without touching Sail state. Because a root is an immutable hash, swapping it is how a
 * write becomes visible — a reader that captured an earlier root keeps seeing a consistent
 * snapshot.
 *
 * <p><b>Concurrency — why single-writer.</b> A {@link ProllySailConnection} acquires the Sail's
 * write lock at {@code startTransaction} and holds it through commit/rollback, so at most one write
 * transaction runs at a time; readers never take it. This is <em>not</em> a storage limitation but
 * a property of the data model. Two concurrent writers would each fork from the same {@code
 * dictRoot} and intern the <em>same</em> new term to <em>different</em> {@code TermId}s — neither
 * sees the other's uncommitted dictionary — then advance the single root pointer last-writer-wins,
 * silently dropping the loser's terms and corrupting the term-to-id mapping; the same hazard
 * applies to each index root. The lock serializes writers so each forks from the previous one's
 * <em>committed</em> roots. Multi-writer with a compare-and-set rebase (the merge engine already
 * exists) is future work. <b>Reader snapshot:</b> the four core roots ({@code dictRoot}, the index
 * roots, {@code namespacesRoot}, {@code statsRoot}) are published as ONE immutable {@link Snapshot}
 * ({@link #publishedSnapshot}); a connection's constructor forks that single reference lock-free,
 * so a read opened concurrently with a commit sees a whole consistent snapshot, never a torn mix of
 * two commits' roots (the publication-race fix — {@code
 * prolly-rdf4j/plans/prollysail-root-publication-race.md}; the provenance / event-sink sidecar
 * roots remain a smaller, off-by-default residual). {@link PrefixTable} is deliberately kept at the
 * Sail level (not per-connection): prefix-id allocation is rare and bootstrap-dominated, so
 * isolating it would cost more complexity than it buys; the {@link ProllyValueFactory} is bound to
 * that same Sail-level prefix table.
 *
 * @apiNote Build it over a {@link NodeStore} (in-memory or RocksDB-backed) and obtain a {@link
 *     SailConnection} per unit of work. Optional collaborators switch on behavior: a {@code
 *     RootMetaTreeStore} makes the roots survive restart (init reads it, commit writes it), and a
 *     {@code CommitLog} records a timestamped hash line per commit to drive the time-travel /
 *     Memento endpoints. It extends {@link AbstractNotifyingSail} so streaming-update progress
 *     listeners can attach.
 * @implNote <b>Collaborators:</b> {@link NodeStore} + {@link BufferPool} (chunk storage / scratch),
 *     {@link PrefixTable} + {@link ProllyValueFactory} (Sail-level IRI prefix interning and value
 *     construction), {@link HashFunction}, {@link SailMetrics} (observability), {@code
 *     RootMetaTreeStore} (durable root pointer), and {@code CommitLog} (the time-travel chain).
 *     <b>Dependents:</b> {@link ProllySailConnection} (every read/write runs through it) and the
 *     REST layer that wraps one Sail per repository.
 */
public class ProllySail extends AbstractNotifyingSail implements VersionedSail {

    private static final Logger LOG = LoggerFactory.getLogger(ProllySail.class);

    // Pre-built schemas for each table — used by auto-restore to rehydrate StaticMaps from raw
    // chunks.
    private static final TupleDescriptor SCHEMA_INT64 =
            new TupleDescriptor(java.util.List.of(new Type(Encoding.Int64, false)));
    private static final TupleDescriptor SCHEMA_INT32 =
            new TupleDescriptor(java.util.List.of(new Type(Encoding.Int32, false)));
    private static final TupleDescriptor SCHEMA_STRING =
            new TupleDescriptor(java.util.List.of(new Type(Encoding.String, false)));

    private final NodeStore store;
    private final BufferPool pool;
    private final HashFunction hashFn;
    private final PrefixTable prefixes;
    private final ProllyValueFactory valueFactory;
    private final MeterRegistry registry;

    /** Optional auto-restore pointer. When non-null, init reads it; commit writes it. */
    private final @Nullable RootMetaTreeStore rootMetaTreeStore;

    /**
     * Optional append-only commit chain. When non-null, every successful commit appends a {@code
     * <RFC 1123 datetime> <hex hash>} line. Drives the Memento-Datetime header and the
     * /sparql/timemap endpoint.
     */
    private final @Nullable CommitLog commitLog;

    /**
     * Optional named-branch refs. When non-null, every commit also advances {@code
     * refs/<currentBranch>} to point at the new RootMetaTree hash. Drives the /branches endpoint.
     */
    private final @Nullable RefsStore refsStore;

    /** Immutable named tags, beside {@link #refsStore} (null on a snapshot Sail). See ADR-0047. */
    private final @Nullable TagStore tagStore;

    /**
     * The branch name commits land on. v2.0 single-writer fixes this to {@code main} at
     * construction; iter 40 will add switching for snapshot reads against historical branches.
     */
    private volatile String currentBranch = RefsStore.DEFAULT_BRANCH;

    /**
     * Last persisted commit info — non-null after the first commit. Volatile so HTTP threads see
     * updates.
     */
    private volatile byte @Nullable [] currentCommitHash;

    /**
     * The head commit <b>id</b> (ADR-0071) — distinct from {@link #currentCommitHash} (the head's
     * <em>tree</em> hash). The id is the stable handle written to refs and used by the commit graph
     * and sync; the tree hash opens the data. They were the same value before the id model (D-6 of
     * {@code commit-identity-redesign.md}).
     */
    private volatile byte @Nullable [] currentCommitId;

    private volatile java.time.@Nullable Instant currentCommitInstant;

    /**
     * One-shot: when set, the very next {@code persistMetaTreeIfConfigured()} attaches this as a
     * second parent on the new commit-log entry, then clears the flag. Used by {@link MergeEngine}
     * to record two-parent merge commits without disturbing the RDF4J commit lifecycle.
     */
    private volatile byte @Nullable [] nextCommitMergeParent;

    /**
     * One-shot: when set, the very next {@code persistMetaTreeIfConfigured()} writes this as the
     * human-readable commit message and then clears the flag. Set via {@link
     * #setNextCommitMessage(String)} by the SPARQL update controller before calling {@code
     * conn.commit()}. {@code null} → empty message.
     */
    private volatile @Nullable String nextCommitMessage;

    /**
     * One-shot: when set, the very next {@code persistMetaTreeIfConfigured()} writes this as the
     * commit author and then clears the flag. Set via {@link #setNextCommitAuthor(String)} by an
     * in-process caller (e.g. the BOM store) or the SPARQL controller from the auth context before
     * {@code conn.commit()}. {@code null} → empty author.
     */
    private volatile @Nullable String nextCommitAuthor;

    /**
     * One-shot: provenance fold for merge commits. When set, the very next commit folds {@code
     * source}'s provenance entries into the in-flight provIdxTx using older-wins. Cleared by {@code
     * persistMetaTreeIfConfigured} regardless of outcome.
     */
    private volatile com.dolthub.prolly.@Nullable StaticMap nextProvenanceFoldSource;

    private volatile java.util.function.@Nullable BiPredicate<byte[], byte[]>
            nextProvenanceFoldPredicate;

    // -- Sail-level committed roots. Volatile = each commit advances atomically vs reads. --
    /** null = empty tree (no commits yet). */
    private volatile @Nullable StaticMap dictRoot;

    // @Nullable values: null = empty tree for that permutation (no commits yet) — same convention
    // as dictRoot. Mutated under the write lock; published via the Snapshot.
    private final Map<QuadOrder, @Nullable StaticMap> indexRoots = new EnumMap<>(QuadOrder.class);
    private volatile @Nullable StaticMap namespacesRoot;
    private volatile @Nullable StaticMap statsRoot;

    /**
     * The four core roots above ({@code dictRoot}, {@code indexRoots}, {@code namespacesRoot},
     * {@code statsRoot}) republished as ONE immutable {@link Snapshot} behind a single volatile
     * reference. A connection forks from this in one atomic read, so a connection opened
     * concurrently with a commit never sees a torn mix of two commits' roots: the writer advances
     * the individual fields one at a time, then {@link #publishSnapshot()} stores the complete new
     * snapshot with a single store. The individual fields stay the writer's working state; this is
     * the reader's consistent view. (The provenance / event-sink sidecar roots are deliberately NOT
     * in this snapshot — they are off by default and audit-only, a smaller residual noted in {@code
     * prolly-rdf4j/plans/prollysail-root-publication-race.md}.) Republished under the write lock
     * after every core-root advance, and at init/restore.
     */
    private volatile @Nullable Snapshot publishedSnapshot;

    /**
     * Optional sidecar — root of the {@link ProvenanceIndex} tree mapping each quad to the parent
     * commit hash at its first appearance. {@code null} when provenance is disabled (the default).
     * See {@link com.earasoft.prolly.rdf4j.index.ProvenanceIndex} + ADR-0001.
     */
    private volatile @Nullable StaticMap provenanceRoot;

    /** Opt-in flag from the constructor — see {@link #provenanceEnabled()}. */
    private final boolean provenanceEnabled;

    /**
     * Read-time opt-in: route eligible cyclic / multi-way BGPs through the WCOJ {@code
     * LeapfrogTriejoin} in SPARQL evaluation ({@code
     * prolly-rdf4j/plans/triejoin-evaluation-wiring.md}). Default {@code false} (RDF4J bind-join).
     * A {@code volatile} field + setter rather than a constructor flag because — unlike {@link
     * #provenanceEnabled} / {@link #eventSinkEnabled}, which fix commit/storage semantics at
     * construction — this only affects <i>read-time</i> query routing, so it is safe to toggle and
     * keeps the change off every {@code ProllySail} ctor call site. Phase 0 of the wiring plan: the
     * seam is inert while {@code false}.
     */
    private volatile boolean triejoinEnabled = false;

    /**
     * Read-time opt-in: when the triejoin routes a cyclic BGP, order its variables by the
     * cardinality-aware {@link com.earasoft.prolly.semantic.SelectivityVariableOrder} instead of
     * the provisional first-appearance order ({@code
     * plans/prepublic/sparql-baseline-cardinality-aware.md}). Answer-invariant — ordering changes
     * only cost, never the result multiset; the Phase-0 gate measured it never regresses and cuts
     * {@code seekWork} up to ~32× (growing with N) on selective cyclic queries. Same {@code
     * volatile}-field-plus-setter rationale as {@link #triejoinEnabled}: a read-time-only knob,
     * safe to toggle, kept off every ctor call site. {@code false} (first-appearance) until the
     * baseline plan's Step 4 flips the operator-property default.
     */
    private volatile boolean triejoinCardinalityOrder = false;

    /**
     * Read-time opt-in: memoize the acyclic bind-join's recurring inner re-probe ({@code
     * prolly-rdf4j/plans/join-approaches-benchmark.md}). Default from the system property {@code
     * prolly.rdf4j.bind-join-memo} ({@code false} when unset → re-probe every outer binding); the
     * property lets a whole test run (e.g. the SPARQL suite) flip it on for correctness coverage.
     * Same {@code volatile}-field-plus-setter rationale as {@link #triejoinEnabled}: a
     * read-time-only knob, safe to toggle, kept off every ctor call site. When on, {@link
     * ProllySailConnection#evaluateInternal} wraps the per-query {@link SailConnectionTripleSource}
     * in a {@link MemoizingTripleSource}.
     */
    private volatile boolean bindJoinMemoEnabled =
            Boolean.getBoolean("prolly.rdf4j.bind-join-memo");

    /**
     * Read-time opt-in: max entries in the per-connection {@code TermId}→{@code ProllyValue} decode
     * cache ({@code prolly.rdf4j.term-cache-size}, Step 3 of {@code
     * prolly-rdf4j/plans/read-path-cache-and-zerocopy.md}). {@code 0} (default) = off; the {@link
     * com.earasoft.prolly.rdf4j.value.DictionaryTermResolver} built at transaction begin reads this
     * to size its cache. Same {@code volatile}-field-plus-setter rationale as {@link
     * #triejoinEnabled} / {@link #bindJoinMemoEnabled}: a read-time-only knob, safe to toggle, kept
     * off every {@code ProllySail} ctor call site. The cache memoizes hot-term decodes (e.g. a
     * join's repeated predicate / superclass ids), skipping the dictionary tree-walk + value wrap
     * that the node cache alone leaves on the CPU; correct without invalidation because {@code
     * TermId} is content-addressed (D-3). Measure-gated (D-1/D-8) — inert until a paired A/B
     * justifies a non-zero default.
     */
    private volatile int termCacheSize = 0;

    /**
     * Optional pluggable mutation-event sink (ADR-0003 event log + future extensions). When
     * non-null AND {@link #eventSinkEnabled} is true, every insert/delete records an entry into the
     * sink's per-tx instance, and the sail persists the sink's tree-root hash in {@link
     * RootMetaTree} under {@link
     * com.earasoft.prolly.rdf4j.sail.spi.MutationEventSinkFactory#rootMetaTreeName()}. The OSS core
     * ships with no factory; binding one (typically {@code prolly-rdf4j-enterprise}'s {@code
     * EventLogIndexSinkFactory}) is a packaging-level choice.
     */
    private final com.earasoft.prolly.rdf4j.sail.spi.@Nullable MutationEventSinkFactory
            eventSinkFactory;

    /**
     * Runtime opt-in flag. Independent of {@link #eventSinkFactory} so ops can disable the feature
     * even when the enterprise jar is on the classpath (e.g., to temporarily quiesce the event-log
     * write path under load). {@code true} requires a bound factory to have any effect.
     */
    private final boolean eventSinkEnabled;

    /** Current committed root for {@link #eventSinkFactory}'s tree, or {@code null} when none. */
    private volatile @Nullable StaticMap eventSinkRoot;

    /**
     * Sail-wide write lock — enforces the v2.0 single-writer guarantee. Held by {@link
     * ProllySailConnection} for the duration of a write transaction (begin → commit/rollback). Two
     * concurrent {@code /sparql/update} requests serialize on this; without it, the second writer
     * forks from a stale dictRoot and last-writer-wins silently drops the first's TermIds (#143).
     * When Phase 4 CAS-rebase lands, this lock can become a read-mostly latch with retry on
     * conflict.
     *
     * <p>A {@link java.util.concurrent.Semaphore}, not a {@code ReentrantLock}: a write transaction
     * may {@code begin} on one thread and {@code commit}/{@code rollback}/{@code close} on another
     * — notably {@code Sail.shutDown()} closes tracked connections from the shutdown thread. A
     * {@code ReentrantLock} cannot be unlocked by a non-owner, so the lock leaked on every
     * cross-thread close and subsequent writers parked forever (caught by {@code
     * SailConcurrencyTest}). A semaphore permit is not thread-owned and can be handed off. Fair, so
     * blocked writers are served in arrival order.
     */
    private final java.util.concurrent.Semaphore writeLock =
            new java.util.concurrent.Semaphore(1, true);

    /**
     * Acquire the write lock — called by ProllySailConnection.startTransactionInternal.
     *
     * <p>Interruptible on purpose: RDF4J's {@code AbstractSailConnection} aborts a connection that
     * is blocked by a concurrent operation by <em>interrupting</em> the thread running that
     * operation. A thread parked here on a slow writer must therefore honour interruption — an
     * uninterruptible acquire makes the blocked {@code begin} un-abortable and {@code
     * close()}/{@code shutDown()} then loops forever waiting for it.
     */
    /**
     * Process-wide count of writers currently blocked acquiring the single-writer lock — the
     * contention signal surfaced as the {@code prolly.write.lock.waiting} gauge (bound at the rest
     * layer; static so it aggregates across every Sail without per-Sail meter duplication).
     */
    private static final java.util.concurrent.atomic.AtomicInteger WRITERS_WAITING =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Writers currently blocked on the write lock (telemetry for {@code
     * prolly.write.lock.waiting}).
     */
    public static int writersWaiting() {
        return WRITERS_WAITING.get();
    }

    void acquireWriteLock() {
        WRITERS_WAITING.incrementAndGet();
        long t0 = System.nanoTime();
        try {
            writeLock.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SailException("interrupted while acquiring the write lock", e);
        } finally {
            WRITERS_WAITING.decrementAndGet();
            // Wait time to acquire the single-writer lock — ~0 uncontended; a high p95 means
            // writers queue.
            registry.timer("prolly.write.lock.wait")
                    .record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Release the write lock. The caller ({@code ProllySailConnection}) must invoke this exactly
     * once per {@link #acquireWriteLock}, guarded by its own {@code writeLockHeld} flag — releasing
     * a permit that was never acquired would raise the permit count above one and let two writers
     * run concurrently.
     */
    void releaseWriteLock() {
        writeLock.release();
    }

    /**
     * Test hook (plan 11, Phase B): write-lock permits currently available — {@code 1} when free,
     * {@code 0} when a writer holds it. Lets concurrency tests assert the lock is fully released
     * after any begin/commit/rollback/ close/shutdown sequence (a permit count of 1 = no leak).
     */
    int writeLockAvailablePermits() {
        return writeLock.availablePermits();
    }

    /** Convenience constructor using in-memory storage. Suitable for tests. */
    public ProllySail() {
        this(new InMemoryNodeStore(), new HeapBufferPool());
    }

    public ProllySail(NodeStore store, BufferPool pool) {
        this(store, pool, new CompositeMeterRegistry());
    }

    public ProllySail(NodeStore store, BufferPool pool, MeterRegistry registry) {
        this(store, pool, registry, null);
    }

    // -- Metric-less overloads: default to an empty CompositeMeterRegistry so
    //    callers that previously passed SailMetrics.noop() simply drop the arg. --

    public ProllySail(
            NodeStore store, BufferPool pool, @Nullable RootMetaTreeStore rootMetaTreeStore) {
        this(store, pool, new CompositeMeterRegistry(), rootMetaTreeStore);
    }

    public ProllySail(
            NodeStore store,
            BufferPool pool,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog) {
        this(store, pool, new CompositeMeterRegistry(), rootMetaTreeStore, commitLog);
    }

    public ProllySail(
            NodeStore store,
            BufferPool pool,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog,
            @Nullable RefsStore refsStore) {
        this(store, pool, new CompositeMeterRegistry(), rootMetaTreeStore, commitLog, refsStore);
    }

    public ProllySail(
            NodeStore store,
            BufferPool pool,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog,
            @Nullable RefsStore refsStore,
            boolean provenanceEnabled) {
        this(
                store,
                pool,
                new CompositeMeterRegistry(),
                rootMetaTreeStore,
                commitLog,
                refsStore,
                provenanceEnabled);
    }

    public ProllySail(
            NodeStore store,
            BufferPool pool,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog,
            @Nullable RefsStore refsStore,
            boolean provenanceEnabled,
            boolean eventSinkEnabled,
            com.earasoft.prolly.rdf4j.sail.spi.@Nullable MutationEventSinkFactory
                    eventSinkFactory) {
        this(
                store,
                pool,
                new CompositeMeterRegistry(),
                rootMetaTreeStore,
                commitLog,
                refsStore,
                provenanceEnabled,
                eventSinkEnabled,
                eventSinkFactory);
    }

    /**
     * Full ctor with auto-restore via a {@link RootMetaTreeStore}. When the rmt store is absent
     * (in-memory mode), the CommitLog falls back to an in-memory log so JVM-local history queries
     * still resolve (#127).
     */
    public ProllySail(
            NodeStore store,
            BufferPool pool,
            MeterRegistry registry,
            @Nullable RootMetaTreeStore rootMetaTreeStore) {
        this(
                store,
                pool,
                registry,
                rootMetaTreeStore,
                rootMetaTreeStore == null
                        ? CommitLog.inMemory()
                        // the meta-head file always lives under <storeDir>, so its parent is
                        // non-null.
                        : CommitLog.beside(
                                Objects.requireNonNull(rootMetaTreeStore.file().getParent())));
    }

    /**
     * Full ctor with an explicit commit log. In-memory mode falls back to in-memory refs so
     * /sparql/branches works without a configured store dir.
     */
    public ProllySail(
            NodeStore store,
            BufferPool pool,
            MeterRegistry registry,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog) {
        this(
                store,
                pool,
                registry,
                rootMetaTreeStore,
                commitLog,
                rootMetaTreeStore == null
                        ? RefsStore.inMemory()
                        // the meta-head file always lives under <storeDir>, so its parent is
                        // non-null.
                        : RefsStore.beside(
                                Objects.requireNonNull(rootMetaTreeStore.file().getParent())));
    }

    /**
     * Full ctor with explicit commit log and refs store. Tests use this to point at a temp
     * directory; production callers rely on the RootMetaTreeStore-derived defaults from the simpler
     * ctors.
     *
     * <p>Provenance is <em>opt-in</em> via the 7-arg ctor below; this 6-arg variant always disables
     * it for backward compatibility with the bulk of existing tests + the auto-config default.
     */
    public ProllySail(
            NodeStore store,
            BufferPool pool,
            MeterRegistry registry,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog,
            @Nullable RefsStore refsStore) {
        this(store, pool, registry, rootMetaTreeStore, commitLog, refsStore, false);
    }

    /**
     * Full ctor with the provenance opt-in flag. When {@code provenanceEnabled} is true, every
     * {@code addStatement} also records the parent commit hash into a sidecar {@link
     * ProvenanceIndex} (see ADR-0001) and the RootMetaTree gains a {@code provenance} entry on each
     * commit. Disabled Sails produce RootMetaTrees byte-identical to a Dolt Go port reading the
     * same data.
     */
    public ProllySail(
            NodeStore store,
            BufferPool pool,
            MeterRegistry registry,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog,
            @Nullable RefsStore refsStore,
            boolean provenanceEnabled) {
        this(
                store,
                pool,
                registry,
                rootMetaTreeStore,
                commitLog,
                refsStore,
                provenanceEnabled,
                false,
                null);
    }

    /**
     * 9-arg ctor — accepts an event-sink enable flag and an optional factory. ADR-0003 event log
     * lives in {@code prolly-rdf4j-enterprise} and binds through {@code eventSinkFactory}. The sink
     * is active iff BOTH {@code eventSinkEnabled} is true AND {@code eventSinkFactory} is non-null
     * — giving ops a runtime kill-switch independent of the classpath-level binding. The OSS
     * distribution passes {@code (false, null)}. Independent from {@code provenanceEnabled}.
     */
    public ProllySail(
            NodeStore store,
            BufferPool pool,
            MeterRegistry registry,
            @Nullable RootMetaTreeStore rootMetaTreeStore,
            @Nullable CommitLog commitLog,
            @Nullable RefsStore refsStore,
            boolean provenanceEnabled,
            boolean eventSinkEnabled,
            com.earasoft.prolly.rdf4j.sail.spi.@Nullable MutationEventSinkFactory
                    eventSinkFactory) {
        this.store = store;
        this.pool = pool;
        this.registry = registry;
        this.rootMetaTreeStore = rootMetaTreeStore;
        this.commitLog = commitLog;
        // ADR-0073: a file-backed commit log persists thin "<datetime> <id>" rows and reconstructs
        // each commit's content from its chunk in this store — so it needs the store attached.
        if (commitLog != null) {
            commitLog.attachStore(store);
        }
        this.refsStore = refsStore;
        // Tags live beside refs (<storeDir>/tags/), derived from the refs store so no
        // constructor churn. A snapshot Sail (null refs) has no tags; an in-memory refs
        // store gets an in-memory tag store. See ADR-0047.
        if (refsStore == null) {
            this.tagStore = null;
        } else {
            java.nio.file.Path refsDir = refsStore.dir();
            this.tagStore =
                    refsDir == null
                            ? TagStore.inMemory()
                            // refs/ always lives under <storeDir>, so its parent is non-null.
                            : TagStore.beside(Objects.requireNonNull(refsDir.getParent()));
        }
        this.provenanceEnabled = provenanceEnabled;
        this.eventSinkEnabled = eventSinkEnabled;
        this.eventSinkFactory = eventSinkFactory;
        this.hashFn = HashFunctions.defaultHash();
        this.prefixes = new PrefixTable(store, pool);
        this.valueFactory = new ProllyValueFactory(prefixes);
        // Roots start null (empty trees); first commit replaces them.
        for (QuadOrder order : QuadOrder.values()) {
            indexRoots.put(order, null);
        }
        publishSnapshot(); // initial (all-null) snapshot so an early fork reads a valid reference
    }

    @Override
    protected void initializeInternal() throws SailException {
        super.initializeInternal();
        LOG.info(
                "ProllySail init: store={}, rootMetaTreeStore={}",
                store.getClass().getSimpleName(),
                rootMetaTreeStore == null ? "<none — in-memory only>" : "configured");
        if (rootMetaTreeStore != null) {
            try {
                java.util.Optional<byte[]> head = rootMetaTreeStore.get();
                java.util.Optional<RootMetaTree> mt = rootMetaTreeStore.load(store);
                if (mt.isPresent()) {
                    LOG.info(
                            "ProllySail restoring from RootMetaTree with {} entries",
                            mt.get().entries().size());
                    restoreFromMetaTree(mt.get());
                    head.ifPresent(h -> currentCommitHash = h); // the TREE hash (D-6)
                    if (commitLog != null && head.isPresent()) {
                        // Post-ADR-0071: recover the head's id + wall-clock from the latest log
                        // entry, which is the commit rootMetaTreeStore points at (append + put
                        // happen together on commit, so latest().metaTreeHash() == head).
                        java.util.Optional<CommitLog.Entry> match = commitLog.latest();
                        match.ifPresent(
                                e -> {
                                    currentCommitId = e.id();
                                    currentCommitInstant = e.timestamp();
                                });
                    }
                } else {
                    LOG.info("ProllySail starting fresh — no RootMetaTree pointer found");
                }
            } catch (java.io.IOException e) {
                LOG.error("ProllySail failed to load RootMetaTree", e);
                throw new SailException("failed to load RootMetaTree", e);
            }
        }
        // Re-publish after any restore so the first connection forks a consistent snapshot.
        publishSnapshot();
    }

    /**
     * The isolation levels this Sail advertises — an <b>honest</b>, fixed contract (pinned by
     * {@code ProllySailIsolationLevelHonestyTest}, test-strategy Step 14). The runtime does <b>not
     * branch on the requested level</b>: every transaction forks an immutable {@link Snapshot}
     * (snapshot isolation), and writers serialize through the single-writer lock, so the Sail in
     * fact delivers <b>serializable-grade</b> isolation whatever level is asked for. It therefore
     * advertises the whole standard ladder (a weaker request is satisfied by delivering more), and
     * {@link #getDefaultIsolationLevel()} is the snapshot level a connection actually observes —
     * which, unlike the inherited {@code AbstractSail} default ({@code [READ_UNCOMMITTED,
     * SERIALIZABLE]} with a default of {@code READ_COMMITTED} that was not even a member of the
     * set), is consistent: the default is in the supported set. See {@code
     * newcomer-docs/foundations/the-concurrency-model.md}.
     */
    private static final List<IsolationLevel> SUPPORTED_ISOLATION =
            List.of(
                    IsolationLevels.READ_UNCOMMITTED,
                    IsolationLevels.READ_COMMITTED,
                    IsolationLevels.SNAPSHOT_READ,
                    IsolationLevels.SNAPSHOT,
                    IsolationLevels.SERIALIZABLE);

    @Override
    public List<IsolationLevel> getSupportedIsolationLevels() {
        return SUPPORTED_ISOLATION;
    }

    /**
     * Snapshot isolation — the level a connection actually observes via the immutable-root fork.
     * The runtime is level-independent, so this is an honest user-facing label, not a behaviour
     * switch.
     */
    @Override
    public IsolationLevel getDefaultIsolationLevel() {
        return IsolationLevels.SNAPSHOT;
    }

    /** Restore Sail roots from a RootMetaTree found at init time. */
    private void restoreFromMetaTree(RootMetaTree mt) {
        mt.hashOf(RootMetaTree.NAME_DICT)
                .ifPresent(
                        h -> {
                            dictRoot = loadStaticMap(h, SCHEMA_INT64);
                            logRestored(RootMetaTree.NAME_DICT, h);
                        });
        mt.hashOf(RootMetaTree.NAME_SPOC)
                .ifPresent(
                        h -> {
                            indexRoots.put(QuadOrder.SPOC, loadStaticMap(h, SpocKey.DESCRIPTOR));
                            logRestored(RootMetaTree.NAME_SPOC, h);
                        });
        mt.hashOf(RootMetaTree.NAME_POSC)
                .ifPresent(
                        h -> {
                            indexRoots.put(QuadOrder.POSC, loadStaticMap(h, SpocKey.DESCRIPTOR));
                            logRestored(RootMetaTree.NAME_POSC, h);
                        });
        mt.hashOf(RootMetaTree.NAME_OSPC)
                .ifPresent(
                        h -> {
                            indexRoots.put(QuadOrder.OSPC, loadStaticMap(h, SpocKey.DESCRIPTOR));
                            logRestored(RootMetaTree.NAME_OSPC, h);
                        });
        mt.hashOf(RootMetaTree.NAME_CSPO)
                .ifPresent(
                        h -> {
                            indexRoots.put(QuadOrder.CSPO, loadStaticMap(h, SpocKey.DESCRIPTOR));
                            logRestored(RootMetaTree.NAME_CSPO, h);
                        });
        mt.hashOf(RootMetaTree.NAME_NAMESPACES)
                .ifPresent(
                        h -> {
                            namespacesRoot = loadStaticMap(h, SCHEMA_STRING);
                            logRestored(RootMetaTree.NAME_NAMESPACES, h);
                        });
        mt.hashOf(RootMetaTree.NAME_STATS)
                .ifPresent(
                        h -> {
                            statsRoot = loadStaticMap(h, SCHEMA_INT64);
                            logRestored(RootMetaTree.NAME_STATS, h);
                        });
        // Provenance is opt-in: only restore if the RootMetaTree carries the entry.
        // SpocKey.DESCRIPTOR matches the schema ProvenanceIndex used at write time.
        mt.hashOf(RootMetaTree.NAME_PROVENANCE)
                .ifPresent(
                        h -> {
                            provenanceRoot =
                                    loadStaticMap(
                                            h, com.earasoft.prolly.rdf4j.index.SpocKey.DESCRIPTOR);
                            logRestored(RootMetaTree.NAME_PROVENANCE, h);
                        });
        // Mutation-event sink (e.g., ADR-0003 event log via prolly-rdf4j-enterprise) —
        // restore whenever a factory is bound, even if the runtime flag is off, so
        // turning the flag back on doesn't lose prior history.
        if (eventSinkFactory != null) {
            mt.hashOf(eventSinkFactory.rootMetaTreeName())
                    .ifPresent(
                            h -> {
                                eventSinkRoot = loadStaticMap(h, eventSinkFactory.schema());
                                logRestored(eventSinkFactory.rootMetaTreeName(), h);
                            });
        }
        // Publish the restored core roots atomically — this covers BOTH the init-time restore and
        // openSnapshotAt(), which restores directly and bypasses initializeInternal. Without it a
        // snapshot Sail would fork an all-null (empty) snapshot and read nothing.
        publishSnapshot();
    }

    private static void logRestored(String tableName, byte[] hash) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("ProllySail restored table '{}' from chunk {}", tableName, hashPrefix(hash));
        }
    }

    /**
     * Short hash prefix for log readability — full hex via DEBUG/TRACE on caller side if needed.
     */
    private static String hashPrefix(byte[] hash) {
        if (hash == null) return "<null>";
        String hex = com.dolthub.prolly.HashUtils.toHex(hash);
        return hex.length() > 12 ? hex.substring(0, 12) + "…" : hex;
    }

    private StaticMap loadStaticMap(byte[] hash, TupleDescriptor schema) {
        java.lang.foreign.MemorySegment chunk =
                store.read(hash)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "RootMetaTree references missing chunk: "
                                                        + com.dolthub.prolly.HashUtils.toHex(
                                                                hash)));
        Node root = Node.fromBytes(chunk);
        return new StaticMap(store, root, schema);
    }

    /**
     * Build a {@link RootMetaTree} of the current Sail roots and advance the Sail's commit metadata
     * (currentCommitHash, currentCommitInstant). When a {@link RootMetaTreeStore} is configured,
     * the hash is also durably persisted; when a {@link CommitLog} / {@link RefsStore} are
     * configured they're appended to. Called from {@link ProllySailConnection#commitInternal}.
     *
     * <p>The in-memory mode (no sidecars at all) still runs the in-memory bookkeeping so the
     * controller's no-op detection — which compares pre/post {@code currentCommitHash} — works on
     * every commit lifecycle, not just durable ones.
     */
    void persistMetaTreeIfConfigured() {
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put(RootMetaTree.NAME_DICT, rootHashOrNull(dictRoot));
        entries.put(RootMetaTree.NAME_SPOC, rootHashOrNull(indexRoots.get(QuadOrder.SPOC)));
        entries.put(RootMetaTree.NAME_POSC, rootHashOrNull(indexRoots.get(QuadOrder.POSC)));
        entries.put(RootMetaTree.NAME_OSPC, rootHashOrNull(indexRoots.get(QuadOrder.OSPC)));
        entries.put(RootMetaTree.NAME_CSPO, rootHashOrNull(indexRoots.get(QuadOrder.CSPO)));
        entries.put(RootMetaTree.NAME_NAMESPACES, rootHashOrNull(namespacesRoot));
        entries.put(RootMetaTree.NAME_STATS, rootHashOrNull(statsRoot));
        // Provenance only emitted when enabled — absence of the entry is the
        // marker for "this commit predates provenance" on disk (ADR-0001 §2.2).
        if (provenanceEnabled) {
            entries.put(RootMetaTree.NAME_PROVENANCE, rootHashOrNull(provenanceRoot));
        }
        // Mutation-event sink — emit when the runtime flag is on AND a factory is bound.
        // (We always preserve a previously-written root if the flag flipped off
        // mid-store life, but only the *active* path produces new entries.)
        if (eventSinkEnabled && eventSinkFactory != null) {
            entries.put(eventSinkFactory.rootMetaTreeName(), rootHashOrNull(eventSinkRoot));
        } else if (eventSinkRoot != null && eventSinkFactory != null) {
            // Flag off but a prior root exists — preserve it so toggling back on
            // doesn't lose history. Same idempotent absent-vs-present semantics.
            entries.put(eventSinkFactory.rootMetaTreeName(), rootHashOrNull(eventSinkRoot));
        }
        RootMetaTree mt = new RootMetaTree(entries);
        byte[] mtHash = mt.writeTo(store);

        // Empty-diff guard: a no-op transaction (set-equivalent INSERT DATA
        // on existing triples, DELETE WHERE that matched nothing, etc.) leaves
        // every data-bearing index root unchanged. The stats sub-tree may
        // still bump (it tracks bookkeeping counters that touch on every
        // commit), so a naive "mtHash == currentCommitHash" check misses
        // these no-ops. Instead, compare the entries that actually carry
        // data — dict + four quad orders + namespaces. If all of those
        // match the previous RootMetaTree's, the user's update produced no
        // change and we skip the commit-log append + the currentCommitHash
        // advance entirely. The controller checks the hash to detect "no
        // change" and returns 422.
        if (currentCommitHash != null && isNoOpVsPreviousRMT(entries)) {
            LOG.debug(
                    "ProllySail commit produced no data change vs {} — skipping log append",
                    hashPrefix(currentCommitHash));
            nextCommitMergeParent = null;
            nextCommitMessage = null;
            nextCommitAuthor = null;
            return;
        }

        try {
            // Durably persist the head pointer only when a store is configured.
            // In-memory mode skips this but still proceeds to advance the
            // in-process bookkeeping below so /sparql/update's no-op check
            // gets a real before/after signal.
            if (rootMetaTreeStore != null) {
                rootMetaTreeStore.put(mtHash);
                LOG.debug("ProllySail persisted RootMetaTree at chunk {}", hashPrefix(mtHash));
            }
            // Truncate to seconds — Memento-Datetime is RFC 1123 (second precision), so we
            // keep the in-memory copy aligned with what's serialized to disk and to the wire.
            // Without this, currentCommitInstant() would not equal CommitLog.latest().timestamp().
            java.time.Instant now =
                    java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            // Parents are parent COMMIT IDS (ADR-0071): the previous head id, plus (on a merge) the
            // source-side id set transiently by MergeEngine. Computed outside the commitLog guard
            // so
            // refs + currentCommitId get the id even in no-log mode.
            java.util.List<byte[]> parents;
            if (currentCommitId == null) {
                // Empty local: a merge/pull seed adopts the source head as its SOLE parent so the
                // fast-forward chain stays intact (ADR-0071 D-4). Without this the seed is a
                // parentless genesis — provenance-OFF it content-collapses to the source id (which
                // masked the bug), but provenance-ON its provenance subtree gives it a distinct
                // tree
                // → a distinct id → a disconnected genesis, so a later push sees the source head as
                // a non-ancestor and the FF check rejects every contender (ConcurrentPushRaceTest
                // provenance-on, winners==0). A genuine genesis (no merge in flight) stays
                // parentless.
                parents =
                        nextCommitMergeParent != null
                                ? java.util.List.of(nextCommitMergeParent)
                                : java.util.Collections.emptyList();
            } else if (nextCommitMergeParent != null) {
                parents = java.util.List.of(currentCommitId, nextCommitMergeParent);
            } else {
                parents = java.util.List.of(currentCommitId);
            }
            String message = nextCommitMessage == null ? "" : nextCommitMessage;
            String author = nextCommitAuthor == null ? "" : nextCommitAuthor;
            // The id includes the parents, so a different parent graph yields a different id even
            // for
            // the same tree (the dangling-parent fix).
            CommitObject commitObject = CommitObject.of(mtHash, parents, author, message);
            byte[] commitId = commitObject.id(); // == CommitId.of(mtHash, parents, author, message)
            // ADR-0073 Phase 1: additionally persist the commit as a content-addressed chunk in the
            // NodeStore (additive — the commit-log stays the authoritative record until Phase 2). A
            // commit object's hash IS its id by construction, so store.write returns exactly
            // commitId; assert that invariant loudly rather than trust it silently.
            byte[] commitChunkId = new CommitStore(store).write(commitObject);
            if (!java.util.Arrays.equals(commitChunkId, commitId)) {
                throw new IllegalStateException(
                        "commit chunk address "
                                + hashPrefix(commitChunkId)
                                + " != commit id "
                                + hashPrefix(commitId)
                                + " (CommitObject serialization diverged from CommitId)");
            }
            if (commitLog != null) {
                commitLog.append(now, commitId, mtHash, parents, message, author);
                LOG.debug(
                        "ProllySail appended commit-log entry id={} tree={} parents={} msgLen={}"
                                + " author={}",
                        hashPrefix(commitId),
                        hashPrefix(mtHash),
                        parents.size(),
                        message.length(),
                        author);
            }
            // Clear the one-shots regardless of whether commitLog is configured.
            nextCommitMergeParent = null;
            nextCommitMessage = null;
            nextCommitAuthor = null;
            if (refsStore != null) {
                refsStore.put(currentBranch, commitId);
                LOG.debug(
                        "ProllySail advanced ref refs/{} -> {}",
                        currentBranch,
                        hashPrefix(commitId));
            }
            currentCommitHash = mtHash; // the TREE hash (D-6)
            currentCommitId = commitId; // the commit id (the head handle)
            currentCommitInstant = now;
        } catch (java.io.IOException e) {
            LOG.error("ProllySail failed to persist RootMetaTree pointer", e);
            throw new org.eclipse.rdf4j.sail.SailException(
                    "failed to persist RootMetaTree pointer", e);
        }
    }

    private static byte @Nullable [] rootHashOrNull(@Nullable StaticMap m) {
        if (m == null || m.root() == null) return null;
        return com.dolthub.prolly.HashUtils.hash(m.root().bytes());
    }

    /**
     * Compare the data-bearing entries of two RootMetaTree chunks (dict + 4 quad indexes +
     * namespaces). Returns true iff every data root hash matches — equivalent to "these two commits
     * represent the same RDF dataset, ignoring bookkeeping sidecars like
     * stats/provenance/event-log."
     *
     * <p>Used by staging (#133) to decide whether a snapshot-sail commit produced a real change:
     * the snapshot's {@code currentCommitHash} advances even for idempotent inserts because some
     * sub-table flush (stats, in particular) isn't perfectly hash-stable. Comparing the data
     * entries directly sidesteps that.
     */
    public static boolean isDataTreeNoOp(NodeStore store, byte[] a, byte[] b) {
        if (a == null || b == null) return false;
        if (java.util.Arrays.equals(a, b)) return true;
        java.util.Optional<RootMetaTree> mtA = RootMetaTree.readFrom(store, a);
        java.util.Optional<RootMetaTree> mtB = RootMetaTree.readFrom(store, b);
        if (mtA.isEmpty() || mtB.isEmpty()) return false;
        // "Data" = the triples themselves: dict + 4 quad orders. Deliberately
        // excludes namespaces (SPARQL parser may auto-register prefixes on
        // every UPDATE even for a no-op), stats (bookkeeping), provenance
        // and event-log (derived sidecars).
        String[] dataNames = {
            RootMetaTree.NAME_DICT,
            RootMetaTree.NAME_SPOC,
            RootMetaTree.NAME_POSC,
            RootMetaTree.NAME_OSPC,
            RootMetaTree.NAME_CSPO,
        };
        for (String name : dataNames) {
            byte[] hashA = mtA.get().hashOf(name).orElse(null);
            byte[] hashB = mtB.get().hashOf(name).orElse(null);
            if (!java.util.Arrays.equals(hashA, hashB)) return false;
        }
        return true;
    }

    /**
     * Look at the *currently-advanced* data roots (dict + 4 indexes + namespaces) and decide
     * whether persisting them now would be a no-op vs the previous commit. Called from {@code
     * ProllySailConnection.commitInternal} to gate sidecar commits like the event sink — those need
     * to discard their pending state if the data commit will be skipped, otherwise events orphan
     * without a corresponding entry in /sparql/commits (#126).
     *
     * <p>First commit is never a no-op (no baseline to compare against).
     */
    public boolean wouldBeNoOpCommit() {
        if (currentCommitHash == null) return false;
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put(RootMetaTree.NAME_DICT, rootHashOrNull(dictRoot));
        entries.put(RootMetaTree.NAME_SPOC, rootHashOrNull(indexRoots.get(QuadOrder.SPOC)));
        entries.put(RootMetaTree.NAME_POSC, rootHashOrNull(indexRoots.get(QuadOrder.POSC)));
        entries.put(RootMetaTree.NAME_OSPC, rootHashOrNull(indexRoots.get(QuadOrder.OSPC)));
        entries.put(RootMetaTree.NAME_CSPO, rootHashOrNull(indexRoots.get(QuadOrder.CSPO)));
        entries.put(RootMetaTree.NAME_NAMESPACES, rootHashOrNull(namespacesRoot));
        return isNoOpVsPreviousRMT(entries);
    }

    /**
     * Compare the data-bearing entries of the in-flight RootMetaTree against the previous commit's.
     * Returns true iff dict + every quad-order index + namespaces match — meaning this transaction
     * inserted, deleted, and renamed nothing in user-visible data. Stats and provenance are
     * deliberately excluded: stats is bookkeeping and bumps on every commit, provenance is a
     * derived sidecar (its movement implies real changes upstream, but it's not load-bearing for
     * "did anything change").
     */
    private boolean isNoOpVsPreviousRMT(java.util.Map<String, byte[]> nextEntries) {
        byte[] prevCommit = currentCommitHash;
        if (prevCommit == null)
            return false; // no previous commit → this transaction can't be a no-op
        try {
            java.util.Optional<RootMetaTree> prev = RootMetaTree.readFrom(store, prevCommit);
            if (prev.isEmpty()) return false;
            RootMetaTree p = prev.get();
            String[] dataNames = {
                RootMetaTree.NAME_DICT,
                RootMetaTree.NAME_SPOC,
                RootMetaTree.NAME_POSC,
                RootMetaTree.NAME_OSPC,
                RootMetaTree.NAME_CSPO,
                RootMetaTree.NAME_NAMESPACES,
            };
            for (String name : dataNames) {
                byte[] prevHash = p.hashOf(name).orElse(null);
                byte[] nextHash = nextEntries.get(name);
                if (!java.util.Arrays.equals(prevHash, nextHash)) return false;
            }
            return true;
        } catch (RuntimeException re) {
            LOG.debug("isNoOpVsPreviousRMT: failed to read previous RMT, assuming changed", re);
            return false;
        }
    }

    @Override
    protected void shutDownInternal() throws SailException {
        LOG.info("ProllySail shutdown");
        // Nothing to release explicitly — InMemoryNodeStore is GC-managed,
        // production NodeStores (RocksDB) would close here.
    }

    @Override
    protected org.eclipse.rdf4j.sail.NotifyingSailConnection getConnectionInternal()
            throws SailException {
        return new ProllySailConnection(this);
    }

    @Override
    public ValueFactory getValueFactory() {
        return valueFactory;
    }

    @Override
    public boolean isWritable() {
        return true;
    }

    // -- Accessors used by ProllySailConnection during transaction setup --

    /** Backing NodeStore — public so snapshot opens can share it across Sails. */
    public NodeStore store() {
        return store;
    }

    /** Backing BufferPool — public so snapshot opens can share it. */
    public BufferPool pool() {
        return pool;
    }

    /**
     * The index trees' boundary-function seam (SPOC boundary-function-adoption D-1). Default is the
     * production rolling hash; set BEFORE init and never after (boundaries are format — switching
     * on a live store makes new chunks stop sharing with old ones).
     */
    private volatile com.dolthub.prolly.BoundarySplitter.Factory boundarySplitterFactory =
            com.dolthub.prolly.BoundarySplitter.ROLLING_HASH;

    public com.dolthub.prolly.BoundarySplitter.Factory boundarySplitterFactory() {
        return boundarySplitterFactory;
    }

    /** See {@link #boundarySplitterFactory()} — pre-init configuration only. */
    public void setBoundarySplitterFactory(com.dolthub.prolly.BoundarySplitter.Factory factory) {
        this.boundarySplitterFactory = factory;
    }

    /**
     * Run a chunk collection on this Sail's store while HOLDING the single-writer lock — reads keep
     * flowing (they touch only claimed, never-swept chunks), writes queue behind the collection,
     * and concurrent collections serialize (ADR-0074's quiesce consequence, narrowed to
     * writer-exclusion).
     *
     * @apiNote The {@code contributor} must claim EVERYTHING this store holds live outside the
     *     engine commit graph — for a Sail store that is the whole history ({@code
     *     SailGarbageCollection.collect(sail)} in the sync package is the safe front door that
     *     composes it from this Sail's commit log). An under-claiming contributor deletes live
     *     data. Rocks-backed stores only: an in-memory store has nothing worth reclaiming and the
     *     sweep needs the store's key iterator.
     * @throws UnsupportedOperationException when the backing store is not RocksDB-backed
     */
    public com.earasoft.prolly.GcResult collectGarbage(
            com.earasoft.prolly.gc.GcReachabilityContributor contributor) {
        if (!(store instanceof com.earasoft.prolly.storage.RocksNodeStore rocks)) {
            throw new UnsupportedOperationException(
                    "garbage collection requires a RocksDB-backed store (got "
                            + store.getClass().getSimpleName()
                            + ")");
        }
        acquireWriteLock();
        try {
            return com.earasoft.prolly.GarbageCollector.collectExclusive(
                    rocks, java.util.List.of(contributor));
        } finally {
            releaseWriteLock();
        }
    }

    HashFunction hashFn() {
        return hashFn;
    }

    PrefixTable prefixes() {
        return prefixes;
    }

    ProllyValueFactory valueFactoryInternal() {
        return valueFactory;
    }

    public MeterRegistry meterRegistry() {
        return registry;
    }

    /**
     * Adapter exposing this Sail's {@link MeterRegistry} as the codec-tier {@link EncoderMetrics}
     * seam — each {@code increment(name)} bumps the named Micrometer counter. Threaded into {@link
     * com.earasoft.prolly.rdf4j.term.Dictionary} so collision-chain counters land in the same
     * registry.
     */
    EncoderMetrics encoderMetrics() {
        return name -> registry.counter(name).increment();
    }

    /**
     * Content hash of the most-recently-persisted RootMetaTree — the commit id surfaced to HTTP
     * clients as {@code X-Prolly-Commit-Id}. Returns {@code null} if no commits have happened yet
     * on this Sail, or if the Sail was created without a {@link RootMetaTreeStore} (in which case
     * there are no persisted commits).
     */
    public byte @Nullable [] currentCommitHash() {
        return currentCommitHash;
    }

    /**
     * The current head commit <b>id</b> (ADR-0071), or null before the first commit. Distinct from
     * {@link #currentCommitHash()} (the head's <em>tree</em> hash): the id is the stable handle
     * used by refs, the commit graph, and sync.
     */
    public byte @Nullable [] currentCommitId() {
        return currentCommitId;
    }

    /**
     * Resolve a commit <b>id</b> to its RootMetaTree (tree) hash via the commit log — the bridge
     * for opening a snapshot from a commit handle (ADR-0071). Falls back to the input unchanged
     * when there is no log or no matching entry (the caller already holds a tree hash), so it is
     * safe to call on a value that may already be a tree hash.
     */
    public byte[] treeHashOf(byte[] commitId) {
        if (commitId == null || commitLog == null) return commitId;
        try {
            return commitLog.findById(commitId).map(CommitLog.Entry::metaTreeHash).orElse(commitId);
        } catch (java.io.IOException e) {
            throw new SailException("failed to resolve commit id to tree hash", e);
        }
    }

    /**
     * Wall-clock instant of the most recent commit — backs the {@code Memento-Datetime} response
     * header. Returns {@code null} when {@link #currentCommitHash()} would also be null.
     */
    public java.time.@Nullable Instant currentCommitInstant() {
        return currentCommitInstant;
    }

    /**
     * The append-only commit log for this Sail, when one is configured. Drives the {@code
     * /sparql/timemap} endpoint.
     */
    public java.util.Optional<CommitLog> commitLog() {
        return java.util.Optional.ofNullable(commitLog);
    }

    /**
     * The auto-restore pointer file, when configured. Exposed for {@code ?commit=&lt;hash&gt;}
     * snapshot reads.
     */
    public java.util.Optional<RootMetaTreeStore> rootMetaTreeStore() {
        return java.util.Optional.ofNullable(rootMetaTreeStore);
    }

    /** The branches refs store, when configured. Drives the /branches endpoints. */
    public java.util.Optional<RefsStore> refsStore() {
        return java.util.Optional.ofNullable(refsStore);
    }

    /**
     * The immutable tags store, when configured (null on a snapshot Sail). Drives the gRPC tag
     * verbs (ADR-0047).
     */
    public java.util.Optional<TagStore> tagStore() {
        return java.util.Optional.ofNullable(tagStore);
    }

    /**
     * Time-travel: a read-only Sail at a past commit, sharing this Sail's content-addressed store
     * (ADR-0048 D-4). The caller {@code init()}s and {@code shutDown()}s it.
     *
     * @implNote uses a throwaway {@link CompositeMeterRegistry} — the snapshot is ephemeral, so its
     *     meters must not bind to (and leak references into) this Sail's long-lived registry.
     */
    @Override
    public VersionedSail openSnapshotAt(byte[] commit) {
        return openSnapshotAt(store, pool, new CompositeMeterRegistry(), commit);
    }

    /** The branch name commits land on. v2.0 always {@code "main"}. */
    public String currentBranch() {
        return currentBranch;
    }

    /**
     * Tag the next commit as a merge — its commit-log entry will list this hash as a second parent.
     * Cleared after one commit; set just before the merge-applying RepositoryConnection commits.
     */
    public void setNextCommitMergeParent(byte[] sourceCommitHash) {
        this.nextCommitMergeParent = sourceCommitHash;
    }

    /**
     * ADR-0002 helper — record a commit on a *non-active* branch (typically a staging branch)
     * without disturbing the live sail's state.
     *
     * <p>Appends the entry to {@link #commitLog} with the given parent hash and message, and
     * updates {@code refs/<branchName>} to point at the new commit. Does NOT touch {@link
     * #currentCommitHash}, {@code dictRoot}, any index root, or {@link #rootMetaTreeStore} — the
     * live sail keeps showing main's state. The caller is responsible for having already written
     * {@code mtHash} (and its referenced chunks) to the NodeStore, typically via a snapshot sail
     * commit at the staging branch's HEAD.
     *
     * @throws IllegalStateException if commitLog or refsStore is missing — staging requires both to
     *     be wired (no in-memory dev-mode caveat).
     */
    public byte[] recordBranchCommit(
            String branchName, byte[] mtHash, byte[] parentHash, String message)
            throws java.io.IOException {
        if (commitLog == null) {
            throw new IllegalStateException("recordBranchCommit requires a CommitLog");
        }
        if (refsStore == null) {
            throw new IllegalStateException("recordBranchCommit requires a RefsStore");
        }
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("branchName must not be blank");
        }
        if (mtHash == null) {
            throw new IllegalArgumentException("mtHash must not be null");
        }
        java.time.Instant now =
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        // parentHash is a parent COMMIT ID (ADR-0071); the branch ref points at the new commit id.
        java.util.List<byte[]> parents =
                (parentHash == null || parentHash.length == 0)
                        ? java.util.Collections.emptyList()
                        : java.util.List.of(parentHash);
        String msg = message == null ? "" : message;
        byte[] id = CommitId.of(mtHash, parents, "", msg);
        commitLog.append(now, id, mtHash, parents, msg, "");
        refsStore.put(branchName, id);
        LOG.debug(
                "recordBranchCommit: branch={} id={} tree={} parents={}",
                branchName,
                hashPrefix(id),
                hashPrefix(mtHash),
                parents.size());
        return id;
    }

    /**
     * ADR-0002 helper — atomically point {@code refs/<branchName>} at {@code commitHash} without
     * appending a commit-log entry. Used by:
     *
     * <ul>
     *   <li>squash-merge: reset the staging branch to the new target HEAD after the squash commit
     *       lands on target.
     *   <li>{@code DELETE /sparql/staging}: abandon a draft by resetting the staging branch to the
     *       target's HEAD.
     * </ul>
     *
     * @throws IllegalStateException if refsStore is missing.
     */
    public void resetBranchRef(String branchName, byte[] commitHash) throws java.io.IOException {
        if (refsStore == null) {
            throw new IllegalStateException("resetBranchRef requires a RefsStore");
        }
        if (branchName == null || branchName.isBlank()) {
            throw new IllegalArgumentException("branchName must not be blank");
        }
        if (commitHash == null) {
            throw new IllegalArgumentException("commitHash must not be null");
        }
        refsStore.put(branchName, commitHash);
        LOG.debug("resetBranchRef: branch={} -> {}", branchName, hashPrefix(commitHash));
    }

    /**
     * Build a read-only snapshot Sail against {@code store} at a specific RootMetaTree {@code
     * commitHash}. The returned Sail has no sidecars (RootMetaTreeStore / CommitLog / RefsStore) so
     * writes never escape back into the live state — callers are expected to use it only for
     * queries.
     *
     * <p>The caller is responsible for {@code init()} and {@code shutDown()}. Sharing a NodeStore
     * across multiple Sails is safe because the store is content-addressed and append-only.
     *
     * @throws SailException if the RootMetaTree chunk is not in the store
     */
    public static ProllySail openSnapshotAt(
            NodeStore store, BufferPool pool, MeterRegistry registry, byte[] commitHash) {
        if (commitHash == null) throw new IllegalArgumentException("commitHash must not be null");
        // Snapshot Sail has provenance "enabled" so the restored provenanceRoot is reachable.
        // We don't bind an event-sink factory: snapshots are read-only, and read endpoints
        // construct their own EventLogIndex over the sink-root accessor if needed.
        ProllySail snap =
                new ProllySail(store, pool, registry, null, null, null, true, false, null);
        RootMetaTree mt =
                RootMetaTree.readFrom(store, commitHash)
                        .orElseThrow(
                                () ->
                                        new SailException(
                                                "RootMetaTree chunk not found in store: "
                                                        + com.dolthub.prolly.HashUtils.toHex(
                                                                commitHash)));
        snap.restoreFromMetaTree(mt);
        snap.currentCommitHash = commitHash;
        // Snapshot Sails carry no timestamp — the caller's CommitLog (if any)
        // knows the wall-clock for this hash. The HTTP layer wires it through.
        return snap;
    }

    /** Current committed dictionary root, or {@code null} if no commits yet. */
    public @Nullable StaticMap dictRoot() {
        return dictRoot;
    }

    /** Atomically replace the dict root. Called from connection commit. */
    void advanceDictRoot(@Nullable StaticMap next) {
        this.dictRoot = next;
    }

    /** Committed root for {@code order}'s permutation index, or {@code null} if no commits yet. */
    @Nullable StaticMap indexRoot(QuadOrder order) {
        return indexRoots.get(order);
    }

    void advanceIndexRoot(QuadOrder order, @Nullable StaticMap next) {
        indexRoots.put(order, next);
    }

    @Nullable StaticMap namespacesRoot() {
        return namespacesRoot;
    }

    void advanceNamespacesRoot(@Nullable StaticMap next) {
        this.namespacesRoot = next;
    }

    @Nullable StaticMap statsRoot() {
        return statsRoot;
    }

    void advanceStatsRoot(StaticMap next) {
        this.statsRoot = next;
    }

    /**
     * The atomically-published snapshot of the four core committed roots — the single reference a
     * connection forks (one read), so it never sees a torn mix of two commits' roots. See the
     * {@link #publishedSnapshot} field for the full contract.
     */
    Snapshot publishedSnapshot() {
        // Published unconditionally at init/restore (before any connection can fork it) and after
        // every core-root advance under the write lock — so it is non-null for the whole open life
        // of the Sail. requireNonNull pins that invariant rather than widening the reader contract.
        return Objects.requireNonNull(publishedSnapshot, "publishedSnapshot read before init()");
    }

    /**
     * Capture the four core roots into {@link #publishedSnapshot} with one store. The
     * <b>invariant</b>: call this after <em>every</em> core-root mutation, under the write lock
     * (commit, merge) or at single-threaded init/restore — otherwise a fork would read stale roots.
     * {@link Snapshot}'s constructor takes the defensive copy of {@code indexRoots}.
     */
    void publishSnapshot() {
        publishedSnapshot = new Snapshot(dictRoot, indexRoots, namespacesRoot, statsRoot);
    }

    /** Opt-in flag — set at construction time, see ADR-0001. */
    public boolean provenanceEnabled() {
        return provenanceEnabled;
    }

    /**
     * Read-time opt-in: route cyclic/multi-way BGPs through the WCOJ triejoin (default off →
     * bind-join). See {@link #triejoinEnabled} + {@code plans/triejoin-evaluation-wiring.md}.
     */
    public boolean triejoinEnabled() {
        return triejoinEnabled;
    }

    /** Toggle WCOJ-triejoin SPARQL routing (read-time; safe to set post-construction). */
    public void setTriejoinEnabled(boolean enabled) {
        this.triejoinEnabled = enabled;
    }

    /**
     * Read-time opt-in: order the routed triejoin's variables by cardinality ({@link
     * com.earasoft.prolly.semantic.SelectivityVariableOrder}) instead of first-appearance (default
     * off). See {@link #triejoinCardinalityOrder} + {@code
     * plans/prepublic/sparql-baseline-cardinality-aware.md}.
     */
    public boolean triejoinCardinalityOrder() {
        return triejoinCardinalityOrder;
    }

    /** Toggle cardinality-aware triejoin variable ordering (read-time; safe post-construction). */
    public void setTriejoinCardinalityOrder(boolean enabled) {
        this.triejoinCardinalityOrder = enabled;
    }

    /**
     * Read-time opt-in: memoize the acyclic bind-join's recurring s+p-bound inner re-probe (default
     * off). See {@link #bindJoinMemoEnabled} + {@code plans/join-approaches-benchmark.md}.
     */
    public boolean bindJoinMemoEnabled() {
        return bindJoinMemoEnabled;
    }

    /** Toggle the bind-join inner-re-probe memo (read-time; safe to set post-construction). */
    public void setBindJoinMemoEnabled(boolean enabled) {
        this.bindJoinMemoEnabled = enabled;
    }

    /** Decode-cache size (max entries); {@code 0} = off. See {@link #termCacheSize} (field). */
    public int termCacheSize() {
        return termCacheSize;
    }

    /** Set the per-connection decode-cache size (read-time; safe to set post-construction). */
    public void setTermCacheSize(int size) {
        this.termCacheSize = size;
    }

    /** Current committed provenance root, or {@code null} if disabled or no commits yet. */
    @Nullable StaticMap provenanceRoot() {
        return provenanceRoot;
    }

    /** Atomically replace the provenance root. Called from connection commit. */
    void advanceProvenanceRoot(StaticMap next) {
        this.provenanceRoot = next;
    }

    /** Bound mutation-event sink factory, or {@code null} when unbound (OSS default). */
    public com.earasoft.prolly.rdf4j.sail.spi.@Nullable MutationEventSinkFactory
            eventSinkFactory() {
        return eventSinkFactory;
    }

    /**
     * True iff the runtime flag is on AND a factory is bound — the only state in which
     * inserts/deletes actually drive the sink. Restore + persist paths use the factory presence on
     * its own; only the *write* path checks this composite.
     */
    public boolean eventSinkActive() {
        return eventSinkEnabled && eventSinkFactory != null;
    }

    /** Operator runtime opt-in flag, independent of the factory binding. */
    public boolean eventSinkEnabled() {
        return eventSinkEnabled;
    }

    /** Current committed root for the bound sink, or {@code null} if none/disabled. */
    public @Nullable StaticMap eventSinkRoot() {
        return eventSinkRoot;
    }

    /** Atomically replace the sink root. Called from connection commit. */
    void advanceEventSinkRoot(StaticMap next) {
        this.eventSinkRoot = next;
    }

    /**
     * Admin-tier swap-in of the event-sink root — used by EL.6 compaction (ADR-0005 drain-to-cold).
     * The replacement isn't tied to a data commit, so it doesn't go through {@link
     * ProllySailConnection}; the caller is responsible for ensuring the new root is logically
     * consistent (e.g., it contains the same SpocKey/commit entries the old root did, minus those
     * moved to cold storage).
     *
     * <p>Does NOT persist to the RootMetaTree on its own — callers either trigger a sail commit
     * afterward, or accept that the in-memory swap is JVM-lifetime-only.
     */
    public void replaceEventSinkRoot(StaticMap next) {
        this.eventSinkRoot = next;
    }

    /**
     * Pre-flight a commit message for the very next commit on this Sail.
     *
     * <p>One-shot: cleared by {@link #persistMetaTreeIfConfigured()} regardless of whether the
     * commit succeeds. Callers (typically {@code SparqlController.update}) set this after running
     * the SPARQL update body but before invoking {@code conn.commit()}.
     *
     * <p>{@code null} or empty messages are written as the absence of an {@code m=} token on the
     * commit-log line — pre-iter-X logs (no messages) stay byte-identical.
     */
    public void setNextCommitMessage(String message) {
        this.nextCommitMessage = message;
    }

    /**
     * Pre-flight a commit author for the very next commit on this Sail.
     *
     * <p>One-shot: cleared by {@link #persistMetaTreeIfConfigured()} regardless of whether the
     * commit succeeds — symmetric with {@link #setNextCommitMessage(String)}. Lets an in-process
     * caller (e.g. {@code ProllySailBomStore}) attribute a commit without going through the HTTP
     * auth context.
     *
     * <p>{@code null} or empty authors are written as the absence of an {@code a=} token on the
     * commit-log line — commits written before the author seam stay byte-identical.
     */
    public void setNextCommitAuthor(String author) {
        this.nextCommitAuthor = author;
    }

    /**
     * One-shot for merge commits: fold {@code sourceProvenanceRoot}'s entries into the in-flight
     * provIdxTx before the next commit, using {@code parentOlder} to decide overrides ({@code
     * parentOlder.test(otherParent, thisParent)} returns true iff other's parent commit is older
     * than this's). Cleared after the next commit regardless of outcome.
     *
     * <p>F.6 correctness fix — without this fold, a merge with overlapping provenance entries
     * produces a non-deterministic result depending on insertion order into provIdxTx.
     */
    public void setNextCommitProvenanceFold(
            com.dolthub.prolly.StaticMap sourceProvenanceRoot,
            java.util.function.BiPredicate<byte[], byte[]> parentOlder) {
        this.nextProvenanceFoldSource = sourceProvenanceRoot;
        this.nextProvenanceFoldPredicate = parentOlder;
    }

    /** Internal — read + clear the one-shot fold. Called from the connection. */
    com.dolthub.prolly.@Nullable StaticMap takeNextProvenanceFoldSource() {
        com.dolthub.prolly.StaticMap v = nextProvenanceFoldSource;
        nextProvenanceFoldSource = null;
        return v;
    }

    /** Internal — read + clear the one-shot fold predicate. */
    java.util.function.@Nullable BiPredicate<byte[], byte[]> takeNextProvenanceFoldPredicate() {
        java.util.function.BiPredicate<byte[], byte[]> v = nextProvenanceFoldPredicate;
        nextProvenanceFoldPredicate = null;
        return v;
    }

    /**
     * Snapshot-mode read-only provenance lookup. Resolves the (s, p, o) triple against the current
     * committed dictionary, looks up the SpocKey in the provenance index, then walks the commit log
     * forward to find the commit that introduced this triple.
     *
     * <p>Result semantics:
     *
     * <ul>
     *   <li>{@code Optional.empty()} → provenance disabled, or one of the terms isn't known in the
     *       dictionary, or the triple has no provenance record (older commits predating opt-in).
     *   <li>Present → {@link ProvenanceLookup#firstSeenAt()} holds the commit that introduced the
     *       triple. {@code parentCommit} is the predecessor at first-seen time; for genesis triples
     *       it's {@code GENESIS_PARENT} (empty byte[]).
     * </ul>
     */
    public java.util.Optional<ProvenanceLookup> lookupProvenance(
            org.eclipse.rdf4j.model.Value s,
            org.eclipse.rdf4j.model.Value p,
            org.eclipse.rdf4j.model.Value o) {
        if (!provenanceEnabled || provenanceRoot == null || dictRoot == null) {
            return java.util.Optional.empty();
        }
        java.util.Optional<com.earasoft.prolly.rdf4j.term.TermId> sId, pId, oId;
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared()) {
            com.earasoft.prolly.rdf4j.term.Dictionary dict =
                    new com.earasoft.prolly.rdf4j.term.Dictionary(
                            store,
                            pool,
                            com.earasoft.prolly.rdf4j.term.HashFunctions.defaultHash(),
                            dictRoot);
            sId = dict.findTermId(com.earasoft.prolly.rdf4j.term.TermEncoder.encode(s, arena));
            pId = dict.findTermId(com.earasoft.prolly.rdf4j.term.TermEncoder.encode(p, arena));
            // o may be a custom-datatype literal — look it up custom-aware (its datatype IRI is
            // resolved,
            // not interned; absent ⇒ empty ⇒ the triple isn't present). ADR-0043 DTYPE-2.
            oId = com.earasoft.prolly.rdf4j.value.DictionaryTermEncoder.findTermId(o, dict, arena);
        }
        if (sId.isEmpty() || pId.isEmpty() || oId.isEmpty()) {
            return java.util.Optional.empty();
        }
        com.earasoft.prolly.rdf4j.index.ProvenanceIndex idx =
                new com.earasoft.prolly.rdf4j.index.ProvenanceIndex(store, pool, provenanceRoot);
        com.earasoft.prolly.rdf4j.index.SpocKey key =
                new com.earasoft.prolly.rdf4j.index.SpocKey(
                        sId.get(),
                        pId.get(),
                        oId.get(),
                        com.earasoft.prolly.rdf4j.term.TermId.ZERO);
        java.util.Optional<com.earasoft.prolly.rdf4j.index.ProvenanceIndex.Entry> entry =
                idx.firstSeenEntry(key);
        if (entry.isEmpty()) return java.util.Optional.empty();
        byte[] parentHash = entry.get().parent();
        byte[] entryRepoId = entry.get().repoId();
        // The provenance record stores the PARENT commit at first-seen time;
        // the actual "first seen at" commit is the one whose parents list
        // contains that parent hash. Walk the commit log forward to find it.
        if (commitLog == null) return java.util.Optional.empty();
        try {
            for (CommitLog.Entry e : commitLog.entries()) {
                if (parentHash.length == 0) {
                    // GENESIS_PARENT sentinel: the introducing commit is genesis itself.
                    if (e.parents().isEmpty()) {
                        return java.util.Optional.of(
                                new ProvenanceLookup(parentHash, entryRepoId, e));
                    }
                } else {
                    for (byte[] p2 : e.parents()) {
                        if (java.util.Arrays.equals(p2, parentHash)) {
                            return java.util.Optional.of(
                                    new ProvenanceLookup(parentHash, entryRepoId, e));
                        }
                    }
                }
            }
        } catch (java.io.IOException ioe) {
            LOG.warn("lookupProvenance: failed to read commit log", ioe);
        }
        return java.util.Optional.empty();
    }

    /** Result of {@link #lookupProvenance}. */
    public record ProvenanceLookup(
            byte[] parentCommit, byte[] repoId, CommitLog.Entry firstSeenAt) {}

    /**
     * ADR-0001 §9 axis 5 — stable per-repo identifier used to scope provenance entries under CAS.
     * Returns the genesis commit hash (the metaTreeHash of the first entry in the commit log) when
     * one exists, else {@code byte[0]} (the "unscoped" sentinel) for stores that haven't committed
     * anything yet. Cached after first computation.
     */
    public byte[] repoId() {
        byte[] cached = cachedRepoId;
        if (cached != null) return cached;
        if (commitLog == null) return cachedRepoId = new byte[0];
        try {
            java.util.List<CommitLog.Entry> entries = commitLog.entries();
            if (entries.isEmpty())
                return new byte[0]; // don't cache — may change once a commit lands
            cachedRepoId = entries.get(0).metaTreeHash();
            return cachedRepoId;
        } catch (java.io.IOException e) {
            LOG.warn("repoId(): failed to read commit log", e);
            return new byte[0];
        }
    }

    private volatile byte @Nullable [] cachedRepoId;

    /**
     * Iter F.7 — backfill the provenance index by walking history from genesis. For each commit,
     * diff against its parent to find the triples introduced at that commit and record their
     * first-seen-at parent in a fresh ProvenanceIndex. After processing every commit the rebuilt
     * root is committed and a new "Rebuild provenance" commit is appended to the log so the
     * operator can see exactly when the backfill ran.
     *
     * <p>Idempotent at the operator level: re-running produces the same recorded data
     * (deterministic — same parent for each triple's first-seen) but bumps another commit. Cheap
     * (O(triples)) but proportional to total store size; intended as a one-off admin call.
     *
     * @return summary of work done — commits walked, entries written.
     * @throws IllegalStateException if provenance is disabled, the store has no commits, or
     *     required wiring (commit log / refs / store) is missing.
     */
    public RebuildProvenanceResult rebuildProvenance() {
        if (!provenanceEnabled) {
            throw new IllegalStateException(
                    "Provenance is disabled — start the server with "
                            + "--prolly.rdf4j.provenance-enabled=true before invoking rebuild");
        }
        if (commitLog == null || rootMetaTreeStore == null || refsStore == null) {
            throw new IllegalStateException(
                    "rebuildProvenance requires commitLog + rootMetaTreeStore + refsStore");
        }

        java.util.List<CommitLog.Entry> entries;
        try {
            entries = commitLog.entries();
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException("Failed to read commit log", ioe);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("No commits to walk");
        }

        com.earasoft.prolly.rdf4j.index.ProvenanceIndex rebuilt =
                new com.earasoft.prolly.rdf4j.index.ProvenanceIndex(store, pool);
        com.earasoft.prolly.rdf4j.term.Dictionary headDict =
                new com.earasoft.prolly.rdf4j.term.Dictionary(
                        store,
                        pool,
                        com.earasoft.prolly.rdf4j.term.HashFunctions.defaultHash(),
                        // entries is non-empty (checked above), so the store has committed —
                        // dictRoot is non-null (null only before the first commit).
                        Objects.requireNonNull(
                                dictRoot, "dictRoot null despite a non-empty commit log"));

        int entriesAdded = 0;
        int commitsProcessed = 0;
        for (CommitLog.Entry e : entries) {
            byte[] parentHash =
                    e.parents().isEmpty()
                            ? com.earasoft.prolly.rdf4j.index.ProvenanceIndex.GENESIS_PARENT
                            : e.parents().get(0);
            entriesAdded += diffAndRecord(rebuilt, headDict, e, parentHash);
            commitsProcessed++;
        }
        LOG.info(
                "rebuildProvenance: walked {} commits, recorded {} entries",
                commitsProcessed,
                entriesAdded);

        // Commit the rebuilt index → new root, then bake into a new RootMetaTree
        // at HEAD and append a commit-log entry so the rebuild is auditable.
        com.dolthub.prolly.StaticMap newProvRoot = rebuilt.commit();
        this.provenanceRoot = newProvRoot;

        java.util.Map<String, byte[]> mtEntries = new java.util.LinkedHashMap<>();
        mtEntries.put(RootMetaTree.NAME_DICT, rootHashOrNull(dictRoot));
        mtEntries.put(RootMetaTree.NAME_SPOC, rootHashOrNull(indexRoots.get(QuadOrder.SPOC)));
        mtEntries.put(RootMetaTree.NAME_POSC, rootHashOrNull(indexRoots.get(QuadOrder.POSC)));
        mtEntries.put(RootMetaTree.NAME_OSPC, rootHashOrNull(indexRoots.get(QuadOrder.OSPC)));
        mtEntries.put(RootMetaTree.NAME_CSPO, rootHashOrNull(indexRoots.get(QuadOrder.CSPO)));
        mtEntries.put(RootMetaTree.NAME_NAMESPACES, rootHashOrNull(namespacesRoot));
        mtEntries.put(RootMetaTree.NAME_STATS, rootHashOrNull(statsRoot));
        mtEntries.put(RootMetaTree.NAME_PROVENANCE, rootHashOrNull(provenanceRoot));
        RootMetaTree newMt = new RootMetaTree(mtEntries);
        byte[] newMtHash = newMt.writeTo(store);

        try {
            rootMetaTreeStore.put(newMtHash);
            java.time.Instant now =
                    java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            String message =
                    "Rebuild provenance index for "
                            + commitsProcessed
                            + " commit"
                            + (commitsProcessed == 1 ? "" : "s")
                            + " ("
                            + entriesAdded
                            + " entr"
                            + (entriesAdded == 1 ? "y" : "ies")
                            + " recorded)";
            java.util.List<byte[]> rebuildParents =
                    currentCommitId == null
                            ? java.util.Collections.emptyList()
                            : java.util.List.of(currentCommitId);
            byte[] rebuildId = CommitId.of(newMtHash, rebuildParents, "", message);
            commitLog.append(now, rebuildId, newMtHash, rebuildParents, message, "");
            refsStore.put(currentBranch, rebuildId);
            currentCommitHash = newMtHash;
            currentCommitId = rebuildId;
            currentCommitInstant = now;
        } catch (java.io.IOException ioe) {
            throw new IllegalStateException("Failed to persist rebuild commit", ioe);
        }

        return new RebuildProvenanceResult(commitsProcessed, entriesAdded, HashUtilsHex(newMtHash));
    }

    /** Hex helper — keeps {@link RebuildProvenanceResult} package-clean. */
    private static String HashUtilsHex(byte[] hash) {
        return com.dolthub.prolly.HashUtils.toHex(hash);
    }

    /**
     * Snapshot-diff helper for the rebuild: open the commit + its parent, compute (commit \
     * parent), and call {@code rebuilt.recordFirstSeen} for each new triple with {@code parentHash}
     * as the recorded parent.
     */
    private int diffAndRecord(
            com.earasoft.prolly.rdf4j.index.ProvenanceIndex rebuilt,
            com.earasoft.prolly.rdf4j.term.Dictionary headDict,
            CommitLog.Entry e,
            byte[] parentHash) {
        java.util.Set<String> parentTriples =
                e.parents().isEmpty()
                        ? java.util.Collections.emptySet()
                        : tripleKeysAt(treeHashOf(e.parents().get(0)));
        java.util.Set<TripleSPOC> commitTriples = tripleSpocsAt(e.metaTreeHash());
        int added = 0;
        for (TripleSPOC t : commitTriples) {
            if (parentTriples.contains(t.key())) continue;
            // Encode each Value via TermEncoder against the HEAD dict.
            java.util.Optional<com.earasoft.prolly.rdf4j.term.TermId> sId;
            java.util.Optional<com.earasoft.prolly.rdf4j.term.TermId> pId;
            java.util.Optional<com.earasoft.prolly.rdf4j.term.TermId> oId;
            try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared()) {
                sId =
                        headDict.findTermId(
                                com.earasoft.prolly.rdf4j.term.TermEncoder.encode(t.s, arena));
                pId =
                        headDict.findTermId(
                                com.earasoft.prolly.rdf4j.term.TermEncoder.encode(t.p, arena));
                // t.o may be a custom-datatype literal — look it up custom-aware (ADR-0043
                // DTYPE-2).
                oId =
                        com.earasoft.prolly.rdf4j.value.DictionaryTermEncoder.findTermId(
                                t.o, headDict, arena);
            }
            if (sId.isEmpty() || pId.isEmpty() || oId.isEmpty()) continue;
            rebuilt.recordFirstSeen(
                    new com.earasoft.prolly.rdf4j.index.SpocKey(
                            sId.get(),
                            pId.get(),
                            oId.get(),
                            com.earasoft.prolly.rdf4j.term.TermId.ZERO),
                    parentHash,
                    repoId());
            added++;
        }
        return added;
    }

    /** Open a snapshot Sail at {@code commitHash} and return canonical (s,p,o) triples. */
    private java.util.Set<TripleSPOC> tripleSpocsAt(byte[] commitHash) {
        ProllySail snap = openSnapshotAt(store, pool, new CompositeMeterRegistry(), commitHash);
        org.eclipse.rdf4j.repository.sail.SailRepository repo =
                new org.eclipse.rdf4j.repository.sail.SailRepository(snap);
        repo.init();
        try {
            java.util.HashSet<TripleSPOC> out = new java.util.HashSet<>();
            try (org.eclipse.rdf4j.repository.RepositoryConnection conn = repo.getConnection();
                    var it = conn.getStatements(null, null, null, false)) {
                while (it.hasNext()) {
                    org.eclipse.rdf4j.model.Statement st = it.next();
                    out.add(new TripleSPOC(st.getSubject(), st.getPredicate(), st.getObject()));
                }
            }
            return out;
        } finally {
            repo.shutDown();
        }
    }

    /** Cheaper version of {@link #tripleSpocsAt} that returns only the string keys. */
    private java.util.Set<String> tripleKeysAt(byte[] commitHash) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (TripleSPOC t : tripleSpocsAt(commitHash)) out.add(t.key());
        return out;
    }

    /** Pair of (RDF4J value triple, canonical string key) used by the rebuild. */
    private record TripleSPOC(
            org.eclipse.rdf4j.model.Value s,
            org.eclipse.rdf4j.model.Value p,
            org.eclipse.rdf4j.model.Value o) {
        String key() {
            return s.stringValue() + "" + p.stringValue() + "" + o.stringValue();
        }
    }

    /**
     * Summary returned from {@link #rebuildProvenance}. {@code newCommit} is the hash of the
     * synthetic "Rebuild provenance" commit appended at HEAD.
     */
    public record RebuildProvenanceResult(
            int commitsProcessed, int entriesAdded, String newCommit) {}
}
