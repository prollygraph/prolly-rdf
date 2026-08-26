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

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.time.Duration;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterProperty;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.parser.QueryParserUtil;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.junit.jupiter.api.Assertions;

/**
 * Keeps {@link SparqlQueryGen} itself honest (the {@code RdfGenSmokeProperty} discipline): every
 * generated query PARSES and EVALUATES without throwing against a pool-vocabulary store — a
 * generator emitting invalid SPARQL would poison the oracle with parse noise instead of evaluation
 * divergences.
 */
class SparqlQueryGenSmokeProperty {

    private SailRepository repo;

    @BeforeProperty
    void setUp() {
        repo = new SailRepository(new ProllySail());
        repo.init();
        ValueFactory vf = repo.getValueFactory();
        try (SailRepositoryConnection con = repo.getConnection()) {
            // a few pool triples in the default graph and both named graphs
            for (int i = 0; i < SparqlQueryGen.POOL_SUBJECTS.size(); i++) {
                var s = vf.createIRI(SparqlQueryGen.POOL_SUBJECTS.get(i));
                var p =
                        vf.createIRI(
                                SparqlQueryGen.POOL_PREDICATES.get(
                                        i % SparqlQueryGen.POOL_PREDICATES.size()));
                var o = vf.createIRI(SparqlQueryGen.POOL_SUBJECTS.get((i + 1) % 5));
                con.add(s, p, o);
                con.add(
                        s,
                        p,
                        vf.createLiteral("alpha"),
                        vf.createIRI(SparqlQueryGen.POOL_GRAPHS.get(0)));
                con.add(o, p, vf.createLiteral(7), vf.createIRI(SparqlQueryGen.POOL_GRAPHS.get(1)));
            }
        }
    }

    @AfterProperty
    void tearDown() {
        repo.shutDown();
    }

    @Provide
    Arbitrary<String> queries() {
        return SparqlQueryGen.queries();
    }

    @Property(tries = 500)
    void everyGeneratedQueryParsesAndEvaluates(@ForAll("queries") String q) {
        assertNotNull(QueryParserUtil.parseTupleQuery(QueryLanguage.SPARQL, q, null), q);
        Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> {
                    try (SailRepositoryConnection con = repo.getConnection();
                            var r = con.prepareTupleQuery(q).evaluate()) {
                        while (r.hasNext()) {
                            r.next();
                        }
                    }
                },
                () -> "query hung or exploded: " + q);
    }
}
