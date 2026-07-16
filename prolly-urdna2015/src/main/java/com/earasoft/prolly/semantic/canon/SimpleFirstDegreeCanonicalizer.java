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
import java.util.Set;

/**
 *
 *
 * <h3>First-degree-only RDF canonicalizer.</h3>
 *
 * <p>Computes a canonical labelling of blank nodes by hashing each blank node's local neighbourhood
 * (the quads where it appears) with other blank-node positions replaced by an opaque placeholder.
 * This handles the common "blank node was renamed by the parser" case cheaply and
 * deterministically.
 *
 * <h4>Algorithm</h4>
 *
 * <ol>
 *   <li>Collect every blank node B in the input.
 *   <li>For each B, build the list of quads where B appears as subject or object. Substitute {@code
 *       _:_self} for B and {@code _:_other} for any other blank node. Sort the resulting strings
 *       lexicographically and SHA-256 the join. That hash is B's <em>first-degree label</em>.
 *   <li>If two distinct blank nodes share the same first-degree label, throw {@link
 *       NonCanonicalizableException}. The collision means the inputs are either truly
 *       indistinguishable (full URDNA2015 would assign them the same canonical label — a correct
 *       collapse this canonicalizer cannot prove safe on its own) or distinguishable only via the
 *       N-degree algorithm. Either way, fail closed and let the caller escalate.
 *   <li>Sort the (blank node → first-degree label) entries by label, assign canonical labels {@code
 *       _:c14n0}, {@code _:c14n1}, … in that order, and rewrite every blank-node occurrence in the
 *       input.
 * </ol>
 *
 * <h4>What this catches</h4>
 *
 * <ul>
 *   <li><strong>Blank-node rename.</strong> Two graphs that differ only in the parser's mint of
 *       blank-node labels canonicalize to byte-identical quad sequences. This is the case that
 *       breaks naive merge for blank-node-bearing data.
 * </ul>
 *
 * <h4>What this does not catch</h4>
 *
 * <ul>
 *   <li>Cyclic blank-node graphs (two blank nodes that reference each other) — collide on
 *       first-degree hash; we throw.
 *   <li>Symmetric subgraphs (two blank nodes with structurally identical first-degree
 *       neighbourhoods but distinguished only by their further-out neighbours) — collide; we throw.
 *   <li>Either of the above is the responsibility of a future UrdnaCanonicalizer (iter 4+).
 * </ul>
 */
public final class SimpleFirstDegreeCanonicalizer implements RdfCanonicalizer {

    public static final SimpleFirstDegreeCanonicalizer INSTANCE =
            new SimpleFirstDegreeCanonicalizer();

    private static final String SELF_PLACEHOLDER = "_:_self";
    private static final String OTHER_PLACEHOLDER = "_:_other";

    @Override
    public List<QuadPattern> canonicalize(List<QuadPattern> quads) {
        Set<String> blanks = collectBlankNodes(quads);
        if (blanks.isEmpty()) return quads;

        Map<String, String> bnodeToHash = new HashMap<>();
        for (String bn : blanks) {
            bnodeToHash.put(bn, firstDegreeHash(bn, quads));
        }

        // Collision detection: distinct blank nodes must not share a first-degree label.
        Set<String> uniqueHashes = new HashSet<>(bnodeToHash.values());
        if (uniqueHashes.size() < blanks.size()) {
            throw new NonCanonicalizableException(
                    "first-degree hash collision among blank nodes: "
                            + collisionDiagnostic(bnodeToHash)
                            + ". Need full URDNA2015 N-degree algorithm.");
        }

        Map<String, String> bnodeToCanonical = assignCanonicalLabels(bnodeToHash);

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

    private static Map<String, String> assignCanonicalLabels(Map<String, String> bnodeToHash) {
        // Hash-sorted assignment: deterministic regardless of input ordering of blank nodes.
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
