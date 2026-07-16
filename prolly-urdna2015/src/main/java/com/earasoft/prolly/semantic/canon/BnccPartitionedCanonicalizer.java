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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 *
 *
 * <h3>BNCC-partitioned canonicalizer — scaling-friendly composition.</h3>
 *
 * <p>Composes {@link BnccPartitioner} with an inner {@link RdfCanonicalizer} to canonicalize each
 * blank-node connected component independently. The win is concrete: a 100M-quad graph with 10M
 * BNCCs averaging 5 blanks each canonicalizes in O(largest-BNCC) wall-clock rather than
 * O(whole-graph), because the algorithm runs N times on small inputs rather than once on a large
 * one.
 *
 * <h4>Output is NOT byte-equal to monolithic URDNA2015</h4>
 *
 * <p>This is the deliberate trade-off. The canonical-name assignment is determined by a different
 * global ordering — BNCCs are sorted by their content hash, then global labels are assigned by
 * concatenating each BNCC's local labels with an offset. Monolithic URDNA2015 uses a single global
 * N-degree pass that assigns labels in a different order. Both outputs are:
 *
 * <ul>
 *   <li>Deterministic — same input always produces same output.
 *   <li>Rename-stable — relabel input blank nodes; same output.
 *   <li>RDF-semantically valid — represents the same RDF graph.
 * </ul>
 *
 * <p>For W3C-byte-exact compliance, use {@link UrdnaCanonicalizer#INSTANCE} directly. Use this
 * class for scale-driven workloads where the substrate's content-addressed stability matters more
 * than W3C-output-compatibility.
 *
 * <h4>Inner canonicalizer choice</h4>
 *
 * <p>The {@code inner} parameter is invoked per BNCC. Reasonable choices:
 *
 * <ul>
 *   <li>{@link UrdnaCanonicalizer#INSTANCE} — full algorithm per BNCC. Best correctness, modest
 *       per-BNCC cost.
 *   <li>{@link CascadeCanonicalizer#INSTANCE} — cheap-first per BNCC. Small BNCCs short-circuit at
 *       first-degree; large/symmetric BNCCs escalate.
 * </ul>
 *
 * <p>Default factory uses cascade.
 */
public final class BnccPartitionedCanonicalizer implements RdfCanonicalizer {

    /** Default instance — cascades within each BNCC, sequential per-BNCC processing. */
    public static final BnccPartitionedCanonicalizer INSTANCE =
            new BnccPartitionedCanonicalizer(CascadeCanonicalizer.INSTANCE, false);

    /**
     * Parallel-execution instance — same composition as {@link #INSTANCE} but each BNCC's
     * inner.canonicalize() runs in parallel across the commonPool. Useful when the graph has many
     * BNCCs and you have spare cores.
     */
    public static final BnccPartitionedCanonicalizer PARALLEL_INSTANCE =
            new BnccPartitionedCanonicalizer(CascadeCanonicalizer.INSTANCE, true);

    private static final Pattern C14N_LABEL = Pattern.compile("^_:c14n(\\d+)$");

    private final RdfCanonicalizer inner;
    private final boolean parallel;

    /** Sequential constructor (back-compat with iter 16). */
    public BnccPartitionedCanonicalizer(RdfCanonicalizer inner) {
        this(inner, false);
    }

    /**
     * @param inner per-BNCC canonicalizer; MUST be thread-safe if {@code parallel == true}. All
     *     shipped canonicalizers in this module are stateless at the instance level (per-call state
     *     is stack-local) so they qualify.
     * @param parallel if true, BNCCs are canonicalized in parallel via the commonPool. Set to true
     *     only when both the graph has enough BNCCs to amortise thread-dispatch cost (≳50) AND the
     *     per-BNCC canonicalization is expensive enough to dominate dispatch.
     */
    public BnccPartitionedCanonicalizer(RdfCanonicalizer inner, boolean parallel) {
        if (inner == null) throw new IllegalArgumentException("inner canonicalizer is required");
        this.inner = inner;
        this.parallel = parallel;
    }

    /** Ergonomic factory for the parallel variant. */
    public static BnccPartitionedCanonicalizer parallel(RdfCanonicalizer inner) {
        return new BnccPartitionedCanonicalizer(inner, true);
    }

    public RdfCanonicalizer inner() {
        return inner;
    }

    public boolean isParallel() {
        return parallel;
    }

    @Override
    public List<QuadPattern> canonicalize(List<QuadPattern> quads) {
        BnccPartitioner.Result part = BnccPartitioner.partition(quads);

        if (part.bnccCount() == 0) {
            // No blank nodes anywhere — pass through.
            return quads;
        }
        if (part.bnccCount() == 1 && part.allNamedQuads().isEmpty()) {
            // Single BNCC, all quads belong to it — just delegate.
            return inner.canonicalize(quads);
        }

        // Step 1: canonicalize each BNCC independently.
        // Result per BNCC: locally-canonicalized quads (with local _:c14n0..N labels).
        // Parallel mode: ForkJoinPool.commonPool() via parallelStream — output
        // order must match input order (BNCC indices), achieved via stream.collect
        // into a list with matching indices.
        List<List<QuadPattern>> perBnccCanonical;
        if (parallel && part.bnccCount() > 1) {
            perBnccCanonical =
                    part.perBnccQuads().parallelStream().map(inner::canonicalize).toList();
        } else {
            perBnccCanonical = new ArrayList<>(part.bnccCount());
            for (List<QuadPattern> sub : part.perBnccQuads()) {
                perBnccCanonical.add(inner.canonicalize(sub));
            }
        }

        // Step 2: compute each BNCC's content hash for stable global sort.
        // The hash is over a sorted canonical-form representation of the BNCC's
        // local canonical quads — same content → same hash regardless of input labels.
        List<BnccBucket> buckets = new ArrayList<>(part.bnccCount());
        for (int i = 0; i < perBnccCanonical.size(); i++) {
            List<QuadPattern> sub = perBnccCanonical.get(i);
            buckets.add(new BnccBucket(i, sub, contentHash(sub)));
        }

        // Step 3: sort BNCCs by content hash (rename-stable global order).
        buckets.sort(Comparator.comparing(b -> b.contentHash));

        // Step 4: assign global label offsets and rewrite per-BNCC quads.
        Map<String, String> globalRelabel =
                new HashMap<>(); // local _:c14nK in bncc i → _:c14n{globalOffset+K}
        List<QuadPattern> globalRewritten = new ArrayList<>();
        int globalOffset = 0;
        for (BnccBucket b : buckets) {
            int localMax = -1;
            for (QuadPattern q : b.canonicalQuads) {
                localMax = Math.max(localMax, extractLabelIndex(q.s().value()));
                localMax = Math.max(localMax, extractLabelIndex(q.o().value()));
            }
            // Build the BNCC-local relabel map.
            Map<String, String> localRelabel = new HashMap<>();
            for (int k = 0; k <= localMax; k++) {
                localRelabel.put("_:c14n" + k, "_:c14n" + (globalOffset + k));
            }
            // Rewrite this BNCC's quads with global labels.
            for (QuadPattern q : b.canonicalQuads) {
                String s = localRelabel.getOrDefault(q.s().value(), q.s().value());
                String o = localRelabel.getOrDefault(q.o().value(), q.o().value());
                globalRewritten.add(QuadPattern.of(s, q.p().value(), o, q.c()));
            }
            globalOffset += (localMax + 1);
            globalRelabel.putAll(localRelabel);
        }

        // Step 5: prepend all-named quads, output combined list.
        List<QuadPattern> result =
                new ArrayList<>(part.allNamedQuads().size() + globalRewritten.size());
        result.addAll(part.allNamedQuads());
        result.addAll(globalRewritten);
        return result;
    }

    // ---- helpers ------------------------------------------------------------

    private record BnccBucket(
            int originalIndex, List<QuadPattern> canonicalQuads, String contentHash) {}

    /** sha256-hex of the BNCC's canonical N-Quads (sorted lines, joined with LF). */
    private static String contentHash(List<QuadPattern> canonQuads) {
        List<String> lines = new ArrayList<>(canonQuads.size());
        for (QuadPattern q : canonQuads) {
            lines.add(q.s().value() + " " + q.p().value() + " " + q.o().value() + " " + q.c());
        }
        Collections.sort(lines);
        return sha256Hex(String.join("\n", lines));
    }

    /** -1 if not a _:c14nN label; otherwise N. */
    private static int extractLabelIndex(String value) {
        var m = C14N_LABEL.matcher(value);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
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
