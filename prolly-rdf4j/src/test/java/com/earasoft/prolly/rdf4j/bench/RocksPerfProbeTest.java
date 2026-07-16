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
package com.earasoft.prolly.rdf4j.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.flatsail.RocksDbFlatSail;
import com.earasoft.prolly.flatsail.RocksFlatStore;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.PerfLevel;

/**
 * Phase 0 Step 3 of {@code plans/rocksdb-perf-instrumentation.md} — pins that {@link
 * RocksPerfProbe} captures real RocksDB ops on a flat-Sail query and leaves the (thread-local
 * global) {@code PerfLevel} back at {@code DISABLE}.
 */
class RocksPerfProbeTest {

    @Test
    void capturesRealRocksDbOpsAndRestoresPerfLevel(@TempDir Path dir) throws Exception {
        RocksFlatStore store = RocksFlatStore.open(dir.resolve("flat").toString());
        SailRepository repo = new SailRepository(new RocksDbFlatSail(store));
        repo.init();
        try (RepositoryConnection conn = repo.getConnection()) {
            ValueFactory vf = conn.getValueFactory();
            IRI p = vf.createIRI("urn:p");
            IRI mid = vf.createIRI("urn:s:50");
            conn.begin();
            for (int i = 0; i < 100; i++) {
                conn.add(vf.createIRI("urn:s:" + i), p, vf.createIRI("urn:o:" + i));
            }
            conn.commit();

            RocksPerfProbe.Counters c =
                    RocksPerfProbe.measure(
                            store.db(),
                            () -> {
                                try (RepositoryResult<Statement> r =
                                        conn.getStatements(mid, null, null)) {
                                    while (r.hasNext()) r.next();
                                }
                            });
            RocksPerfProbe.print("flat point-lookup", c);

            assertTrue(
                    c.userKeyComparisons() > 0 || c.blockReads() > 0 || c.blockCacheHits() > 0,
                    "probe must capture real RocksDB ops (got " + c + ")");
            assertEquals(
                    PerfLevel.DISABLE,
                    store.db().getPerfLevel(),
                    "PerfLevel must be restored to DISABLE after measure()");
        } finally {
            repo.shutDown();
        }
    }
}
