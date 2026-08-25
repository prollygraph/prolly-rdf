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
 * Phase 1 Step 5's gap pin, <b>flipped to a parity test</b> when the RDF-star write-path wiring
 * landed (conformance round 3, 2026-08-25): {@code ProllySail} now ingests an RDF-star statement
 * whose quoted triple was built by a <i>foreign</i> {@code ValueFactory} exactly like RDF4J's
 * {@code MemoryStore} does — {@code DictionaryTermEncoder} recursively interns the triple's
 * components, so no pre-resolved {@code TermId}s are required. The historical gap (the add-path
 * handing a raw {@code SimpleTriple} to {@code TermEncoder}, which threw) is what the differential
 * oracle (S-2) found on its very first run; per this file's own old instructions, the {@code
 * assertThrows} flipped to acceptance and the differential generators dropped their star
 * restriction ({@code QuadGen.differentialStatements} now generates quoted triples).
 */
class RdfStarIngestGapTest {

    private static final ValueFactory VF = RdfValueGen.VF; // SimpleValueFactory
    private static final IRI A = VF.createIRI("urn:test:a");
    private static final IRI P = VF.createIRI("urn:test:p");
    private static final IRI B = VF.createIRI("urn:test:b");

    @Test
    void bothStoresAcceptForeignRdfStar(@TempDir Path dir) {
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
            c.add(starStmt);
            c.commit();
            assertEquals(
                    1,
                    c.size(),
                    "ProllySail ingests a foreign-factory RDF-star quoted triple — the round-3"
                            + " write-path wiring (DictionaryTermEncoder interns the components"
                            + " recursively; no TermIds required up front)");
        } finally {
            prolly.shutDown();
        }
    }
}
