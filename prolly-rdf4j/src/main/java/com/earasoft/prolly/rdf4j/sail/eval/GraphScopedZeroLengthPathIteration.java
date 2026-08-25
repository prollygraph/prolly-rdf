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
package com.earasoft.prolly.rdf4j.sail.eval;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.MutableBindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.StatementPattern.Scope;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.jspecify.annotations.Nullable;

/**
 * Zero-length property-path solutions with a <b>graph-aware</b> vertex walk — the fix for W3C
 * {@code (pp35) Named Graph 2}.
 *
 * <p><b>The upstream defect this replaces.</b> RDF4J's {@code ZeroLengthPathIteration} enumerates
 * the path's zero-step solutions (every node of the queried graph, bound to both endpoints) by
 * scanning statements and reporting each subject/object once, deduplicated in a single {@code
 * Set<Value>} — <i>even under {@code GRAPH ?g} with {@code ?g} unbound</i>, where the scan spans
 * every named graph. A vertex present in several graphs is therefore reported for only the FIRST
 * graph the scan happens to surface, and which graph that is depends on statement enumeration
 * order. RDF4J's memory store passes pp35 by insertion-order luck; this store's content-addressed
 * TermId ordering surfaces another graph first, loses the {@code ?g = <ng-01.ttl>} row for a shared
 * vertex, and fails the test (missing {@code t=:a}). Per SPARQL 1.1 the zero-length solutions are
 * per active graph: {@code GRAPH ?g} iterates named graphs and each graph contributes its own node
 * set.
 *
 * <p><b>The fix</b>: when both endpoints are unbound AND a context variable is in play, the
 * deduplication key is {@code (vertex, graph)} instead of {@code vertex} — one row per graph a
 * vertex inhabits, independent of enumeration order. Without a context variable (default-graph
 * paths) the key degrades to the vertex alone, byte-for-byte upstream behavior. The bound-endpoint
 * branches replicate upstream semantics exactly (they mirror the bound value onto the free end and
 * never consult the store), so the ONLY divergence is the named-graph vertex walk.
 */
public final class GraphScopedZeroLengthPathIteration extends LookAheadIteration<BindingSet> {

    private static final String ANON_SUBJECT_VAR = "zero_length_internal_start";
    private static final String ANON_PREDICATE_VAR = "zero_length_internal_pred";
    private static final String ANON_OBJECT_VAR = "zero_length_internal_end";

    /** Dedup key: a vertex within one named graph ({@code graph == null} = default graph). */
    private record VertexInGraph(Value vertex, @Nullable Value graph) {}

    private final BindingSet bindings;
    private final @Nullable Value subj;
    private final @Nullable Value obj;
    private final @Nullable Var contextVar;
    private final QueryEvaluationContext context;
    private final BiConsumer<Value, MutableBindingSet> setSubject;
    private final BiConsumer<Value, MutableBindingSet> setObject;
    private final @Nullable BiConsumer<Value, MutableBindingSet> setContext;

    /** Lazily-opened statement scan for the both-unbound vertex walk. */
    private @Nullable CloseableIteration<BindingSet> statements;

    private final @Nullable QueryEvaluationStep statementStep;
    private final Set<VertexInGraph> reported = new HashSet<>();

    /** The current statement's not-yet-emitted vertices (subject first, then object). */
    private @Nullable Value pendingVertex;

    private @Nullable Value pendingGraph;

    /** Single-result state for the bound-endpoint branches (upstream-identical semantics). */
    private @Nullable MutableBindingSet boundResult;

    private boolean boundResultEmitted;

    public GraphScopedZeroLengthPathIteration(
            EvaluationStrategy strategy,
            Var subjectVar,
            Var objVar,
            @Nullable Value subj,
            @Nullable Value obj,
            @Nullable Var contextVar,
            BindingSet bindings,
            QueryEvaluationContext context) {
        this.bindings = bindings;
        this.subj = subj;
        this.obj = obj;
        this.contextVar = contextVar;
        this.context = context;
        this.setSubject = context.addBinding(subjectVar.getName());
        this.setObject = context.addBinding(objVar.getName());
        this.setContext = contextVar != null ? context.addBinding(contextVar.getName()) : null;
        if (subj == null && obj == null) {
            // The vertex walk: every subject and object of every statement in the
            // active graph(s), one row per (vertex, graph). Same anonymous-pattern
            // construction as upstream, so dataset scoping and the context var
            // flow through the ordinary StatementPattern machinery.
            Var startVar = new Var(ANON_SUBJECT_VAR, true);
            Var predVar = new Var(ANON_PREDICATE_VAR, true);
            Var endVar = new Var(ANON_OBJECT_VAR, true);
            StatementPattern pattern =
                    contextVar != null
                            ? new StatementPattern(
                                    Scope.NAMED_CONTEXTS,
                                    startVar,
                                    predVar,
                                    endVar,
                                    contextVar.clone())
                            : new StatementPattern(startVar, predVar, endVar);
            this.statementStep = strategy.precompile(pattern, context);
        } else {
            this.statementStep = null;
            MutableBindingSet result = context.createBindingSet(bindings);
            if (obj == null) {
                // subj bound: the zero-length path ends where it starts
                setObject.accept(subj, result);
                this.boundResult = result;
            } else if (subj == null) {
                setSubject.accept(obj, result);
                this.boundResult = result;
            } else if (subj.equals(obj)) {
                this.boundResult = result; // both bound and equal: one empty-extension row
            } else {
                this.boundResult = null; // both bound, different: no zero-length solution
            }
        }
    }

    @Override
    protected @Nullable BindingSet getNextElement() throws QueryEvaluationException {
        if (statementStep == null) {
            if (boundResult != null && !boundResultEmitted) {
                boundResultEmitted = true;
                return boundResult;
            }
            return null;
        }
        if (statements == null) {
            statements = statementStep.evaluate(bindings);
        }
        while (true) {
            if (pendingVertex != null) {
                Value v = pendingVertex;
                pendingVertex = null;
                BindingSet emitted = emitIfFresh(v, pendingGraph);
                if (emitted != null) {
                    return emitted;
                }
                continue;
            }
            if (!statements.hasNext()) {
                statements.close();
                return null;
            }
            BindingSet st = statements.next();
            Value graph = contextVar != null ? st.getValue(contextVar.getName()) : null;
            Value subject = st.getValue(ANON_SUBJECT_VAR);
            pendingVertex = st.getValue(ANON_OBJECT_VAR); // object emitted on the next spin
            pendingGraph = graph;
            BindingSet emitted = emitIfFresh(Objects.requireNonNull(subject), graph);
            if (emitted != null) {
                return emitted;
            }
        }
    }

    private @Nullable BindingSet emitIfFresh(Value vertex, @Nullable Value graph) {
        if (!reported.add(new VertexInGraph(vertex, graph))) {
            return null;
        }
        MutableBindingSet next = context.createBindingSet(bindings);
        setSubject.accept(vertex, next);
        setObject.accept(vertex, next);
        // Bind the graph var only when the incoming bindings didn't already fix
        // it (a pre-bound ?g — e.g. the per-graph ArbitraryLengthPath
        // decomposition — already scoped the scan; re-adding would collide).
        if (setContext != null
                && graph != null
                && !bindings.hasBinding(Objects.requireNonNull(contextVar).getName())) {
            setContext.accept(graph, next);
        }
        return next;
    }

    @Override
    protected void handleClose() throws QueryEvaluationException {
        if (statements != null) {
            statements.close();
        }
    }
}
