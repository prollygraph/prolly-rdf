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

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;

/**
 *
 *
 * <h3>Per-query bindings-result memo for the acyclic bind-join.</h3>
 *
 * <p>Wraps the {@link SailConnectionTripleSource} for one query evaluation when {@link
 * ProllySail#bindJoinMemoEnabled()} is on. The acyclic bind-join (RDF4J's {@code
 * DefaultEvaluationStrategy} — cyclic BGPs route to the triejoin instead) re-evaluates its inner
 * pattern <i>once per outer binding</i>; when the join key recurs, the same {@code getStatements}
 * call repeats. This memo erases the repeats.
 *
 * <p>It memoizes <b>only subject-and-predicate-bound point lookups</b> ({@code s != null && p !=
 * null}) — the measured recurring shape (e.g. {@code <superclass> rdfs:label ?l} in {@code ?c
 * rdfs:subClassOf ?s . ?s rdfs:label ?l}, where the superclass {@code ?s} fans in). Everything with
 * an unbound subject (the driving scans) streams straight through and is <b>never materialized</b>
 * — that is the guard that keeps the memo off the large scans and on the small re-probes (see
 * {@code prolly-rdf4j/plans/join-approaches-benchmark.md}, where the recurrence was characterized
 * at 2.66× / 62.4% memo-hit for the subClassOf join before this was built).
 *
 * @apiNote Not thread-safe; a {@code TripleSource} is owned by a single query evaluation on a
 *     single connection, which is single-threaded. The memo is correct because that evaluation
 *     reads a fixed snapshot (no interleaved writes), so {@code (s, p, o, contexts) → statements}
 *     is stable for the memo's whole (short) lifetime, then garbage-collected with the evaluation.
 * @implNote <b>Collaborators:</b> wraps {@link SailConnectionTripleSource} (the delegate);
 *     constructed in {@link ProllySailConnection#evaluateInternal}; reports {@code
 *     prolly.bindjoinmemo.hits} / {@code .misses} to the connection's {@link MeterRegistry} so an
 *     A/B can compare the realized hit rate against the data-level prediction. Bounds: {@code
 *     perEntryCap} (a result larger than this is served but not retained — it's a scan in
 *     disguise), {@code maxEntries} (memo entry-count ceiling), and {@code totalBudget} (the hard
 *     <b>global statement ceiling</b> — once the memo holds that many statements it stops growing
 *     and serves further misses directly, so memory is bounded regardless of query shape; this is
 *     what lets the memo default on, per {@code prolly-rdf4j-rest/plans/productionize-the-cache.md}
 *     D-3). All three are belt-and-suspenders on top of the s+p-bound gate, which already excludes
 *     the large scans.
 */
final class MemoizingTripleSource implements TripleSource {

    /** A memoizable lookup: subject + predicate bound, object + contexts as given. */
    private record Key(Resource subj, IRI pred, Value obj, List<Resource> contexts) {}

    private final TripleSource delegate;
    private final int perEntryCap;
    private final int maxEntries;
    private final long totalBudget;
    private final Map<Key, List<Statement>> memo = new HashMap<>();
    private long retained; // total statements held across all entries — the hard memory ceiling
    private final Counter hits;
    private final Counter misses;

    MemoizingTripleSource(
            TripleSource delegate,
            MeterRegistry registry,
            int perEntryCap,
            int maxEntries,
            long totalBudget) {
        this.delegate = delegate;
        this.perEntryCap = perEntryCap;
        this.maxEntries = maxEntries;
        this.totalBudget = totalBudget;
        this.hits = registry.counter("prolly.bindjoinmemo.hits");
        this.misses = registry.counter("prolly.bindjoinmemo.misses");
    }

    @Override
    public CloseableIteration<? extends Statement> getStatements(
            Resource subj, IRI pred, Value obj, Resource... contexts)
            throws QueryEvaluationException {
        // Only s+p-bound point lookups are the recurring inner re-probe; everything else (the
        // driving
        // scans) streams straight through, never materialized.
        if (subj == null || pred == null) {
            return delegate.getStatements(subj, pred, obj, contexts);
        }
        Key key = new Key(subj, pred, obj, Arrays.asList(contexts.clone()));
        List<Statement> cached = memo.get(key);
        if (cached != null) {
            hits.increment();
            return new CloseableIteratorIteration<>(cached.iterator());
        }
        misses.increment();
        List<Statement> materialized = new ArrayList<>();
        boolean overflow = false;
        try (CloseableIteration<? extends Statement> it =
                delegate.getStatements(subj, pred, obj, contexts)) {
            while (it.hasNext()) {
                materialized.add(it.next());
                if (materialized.size() > perEntryCap) {
                    overflow = true;
                    break;
                }
            }
            while (it.hasNext())
                materialized.add(it.next()); // drain the rest (correct result), but won't retain
        }
        // Retain only within the hard bounds: per-entry cap (not a scan in disguise), entry-count
        // cap, and
        // the global statement budget (the OOM ceiling — once the memo holds totalBudget statements
        // it stops
        // growing and serves further misses directly).
        if (!overflow
                && memo.size() < maxEntries
                && retained + materialized.size() <= totalBudget) {
            memo.put(key, materialized);
            retained += materialized.size();
        }
        return new CloseableIteratorIteration<>(materialized.iterator());
    }

    @Override
    public ValueFactory getValueFactory() {
        return delegate.getValueFactory();
    }
}
