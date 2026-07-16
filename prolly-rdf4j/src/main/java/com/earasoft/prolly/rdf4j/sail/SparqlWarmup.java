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

import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * Preloads the SPARQL query engine so the <b>first real query a process serves is fast</b>.
 *
 * <p>The first {@code prepareTupleQuery().evaluate()} on a fresh JVM class-loads + links + verifies
 * RDF4J's SPARQL parser, query algebra, and evaluation pipeline (plus the prolly Sail's eval path)
 * — roughly 300 classes, synchronously on the calling thread — so it runs ~100&nbsp;ms while every
 * later query on the same data runs ~1–3&nbsp;ms. {@link #warmUp(Repository)} pays that one-time
 * cost <em>deliberately at startup</em> by running a few throwaway read queries, moving it off the
 * first user request's critical path. (Measured + attributed by {@code JvmWarmupDemo}.)
 *
 * @apiNote Read-only and idempotent — it issues only {@code SELECT}/{@code ASK} queries (never a
 *     write), so it is safe against any repository at any time, including an empty one at boot. The
 *     class-loading it triggers is JVM-global, so warming <em>one</em> repository warms the engine
 *     for every repository in the process; call it once after a Sail/Repository is initialised.
 * @implNote <b>Collaborators:</b> an RDF4J {@link Repository} (any backend — the warm-up exercises
 *     the engine, not the data). <b>Dependents:</b> {@code SparqlWarmupRunner} (the server's boot
 *     hook) and {@code JvmWarmupDemo} (which records what it loads). The throwaway queries
 *     deliberately touch the common operators (a scan, a {@code FILTER}, an {@code ORDER BY}, an
 *     {@code ASK}) so a real {@code SELECT} afterwards loads nothing new.
 */
public final class SparqlWarmup {

    private SparqlWarmup() {
        // static utility
    }

    /**
     * Run a few throwaway read queries against {@code repo} to class-load + JIT-warm the SPARQL
     * engine. Returns when the engine is warm; never mutates data.
     */
    public static void warmUp(Repository repo) {
        try (RepositoryConnection conn = repo.getConnection()) {
            drain(conn, "SELECT ?s ?p ?o WHERE { ?s ?p ?o } LIMIT 1");
            drain(conn, "SELECT ?s WHERE { ?s ?p ?o FILTER(BOUND(?o)) } ORDER BY ?s LIMIT 1");
            conn.prepareBooleanQuery("ASK { ?s ?p ?o }").evaluate();
        }
    }

    private static void drain(RepositoryConnection conn, String query) {
        try (TupleQueryResult r = conn.prepareTupleQuery(query).evaluate()) {
            while (r.hasNext()) {
                r.next();
            }
        }
    }
}
