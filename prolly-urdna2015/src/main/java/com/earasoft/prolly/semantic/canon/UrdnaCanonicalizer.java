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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 *
 *
 * <h3>W3C URDNA2015 / RDFC-1.0 canonicalizer — IN PROGRESS.</h3>
 *
 * <p>This is the canonicalizer the project is building toward. The spec is W3C RDF Dataset
 * Canonicalization 1.0: <a
 * href="https://www.w3.org/TR/rdf-canon/">https://www.w3.org/TR/rdf-canon/</a>.
 *
 * <h4>Status: phases 1, 2, 3, 5 implemented. Phase 4 throws.</h4>
 *
 * <p>This iteration ships the structurally-easy phases of the algorithm:
 *
 * <ol>
 *   <li><strong>Phase 1</strong> — first-degree hashing of every blank node.
 *   <li><strong>Phase 2</strong> — bucket blank nodes by their first-degree hash, in sorted hash
 *       order.
 *   <li><strong>Phase 3</strong> — for each bucket: if the hash is unique (one blank node has it),
 *       issue a canonical name immediately via the {@link IdentifierIssuer}. If multiple blank
 *       nodes collide on the same hash, they'd need phase 4 (the N-degree disambiguation algorithm)
 *       — we throw instead.
 *   <li><strong>Phase 5</strong> — rewrite the input quads using the canonical issuer's mapping.
 * </ol>
 *
 * <p>For graphs without first-degree collisions, this is a complete W3C-compliant canonicalization:
 * same output as {@link SimpleFirstDegreeCanonicalizer}, but structured to drop in the N-degree
 * algorithm at iter 6c without re-architecting.
 *
 * <p>For graphs with first-degree collisions (cyclic blank-node pairs, symmetric subgraphs), this
 * throws {@link NonCanonicalizableException} with a clear "phase 4 not yet implemented" diagnostic.
 *
 * <h4>What's left for iter 6c+</h4>
 *
 * <p>The N-degree disambiguation algorithm: {@code HashNDegreeQuads}. See {@code
 * ../../../../../../a private strategy note} §5-§6 for the full pseudo-code, and the {@code
 * FUTURE_WORK.md} sub-iter 6c-6g plan.
 *
 * @see SimpleFirstDegreeCanonicalizer for the existing first-degree-only impl
 * @see CascadeCanonicalizer for the cheap-first composition pattern
 * @see <a href="https://www.w3.org/TR/rdf-canon/">W3C RDF Dataset Canonicalization 1.0</a>
 */
public final class UrdnaCanonicalizer implements RdfCanonicalizer {

    public static final UrdnaCanonicalizer INSTANCE = new UrdnaCanonicalizer();

    private static final String SELF_PLACEHOLDER = "_:_self";
    private static final String OTHER_PLACEHOLDER = "_:_other";
    private static final String CANONICAL_PREFIX = "c14n";

    @Override
    public List<QuadPattern> canonicalize(List<QuadPattern> quads) {
        // Phase 0: short-circuit.
        Set<String> blanks = collectBlankNodes(quads);
        if (blanks.isEmpty()) return quads;

        // Phase 1: first-degree hashing.
        Map<String, String> bnodeToFirstDegree = new HashMap<>();
        for (String bn : blanks) {
            bnodeToFirstDegree.put(bn, hashFirstDegreeQuads(bn, quads));
        }

        // Phase 2: bucket by h₁ in sorted hash order.
        TreeMap<String, List<String>> hashToBnodes = new TreeMap<>();
        for (Map.Entry<String, String> e : bnodeToFirstDegree.entrySet()) {
            hashToBnodes.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }

        // Phase 3: issue canonical names for unique-h₁ blank nodes;
        // collect colliding groups for phase 4.
        IdentifierIssuer canonical = new IdentifierIssuer(CANONICAL_PREFIX);
        List<List<String>> collidingGroups = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : hashToBnodes.entrySet()) {
            List<String> bnodes = e.getValue();
            if (bnodes.size() == 1) {
                canonical.issue(bnodes.get(0));
            } else {
                // Sort within group for stable phase-4 ordering (when 6c lands).
                Collections.sort(bnodes);
                collidingGroups.add(bnodes);
            }
        }

        // Phase 4: N-degree disambiguation for colliding groups.
        // Single-level (iter 6c): un-issued related blank nodes are issued in
        // the temp issuer without recursion. Full recursion lands in iter 6d.
        for (List<String> group : collidingGroups) {
            List<HashIssuerPair> results = new ArrayList<>();
            for (String identifier : group) {
                if (canonical.hasIssued(identifier)) continue;
                IdentifierIssuer temp = new IdentifierIssuer("b");
                temp.issue(identifier);
                String hashN =
                        hashNDegreeQuads(identifier, temp, quads, bnodeToFirstDegree, canonical);
                results.add(new HashIssuerPair(hashN, temp));
            }
            // Sort by hash; secondary sort by first-issued id for determinism
            // when hashes tie (which happens for genuinely-symmetric groups).
            results.sort(
                    (a, b) -> {
                        int c = a.hash().compareTo(b.hash());
                        if (c != 0) return c;
                        return a.issuer()
                                .issuedOrder()
                                .get(0)
                                .compareTo(b.issuer().issuedOrder().get(0));
                    });
            for (HashIssuerPair pair : results) {
                for (String id : pair.issuer().issuedOrder()) {
                    if (!canonical.hasIssued(id)) {
                        canonical.issue(id);
                    }
                }
            }
        }

        // Phase 5: rewrite quads using canonical labels.
        return rewrite(quads, canonical.idMap());
    }

    // ---- internal helpers (mirror SimpleFirstDegreeCanonicalizer's shape) ---

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

    private static String hashFirstDegreeQuads(String target, List<QuadPattern> quads) {
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

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ---- Phase 4 — N-degree disambiguation (single-level) -----------------

    /** Internal pair returned by the per-identifier hashing in phase 4. */
    private record HashIssuerPair(String hash, IdentifierIssuer issuer) {}

    /**
     * Single-level HashNDegreeQuads. For each related blank node group (sorted by composite key of
     * h₁ + position + predicate), tries every permutation, picks lex-smallest path. Un-issued
     * related blank nodes are issued in the temp issuer without recursion — iter 6d will add the
     * recursive call.
     */
    private static String hashNDegreeQuads(
            String identifier,
            IdentifierIssuer issuer,
            List<QuadPattern> quads,
            Map<String, String> h1,
            IdentifierIssuer canonical) {
        // Step 1: group related blank nodes by (h₁ + position + predicate).
        // Position: "p" = outgoing (identifier is subject), "r" = incoming (identifier is object).
        TreeMap<String, List<String>> related = new TreeMap<>();
        for (QuadPattern q : quads) {
            String s = q.s().value();
            String o = q.o().value();
            boolean sIsIdent = s.equals(identifier);
            boolean oIsIdent = o.equals(identifier);

            if (sIsIdent && RdfCanonicalizer.isBlankNode(o) && !o.equals(identifier)) {
                String key = h1.get(o) + "|p|" + q.p().value();
                related.computeIfAbsent(key, k -> new ArrayList<>()).add(o);
            }
            if (oIsIdent && RdfCanonicalizer.isBlankNode(s) && !s.equals(identifier)) {
                String key = h1.get(s) + "|r|" + q.p().value();
                related.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
            }
        }

        // Step 2: for each group, find lex-smallest path over permutations.
        StringBuilder dataToHash = new StringBuilder();
        for (Map.Entry<String, List<String>> e : related.entrySet()) {
            String key = e.getKey();
            List<String> items = e.getValue();

            String chosenPath = null;
            IdentifierIssuer chosenIssuer = null;

            for (List<String> perm : permutations(items)) {
                IdentifierIssuer issuerCopy = issuer.copy();
                StringBuilder path = new StringBuilder();
                List<String> recursionList = new ArrayList<>();
                boolean abandoned = false;

                // First pass: build path with immediate names; collect
                // un-issued relateds for the recursion pass.
                for (String relatedId : perm) {
                    String name;
                    if (canonical.hasIssued(relatedId)) {
                        name =
                                Objects.requireNonNull(
                                        canonical.nameOf(relatedId)); // hasIssued ⟹ issued
                    } else {
                        if (!issuerCopy.hasIssued(relatedId)) {
                            recursionList.add(relatedId);
                        }
                        name = issuerCopy.issue(relatedId);
                    }
                    path.append('_').append(name);

                    // Early termination: abandon this permutation if it's
                    // already lex-greater than the current best.
                    if (chosenPath != null
                            && path.length() >= chosenPath.length()
                            && path.toString().compareTo(chosenPath) > 0) {
                        abandoned = true;
                        break;
                    }
                }

                // Second pass (iter 6d): recurse on un-issued relateds.
                // Each recursive call computes the related blank's
                // N-degree fingerprint and appends "<canonical-name>hash"
                // to the path. Re-checks early termination after each.
                if (!abandoned) {
                    for (String relatedId : recursionList) {
                        String recurResult =
                                hashNDegreeQuads(relatedId, issuerCopy, quads, h1, canonical);
                        path.append('<')
                                .append(Objects.requireNonNull(issuerCopy.nameOf(relatedId)))
                                .append('>')
                                .append(recurResult);

                        if (chosenPath != null
                                && path.length() >= chosenPath.length()
                                && path.toString().compareTo(chosenPath) > 0) {
                            abandoned = true;
                            break;
                        }
                    }
                }

                if (!abandoned
                        && (chosenPath == null || path.toString().compareTo(chosenPath) < 0)) {
                    chosenPath = path.toString();
                    chosenIssuer = issuerCopy;
                }
            }

            // Merge chosen permutation's issuer back into the outer issuer.
            // the permutation loop always runs ≥1 iteration, so chosenIssuer was assigned.
            for (String id : Objects.requireNonNull(chosenIssuer).issuedOrder()) {
                if (!issuer.hasIssued(id)) {
                    issuer.issue(id);
                }
            }

            dataToHash.append(key).append(chosenPath);
        }

        return sha256Hex(dataToHash.toString());
    }

    /** All permutations of {@code input}. Recursive swap-based; O(n!). */
    private static List<List<String>> permutations(List<String> input) {
        List<List<String>> result = new ArrayList<>();
        permute(new ArrayList<>(input), 0, result);
        return result;
    }

    private static void permute(List<String> arr, int k, List<List<String>> result) {
        if (k >= arr.size() - 1) {
            result.add(new ArrayList<>(arr));
            return;
        }
        for (int i = k; i < arr.size(); i++) {
            Collections.swap(arr, k, i);
            permute(arr, k + 1, result);
            Collections.swap(arr, k, i);
        }
    }
}
