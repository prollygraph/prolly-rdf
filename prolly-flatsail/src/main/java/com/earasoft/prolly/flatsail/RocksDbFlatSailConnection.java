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
package com.earasoft.prolly.flatsail;

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.QuadRole;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Namespace;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleNamespace;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.optimizer.BindingAssignerOptimizer;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.AbstractSailConnection;
import org.jspecify.annotations.Nullable;
import org.rocksdb.AbstractWriteBatch;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.rocksdb.WriteBatchWithIndex;
import org.rocksdb.WriteOptions;

/**
 * Connection to a {@link RocksDbFlatSail}.
 *
 * <p>A transaction buffers mutations into a RocksDB {@link WriteBatchWithIndex}; {@code commit()}
 * applies it atomically (write-ahead log on), {@code rollback()} discards it. Because the batch is
 * <em>indexed</em>, every read in this connection — {@code getStatements}, {@code size}, {@code
 * getContextIDs}, namespaces, the dictionary lookups — is served by merging the open transaction's
 * uncommitted writes over committed RocksDB state, so a connection sees its own writes
 * (read-your-writes). One residual gap: the all-graphs {@code clear()} uses a RocksDB range delete,
 * which {@code WriteBatchWithIndex} does not reflect in its in-batch index — a {@code clear()} is
 * visible only after commit.
 *
 * <p>Writes are serialized by the Sail's single-writer gate, acquired lazily on the transaction's
 * first mutation; reads never take it.
 *
 * @apiNote Obtained from {@link RocksDbFlatSail}; not safe to share across threads. A read pattern
 *     is answered by encoding the bound columns into a key prefix (via {@link FlatKeyCodec}) and
 *     range-scanning the matching permutation's column family, merging the open write batch over
 *     committed state. The one read-your-writes gap is the all-graphs {@code clear()} noted above.
 * @implNote <b>Collaborators:</b> {@link RocksDbFlatSail} (owner + single-writer gate), {@link
 *     RocksFlatStore} (the column families), {@link FlatDictionary} (term-to-id interning), {@link
 *     FlatKeyCodec} (key encode/decode), {@link QuadOrder} / {@link SpocKey} / {@link TermId} (the
 *     index-key model), a RocksDB {@link WriteBatchWithIndex} (the buffered transaction), and
 *     RDF4J's {@link EvaluationStrategy} for query algebra. <b>Dependents:</b> RDF4J's query and
 *     update machinery, which drives every read and write through this connection.
 */
public class RocksDbFlatSailConnection extends AbstractSailConnection {

    /** Index keys are key-only; the value column is unused. */
    private static final byte[] EMPTY_VALUE = new byte[0];

    /** Default read options, shared — immutable in use, so safe across threads. */
    private static final ReadOptions READ_OPTIONS = new ReadOptions();

    private final RocksDbFlatSail sail;
    private final RocksFlatStore store;
    private final FlatDictionary dictionary;

    /** Open transaction's indexed write buffer, or {@code null} when no transaction is active. */
    private @Nullable WriteBatchWithIndex txBatch;

    /**
     * Per-transaction dictionary cache so a term recurring in one batch keeps one id; {@code null}
     * when no transaction is active (set in lockstep with {@link #txBatch}).
     */
    private @Nullable Map<ByteBuffer, TermId> dictPending;

    /** True while this connection holds the Sail's single-writer gate. */
    private boolean holdsWriteLock;

    RocksDbFlatSailConnection(RocksDbFlatSail sail) {
        super(sail);
        this.sail = sail;
        this.store = sail.store();
        this.dictionary = sail.dictionary();
    }

    @Override
    protected void closeInternal() throws SailException {
        discardTransaction(); // defensive — a well-behaved caller commits/rolls back first
    }

    // ---- transactions ---------------------------------------------------

    @Override
    protected void startTransactionInternal() throws SailException {
        // overwriteKey=true so the in-batch index resolves a re-written key to
        // its latest value — required for correct merged reads.
        txBatch = new WriteBatchWithIndex(true);
        dictPending = new HashMap<>();
    }

    @Override
    protected void commitInternal() throws SailException {
        WriteBatchWithIndex batch = requireTransaction();
        try (WriteOptions options = new WriteOptions()) {
            store.db().write(options, batch); // atomic, WAL on
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail commit failed", e);
        } finally {
            discardTransaction();
        }
    }

    @Override
    protected void rollbackInternal() throws SailException {
        discardTransaction(); // drop the buffered batch unapplied
    }

    private WriteBatchWithIndex requireTransaction() throws SailException {
        if (txBatch == null) {
            throw new SailException("no active transaction — call begin() first");
        }
        return txBatch;
    }

    private void discardTransaction() {
        if (txBatch != null) {
            txBatch.close();
            txBatch = null;
        }
        dictPending = null;
        releaseWriteLock();
    }

    /**
     * Acquire the Sail's single-writer gate on the transaction's first mutation; a no-op on later
     * mutations of the same transaction. Readers never call this, so reads stay fully concurrent.
     */
    private void acquireWriteLock() throws SailException {
        if (!holdsWriteLock) {
            try {
                sail.writeLock().acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SailException("interrupted while acquiring the write lock", e);
            }
            holdsWriteLock = true;
        }
    }

    /** Release the single-writer gate at commit / rollback / close. */
    private void releaseWriteLock() {
        if (holdsWriteLock) {
            holdsWriteLock = false;
            sail.writeLock().release();
        }
    }

    // ---- transaction-aware reads ----------------------------------------

    /**
     * An iterator over {@code cf}. While a transaction is open it merges the transaction's
     * uncommitted writes over committed state (read-your-writes); otherwise it is a plain
     * committed-state iterator.
     */
    private RocksIterator indexIterator(ColumnFamilyHandle cf) {
        RocksIterator base = store.db().newIterator(cf);
        return (txBatch != null) ? txBatch.newIteratorWithBase(cf, base) : base;
    }

    /** A point read of {@code cf}, merging an open transaction's writes if any. */
    private byte[] pointGet(ColumnFamilyHandle cf, byte[] key) throws RocksDBException {
        return (txBatch != null)
                ? txBatch.getFromBatchAndDB(store.db(), cf, READ_OPTIONS, key)
                : store.db().get(cf, key);
    }

    // ---- add / remove ---------------------------------------------------

    @Override
    protected void addStatementInternal(Resource subj, IRI pred, Value obj, Resource... contexts)
            throws SailException {
        rejectTripleContexts(contexts);
        acquireWriteLock();
        WriteBatchWithIndex batch = requireTransaction();
        // dictPending is set in lockstep with txBatch, so requireTransaction() implies it is
        // present.
        Map<ByteBuffer, TermId> pending = Objects.requireNonNull(dictPending);
        TermId s = dictionary.intern(subj, batch, pending);
        TermId p = dictionary.intern(pred, batch, pending);
        TermId o = dictionary.intern(obj, batch, pending);
        try {
            // An empty contexts array means the default graph (null context).
            if (contexts.length == 0) {
                writeQuad(batch, s, p, o, TermId.ZERO);
            } else {
                for (Resource context : contexts) {
                    TermId c =
                            (context == null)
                                    ? TermId.ZERO
                                    : dictionary.intern(context, batch, pending);
                    writeQuad(batch, s, p, o, c);
                }
            }
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail addStatement failed", e);
        }
    }

    /** Write the quad's key into all four permutation indexes. */
    private void writeQuad(AbstractWriteBatch batch, TermId s, TermId p, TermId o, TermId c)
            throws RocksDBException {
        for (QuadOrder order : QuadOrder.values()) {
            batch.put(store.index(order), FlatKeyCodec.encode(order, s, p, o, c), EMPTY_VALUE);
        }
    }

    /** Delete the quad's key from all four permutation indexes. */
    private void deleteQuad(AbstractWriteBatch batch, TermId s, TermId p, TermId o, TermId c)
            throws RocksDBException {
        for (QuadOrder order : QuadOrder.values()) {
            batch.delete(store.index(order), FlatKeyCodec.encode(order, s, p, o, c));
        }
    }

    @Override
    protected void removeStatementsInternal(
            @Nullable Resource subj, @Nullable IRI pred, @Nullable Value obj, Resource... contexts)
            throws SailException {
        rejectTripleContexts(contexts);
        acquireWriteLock();
        WriteBatchWithIndex batch = requireTransaction();

        // Resolve each bound term. A bound term absent from the dictionary
        // means nothing can match — there is nothing to remove.
        TermId s = resolveOrNull(subj);
        if (subj != null && s == null) {
            return;
        }
        TermId p = resolveOrNull(pred);
        if (pred != null && p == null) {
            return;
        }
        TermId o = resolveOrNull(obj);
        if (obj != null && o == null) {
            return;
        }

        // An empty contexts array is a context wildcard; otherwise restrict to
        // the resolvable contexts (a non-null context absent from the dict
        // contributes no match).
        Set<TermId> contextFilter = null;
        if (contexts.length > 0) {
            contextFilter = new HashSet<>();
            for (Resource context : contexts) {
                if (context == null) {
                    contextFilter.add(TermId.ZERO);
                } else {
                    TermId c = resolveOrNull(context);
                    if (c != null) {
                        contextFilter.add(c);
                    }
                }
            }
            if (contextFilter.isEmpty()) {
                return;
            }
        }

        try {
            if (s != null && p != null && o != null && contextFilter != null) {
                // Fully bound: every (s,p,o,context) quad is determined — delete
                // directly, no scan. Deleting an absent key is a harmless no-op.
                for (TermId c : contextFilter) {
                    deleteQuad(batch, s, p, o, c);
                }
            } else {
                scanSpocAndDelete(batch, s, p, o, contextFilter);
            }
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail removeStatement failed", e);
        }
    }

    /**
     * Scan the SPOC index for quads matching the (nullable) term filters and delete each match from
     * all four indexes. The SPOC key decodes directly to logical (s, p, o, c) order. The scan
     * merges the open transaction's own pending writes, so a remove-by-pattern sees
     * same-transaction adds.
     */
    private void scanSpocAndDelete(
            AbstractWriteBatch batch,
            @Nullable TermId s,
            @Nullable TermId p,
            @Nullable TermId o,
            @Nullable Set<TermId> contextFilter)
            throws RocksDBException {
        try (RocksIterator it = indexIterator(store.index(QuadOrder.SPOC))) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                SpocKey quad = FlatKeyCodec.decode(it.key());
                if (s != null && !s.equals(quad.col0())) {
                    continue;
                }
                if (p != null && !p.equals(quad.col1())) {
                    continue;
                }
                if (o != null && !o.equals(quad.col2())) {
                    continue;
                }
                if (contextFilter != null && !contextFilter.contains(quad.col3())) {
                    continue;
                }
                deleteQuad(batch, quad.col0(), quad.col1(), quad.col2(), quad.col3());
            }
        }
    }

    /**
     * Reject an RDF-star {@link Triple} used as a graph name — the flat Sail (like RDF4J's
     * contract) does not allow a quoted triple as a context.
     */
    private static void rejectTripleContexts(Resource... contexts) throws SailException {
        for (Resource context : contexts) {
            if (context instanceof Triple) {
                throw new SailException("context argument can not be of type Triple: " + context);
            }
        }
    }

    private @Nullable TermId resolveOrNull(@Nullable Value value) {
        if (value == null) {
            return null;
        }
        return dictionary.find(value, txBatch).orElse(null);
    }

    // ---- query evaluation -----------------------------------------------

    @Override
    protected CloseableIteration<? extends BindingSet> evaluateInternal(
            TupleExpr tupleExpr, Dataset dataset, BindingSet bindings, boolean includeInferred)
            throws SailException {
        // No SPARQL pushdown: RDF4J's DefaultEvaluationStrategy evaluates the
        // query over a TripleSource that simply delegates to getStatements().
        TupleExpr root = (tupleExpr instanceof QueryRoot) ? tupleExpr : new QueryRoot(tupleExpr);
        // Inline any pre-set bindings into the algebra (RDF4J's BindingAssigner) — without it a
        // binding on
        // a variable that appears ONLY in a FILTER (not the basic graph pattern) is dropped, so the
        // filter
        // sees it unbound and the query yields 0 rows. RDF4J's SailSourceConnection inlines via the
        // optimizer before evaluate; we do the targeted inline. Clone first: the optimizer mutates
        // the tree
        // and a caller may reuse its TupleExpr across evaluate() calls
        // (RDFStoreTest.testQueryBindings).
        // Guarded on non-empty bindings, so the common empty-bindings path is unchanged. (Same fix
        // as
        // ProllySailConnection; prolly-rdf4j-test-strategy-followons Step 4.)
        if (!bindings.isEmpty()) {
            root = root.clone();
            new BindingAssignerOptimizer().optimize(root, dataset, bindings);
        }
        TripleSource tripleSource =
                new TripleSource() {
                    @Override
                    public CloseableIteration<? extends Statement> getStatements(
                            Resource subj, IRI pred, Value obj, Resource... contexts) {
                        return RocksDbFlatSailConnection.this.getStatements(
                                subj, pred, obj, includeInferred, contexts);
                    }

                    @Override
                    public ValueFactory getValueFactory() {
                        return sail.getValueFactory();
                    }
                };
        EvaluationStrategy strategy = new DefaultEvaluationStrategy(tripleSource, dataset, null);
        try {
            return strategy.evaluate(root, bindings);
        } catch (QueryEvaluationException e) {
            throw new SailException(e);
        }
    }

    @Override
    protected CloseableIteration<? extends Statement> getStatementsInternal(
            Resource subj, IRI pred, Value obj, boolean includeInferred, Resource... contexts)
            throws SailException {
        // A bound term absent from the dictionary cannot match anything.
        TermId s = resolveOrNull(subj);
        if (subj != null && s == null) {
            return emptyStatements();
        }
        TermId p = resolveOrNull(pred);
        if (pred != null && p == null) {
            return emptyStatements();
        }
        TermId o = resolveOrNull(obj);
        if (obj != null && o == null) {
            return emptyStatements();
        }

        Set<TermId> contextFilter = wantedContexts(contexts);
        if (contextFilter != null && contextFilter.isEmpty()) {
            return emptyStatements(); // contexts were named but none are known
        }

        // Context joins the index choice only when exactly one is wanted —
        // otherwise it cannot form a single seek prefix and is post-filtered.
        TermId singleContext =
                (contextFilter != null && contextFilter.size() == 1)
                        ? contextFilter.iterator().next()
                        : null;
        FlatIndexSelector.Choice choice = FlatIndexSelector.choose(s, p, o, singleContext);
        byte[] prefix = FlatKeyCodec.prefix(choice.prefixTerms());

        RocksIterator iterator = indexIterator(store.index(choice.order()));
        return new FlatStatementIteration(
                iterator, prefix, choice.order().role(), s, p, o, contextFilter);
    }

    /**
     * The set of context TermIds to match, or {@code null} for "every graph". A single {@code null}
     * context is <em>not</em> "every graph" — per the RDF4J contract it denotes the default graph
     * specifically, kept here under the {@link TermId#ZERO} sentinel.
     */
    private @Nullable Set<TermId> wantedContexts(Resource[] contexts) {
        if (contexts.length == 0) {
            return null;
        }
        Set<TermId> wanted = new LinkedHashSet<>();
        for (Resource context : contexts) {
            if (context == null) {
                wanted.add(TermId.ZERO);
            } else {
                TermId id = resolveOrNull(context);
                if (id != null) {
                    wanted.add(id); // an unknown named context simply contributes no match
                }
            }
        }
        return wanted;
    }

    private Statement buildStatement(TermId s, TermId p, TermId o, TermId c) {
        boolean defaultGraph = TermId.ZERO.equals(c);
        // Resolve all of a statement's terms in one batched dictionary fetch —
        // on a cold scan that is a single RocksDB multiGet, not 3-4 reads.
        TermId[] ids = defaultGraph ? new TermId[] {s, p, o} : new TermId[] {s, p, o, c};
        Value[] values = dictionary.lookupAll(ids, txBatch);

        if (!(required(values[0], s) instanceof Resource subject)) {
            throw new SailException("stored subject term is not a Resource: " + values[0]);
        }
        if (!(required(values[1], p) instanceof IRI predicate)) {
            throw new SailException("stored predicate term is not an IRI: " + values[1]);
        }
        Value objectValue = required(values[2], o);
        ValueFactory vf = sail.getValueFactory();
        if (defaultGraph) {
            return vf.createStatement(subject, predicate, objectValue);
        }
        if (!(required(values[3], c) instanceof Resource context)) {
            throw new SailException("stored context term is not a Resource: " + values[3]);
        }
        return vf.createStatement(subject, predicate, objectValue, context);
    }

    /** A dictionary value that must be present — a missing one is store corruption. */
    private static Value required(Value value, TermId id) {
        if (value == null) {
            throw new SailException(
                    "dictionary has no entry for TermId "
                            + id.toHex()
                            + " — index/dictionary inconsistency");
        }
        return value;
    }

    private Value lookupRequired(TermId id) {
        return dictionary
                .lookup(id, txBatch)
                .orElseThrow(
                        () ->
                                new SailException(
                                        "dictionary has no entry for TermId "
                                                + id.toHex()
                                                + " — index/dictionary inconsistency"));
    }

    private static boolean startsWith(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static CloseableIteration<Statement> emptyStatements() {
        return new CloseableIteration<>() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public Statement next() {
                throw new NoSuchElementException();
            }

            @Override
            public void close() {
                /* nothing to release */
            }
        };
    }

    /**
     * Lazily decodes a {@link RocksIterator} range scan into {@link Statement}s. The prefix bounds
     * the scan; remaining bound terms and the context filter are checked per row. Closing the
     * iteration closes the RocksDB iterator.
     */
    private final class FlatStatementIteration implements CloseableIteration<Statement> {

        private final RocksIterator iterator;
        private final byte[] prefix;
        private final QuadRole role;
        // @Nullable filters: null = unconstrained on that position (a wildcard); advance() guards
        // each with a != null check before matching.
        private final @Nullable TermId subjectFilter;
        private final @Nullable TermId predicateFilter;
        private final @Nullable TermId objectFilter;
        private final @Nullable Set<TermId> contextFilter;

        // @Nullable lookahead: null when not yet computed or the scan is exhausted; next() asserts
        // it
        // after hasNext().
        private @Nullable Statement nextStatement;
        private boolean started;
        private boolean closed;

        FlatStatementIteration(
                RocksIterator iterator,
                byte[] prefix,
                QuadRole role,
                @Nullable TermId subjectFilter,
                @Nullable TermId predicateFilter,
                @Nullable TermId objectFilter,
                @Nullable Set<TermId> contextFilter) {
            this.iterator = iterator;
            this.prefix = prefix;
            this.role = role;
            this.subjectFilter = subjectFilter;
            this.predicateFilter = predicateFilter;
            this.objectFilter = objectFilter;
            this.contextFilter = contextFilter;
        }

        @Override
        public boolean hasNext() {
            if (closed) {
                return false;
            }
            if (nextStatement == null) {
                nextStatement = advance();
            }
            return nextStatement != null;
        }

        @Override
        public Statement next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            // hasNext() == true guarantees nextStatement is set.
            Statement statement = Objects.requireNonNull(nextStatement);
            nextStatement = null;
            return statement;
        }

        private @Nullable Statement advance() {
            if (!started) {
                started = true;
                if (prefix.length == 0) {
                    iterator.seekToFirst();
                } else {
                    iterator.seek(prefix);
                }
            }
            while (iterator.isValid()) {
                byte[] key = iterator.key();
                if (!startsWith(key, prefix)) {
                    break; // left the prefix range — scan done
                }
                iterator.next();
                SpocKey physical = FlatKeyCodec.decode(key);
                TermId s = role.col(physical, 0);
                TermId p = role.col(physical, 1);
                TermId o = role.col(physical, 2);
                TermId c = role.col(physical, 3);
                if (subjectFilter != null && !subjectFilter.equals(s)) {
                    continue;
                }
                if (predicateFilter != null && !predicateFilter.equals(p)) {
                    continue;
                }
                if (objectFilter != null && !objectFilter.equals(o)) {
                    continue;
                }
                if (contextFilter != null && !contextFilter.contains(c)) {
                    continue;
                }
                return buildStatement(s, p, o, c);
            }
            return null;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                iterator.close();
            }
        }
    }

    // ---- size / clear / contexts ----------------------------------------

    @Override
    protected long sizeInternal(Resource... contexts) throws SailException {
        // One quad = one key in any single index — count keys, no decoding.
        if (contexts.length == 0) {
            return countKeys(store.index(QuadOrder.SPOC), new byte[0]);
        }
        long total = 0;
        // contexts.length > 0 here (the empty case returned above), so wantedContexts is non-null.
        for (TermId context : Objects.requireNonNull(wantedContexts(contexts))) {
            // CSPO leads with the context column, so each context is a prefix scan.
            total += countKeys(store.index(QuadOrder.CSPO), FlatKeyCodec.prefix(context));
        }
        return total;
    }

    private long countKeys(ColumnFamilyHandle cf, byte[] prefix) {
        long count = 0;
        try (RocksIterator it = indexIterator(cf)) {
            if (prefix.length == 0) {
                it.seekToFirst();
            } else {
                it.seek(prefix);
            }
            while (it.isValid() && startsWith(it.key(), prefix)) {
                count++;
                it.next();
            }
        }
        return count;
    }

    @Override
    protected CloseableIteration<? extends Resource> getContextIDsInternal() throws SailException {
        // Distinct non-default context TermIds, read off the CSPO index whose
        // leading column is the context.
        Set<TermId> contextIds = new LinkedHashSet<>();
        try (RocksIterator it = indexIterator(store.index(QuadOrder.CSPO))) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                TermId context = FlatKeyCodec.decode(it.key()).col0();
                if (!TermId.ZERO.equals(context)) {
                    contextIds.add(context);
                }
            }
        }
        List<Resource> contexts = new ArrayList<>(contextIds.size());
        for (TermId id : contextIds) {
            Value value = lookupRequired(id);
            if (!(value instanceof Resource resource)) {
                throw new SailException("stored context term is not a Resource: " + value);
            }
            contexts.add(resource);
        }
        return iterate(contexts);
    }

    @Override
    protected void clearInternal(Resource... contexts) throws SailException {
        // Clearing is removeStatements with all three terms wildcarded.
        // A RocksDB range delete would be O(1), but WriteBatchWithIndex does
        // not support deleteRange — and an indexed batch (read-your-writes,
        // Step 16) is the firmer requirement — so this is a scan-and-delete.
        removeStatementsInternal(null, null, null, contexts);
    }

    /** A {@link CloseableIteration} over an already-materialized list. */
    private static <E> CloseableIteration<E> iterate(List<E> items) {
        Iterator<E> inner = items.iterator();
        return new CloseableIteration<>() {
            @Override
            public boolean hasNext() {
                return inner.hasNext();
            }

            @Override
            public E next() {
                return inner.next();
            }

            @Override
            public void close() {
                /* already materialized */
            }
        };
    }

    // ---- namespaces (the `ns` column family: prefix -> IRI) -------------

    @Override
    protected void setNamespaceInternal(String prefix, String name) throws SailException {
        acquireWriteLock();
        WriteBatchWithIndex batch = requireTransaction();
        try {
            batch.put(store.namespaces(), utf8(prefix), utf8(name));
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail setNamespace failed", e);
        }
    }

    @Override
    protected void removeNamespaceInternal(String prefix) throws SailException {
        acquireWriteLock();
        WriteBatchWithIndex batch = requireTransaction();
        try {
            batch.delete(store.namespaces(), utf8(prefix));
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail removeNamespace failed", e);
        }
    }

    @Override
    protected void clearNamespacesInternal() throws SailException {
        acquireWriteLock();
        WriteBatchWithIndex batch = requireTransaction();
        try (RocksIterator it = indexIterator(store.namespaces())) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                batch.delete(store.namespaces(), it.key());
            }
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail clearNamespaces failed", e);
        }
    }

    @Override
    protected @Nullable String getNamespaceInternal(String prefix) throws SailException {
        try {
            byte[] name = pointGet(store.namespaces(), utf8(prefix));
            return name == null ? null : new String(name, StandardCharsets.UTF_8);
        } catch (RocksDBException e) {
            throw new SailException("RocksDbFlatSail getNamespace failed", e);
        }
    }

    @Override
    protected CloseableIteration<? extends Namespace> getNamespacesInternal() throws SailException {
        // Namespaces are few — materialize them so the RocksDB iterator is
        // fully drained and closed before this method returns.
        List<Namespace> namespaces = new ArrayList<>();
        try (RocksIterator it = indexIterator(store.namespaces())) {
            for (it.seekToFirst(); it.isValid(); it.next()) {
                namespaces.add(
                        new SimpleNamespace(
                                new String(it.key(), StandardCharsets.UTF_8),
                                new String(it.value(), StandardCharsets.UTF_8)));
            }
        }
        return iterate(namespaces);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
