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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.parser.sparql.SPARQLParser;
import org.junit.jupiter.api.Test;

/**
 * Step 3 of plans/triejoin-evaluation-wiring.md — BGP extraction over the parsed SPARQL algebra.
 */
class BgpExtractorTest {

    private static TupleExpr parse(String q) {
        return new SPARQLParser().parseQuery(q, "urn:base:").getTupleExpr();
    }

    /** Does any extracted multi-pattern BGP test cyclic? (the route-to-triejoin decision). */
    private static boolean anyCyclicBgp(String q) {
        return BgpExtractor.extract(parse(q)).stream()
                .filter(b -> b.patterns().size() >= 2)
                .anyMatch(b -> CyclicBgpDetector.isCyclic(b.varSets()));
    }

    @Test
    void triangleExtractsOneCyclicBgpOfThree() {
        List<BgpExtractor.Bgp> bgps =
                BgpExtractor.extract(
                        parse("SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x }"));
        List<BgpExtractor.Bgp> multi = bgps.stream().filter(b -> b.patterns().size() >= 2).toList();
        assertEquals(1, multi.size(), "one maximal BGP");
        assertEquals(3, multi.get(0).patterns().size(), "with all three patterns");
        assertTrue(CyclicBgpDetector.isCyclic(multi.get(0).varSets()));
    }

    @Test
    void path2IsNotRouted() {
        assertFalse(anyCyclicBgp("SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z }"));
    }

    @Test
    void starIsNotRouted() {
        assertFalse(
                anyCyclicBgp("SELECT * WHERE { ?h <urn:a> ?a . ?h <urn:b> ?b . ?h <urn:c> ?c }"));
    }

    @Test
    void filterDoesNotBreakBgpExtraction() {
        assertTrue(
                anyCyclicBgp(
                        "SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x . FILTER(?x != ?y) }"));
    }

    @Test
    void optionalPatternIsNotMergedIntoTheTriangle() {
        // the required triangle is cyclic; the OPTIONAL pattern sits across a LeftJoin boundary
        assertTrue(
                anyCyclicBgp(
                        "SELECT * WHERE { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x OPTIONAL { ?x <urn:f> ?w } }"));
    }

    @Test
    void namedGraphTriangleIsExcluded() { // D-7: default-graph only
        assertFalse(
                anyCyclicBgp(
                        "SELECT * WHERE { GRAPH ?g { ?x <urn:e> ?y . ?y <urn:e> ?z . ?z <urn:e> ?x } }"));
    }

    @Test
    void selfPatternRejectsTheBgp() { // D-7: no repeated var within a pattern
        assertFalse(
                anyCyclicBgp("SELECT * WHERE { ?x <urn:e> ?x . ?x <urn:e> ?z . ?z <urn:e> ?x }"));
    }
}
