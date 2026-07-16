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
 * Permutation table mapping logical SPOC positions (subject=0, predicate=1, object=2, context=3) to
 * physical column indexes in a {@link SpocKey}.
 *
 * <p>The inverse of {@link QuadOrder}: where {@code QuadOrder} permutes a logical quad
 * <em>into</em> a physical key, {@code QuadRole} reads a physical key <em>back</em> to logical
 * positions. A read-path row decoder (the versioned Sail's {@code ProllyStatement}, the flat Sail's
 * statement decoder) picks the {@code QuadRole} matching the index it scanned.
 *
 * <p>A pure codec primitive — extracted from {@code ProllyStatement} into {@code prolly-codec} so
 * the unversioned Sail can decode keys without the versioned Sail on its classpath.
 */
public record QuadRole(int s, int p, int o, int c) {
    /** SPOC ordering: columns 0..3 are subject, predicate, object, context. */
    public static final QuadRole SPOC = new QuadRole(0, 1, 2, 3);

    /** POSC ordering: column 0=predicate, 1=object, 2=subject, 3=context. */
    public static final QuadRole POSC = new QuadRole(2, 0, 1, 3);

    /** OSPC ordering: column 0=object, 1=subject, 2=predicate, 3=context. */
    public static final QuadRole OSPC = new QuadRole(1, 2, 0, 3);

    /** CSPO ordering: column 0=context, 1=subject, 2=predicate, 3=object. */
    public static final QuadRole CSPO = new QuadRole(1, 2, 3, 0);

    /** Resolve a logical position (0=s, 1=p, 2=o, 3=c) to the matching SpocKey column. */
    public TermId col(SpocKey key, int logicalPosition) {
        int physical =
                switch (logicalPosition) {
                    case 0 -> s;
                    case 1 -> p;
                    case 2 -> o;
                    case 3 -> c;
                    default -> throw new IllegalArgumentException("position must be in [0,3]");
                };
        return switch (physical) {
            case 0 -> key.col0();
            case 1 -> key.col1();
            case 2 -> key.col2();
            case 3 -> key.col3();
            default -> throw new IllegalStateException("impossible");
        };
    }
}
