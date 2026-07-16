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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;

/**
 * Read-your-writes contract for {@link RocksDbFlatSail} — the within-transaction counterpart of
 * {@code RocksDbFlatSailContextRemoveTest}, and the FlatSail analogue of {@code
 * ProllySailReadYourWritesTest}.
 *
 * <p><b>How FlatSail uses RocksDB's {@code WriteBatchWithIndex} (WBWI).</b> A WBWI is a RocksDB
 * structure that buffers a transaction's mutations (puts and deletes) in an <i>in-memory,
 * indexed</i> batch — indexed so the buffered writes can be both flushed atomically at commit
 * <i>and read back before commit</i>. FlatSail has no separate transaction object; the WBWI
 * <b>is</b> the transaction. The RDF4J connection lifecycle maps onto it directly (see {@code
 * RocksDbFlatSailConnection}):
 *
 * <ul>
 *   <li><b>begin</b> ({@code startTransactionInternal}) → {@code new WriteBatchWithIndex(true)}
 *       plus a per-transaction dictionary-pending map. The {@code true} is {@code overwriteKey}
 *       (below).
 *   <li><b>add / remove</b> ({@code addStatementInternal} / {@code removeStatementsInternal}) →
 *       {@code batch.put} / {@code batch.delete} of the quad key in <i>all four</i> permutation
 *       index column families (SPOC/POSC/OSPC/CSPO), after interning terms into the batch's
 *       dictionary.
 *   <li><b>read</b> ({@code getStatementsInternal} / {@code sizeInternal}) → merges the batch over
 *       committed state: scans use {@code txBatch.newIteratorWithBase(cf, dbIterator)} (the {@code
 *       indexIterator} helper) and point reads use {@code txBatch.getFromBatchAndDB(...)} (the
 *       {@code pointGet} helper). This merge is what gives read-your-writes — a read sees the open
 *       transaction's own uncommitted writes layered over the committed base.
 *   <li><b>commit</b> ({@code commitInternal}) → {@code db.write(options, batch)} applies the whole
 *       batch atomically (write-ahead log on), then the batch is discarded.
 *   <li><b>rollback</b> ({@code rollbackInternal}) → the batch is discarded <i>unapplied</i>; the
 *       buffered writes simply vanish (no undo log needed — nothing was written to the database).
 * </ul>
 *
 * <p><b>Why {@code overwriteKey=true} is load-bearing — and the sharp case these tests pin.</b> The
 * merged read iterator ({@code newIteratorWithBase}, a {@code BaseDeltaIterator}) layers the
 * batch's delta over the base. The easy direction — a buffered <i>put</i> shadowing the base —
 * works regardless. The hard direction is a buffered <b>delete over a key that lives in the
 * committed base</b>: the iterator must <i>skip</i> a base key because the batch deletes it.
 * RocksDB only honours that when the WBWI was built with {@code overwriteKey=true} (otherwise the
 * delta carries both the base key and a delete marker the scan does not reconcile, and the deleted
 * row reappears). FlatSail passes {@code true}, but no existing test exercised the case: the other
 * read-your-writes tests remove a statement <i>added earlier in the same batch</i>, where the put
 * and delete cancel <i>inside</i> the WBWI and never touch the base. The two {@code
 * *_remove_of_COMMITTED_data} tests here commit first, <i>then</i> remove in a later transaction,
 * so the delete genuinely lands over a base key — pinning that {@code size()} and {@code
 * getStatements()} both reflect it mid-transaction, and that {@code rollback} makes the committed
 * statement reappear. Driven against a real {@link RocksDbFlatSail} — no mocks.
 */
class RocksDbFlatSailReadYourWritesTest {

    static {
        RocksDB.loadLibrary();
    }

    private RocksDbFlatSail sail;
    private ValueFactory vf;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        sail = new RocksDbFlatSail(dir);
        sail.init();
        vf = sail.getValueFactory();
    }

    @AfterEach
    void tearDown() {
        if (sail != null) {
            sail.shutDown();
        }
    }

    private IRI iri(String s) {
        return vf.createIRI("urn:test:" + s);
    }

    private static int count(CloseableIteration<? extends Statement> it) {
        int n = 0;
        try (it) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    @Test
    void size_reflects_a_same_tx_add() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(iri("a"), iri("p"), iri("x"));
            assertEquals(1L, conn.size(), "size() within a tx must see the same-tx add");
            conn.commit();
        }
    }

    @Test
    void size_reflects_a_same_tx_remove_of_COMMITTED_data() {
        // The WriteBatchWithIndex delete-over-base case: the statement is committed first,
        // then removed in a later transaction; a mid-tx size() must reflect the buffered delete.
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(iri("a"), iri("p"), iri("x"));
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.removeStatements(iri("a"), iri("p"), iri("x"));
            assertEquals(
                    0L,
                    conn.size(),
                    "size() must reflect a same-tx remove of COMMITTED data (batch delete over base)");
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(0L, conn.size(), "and the removal must survive commit");
        }
    }

    @Test
    void getStatements_reflects_a_same_tx_remove_of_COMMITTED_data() {
        // Same as above but via the scan path (getStatements), the merged iterator that is
        // most at risk of ignoring a batch delete over a base key.
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(iri("a"), iri("p"), iri("x"));
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.removeStatements(iri("a"), iri("p"), iri("x"));
            assertEquals(
                    0,
                    count(conn.getStatements(iri("a"), iri("p"), iri("x"), false)),
                    "getStatements() must reflect a same-tx remove of COMMITTED data");
            conn.commit();
        }
    }

    @Test
    void rollback_after_removing_committed_data_restores_it() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(iri("a"), iri("p"), iri("x"));
            conn.commit();
        }
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.removeStatements(iri("a"), iri("p"), iri("x"));
            assertEquals(0L, conn.size(), "removed within the tx");
            conn.rollback();
        }
        try (SailConnection conn = sail.getConnection()) {
            assertEquals(
                    1L,
                    conn.size(),
                    "rollback must drop the buffered delete — the statement returns");
        }
    }

    @Test
    void interleaved_add_read_remove_read_within_one_tx() {
        try (SailConnection conn = sail.getConnection()) {
            conn.begin();
            conn.addStatement(iri("a"), iri("p"), iri("x"));
            assertEquals(1L, conn.size());
            conn.addStatement(iri("b"), iri("p"), iri("y"));
            assertEquals(2L, conn.size());
            conn.removeStatements(iri("a"), iri("p"), iri("x"));
            assertEquals(
                    1L,
                    conn.size(),
                    "each read in an interleaved tx must reflect all prior writes");
            conn.commit();
        }
    }
}
