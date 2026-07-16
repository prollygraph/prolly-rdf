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
package com.earasoft.prolly.semantic.canon;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 *
 *
 * <h3>Blank-node connected-component (BNCC) partitioner.</h3>
 *
 * <p>Partitions blank nodes into connected components via union-find over blank-blank edges. Two
 * blank nodes are in the same BNCC iff they are connected by a path of quads in which the two
 * endpoints of at least one quad on the path are both blank.
 *
 * <p>The key correctness property: <strong>blank nodes in different BNCCs cannot affect each
 * other's canonical labels.</strong> Canonicalization can therefore process each BNCC independently
 * and combine the results. This is the highest-leverage optimisation for gigabyte-scale graphs (see
 * {@code SCALING.md} §4).
 *
 * <h4>Examples</h4>
 *
 * <ul>
 *   <li>{@code _:a ex:knows ex:bob; _:b ex:knows ex:bob} — <em>two</em> BNCCs ({_:a}, {_:b}). The
 *       shared {@code ex:bob} is a named IRI, not a blank, so no edge unifies them.
 *   <li>{@code _:a ex:knows _:b; _:b ex:age 30} — <em>one</em> BNCC ({_:a, _:b}) because the first
 *       quad has two blank endpoints that get unioned.
 *   <li>{@code ex:alice ex:knows ex:bob} — <em>zero</em> BNCCs; the quad is all-named and lands in
 *       {@link Result#allNamedQuads()}.
 * </ul>
 *
 * <h4>Cost</h4>
 *
 * <p>O(quads × α(blanks)) where α is the inverse Ackermann function (effectively constant). For a
 * 100M-quad graph with 10M blank nodes, partitioning costs ~100M union-find operations; benchmark
 * target is sub-second on modern hardware.
 */
public final class BnccPartitioner {

    private BnccPartitioner() {}

    /**
     * Result of a BNCC partitioning pass.
     *
     * @param bnodeToBnccId blank-node identifier → 0-based BNCC index
     * @param bnccCount total number of distinct BNCCs
     * @param allNamedQuads quads whose subject and object are both named (not blank); these can
     *     pass through canonicalization unchanged
     * @param perBnccQuads one quad-list per BNCC, indexed by id; each list contains every quad that
     *     touches at least one blank in that BNCC
     */
    public record Result(
            Map<String, Integer> bnodeToBnccId,
            int bnccCount,
            List<QuadPattern> allNamedQuads,
            List<List<QuadPattern>> perBnccQuads) {

        public Result {
            bnodeToBnccId = Map.copyOf(bnodeToBnccId);
            allNamedQuads = List.copyOf(allNamedQuads);
            // perBnccQuads holds inner mutable lists during construction;
            // freeze on read via Collections.unmodifiableList wrapper.
            List<List<QuadPattern>> frozen = new ArrayList<>(perBnccQuads.size());
            for (List<QuadPattern> inner : perBnccQuads) frozen.add(List.copyOf(inner));
            perBnccQuads = Collections.unmodifiableList(frozen);
        }
    }

    /**
     * Partition the blank nodes of {@code quads} into BNCCs.
     *
     * @param quads the input graph; not mutated
     * @return result describing the partitioning
     */
    public static Result partition(List<QuadPattern> quads) {
        // Pass 1: union-find. For each quad, if both endpoints are blank, union them.
        UnionFind uf = new UnionFind();
        for (QuadPattern q : quads) {
            String s = q.s().value();
            String o = q.o().value();
            boolean sBlank = RdfCanonicalizer.isBlankNode(s);
            boolean oBlank = RdfCanonicalizer.isBlankNode(o);

            if (sBlank) uf.makeSet(s);
            if (oBlank) uf.makeSet(o);
            if (sBlank && oBlank) uf.union(s, o);
        }

        // Pass 2: assign sequential bncc ids to roots in order-of-discovery
        // (deterministic for a given quad-list).
        Map<String, Integer> rootToBnccId = new HashMap<>();
        Map<String, Integer> bnodeToBnccId = new HashMap<>();
        for (String bn : uf.allElements()) {
            String root = uf.find(bn);
            Integer id = rootToBnccId.get(root);
            if (id == null) {
                id = rootToBnccId.size();
                rootToBnccId.put(root, id);
            }
            bnodeToBnccId.put(bn, id);
        }
        int bnccCount = rootToBnccId.size();

        // Pass 3: assign quads to buckets.
        List<QuadPattern> allNamed = new ArrayList<>();
        List<List<QuadPattern>> perBncc = new ArrayList<>(bnccCount);
        for (int i = 0; i < bnccCount; i++) perBncc.add(new ArrayList<>());

        for (QuadPattern q : quads) {
            String s = q.s().value();
            String o = q.o().value();
            boolean sBlank = RdfCanonicalizer.isBlankNode(s);
            boolean oBlank = RdfCanonicalizer.isBlankNode(o);

            if (!sBlank && !oBlank) {
                allNamed.add(q);
                continue;
            }
            // Both blanks must be in same BNCC if both present (we unioned them).
            // s/o is a blank ⟹ it is in bnodeToBnccId; its BNCC id ⟹ a list in perBncc.
            Integer id =
                    Objects.requireNonNull(sBlank ? bnodeToBnccId.get(s) : bnodeToBnccId.get(o));
            Objects.requireNonNull(perBncc.get(id)).add(q);
        }

        return new Result(bnodeToBnccId, bnccCount, allNamed, perBncc);
    }

    // ---- Union-find (path compression + union by rank) ----------------------

    private static final class UnionFind {
        private final Map<String, String> parent = new HashMap<>();
        private final Map<String, Integer> rank = new HashMap<>();

        void makeSet(String x) {
            parent.putIfAbsent(x, x);
            rank.putIfAbsent(x, 0);
        }

        String find(String x) {
            String p = Objects.requireNonNull(parent.get(x)); // x was makeSet'd
            if (!p.equals(x)) {
                String root = find(p);
                parent.put(x, root); // path compression
                return root;
            }
            return p;
        }

        void union(String x, String y) {
            String rx = find(x);
            String ry = find(y);
            if (rx.equals(ry)) return;
            int rkx = Objects.requireNonNull(rank.get(rx)); // rx/ry are roots, makeSet'd
            int rky = Objects.requireNonNull(rank.get(ry));
            if (rkx < rky) {
                parent.put(rx, ry);
            } else if (rkx > rky) {
                parent.put(ry, rx);
            } else {
                parent.put(ry, rx);
                rank.put(rx, rkx + 1);
            }
        }

        Iterable<String> allElements() {
            return parent.keySet();
        }
    }
}
