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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.sail.SailConnection;

/**
 * Phase 4 Step 15 of {@code prolly-rdf4j-test-strategy.md} (S-5) — <b>context (named-graph)
 * isolation</b> as a <i>metamorphic</i> property over generated multi-graph datasets, generalizing
 * the single-bug {@code ProllySailContextTest}.
 *
 * <p>"Metamorphic" = the invariants relate the results of different context-filtered queries to
 * each other, so they need <b>no reference oracle</b> (they hold whatever the data is). Over a
 * committed dataset spanning the default graph plus a few named graphs, with {@code Q(ctx=…)} =
 * {@code getStatements(null,null,null,false, …)}:
 *
 * <ul>
 *   <li><b>Default isolation</b> — every statement in {@code Q(ctx=null)} has a null context, and
 *       {@code Q(ctx=null) ⊆ Q()}.
 *   <li><b>Named isolation</b> — every statement in {@code Q(ctx=C)} has context exactly {@code C}.
 *   <li><b>Idempotent context</b> — {@code Q(ctx=C) == Q(ctx=C,C)}.
 *   <li><b>Subset</b> — {@code Q(ctx=C) ⊆ Q()}.
 *   <li><b>Exact partition</b> — the default result and the per-named-context results are pairwise
 *       <i>disjoint</i> and their union is exactly {@code Q()} (every statement lands in one
 *       graph).
 *   <li><b>{@code getContextIDs}</b> — equals the set of named graphs present (the default graph is
 *       not a context id).
 * </ul>
 *
 * <p>Differential-safe statements (IRIs, plain/lang literals; contexts from {@code QuadGen}'s small
 * pool incl. the default graph) so {@code (s,p,o,c)} keys count without canonicalization surprises.
 */
class ContextIsolationProperty {

    private static List<Statement> collect(CloseableIteration<? extends Statement> it) {
        List<Statement> out = new ArrayList<>();
        try (it) {
            while (it.hasNext()) {
                out.add(it.next());
            }
        }
        return out;
    }

    private static String key(Statement s) {
        return s.getSubject()
                + "|"
                + s.getPredicate()
                + "|"
                + s.getObject()
                + "|"
                + (s.getContext() == null ? "" : s.getContext());
    }

    private static Set<String> keys(List<Statement> stmts) {
        Set<String> out = new HashSet<>();
        for (Statement s : stmts) {
            out.add(key(s));
        }
        return out;
    }

    private static void add(SailConnection w, Statement s) {
        if (s.getContext() == null) {
            w.addStatement(s.getSubject(), s.getPredicate(), s.getObject());
        } else {
            w.addStatement(s.getSubject(), s.getPredicate(), s.getObject(), s.getContext());
        }
    }

    @Property(tries = 100)
    void context_filtering_is_an_exact_partition(@ForAll @From("dataset") List<Statement> dataset) {
        ProllySail sail = new ProllySail();
        sail.init();
        try {
            try (SailConnection w = sail.getConnection()) {
                w.begin();
                for (Statement s : dataset) {
                    add(w, s);
                }
                w.commit();
            }
            try (SailConnection r = sail.getConnection()) {
                List<Statement> all = collect(r.getStatements(null, null, null, false)); // Q()
                Set<String> allKeys = keys(all);

                // The named graphs actually present in the store (default excluded).
                Set<Resource> named = new HashSet<>();
                for (Statement s : all) {
                    if (s.getContext() != null) {
                        named.add(s.getContext());
                    }
                }

                // Q(ctx=null) — the default graph only.
                List<Statement> def =
                        collect(r.getStatements(null, null, null, false, (Resource) null));
                for (Statement s : def) {
                    assertNull(
                            s.getContext(),
                            "Q(ctx=null) must return only default-graph statements");
                }
                assertTrue(allKeys.containsAll(keys(def)), "Q(ctx=null) must be a subset of Q()");

                Set<String> partition = new HashSet<>(keys(def));
                for (Resource c : named) {
                    List<Statement> qc = collect(r.getStatements(null, null, null, false, c));
                    for (Statement s : qc) {
                        assertEquals(
                                c, s.getContext(), "Q(ctx=C) must return only statements in C");
                    }
                    assertEquals(
                            keys(qc),
                            keys(collect(r.getStatements(null, null, null, false, c, c))),
                            "Q(ctx=C) == Q(ctx=C,C) — duplicating the context is a no-op");
                    assertTrue(allKeys.containsAll(keys(qc)), "Q(ctx=C) must be a subset of Q()");
                    for (String k : keys(qc)) {
                        assertTrue(
                                partition.add(k),
                                "the default + per-context results must partition Q() disjointly");
                    }
                }
                assertEquals(
                        allKeys,
                        partition,
                        "Q() == the default result union the per-named-context results (a total partition)");

                Set<Resource> contextIds = new HashSet<>();
                try (var it = r.getContextIDs()) {
                    while (it.hasNext()) {
                        contextIds.add(it.next());
                    }
                }
                assertEquals(
                        named,
                        contextIds,
                        "getContextIDs must be exactly the named graphs present (default is not a context id)");
            }
        } finally {
            sail.shutDown();
        }
    }

    /**
     * A multi-graph dataset: 0–20 differential-safe statements over default + a few named graphs.
     */
    @Provide
    Arbitrary<List<Statement>> dataset() {
        return QuadGen.differentialStatements().list().ofMinSize(0).ofMaxSize(20);
    }
}
