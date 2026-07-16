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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 *
 * <h3>α-acyclicity (GYO) test for a basic graph pattern's join hypergraph.</h3>
 *
 * <p>Step 4 of {@code prolly-rdf4j/plans/triejoin-evaluation-wiring.md}: decides whether a BGP is
 * the <b>cyclic / multi-way</b> shape where the worst-case-optimal {@code LeapfrogTriejoin} beats
 * RDF4J's bind-join (it dodges the O(N²) intermediate blow-up), versus the <b>acyclic</b> shape
 * where the bind-join wins (per the head-to-head in {@code triejoin-performance.md}).
 *
 * <p>Model: one hyperedge per pattern = that pattern's <i>join-variable</i> set (constants don't
 * participate). The hypergraph is <b>α-acyclic</b> iff the GYO reduction empties it — repeatedly:
 * (1) drop a variable that occurs in exactly one edge (an "ear" vertex), and (2) drop an edge that
 * is contained in another edge. The BGP is <b>cyclic</b> iff any edge survives. (Cyclicity requires
 * ≥3 patterns — two hyperedges always form a join tree.)
 */
public final class CyclicBgpDetector {

    private CyclicBgpDetector() {}

    /**
     * True iff the join hypergraph is NOT α-acyclic (i.e. the join is cyclic — route to the
     * triejoin).
     */
    public static boolean isCyclic(Collection<? extends Set<String>> patternVarSets) {
        List<Set<String>> edges = new ArrayList<>();
        for (Set<String> e : patternVarSets) {
            if (!e.isEmpty()) edges.add(new HashSet<>(e));
        }
        boolean changed = true;
        while (changed) {
            changed = false;

            // (1) Ear vertices: drop variables that occur in exactly one edge.
            Map<String, Integer> occ = new HashMap<>();
            for (Set<String> e : edges) {
                for (String v : e) occ.merge(v, 1, Integer::sum);
            }
            for (Set<String> e : edges) {
                if (e.removeIf(v -> Objects.requireNonNull(occ.get(v)) == 1)) changed = true;
            }
            if (edges.removeIf(Set::isEmpty)) changed = true;

            // (2) Edge containment: drop an edge contained in a different edge.
            containment:
            for (int i = 0; i < edges.size(); i++) {
                for (int j = 0; j < edges.size(); j++) {
                    if (i != j && edges.get(j).containsAll(edges.get(i))) {
                        edges.remove(i);
                        changed = true;
                        break containment;
                    }
                }
            }
        }
        return !edges.isEmpty();
    }
}
