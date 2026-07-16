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
package com.earasoft.prolly.flatsail;

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Picks which of the four permutation indexes ({@code SPOC}/{@code POSC}/ {@code OSPC}/{@code
 * CSPO}) to scan for a quad pattern, and the key prefix to seek with.
 *
 * <p>A quad pattern binds some of subject/predicate/object/context and leaves the rest free. The
 * cheapest scan is the index whose <em>leading</em> physical columns cover the longest unbroken run
 * of bound terms — that run becomes a byte-prefix seek, turning a full-CF scan into a range scan.
 * The selector evaluates all four orders and returns the best.
 *
 * <p>Example: a pattern binding only subject and object scans {@code OSPC} (physical columns
 * object, subject, …) with a two-column {@code [o, s]} prefix — neither {@code SPOC} nor any other
 * order pins both leading.
 */
final class FlatIndexSelector {

    /** Logical term indexes used to address a quad pattern. */
    private static final int SUBJECT = 0;

    private static final int PREDICATE = 1;
    private static final int OBJECT = 2;
    private static final int CONTEXT = 3;

    /**
     * {@code PHYSICAL_TO_LOGICAL.get(order)[k]} is the logical term index (0=s, 1=p, 2=o, 3=c)
     * stored at physical column {@code k} of {@code order}. Derived once from {@link
     * QuadOrder#keyOf} with sentinel TermIds.
     */
    private static final Map<QuadOrder, int[]> PHYSICAL_TO_LOGICAL = new EnumMap<>(QuadOrder.class);

    static {
        for (QuadOrder order : QuadOrder.values()) {
            SpocKey probe =
                    order.keyOf(
                            TermId.of(SUBJECT),
                            TermId.of(PREDICATE),
                            TermId.of(OBJECT),
                            TermId.of(CONTEXT));
            PHYSICAL_TO_LOGICAL.put(
                    order,
                    new int[] {
                        (int) probe.col0().value(), (int) probe.col1().value(),
                        (int) probe.col2().value(), (int) probe.col3().value(),
                    });
        }
    }

    private FlatIndexSelector() {}

    /**
     * The chosen index and the bound leading-column TermIds to seek with (length 0 — a full scan —
     * to 4 — an exact-key lookup).
     */
    record Choice(QuadOrder order, TermId[] prefixTerms) {}

    /**
     * Pick the index whose leading physical columns cover the longest run of bound terms. Pass
     * {@code null} for an unbound position; {@code context} should be a single wanted context, or
     * {@code null} when context is unbound or more than one context is wanted (then it is
     * post-filtered).
     */
    static Choice choose(
            @Nullable TermId subject,
            @Nullable TermId predicate,
            @Nullable TermId object,
            @Nullable TermId context) {
        TermId[] logical = {subject, predicate, object, context};
        QuadOrder bestOrder = QuadOrder.SPOC;
        TermId[] bestPrefix = new TermId[0];
        for (QuadOrder order : QuadOrder.values()) {
            // The static initializer populates PHYSICAL_TO_LOGICAL for every QuadOrder.
            int[] physicalToLogical = Objects.requireNonNull(PHYSICAL_TO_LOGICAL.get(order));
            int prefixLength = 0;
            while (prefixLength < 4 && logical[physicalToLogical[prefixLength]] != null) {
                prefixLength++;
            }
            if (prefixLength > bestPrefix.length) {
                bestOrder = order;
                TermId[] prefix = new TermId[prefixLength];
                for (int k = 0; k < prefixLength; k++) {
                    prefix[k] = logical[physicalToLogical[k]];
                }
                bestPrefix = prefix;
            }
        }
        return new Choice(bestOrder, bestPrefix);
    }
}
