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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import java.nio.file.Path;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage for {@link SparqlWarmup} — the boot-time SPARQL-engine preload. */
class SparqlWarmupTest {

    private static SailRepository repo(Path dir) {
        NodeStore store = new InMemoryNodeStore();
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();
        return repo;
    }

    @Test
    void warmUp_runs_then_repo_is_still_queryable(@TempDir Path dir) {
        SailRepository repo = repo(dir);
        try {
            ValueFactory vf = repo.getValueFactory();
            try (RepositoryConnection c = repo.getConnection()) {
                c.begin();
                c.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
                c.commit();
            }

            SparqlWarmup.warmUp(repo); // must not throw, must not mutate

            try (RepositoryConnection c = repo.getConnection();
                    TupleQueryResult r =
                            c.prepareTupleQuery("SELECT ?s WHERE { ?s ?p ?o }").evaluate()) {
                assertTrue(r.hasNext(), "the single triple must survive warm-up (read-only)");
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void warmUp_is_safe_on_an_empty_repo(@TempDir Path dir) {
        // Warm-up is about the engine, not the data — it must not throw on an empty store (boot).
        SailRepository repo = repo(dir);
        try {
            SparqlWarmup.warmUp(repo);
        } finally {
            repo.shutDown();
        }
    }
}
