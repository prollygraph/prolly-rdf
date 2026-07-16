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
package com.earasoft.prolly.semantic;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 Step 10 of {@code multi-variable-leapfrog-triejoin.md} — pins the {@link
 * IndexStreamability} classifier, including the <b>graph-trailing finding</b>: the maintained
 * {@code SPOC}/{@code POSC} permutations (graph last) cannot stream any constant-graph pattern, so
 * everything correctly falls back to the sort-materialized projection. The test also records which
 * graph-leading permutations each triangle pattern needs — the input to Step 11's permutation set +
 * sub-ADR.
 */
class IndexStreamabilityTest {

    // Index permutations as SPOC column indices (S=0,P=1,O=2,C=3) in key order.
    private static final int[] SPOC = {0, 1, 2, 3};
    private static final int[] POSC = {1, 2, 0, 3};
    private static final int[] CPSO = {3, 1, 0, 2};
    private static final int[] CPOS = {3, 1, 2, 0};

    private static final String E = "e"; // constant predicate
    private static final String G = "g"; // constant graph

    // ---- The finding: graph-trailing indexes stream nothing ----------------

    @Test
    void spocAndPoscStreamNoConstantGraphPattern() {
        // (?x e ?y) — the canonical 2-var pattern.
        QuadPattern p = QuadPattern.of("?x", E, "?y", G);
        List<String> order = List.of("?x", "?y");

        // SPOC: col0=s(?x) is a variable, then col1=p(const) trails it → constant-not-prefix.
        assertFalse(
                IndexStreamability.analyze(SPOC, p, order).streamable(),
                "SPOC (graph + predicate trailing) cannot stream (?x e ?y)");
        // POSC: p,o,s,c — the graph c (constant) trails the s/o variables.
        assertFalse(
                IndexStreamability.analyze(POSC, p, order).streamable(),
                "POSC (graph trailing) cannot stream (?x e ?y)");
    }

    // ---- Graph-leading permutations CAN stream --------------------------------

    @Test
    void cpsoStreamsForwardVarOrder() {
        // (?x e ?y), x<y. CPSO = c,p,s,o: constants {c,p} prefix; vars s(?x,0), o(?y,1) ascending.
        IndexStreamability.Plan plan =
                IndexStreamability.analyze(
                        CPSO, QuadPattern.of("?x", E, "?y", G), List.of("?x", "?y"));
        assertTrue(plan.streamable(), "CPSO streams (?x e ?y) under x<y");
        assertArrayEquals(
                new int[] {IndexStreamability.C, IndexStreamability.P}, plan.prefixCols());
        assertArrayEquals(new int[] {IndexStreamability.S, IndexStreamability.O}, plan.levelCols());
        assertArrayEquals(new int[] {0, 1}, plan.levelVarIdx());
    }

    @Test
    void cpsoRejectsReversedVarOrder() {
        // (?z e ?x) with global order [x,y,z]: s=?z(idx2), o=?x(idx0). CPSO presents s before o
        // → var-order 2 then 0, descending → not streamable by CPSO.
        IndexStreamability.Plan plan =
                IndexStreamability.analyze(
                        CPSO, QuadPattern.of("?z", E, "?x", G), List.of("?x", "?y", "?z"));
        assertFalse(
                plan.streamable(), "CPSO cannot stream (?z e ?x) under x<y<z (vars out of order)");
    }

    @Test
    void cposStreamsTheTriangleClosingEdge() {
        // (?z e ?x) with [x,y,z]: CPOS = c,p,o,s → o=?x(idx0) before s=?z(idx2), ascending →
        // streams.
        IndexStreamability.Plan plan =
                IndexStreamability.analyze(
                        CPOS, QuadPattern.of("?z", E, "?x", G), List.of("?x", "?y", "?z"));
        assertTrue(plan.streamable(), "CPOS streams the triangle's closing edge (?z e ?x)");
        assertArrayEquals(
                new int[] {IndexStreamability.C, IndexStreamability.P}, plan.prefixCols());
        assertArrayEquals(new int[] {IndexStreamability.O, IndexStreamability.S}, plan.levelCols());
        assertArrayEquals(new int[] {0, 2}, plan.levelVarIdx());
    }

    @Test
    void triangleNeedsBothCpsoAndCpos() {
        // The full triangle under [x,y,z]: edges (?x,?y),(?y,?z) stream on CPSO; (?z,?x) on CPOS.
        List<String> order = List.of("?x", "?y", "?z");
        assertTrue(
                IndexStreamability.analyze(CPSO, QuadPattern.of("?x", E, "?y", G), order)
                        .streamable());
        assertTrue(
                IndexStreamability.analyze(CPSO, QuadPattern.of("?y", E, "?z", G), order)
                        .streamable());
        assertFalse(
                IndexStreamability.analyze(CPSO, QuadPattern.of("?z", E, "?x", G), order)
                        .streamable());
        assertTrue(
                IndexStreamability.analyze(CPOS, QuadPattern.of("?z", E, "?x", G), order)
                        .streamable());
    }

    @Test
    void boundObjectStreamsUnderGraphLeadingPrefix() {
        // (?x e o0) — predicate AND object constant. CPOS = c,p,o,s: constants {c,p,o} prefix, var
        // s=?x.
        IndexStreamability.Plan plan =
                IndexStreamability.analyze(CPOS, QuadPattern.of("?x", E, "o0", G), List.of("?x"));
        assertTrue(
                plan.streamable(),
                "CPOS streams (?x e o0) — all constants lead, single var trails");
        assertArrayEquals(
                new int[] {IndexStreamability.C, IndexStreamability.P, IndexStreamability.O},
                plan.prefixCols());
        assertArrayEquals(new int[] {IndexStreamability.S}, plan.levelCols());
    }

    @Test
    void allConstantPatternIsNotATrie() {
        // (s0 e o0) — no variables → existence filter, not streamable as a trie.
        assertFalse(
                IndexStreamability.analyze(CPOS, QuadPattern.of("s0", E, "o0", G), List.of("?x"))
                        .streamable(),
                "all-constant pattern is an existence filter, not a trie");
    }
}
