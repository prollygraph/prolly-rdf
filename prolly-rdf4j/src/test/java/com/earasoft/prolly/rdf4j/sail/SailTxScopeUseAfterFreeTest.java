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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.pool.DirectBufferPool;
import java.util.HashSet;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the Sail's per-transaction scratch scope
 * (plans/off-heap-use-after-free-tests.md Phase 4 Step 13). {@code ProllySailConnection} opens an
 * {@code arenaTx} + a {@code poolTx} per fork and closes them at the next fork / connection close
 * (H1). Production wires {@code HeapBufferPool} (where {@code newTransactionScope} is a no-op), so
 * the scope only does real off-heap work under {@code DirectBufferPool} — which this test drives
 * directly: cycling the per-tx scope across five transactions must not corrupt data
 * (read-your-writes within each tx + cumulative consistency), i.e. a scope's freed segment is never
 * read by a later transaction.
 *
 * <p>The internal H1 (accessing a tx-scope segment after {@code closeTxScratch} throws) is a
 * private-field concern; this is its observable, Sail-level pin. The commit fan-out H5 is covered
 * by the cross-thread step (Step 17).
 */
class SailTxScopeUseAfterFreeTest {

    private static Set<Statement> drain(CloseableIteration<? extends Statement> it) {
        Set<Statement> out = new HashSet<>();
        try {
            while (it.hasNext()) {
                out.add(it.next());
            }
        } finally {
            it.close();
        }
        return out;
    }

    @Test
    void perTransactionScopeCyclesCorrectlyUnderDirectBufferPool() {
        try (DirectBufferPool pool = new DirectBufferPool();
                InMemoryNodeStore store = new InMemoryNodeStore()) {
            ProllySail sail = new ProllySail(store, pool);
            sail.init();
            try {
                ValueFactory vf = sail.getValueFactory();
                IRI p = vf.createIRI("http://ex/p");
                int rounds = 5;
                int perRound = 10;

                for (int r = 0; r < rounds; r++) {
                    try (SailConnection conn = sail.getConnection()) {
                        conn.begin(); // forks a fresh arenaTx + poolTx scope
                        for (int i = 0; i < perRound; i++) {
                            conn.addStatement(
                                    vf.createIRI("http://ex/s" + r + "_" + i),
                                    p,
                                    vf.createIRI("http://ex/o" + i));
                        }
                        int inTx = drain(conn.getStatements(null, null, null, false)).size();
                        assertEquals(
                                r * perRound + perRound,
                                inTx,
                                "read-your-writes within the tx, cumulative across the per-tx scope cycles");
                        conn.commit();
                    }
                }

                try (SailConnection conn = sail.getConnection()) {
                    assertEquals(
                            rounds * perRound,
                            drain(conn.getStatements(null, null, null, false)).size(),
                            "all statements consistent after the per-tx scope cycled under the off-heap pool");
                }
            } finally {
                sail.shutDown();
            }
        }
    }
}
