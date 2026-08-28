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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

/**
 * Which index a graph-scoped read actually seeks.
 *
 * <p>The store keeps a CSPO permutation precisely so that a query bound on context is a prefix
 * seek, and {@code IndexPlannerTest.c_bound_only_chooses_cspo} proves the planner picks it. The
 * Sail never asked: {@code getStatementsInternal} passed {@code null} for the context to both
 * {@code choose} and {@code scan} and applied the graph as a Java post-filter, so every four orders
 * tied at prefix 0 and the seek was the whole store. Measured downstream on NCIt: 10.77M statements
 * read to find zero rows, 51.4 s of an 89 s build.
 *
 * <p><b>Half of these tests pin the fix and half guard against the WRONG fix</b>, which is the more
 * interesting half. Pushing the context unconditionally is worse than the defect: the default-graph
 * sentinel {@code TermId.ZERO} is deliberately never counted by {@code insertEverywhere}, and
 * {@code TermStats} answers 0 for an id it has never seen, so the planner's selectivity tie-break
 * rates it the rarest term in the store — forever. An unguarded pushdown therefore trades a
 * predicate seek for a scan of the entire default graph.
 *
 * <p>Asserted through counters, not durations: {@code planner.choice} says which index was picked
 * and {@code index{name=<order>.scan.examined}} says how many rows it walked. Three ways this goes
 * vacuous, all avoided here — the meter NAME is the literal {@code "index"} with the order as a
 * TAG, so a dotted lookup finds nothing and reads as 0; the scan counters publish only when the
 * base iterator is exhausted, so every read is DRAINED; and the two-argument {@code ProllySail}
 * constructor gets a registry that records nothing, so all of these use the three-argument form.
 */
class ProllySailContextPushdownTest {

    private static final int DEFAULT_GRAPH_ROWS = 6;
    private static final int NAMED_GRAPH_ROWS = 2;

    private static long drain(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        } finally {
            it.close();
        }
        return n;
    }

    private static double counter(SimpleMeterRegistry m, String name, String key, String val) {
        var c = m.find(name).tag(key, val).counter();
        return c == null ? 0d : c.count();
    }

    /** Rows the named index walked. 0 when that index was not the one chosen. */
    private static double examined(SimpleMeterRegistry m, String order) {
        return counter(m, "index", "name", order + ".scan.examined");
    }

    private interface Read {
        void run(SailConnection conn, ValueFactory vf, IRI p, Resource g1);
    }

    /**
     * A store with {@value #DEFAULT_GRAPH_ROWS} statements in the default graph and {@value
     * #NAMED_GRAPH_ROWS} in {@code g1}, all sharing one predicate so that a predicate-bound query
     * has a POSC prefix worth keeping.
     */
    private static SimpleMeterRegistry over(Read read) {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = vf.createIRI("http://example/p");
            Resource g1 = vf.createIRI("http://example/g1");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                for (int i = 0; i < DEFAULT_GRAPH_ROWS; i++) {
                    conn.addStatement(
                            vf.createIRI("http://example/s" + i),
                            p,
                            vf.createIRI("http://example/o" + i));
                }
                for (int i = 0; i < NAMED_GRAPH_ROWS; i++) {
                    conn.addStatement(
                            vf.createIRI("http://example/n" + i), p,
                            vf.createIRI("http://example/x" + i), g1);
                }
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                read.run(conn, vf, p, g1);
            }
            return m;
        } finally {
            sail.shutDown();
        }
    }

    /**
     * THE DEFECT. Before the fix this failed with {@code SPOC.prefix0} chosen and {@code
     * spoc.scan.examined == 8} — every row in the store walked to emit 2. That ratio is the NCIt
     * story (10.77M examined, 0 emitted) reduced to a unit test.
     */
    @Test
    void a_named_graph_read_seeks_cspo_and_walks_only_that_graph() {
        SimpleMeterRegistry m =
                over(
                        (conn, vf, p, g1) ->
                                assertEquals(
                                        NAMED_GRAPH_ROWS,
                                        drain(conn.getStatements(null, null, null, false, g1))));

        assertEquals(
                1d,
                counter(m, "planner.choice", "choice", "CSPO.prefix1"),
                "context is the only bound position, and CSPO puts it at physical column 0 — this "
                        + "is the one query shape the fourth permutation exists for");
        assertEquals(
                (double) NAMED_GRAPH_ROWS,
                examined(m, "cspo"),
                "and the seek walks the graph, not the store: examined must equal the rows in g1");
        assertEquals(0d, examined(m, "spoc"), "nothing should have touched SPOC");
    }

    /**
     * THE WRONG FIX, guarded. Passes before the change too — that is the point of it: it fails only
     * if someone pushes the context unconditionally.
     *
     * <p>{@code TermId.ZERO} is never counted ({@code insertEverywhere}: {@code if
     * (!cId.equals(TermId.ZERO)) statsTx.increment(cId)}), and an uncounted term has frequency 0,
     * which the planner reads as maximum selectivity. So CSPO would tie POSC on prefix length and
     * WIN the tie-break, trading a seek on one predicate for a walk of the whole default graph.
     */
    @Test
    void the_default_graph_keeps_its_predicate_seek_and_never_seeks_the_ZERO_sentinel() {
        SimpleMeterRegistry m =
                over(
                        (conn, vf, p, g1) ->
                                assertEquals(
                                        DEFAULT_GRAPH_ROWS,
                                        drain(
                                                conn.getStatements(
                                                        null, p, null, false, (Resource) null))));

        assertEquals(
                1d,
                counter(m, "planner.choice", "choice", "POSC.prefix1"),
                "the bound predicate is the useful prefix here. A CSPO seek on the default-graph "
                        + "sentinel is a scan of every statement that has no graph — which in a "
                        + "store that uses one graph is the whole store, and worse than the defect "
                        + "this fix removes");
        assertEquals(0d, examined(m, "cspo"), "CSPO must not have been chosen");
    }

    /** No contexts at all means every graph, so there is nothing to seek on. */
    @Test
    void a_read_with_no_contexts_matches_every_graph_and_pushes_nothing() {
        SimpleMeterRegistry m =
                over(
                        (conn, vf, p, g1) ->
                                assertEquals(
                                        DEFAULT_GRAPH_ROWS + NAMED_GRAPH_ROWS,
                                        drain(conn.getStatements(null, null, null, false))));

        assertEquals(
                0d,
                examined(m, "cspo"),
                "empty varargs is 'any graph', which no single prefix can express");
    }

    /**
     * Two DIFFERENT graphs cannot form one seek prefix, so the read falls back and the Java filter
     * does the work — the same answer {@code RocksDbFlatSailConnection} already gives.
     */
    @Test
    void two_different_contexts_fall_back_to_a_context_free_seek() {
        SimpleMeterRegistry m =
                over(
                        (conn, vf, p, g1) ->
                                assertEquals(
                                        DEFAULT_GRAPH_ROWS + NAMED_GRAPH_ROWS,
                                        drain(
                                                conn.getStatements(
                                                        null, null, null, false, null, g1))));

        assertEquals(
                0d,
                examined(m, "cspo"),
                "{default, g1} is two graphs; seeking either one would silently drop the other's "
                        + "rows, which is a wrong answer rather than a slow one");
    }

    /**
     * ...but the SAME graph twice is still one graph. This is why the guard must read the deduped
     * set the filter uses and not {@code ctxs.length}: {@code {g1, g1}} collapses to one and is
     * genuinely single-context, and a length-based guard would give up the seek for nothing.
     */
    @Test
    void the_same_context_twice_is_still_one_context_and_still_seeks() {
        SimpleMeterRegistry m =
                over(
                        (conn, vf, p, g1) ->
                                assertEquals(
                                        NAMED_GRAPH_ROWS,
                                        drain(
                                                conn.getStatements(
                                                        null, null, null, false, g1, g1))));

        assertEquals(
                1d,
                counter(m, "planner.choice", "choice", "CSPO.prefix1"),
                "the seek term and the filter set must come from the same deduped collection");
        assertEquals((double) NAMED_GRAPH_ROWS, examined(m, "cspo"));
    }
}
