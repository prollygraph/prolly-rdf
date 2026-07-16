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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.Test;

/**
 * Step 5 of plans/triejoin-evaluation-wiring.md — asserts the rewrite shape + the routed counter
 * (no evaluation yet). Cyclic BGPs become a {@link TriejoinNode}; acyclic ones are left for the
 * bind-join.
 */
class TriejoinRoutingOptimizerTest {

    private static int routeAndCountNodes(String query) {
        TupleExpr expr = new SPARQLParser().parseQuery(query, "urn:base:").getTupleExpr();
        new TriejoinRoutingOptimizer().optimize(expr, null, null);
        int[] count = {0};
        expr.visit(
                new AbstractQueryModelVisitor<RuntimeException>() {
                    @Override
                    public void meetOther(QueryModelNode node) {
                        if (node instanceof TriejoinNode) count[0]++;
                        else super.meetOther(node);
                    }
                });
        return count[0];
    }

    private static int routedCount(String query) {
        TupleExpr expr = new SPARQLParser().parseQuery(query, "urn:base:").getTupleExpr();
        TriejoinRoutingOptimizer opt = new TriejoinRoutingOptimizer();
        opt.optimize(expr, null, null);
        return opt.routedCount();
    }

    @Test
    void triangleIsRewrittenToOneTriejoinNode() {
        String q = "SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x }";
        assertEquals(1, routedCount(q), "the cyclic triangle is routed");
        assertEquals(
                1, routeAndCountNodes(q), "and the algebra now holds exactly one TriejoinNode");
    }

    @Test
    void fourCycleIsRewritten() {
        assertEquals(
                1,
                routedCount(
                        "SELECT * WHERE { ?a <urn:e> ?b . ?b <urn:e> ?c . ?c <urn:e> ?d . ?d <urn:e> ?a }"));
    }

    @Test
    void triangleUnderFilterIsRewritten_filterPreserved() {
        assertEquals(
                1,
                routedCount(
                        "SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x . FILTER(?x != ?y) }"));
    }

    @Test
    void path2IsNotRouted() {
        assertEquals(0, routedCount("SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z }"));
        assertEquals(0, routeAndCountNodes("SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z }"));
    }

    @Test
    void starIsNotRouted() {
        assertEquals(
                0, routedCount("SELECT * WHERE { ?h <urn:a> ?a . ?h <urn:b> ?b . ?h <urn:c> ?c }"));
    }

    @Test
    void namedGraphTriangleIsNotRouted() {
        assertEquals(
                0,
                routedCount(
                        "SELECT * WHERE { GRAPH ?g { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x } }"));
    }
}
