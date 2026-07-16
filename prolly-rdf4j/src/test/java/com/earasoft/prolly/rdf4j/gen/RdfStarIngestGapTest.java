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
package com.earasoft.prolly.rdf4j.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 1 Step 5 of {@code prolly-rdf4j-test-strategy.md} — <b>pins a gap the differential oracle
 * (S-2) found on its first run</b>: {@code ProllySail} cannot ingest an RDF-star statement whose
 * quoted triple was built by a <i>foreign</i> {@code ValueFactory} (here {@code
 * SimpleValueFactory}). The Sail's add-path hands the raw {@code SimpleTriple} to {@code
 * TermEncoder}, which requires the components already resolved to {@code TermId}s ("Triple requires
 * TermIds, use TermCodec.encodeQuotedTriple") — only {@code ProllyValueFactory.createTriple}
 * produces that. RDF4J's {@code MemoryStore} accepts any factory's values, so this is a real
 * RDF4J-contract divergence, tracked for the Phase-8 frontier (S-11).
 *
 * <p><b>This test documents the CURRENT behaviour, it does not bless it.</b> When the add-path
 * learns to recursively resolve a foreign quoted triple, the {@code assertThrows} flips to a
 * differential equality — delete it then and drop the {@code nonStar} restriction from {@link
 * OpStreamGen}.
 */
class RdfStarIngestGapTest {

    private static final ValueFactory VF = RdfValueGen.VF; // SimpleValueFactory
    private static final IRI A = VF.createIRI("urn:test:a");
    private static final IRI P = VF.createIRI("urn:test:p");
    private static final IRI B = VF.createIRI("urn:test:b");

    @Test
    void memoryStoreAcceptsForeignRdfStar_prollySailDoesNot(@TempDir Path dir) {
        Triple quoted = VF.createTriple(A, P, B); // a SimpleTriple — no TermIds
        Statement starStmt = VF.createStatement(quoted, P, B); // << quoted-triple subject

        // RDF4J MemoryStore: accepts it (the contract — any factory's values).
        Repository memory = new SailRepository(new MemoryStore());
        memory.init();
        try (RepositoryConnection c = memory.getConnection()) {
            c.add(starStmt);
            assertEquals(1, c.size(), "MemoryStore ingests foreign RDF-star");
        } finally {
            memory.shutDown();
        }

        // ProllySail: throws — foreign quoted triple not resolved to TermIds.
        Repository prolly =
                new SailRepository(
                        new ProllySail(
                                new InMemoryNodeStore(),
                                new HeapBufferPool(),
                                RootMetaTreeStore.beside(dir),
                                CommitLog.beside(dir),
                                RefsStore.beside(dir),
                                false));
        prolly.init();
        try (RepositoryConnection c = prolly.getConnection()) {
            assertThrows(
                    RuntimeException.class,
                    () -> {
                        c.add(starStmt);
                        c.commit();
                    },
                    "ProllySail rejects a foreign-factory RDF-star quoted triple (tracked gap)");
        } finally {
            prolly.shutDown();
        }
    }
}
