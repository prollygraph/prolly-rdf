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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 *
 *
 * <h3>Two-degree RDF canonicalizer.</h3>
 *
 * <p>A strict generalisation of {@link SimpleFirstDegreeCanonicalizer}. After computing
 * first-degree hashes, it does one round of hash propagation through blank-node neighbours: each
 * blank node's second-degree label combines its own first-degree hash with the sorted first-degree
 * hashes of any blank-node neighbours it touches.
 *
 * <h4>What this closes (relative to iter 2)</h4>
 *
 * <ul>
 *   <li>Two blank nodes with structurally identical first-degree neighbourhoods but different
 *       blank-node neighbours (and those neighbours are distinguishable by first-degree). Common in
 *       practice for reified statements pointing at distinguishable subjects.
 * </ul>
 *
 * <h4>What this still fails closed on (still needs full URDNA2015)</h4>
 *
 * <ul>
 *   <li>Cyclic blank-node pairs with no distinguishing distant signal (e.g. {@code _:b1 → _:b2},
 *       {@code _:b2 → _:b1} alone).
 *   <li>Truly symmetric graphs (where N-degree propagation would also collapse the candidates to
 *       the same canonical name — legitimate, but this canonicalizer conservatively throws rather
 *       than guess).
 *   <li>Anything requiring full N-degree recursion through deep symmetric subgraphs.
 * </ul>
 *
 * <p>Failure mode is identical to {@link SimpleFirstDegreeCanonicalizer}: {@link
 * NonCanonicalizableException}. Caller can fall through to a full URDNA2015 implementation (iter
 * 5+) when one exists.
 */
public final class SecondDegreeCanonicalizer implements RdfCanonicalizer {

    public static final SecondDegreeCanonicalizer INSTANCE = new SecondDegreeCanonicalizer();

    private static final String SELF_PLACEHOLDER = "_:_self";
    private static final String OTHER_PLACEHOLDER = "_:_other";

    @Override
    public List<QuadPattern> canonicalize(List<QuadPattern> quads) {
        Set<String> blanks = collectBlankNodes(quads);
        if (blanks.isEmpty()) return quads;

        // Phase 1: first-degree hashes.
        Map<String, String> bnodeToFirstDegree = new HashMap<>();
        for (String bn : blanks) {
            bnodeToFirstDegree.put(bn, firstDegreeHash(bn, quads));
        }

        // Phase 2: adjacency map. For each blank node, the set of
        // other blank nodes it touches via any predicate.
        Map<String, Set<String>> adjacency = buildBlankAdjacency(quads, blanks);

        // Phase 3: second-degree = h₁(self) || sorted h₁(neighbours).
        Map<String, String> bnodeToSecondDegree = new HashMap<>();
        for (String bn : blanks) {
            List<String> neighbourHashes = new ArrayList<>();
            for (String neighbour : adjacency.getOrDefault(bn, Set.of())) {
                neighbourHashes.add(bnodeToFirstDegree.get(neighbour));
            }
            Collections.sort(neighbourHashes);
            bnodeToSecondDegree.put(
                    bn,
                    sha256Hex(
                            bnodeToFirstDegree.get(bn) + "|" + String.join(",", neighbourHashes)));
        }

        // Phase 4: collision detection on h₂.
        Set<String> uniqueHashes = new HashSet<>(bnodeToSecondDegree.values());
        if (uniqueHashes.size() < blanks.size()) {
            throw new NonCanonicalizableException(
                    "second-degree hash collision among blank nodes: "
                            + collisionDiagnostic(bnodeToSecondDegree)
                            + ". Need full URDNA2015 N-degree algorithm.");
        }

        Map<String, String> bnodeToCanonical = assignCanonicalLabels(bnodeToSecondDegree);
        return rewrite(quads, bnodeToCanonical);
    }

    // ---- internal helpers ---------------------------------------------------

    private static Set<String> collectBlankNodes(List<QuadPattern> quads) {
        Set<String> blanks = new HashSet<>();
        for (QuadPattern q : quads) {
            String s = q.s().value();
            String o = q.o().value();
            if (RdfCanonicalizer.isBlankNode(s)) blanks.add(s);
            if (RdfCanonicalizer.isBlankNode(o)) blanks.add(o);
        }
        return blanks;
    }

    private static String firstDegreeHash(String target, List<QuadPattern> quads) {
        List<String> serialized = new ArrayList<>();
        for (QuadPattern q : quads) {
            String s = q.s().value();
            String o = q.o().value();
            boolean sIsTarget = s.equals(target);
            boolean oIsTarget = o.equals(target);
            if (!sIsTarget && !oIsTarget) continue;

            String sCanon =
                    sIsTarget
                            ? SELF_PLACEHOLDER
                            : (RdfCanonicalizer.isBlankNode(s) ? OTHER_PLACEHOLDER : s);
            String oCanon =
                    oIsTarget
                            ? SELF_PLACEHOLDER
                            : (RdfCanonicalizer.isBlankNode(o) ? OTHER_PLACEHOLDER : o);
            serialized.add(sCanon + " " + q.p().value() + " " + oCanon + " " + q.c());
        }
        Collections.sort(serialized);
        return sha256Hex(String.join("\n", serialized));
    }

    private static Map<String, Set<String>> buildBlankAdjacency(
            List<QuadPattern> quads, Set<String> blanks) {
        Map<String, Set<String>> adj = new HashMap<>();
        for (String bn : blanks) adj.put(bn, new HashSet<>());

        for (QuadPattern q : quads) {
            String s = q.s().value();
            String o = q.o().value();
            boolean sBlank = blanks.contains(s);
            boolean oBlank = blanks.contains(o);
            if (sBlank && oBlank && !s.equals(o)) {
                // s and o are blanks ⟹ both are keys in adj (populated above for every blank).
                Objects.requireNonNull(adj.get(s)).add(o);
                Objects.requireNonNull(adj.get(o)).add(s);
            }
        }
        return adj;
    }

    private static Map<String, String> assignCanonicalLabels(Map<String, String> bnodeToHash) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(bnodeToHash.entrySet());
        entries.sort(Map.Entry.<String, String>comparingByValue().thenComparing(Map.Entry::getKey));
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            result.put(entries.get(i).getKey(), "_:c14n" + i);
        }
        return result;
    }

    private static List<QuadPattern> rewrite(
            List<QuadPattern> quads, Map<String, String> bnodeToCanonical) {
        List<QuadPattern> result = new ArrayList<>(quads.size());
        for (QuadPattern q : quads) {
            String s = bnodeToCanonical.getOrDefault(q.s().value(), q.s().value());
            String o = bnodeToCanonical.getOrDefault(q.o().value(), q.o().value());
            result.add(QuadPattern.of(s, q.p().value(), o, q.c()));
        }
        return result;
    }

    private static String collisionDiagnostic(Map<String, String> bnodeToHash) {
        Map<String, List<String>> hashToBnodes = new HashMap<>();
        for (Map.Entry<String, String> e : bnodeToHash.entrySet()) {
            hashToBnodes.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : hashToBnodes.entrySet()) {
            if (e.getValue().size() > 1) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getValue());
            }
        }
        return sb.toString();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
