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

import com.earasoft.prolly.rdf4j.sail.eval.GraphScopedZeroLengthPathIteration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.ZeroLengthPath;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryValueEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.DefaultEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.eclipse.rdf4j.query.impl.SimpleDataset;
import org.eclipse.rdf4j.sail.SailException;
import org.jspecify.annotations.Nullable;

/**
 * {@link DefaultEvaluationStrategy} with this store's conformance corrections — the base for BOTH
 * of {@code ProllySailConnection}'s evaluation paths (the stock path uses it directly; the routed
 * {@link ProllyEvaluationStrategy} extends it), so a correction applies identically with the
 * triejoin flag on or off.
 *
 * <p><b>The defect class both corrections close (W3C {@code (pp35) Named Graph 2}):</b> upstream
 * evaluates property paths under an unbound {@code GRAPH ?g} with <i>graph-blind</i> global
 * deduplication — {@code ZeroLengthPathIteration} keys its vertex walk on the vertex alone, and
 * {@code PathIteration} keys reachability on the {@code (start, end)} pair alone — so a vertex/pair
 * present in several named graphs is reported only for whichever graph the statement scan happens
 * to surface first. RDF4J's memory store passes the W3C test by insertion-order luck; this store's
 * content-addressed TermId ordering surfaces another graph first and loses the {@code ?g =
 * <ng-01.ttl>} rows. Per SPARQL 1.1, {@code GRAPH ?g} ranges over the dataset's named graphs and
 * each graph contributes its own solutions.
 *
 * <ul>
 *   <li>{@link ZeroLengthPath}: evaluated through {@link GraphScopedZeroLengthPathIteration}, whose
 *       vertex walk deduplicates per {@code (vertex, graph)} — covers bare zero-length nodes (the
 *       {@code p?} compilation shape).
 *   <li>{@link ArbitraryLengthPath} under an unbound context var: decomposed per named graph —
 *       {@code ?g} is bound to each graph in turn and upstream's {@code PathIteration} runs
 *       per-graph (its pair dedup is then correct by construction: the scan cannot leave the
 *       graph), results concatenated lazily. With a bound or absent context var the upstream step
 *       runs unchanged. The graph list honors the query dataset's named-graph set (FROM NAMED) when
 *       one is present, else the store's contexts — the same range {@code GRAPH ?g} has in a {@code
 *       StatementPattern}.
 * </ul>
 */
public class ProllyDefaultEvaluationStrategy extends DefaultEvaluationStrategy {

    private final ProllySailConnection conn;
    private final @Nullable Dataset queryDataset;

    public ProllyDefaultEvaluationStrategy(
            TripleSource tripleSource, @Nullable Dataset dataset, ProllySailConnection conn) {
        super(tripleSource, dataset, null);
        this.conn = conn;
        this.queryDataset = dataset;
    }

    @Override
    protected QueryEvaluationStep prepare(ZeroLengthPath zlp, QueryEvaluationContext context)
            throws QueryEvaluationException {
        Var subjectVar = zlp.getSubjectVar();
        Var objVar = zlp.getObjectVar();
        Var contextVar = zlp.getContextVar();
        QueryValueEvaluationStep subPrep = precompile(subjectVar, context);
        QueryValueEvaluationStep objPrep = precompile(objVar, context);
        return bindings -> {
            Value subj = null;
            try {
                subj = subPrep.evaluate(bindings);
            } catch (QueryEvaluationException ignored) {
                // an unevaluable endpoint is an unbound endpoint (upstream contract)
            }
            Value obj = null;
            try {
                obj = objPrep.evaluate(bindings);
            } catch (QueryEvaluationException ignored) {
                // as above
            }
            if (subj != null && obj != null && !subj.equals(obj)) {
                return QueryEvaluationStep.EMPTY_ITERATION;
            }
            return new GraphScopedZeroLengthPathIteration(
                    this, subjectVar, objVar, subj, obj, contextVar, bindings, context);
        };
    }

    @Override
    protected QueryEvaluationStep prepare(ArbitraryLengthPath alp, QueryEvaluationContext context)
            throws QueryEvaluationException {
        QueryEvaluationStep upstream = super.prepare(alp, context);
        Var contextVar = alp.getContextVar();
        if (contextVar == null || contextVar.hasValue()) {
            return upstream; // no graph var, or GRAPH <g>: upstream is already graph-confined
        }
        String ctxName = contextVar.getName();
        Var subjVar = alp.getSubjectVar();
        Var objVar = alp.getObjectVar();
        return bindings -> {
            // Decompose ONLY the shape the graph-blind pair dedup actually loses
            // (both endpoints unbound — the W3C pp35 shape). With a bound
            // endpoint upstream emits its zero-length mirror row exactly once
            // with ?g unbound; decomposing would multiply it per graph, a
            // cardinality deviation from upstream this store does not need.
            boolean subjUnbound = !subjVar.hasValue() && !bindings.hasBinding(subjVar.getName());
            boolean objUnbound = !objVar.hasValue() && !bindings.hasBinding(objVar.getName());
            if (bindings.hasBinding(ctxName) || !subjUnbound || !objUnbound) {
                return upstream.evaluate(bindings); // ?g or an endpoint arrived bound: upstream
            }
            List<Resource> graphs = namedGraphsInScope();
            // Deterministic result order regardless of store enumeration order.
            graphs.sort(Comparator.comparing(Value::stringValue));
            return new LookAheadIteration<>() {
                private int nextGraph = 0;
                private @Nullable CloseableIteration<BindingSet> current;

                @Override
                protected @Nullable BindingSet getNextElement() throws QueryEvaluationException {
                    while (true) {
                        if (current != null && current.hasNext()) {
                            return current.next();
                        }
                        if (current != null) {
                            current.close();
                            current = null;
                        }
                        if (nextGraph >= graphs.size()) {
                            return null;
                        }
                        // One upstream PathIteration per graph, ?g pre-bound: its
                        // (start, end) dedup is scoped to this graph by construction.
                        org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet augmented =
                                new org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet(
                                        bindings);
                        augmented.setBinding(ctxName, graphs.get(nextGraph++));
                        current = upstream.evaluate(augmented);
                    }
                }

                @Override
                protected void handleClose() throws QueryEvaluationException {
                    if (current != null) {
                        current.close();
                    }
                }
            };
        };
    }

    /**
     * The named graphs {@code GRAPH ?g} ranges over: the query dataset's named-graph set when a
     * dataset is present (SPARQL's FROM NAMED semantics — possibly empty, and {@link SimpleDataset}
     * with no named graphs genuinely means "none"), else every context in the store.
     */
    private List<Resource> namedGraphsInScope() {
        if (queryDataset != null) {
            return new ArrayList<>(queryDataset.getNamedGraphs());
        }
        List<Resource> graphs = new ArrayList<>();
        try (CloseableIteration<? extends Resource> it = conn.getContextIDs()) {
            while (it.hasNext()) {
                graphs.add(it.next());
            }
        } catch (SailException e) {
            throw new QueryEvaluationException(e);
        }
        return graphs;
    }
}
