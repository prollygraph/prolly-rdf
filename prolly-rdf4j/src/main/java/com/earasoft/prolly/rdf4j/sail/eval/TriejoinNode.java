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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.query.algebra.AbstractQueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryModelVisitor;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.Var;

/**
 *
 *
 * <h3>Custom SPARQL algebra node: a cyclic BGP routed to the WCOJ {@code LeapfrogTriejoin}.</h3>
 *
 * <p>Step 5 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md}. The {@link
 * TriejoinRoutingOptimizer} replaces a maximal pure-{@code Join} subtree with one of these; the
 * surrounding algebra (FILTER, projection, OPTIONAL, ORDER BY) is untouched (D-1). Phase 2's {@code
 * ProllyEvaluationStrategy} evaluates it by running the triejoin over ProllySail's TermId indexes
 * and emitting {@code BindingSet}s.
 *
 * <p>The original {@link StatementPattern}s + the chosen global variable order are carried as
 * <b>data</b>, not visited children (the node is a leaf to RDF4J's tree machinery; it accepts
 * visitors via {@code meetOther}). It produces the union of the patterns' variable names.
 */
public final class TriejoinNode extends AbstractQueryModelNode implements TupleExpr {

    private final List<StatementPattern> patterns;
    private final List<String> varOrder;

    public TriejoinNode(List<StatementPattern> patterns, List<String> varOrder) {
        this.patterns = new ArrayList<>(patterns);
        this.varOrder = List.copyOf(varOrder);
    }

    public List<StatementPattern> patterns() {
        return patterns;
    }

    public List<String> varOrder() {
        return varOrder;
    }

    @Override
    public Set<String> getBindingNames() {
        Set<String> names = new LinkedHashSet<>();
        for (StatementPattern p : patterns) {
            for (Var v :
                    new Var[] {
                        p.getSubjectVar(), p.getPredicateVar(), p.getObjectVar(), p.getContextVar()
                    }) {
                if (v != null && !v.hasValue()) names.add(v.getName());
            }
        }
        return names;
    }

    @Override
    public Set<String> getAssuredBindingNames() {
        return getBindingNames(); // a BGP binds every variable it mentions (no optionals within)
    }

    @Override
    public <X extends Exception> void visit(QueryModelVisitor<X> visitor) throws X {
        visitor.meetOther(this); // RDF4J's extension hook for unknown nodes
    }

    @Override
    public <X extends Exception> void visitChildren(QueryModelVisitor<X> visitor) throws X {
        // no child nodes — the patterns are carried as data, not as visitable children
    }

    @Override
    public void replaceChildNode(QueryModelNode current, QueryModelNode replacement) {
        // Leaf to RDF4J's tree machinery — the patterns are carried as data, not child nodes.
        throw new IllegalArgumentException("Node is not a child node: " + current);
    }

    @Override
    public TriejoinNode clone() {
        List<StatementPattern> copy = new ArrayList<>(patterns.size());
        for (StatementPattern p : patterns) copy.add(p.clone());
        return new TriejoinNode(copy, varOrder);
    }

    @Override
    public String getSignature() {
        return "TriejoinNode (" + patterns.size() + " patterns, order=" + varOrder + ")";
    }
}
