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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Step 8 of {@code plans/consolidate-rdf-on-rdf4j.md} — {@link CanonicalizingProllySail} over a
 * real {@link ProllySail}. Replaces {@code CanonicalizingQuadStoreIntegrationTest} (which drove the
 * retired native {@code VersionedQuadStore}). Exercises canonicalize-at-commit + read delegation
 * through the RDF4J Sail surface.
 */
class CanonicalizingProllySailIntegrationTest {

    private static final String E = "urn:ex/knows";
    private static final String BOB = "urn:ex/bob";

    private static CanonicalizingProllySail canonSail(Path dir) {
        ProllySail delegate =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir));
        return new CanonicalizingProllySail(delegate);
    }

    @Test
    void named_iri_commit_and_query_round_trip(@TempDir Path dir) {
        Repository repo = new SailRepository(canonSail(dir));
        repo.init();
        try {
            ValueFactory vf = repo.getValueFactory();
            IRI alice = vf.createIRI("urn:ex/alice");
            IRI knows = vf.createIRI(E);
            IRI bob = vf.createIRI(BOB);
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(alice, knows, bob);
                conn.commit();
            }
            try (RepositoryConnection conn = repo.getConnection()) {
                assertTrue(
                        conn.hasStatement(alice, knows, bob, false),
                        "named-IRI statement survives canonicalize-at-commit (identity) + read delegation");
            }
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void blank_node_subject_is_canonicalized_at_commit(@TempDir Path dir) {
        Repository repo = new SailRepository(canonSail(dir));
        repo.init();
        try {
            ValueFactory vf = repo.getValueFactory();
            IRI knows = vf.createIRI(E);
            IRI age = vf.createIRI("urn:ex/age");
            BNode x = vf.createBNode("x");
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                conn.add(x, knows, vf.createIRI(BOB));
                conn.add(x, age, vf.createIRI("urn:ex/thirty"));
                conn.commit();
            }
            // The canonicalizer renamed _:x → _:c14n0 deterministically before the commit landed.
            List<Statement> stmts = new ArrayList<>();
            try (RepositoryConnection conn = repo.getConnection();
                    RepositoryResult<Statement> r = conn.getStatements(null, null, null, false)) {
                r.forEach(stmts::add);
            }
            assertEquals(2, stmts.size());
            assertTrue(
                    stmts.stream().allMatch(s -> s.getSubject() instanceof BNode),
                    "subject stays a blank node");
            assertTrue(
                    stmts.stream().allMatch(s -> ((BNode) s.getSubject()).getID().equals("c14n0")),
                    "both statements share the canonical blank-node label c14n0");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void accessors_expose_the_configuration(@TempDir Path dir) {
        CanonicalizingProllySail sail = canonSail(dir);
        assertNotNull(sail.canonicalizer(), "the default canonicalizer is wired");
        assertEquals(CanonicalizationBudget.DEFAULT_TIME_BUDGET, sail.timeBudget());
    }
}
