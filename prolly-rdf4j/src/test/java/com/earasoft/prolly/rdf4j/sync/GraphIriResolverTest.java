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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.nio.file.Path;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Phase 0 Step 4 — IRI → TermId resolution for the syncpack filter. */
class GraphIriResolverTest {

    private static ProllySail initedSail(Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        new SailRepository(sail).init();
        return sail;
    }

    @Test
    void empty_input_returns_empty(@TempDir Path dir) {
        ProllySail sail = initedSail(dir);
        assertTrue(GraphIriResolver.resolve(sail, Set.of()).isEmpty());
    }

    @Test
    void null_input_returns_empty(@TempDir Path dir) {
        ProllySail sail = initedSail(dir);
        assertTrue(GraphIriResolver.resolve(sail, null).isEmpty());
    }

    @Test
    void unknown_iri_silently_dropped(@TempDir Path dir) {
        // An IRI never inserted into the dict has no TermId and
        // must not contribute to the result — the contract from
        // ChunkGraphFilter's "unknown TermIds drop nothing".
        ProllySail sail = initedSail(dir);
        Set<Long> ids = GraphIriResolver.resolve(sail, Set.of("urn:never-existed"));
        assertTrue(ids.isEmpty(), "unknown IRI must resolve to empty, not throw");
    }

    @Test
    void known_iri_resolves_to_its_term_id(@TempDir Path dir) {
        // Seed a triple whose context is <urn:prolly-rdf4j:auth/users>
        // — that IRI is now in the dict. Resolving should return
        // exactly one TermId (the context IRI's id).
        ProllySail sail = initedSail(dir);
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            IRI authGraph = vf.createIRI("urn:prolly-rdf4j:auth/users");
            conn.add(
                    vf.createIRI("urn:prolly-rdf4j:auth/alice"),
                    vf.createIRI("urn:prolly-rdf4j:auth/passwordHash"),
                    vf.createLiteral("bcrypt"),
                    authGraph);
            conn.commit();
        }
        Set<Long> ids = GraphIriResolver.resolve(sail, Set.of("urn:prolly-rdf4j:auth/users"));
        assertEquals(1, ids.size(), "the auth-graph IRI must resolve to exactly one TermId");
    }

    @Test
    void mixed_known_and_unknown_drops_only_unknown(@TempDir Path dir) {
        ProllySail sail = initedSail(dir);
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(
                    vf.createIRI("urn:s"),
                    vf.createIRI("urn:p"),
                    vf.createIRI("urn:o"),
                    vf.createIRI("urn:prolly-rdf4j:auth/users"));
            conn.commit();
        }
        Set<Long> ids =
                GraphIriResolver.resolve(
                        sail, Set.of("urn:prolly-rdf4j:auth/users", "urn:not-in-dict"));
        assertEquals(1, ids.size(), "known IRI resolves; unknown one is silently dropped");
    }

    @Test
    void default_auth_graphs_constant_lists_both_auth_iris() {
        assertTrue(GraphIriResolver.DEFAULT_AUTH_GRAPHS.contains("urn:prolly-rdf4j:auth/users"));
        assertTrue(
                GraphIriResolver.DEFAULT_AUTH_GRAPHS.contains("urn:prolly-rdf4j:auth/pseudonyms"));
        assertEquals(
                2,
                GraphIriResolver.DEFAULT_AUTH_GRAPHS.size(),
                "the default-DENY set is exactly the 2 auth graphs");
    }

    @Test
    void blank_iri_strings_are_skipped(@TempDir Path dir) {
        ProllySail sail = initedSail(dir);
        Set<Long> ids = GraphIriResolver.resolve(sail, Set.of("", "   "));
        assertTrue(ids.isEmpty(), "blank IRI strings yield no TermIds");
    }
}
