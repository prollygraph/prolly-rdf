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

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 3 Step 10 of {@code multi-variable-leapfrog-triejoin.md} — the <b>streamability
 * classifier</b>. Given a {@link QuadPattern}, a global variable order, and an <i>index
 * permutation</i> (a column ordering of the quad columns {@code s,p,o,c}), decides whether the
 * triejoin can stream that pattern <b>directly off that index</b> — i.e. without the query-time
 * sort-materialized projection {@link LeapfrogTriejoin} builds today.
 *
 * <p>An index can stream a pattern under variable order {@code V} iff, reading the index's columns
 * left to right:
 *
 * <ol>
 *   <li><b>constants form a prefix</b> — every constant column (the pattern's bound positions
 *       <i>and</i> the graph) precedes every variable column; a constant that trails a variable
 *       cannot be fixed as a seek prefix; and
 *   <li><b>variables follow in global order</b> — the variable columns, in index order, are
 *       non-decreasing in their {@code V} position (so the trie's levels line up with the driver's
 *       binding order).
 * </ol>
 *
 * <p><b>The graph-trailing finding (the reason this step matters).</b> Quad patterns here have a
 * <i>constant graph</i> ({@code c}), and the two permutations the engine maintains — {@code SPOC}
 * and {@code POSC} — both put {@code c} <b>last</b>. A trailing constant violates rule&nbsp;1 for
 * every pattern with ≥1 variable, so neither SPOC nor POSC can stream <i>any</i> such pattern: they
 * all correctly fall back to the projection. Streaming needs a <b>graph-leading</b> permutation
 * (e.g. {@code CPSO}, {@code CPOS}). This classifier is what tells Step&nbsp;11 exactly which
 * permutations to add.
 *
 * <p>Columns are identified by their canonical SPOC tuple index: {@code S=0, P=1, O=2, C=3}. An
 * index permutation is the array of those indices in key order — {@code SPOC = [0,1,2,3]}, {@code
 * CPSO = [3,1,0,2]}.
 */
public final class IndexStreamability {

    public static final int S = 0, P = 1, O = 2, C = 3;

    private IndexStreamability() {}

    /**
     * The result of classifying one (pattern, order, index) triple.
     *
     * @param streamable whether the index can stream the pattern directly
     * @param prefixCols constant columns (SPOC indices) in index order — the fixed seek prefix;
     *     empty if not streamable
     * @param levelCols variable columns (SPOC indices) in index order — the trie levels; empty if
     *     not streamable
     * @param levelVarIdx global-variable-order index for each level (parallel to {@code
     *     levelCols}); empty if not streamable
     */
    public record Plan(boolean streamable, int[] prefixCols, int[] levelCols, int[] levelVarIdx) {
        static Plan notStreamable() {
            return new Plan(false, new int[0], new int[0], new int[0]);
        }
    }

    /** Classify whether {@code perm} can stream {@code q} under {@code varOrder}. */
    public static Plan analyze(int[] perm, QuadPattern q, List<String> varOrder) {
        // Role of each SPOC column: a global-variable index, or -1 for constant
        // (bound s/p/o, and the always-constant graph c).
        int[] roleVarIdx = new int[4];
        roleVarIdx[S] = q.s().isVar() ? varIdx(varOrder, q.s().value()) : -1;
        roleVarIdx[P] = q.p().isVar() ? varIdx(varOrder, q.p().value()) : -1;
        roleVarIdx[O] = q.o().isVar() ? varIdx(varOrder, q.o().value()) : -1;
        roleVarIdx[C] = -1; // graph is constant

        List<Integer> prefix = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        List<Integer> levelVars = new ArrayList<>();
        boolean seenVar = false;
        int lastVarOrder = -1;

        for (int col : perm) {
            int vi = roleVarIdx[col];
            if (vi < 0) { // constant column
                if (seenVar) return Plan.notStreamable(); // rule 1: constant trails a variable
                prefix.add(col);
            } else { // variable column
                if (vi <= lastVarOrder) return Plan.notStreamable(); // rule 2: out of global order
                seenVar = true;
                lastVarOrder = vi;
                levels.add(col);
                levelVars.add(vi);
            }
        }
        if (levels.isEmpty())
            return Plan.notStreamable(); // all-constant pattern: existence filter, not a trie
        return new Plan(true, toArray(prefix), toArray(levels), toArray(levelVars));
    }

    private static int varIdx(List<String> varOrder, String name) {
        int i = varOrder.indexOf(name);
        if (i < 0) throw new IllegalArgumentException("variable not in varOrder: " + name);
        return i;
    }

    private static int[] toArray(List<Integer> xs) {
        int[] a = new int[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        return a;
    }
}
