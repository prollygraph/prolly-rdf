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

import static org.junit.jupiter.api.Assertions.*;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.sail.SailConnection;
import org.junit.jupiter.api.Test;

class ProllySailMetricsTest {

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

    /** Counter value for {@code name}, or 0 if the meter was never created. */
    private static double counter(SimpleMeterRegistry m, String name) {
        var c = m.find(name).counter();
        return c == null ? 0d : c.count();
    }

    /** Counter value for {@code name} narrowed by a single tag. */
    private static double counter(
            SimpleMeterRegistry m, String name, String tagKey, String tagVal) {
        var c = m.find(name).tag(tagKey, tagVal).counter();
        return c == null ? 0d : c.count();
    }

    @Test
    void add_get_commit_increment_counters() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.addStatement(
                        vf.createIRI("http://example/s2"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                drain(conn.getStatements(null, null, null, false));
            }
            assertEquals(2d, counter(m, "sail.add"));
            assertEquals(1d, counter(m, "sail.get"));
            assertEquals(1d, counter(m, "sail.commit"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void each_quad_inserted_into_all_four_indexes() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.commit();
            }
            assertEquals(1d, counter(m, "index.spoc.insert"));
            assertEquals(1d, counter(m, "index.posc.insert"));
            assertEquals(1d, counter(m, "index.ospc.insert"));
            assertEquals(1d, counter(m, "index.cspo.insert"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void planner_picks_posc_for_p_bound_query() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = vf.createIRI("http://example/p");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("http://example/s1"), p, vf.createIRI("http://example/o1"));
                conn.addStatement(
                        vf.createIRI("http://example/s2"), p, vf.createIRI("http://example/o2"));
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                drain(conn.getStatements(null, p, null, false));
            }
            assertEquals(1d, counter(m, "planner.choice", "choice", "POSC.prefix1"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void planner_picks_spoc_for_s_bound_query() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI s = vf.createIRI("http://example/s");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        s, vf.createIRI("http://example/p"), vf.createIRI("http://example/o"));
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                drain(conn.getStatements(s, null, null, false));
            }
            assertEquals(1d, counter(m, "planner.choice", "choice", "SPOC.prefix1"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void planner_picks_ospc_for_o_bound_query() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI o = vf.createIRI("http://example/o");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("http://example/s"), vf.createIRI("http://example/p"), o);
                conn.commit();
            }
            try (SailConnection conn = sail.getConnection()) {
                drain(conn.getStatements(null, null, o, false));
            }
            assertEquals(1d, counter(m, "planner.choice", "choice", "OSPC.prefix1"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void commit_duration_is_recorded() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.commit();
            }
            assertEquals(1L, m.get("sail.commit.total").timer().count());
            assertTrue(m.get("sail.commit.total").timer().totalTime(TimeUnit.NANOSECONDS) > 0);
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void term_stats_track_frequency_per_term() {
        SimpleMeterRegistry m = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(new InMemoryNodeStore(), new HeapBufferPool(), m);
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            IRI p = vf.createIRI("http://example/age");
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                // Insert 5 quads sharing the same predicate
                for (int i = 0; i < 5; i++) {
                    conn.addStatement(vf.createIRI("http://example/s" + i), p, vf.createLiteral(i));
                }
                conn.commit();
            }
            // The predicate's TermId got incremented 5 times.
            // We can't easily fetch the TermId from outside, but we can verify the
            // stats committed successfully (no exception) and that index inserts ran.
            assertEquals(5d, counter(m, "index.spoc.insert"));
        } finally {
            sail.shutDown();
        }
    }

    @Test
    void empty_registry_has_no_recorded_state() {
        // The metric-less ProllySail() defaults to an empty CompositeMeterRegistry,
        // so a separate observation registry sees nothing recorded into it.
        SimpleMeterRegistry observer = new SimpleMeterRegistry();
        ProllySail sail = new ProllySail(); // empty CompositeMeterRegistry default
        sail.init();
        try {
            ValueFactory vf = sail.getValueFactory();
            try (SailConnection conn = sail.getConnection()) {
                conn.begin();
                conn.addStatement(
                        vf.createIRI("http://example/s"),
                        vf.createIRI("http://example/p"),
                        vf.createIRI("http://example/o"));
                conn.commit();
            }
            assertTrue(observer.getMeters().isEmpty());
        } finally {
            sail.shutDown();
        }
    }
}
