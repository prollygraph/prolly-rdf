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

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Regression for the within-transaction <em>read-your-own-writes</em> contract of {@code
 * ProllySailConnection}.
 *
 * <p>The scan-based read path ({@code getStatementsInternal} / {@code sizeInternal} / {@code
 * getContextIDsInternal}) reads each index's committed {@code StaticMap}. To honour RDF4J's
 * transaction contract — reads within a transaction must see that transaction's own earlier writes
 * — those methods now flush the per-tx index buffer before scanning ({@code MutableMap.flush()} is
 * a no-op when nothing is pending, so read-only transactions are unaffected).
 *
 * <p>Before the fix, a same-transaction add was invisible to {@code size}/{@code getStatements} and
 * a same-transaction remove still appeared. Driven against a real {@link ProllySail} — no mocks.
 */
class ProllySailReadYourWritesTest {

    private static IRI iri(ValueFactory vf, String s) {
        return vf.createIRI("http://example.org/" + s);
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
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                assertEquals(1L, conn.size(), "size() within a tx must see the same-tx add");
                conn.commit();
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void getStatements_reflects_a_same_tx_add() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                assertEquals(
                        1,
                        count(conn.getStatements(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"), false)),
                        "getStatements within a tx must see the same-tx add");
                conn.commit();
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void size_reflects_a_same_tx_remove() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.removeStatements(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                assertEquals(0L, conn.size(), "size() within a tx must reflect the same-tx remove");
                conn.commit();
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void interleaved_add_read_add_read_within_one_tx() {
        // Multiple read/write cycles in one transaction must each be consistent.
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                assertEquals(1L, conn.size());
                conn.addStatement(iri(vf, "b"), iri(vf, "p"), iri(vf, "y"));
                assertEquals(2L, conn.size());
                conn.removeStatements(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                assertEquals(
                        1L,
                        conn.size(),
                        "each read in an interleaved tx must reflect all prior writes");
                conn.commit();
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void wildcard_remove_sees_a_same_tx_add() {
        // removeStatements(null, p, o) scans for matches — it must find and
        // delete a statement added earlier in the same transaction.
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                conn.removeStatements(null, iri(vf, "p"), iri(vf, "x"));
                assertEquals(
                        0L,
                        conn.size(),
                        "a wildcard remove must delete a same-tx-added matching statement");
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(0L, conn.size(), "and the removal must survive commit");
            }
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void post_commit_read_in_a_fresh_connection_still_correct() {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(iri(vf, "a"), iri(vf, "p"), iri(vf, "x"));
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                assertEquals(1L, conn.size(), "committed statement visible post-commit");
            }
        } finally {
            sail.shutDown();
        }
    }
}
