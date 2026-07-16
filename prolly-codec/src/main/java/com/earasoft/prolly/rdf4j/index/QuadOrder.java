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
package com.earasoft.prolly.rdf4j.index;

import com.earasoft.prolly.rdf4j.term.TermId;

/**
 * Permutation between the logical RDF quad order (s, p, o, c) and a 4-column {@link SpocKey}'s
 * physical column order.
 *
 * <p>Each entry names the index by listing its physical columns in order:
 *
 * <ul>
 *   <li>{@link #SPOC}: column 0 = subject, 1 = predicate, 2 = object, 3 = context.
 *   <li>{@link #POSC}: column 0 = predicate, 1 = object, 2 = subject, 3 = context.
 *   <li>{@link #OSPC}: column 0 = object, 1 = subject, 2 = predicate, 3 = context.
 *   <li>{@link #CSPO}: column 0 = context, 1 = subject, 2 = predicate, 3 = object.
 * </ul>
 *
 * <p>Use {@link #keyOf} to construct a {@link SpocKey} from a logical quad in the order described
 * by this enum. Use {@link #role} to interpret an already-stored key when building a statement.
 */
public enum QuadOrder {
    SPOC, // col 0 = s, col 1 = p, col 2 = o, col 3 = c
    POSC, // col 0 = p, col 1 = o, col 2 = s, col 3 = c
    OSPC, // col 0 = o, col 1 = s, col 2 = p, col 3 = c
    CSPO; // col 0 = c, col 1 = s, col 2 = p, col 3 = o

    // Per-order metric keys, computed once. The ingest hot path increments
    // these on every index write; building "index.<order>.insert" by string
    // concatenation + toLowerCase per call would allocate three throwaway
    // Strings per index per triple (pure waste under the noop metrics sink).
    private final String insertMetricKey =
            "index." + name().toLowerCase(java.util.Locale.ROOT) + ".insert";
    private final String deleteMetricKey =
            "index." + name().toLowerCase(java.util.Locale.ROOT) + ".delete";

    /** Pre-built {@code "index.<order>.insert"} metric key for this order. */
    public String insertMetricKey() {
        return insertMetricKey;
    }

    /** Pre-built {@code "index.<order>.delete"} metric key for this order. */
    public String deleteMetricKey() {
        return deleteMetricKey;
    }

    /**
     * Build a SpocKey from a logical (s, p, o, c) quad using this order's permutation. Hot path —
     * runs 4× per ingested triple, so it permutes the four references directly in a switch rather
     * than allocating a throwaway {@code TermId[]} to index into.
     */
    public SpocKey keyOf(TermId s, TermId p, TermId o, TermId c) {
        return switch (this) {
            case SPOC -> new SpocKey(s, p, o, c);
            case POSC -> new SpocKey(p, o, s, c);
            case OSPC -> new SpocKey(o, s, p, c);
            case CSPO -> new SpocKey(c, s, p, o);
        };
    }

    /**
     * Map of physical column → logical position. For a statement decoder reading a key written
     * under this order, this tells it which physical column carries subject vs predicate vs object
     * vs context.
     */
    public QuadRole role() {
        return switch (this) {
            case SPOC -> QuadRole.SPOC;
            case POSC -> QuadRole.POSC;
            case OSPC -> QuadRole.OSPC;
            case CSPO -> QuadRole.CSPO;
        };
    }
}
