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
import java.util.List;

/**
 * Pass-through {@link RdfCanonicalizer}. Returns the input list unchanged.
 *
 * <p>Two legitimate use cases:
 *
 * <ol>
 *   <li>The graph is known to have no blank nodes (all subjects and objects are named IRIs or
 *       literals). Common for schema-typed reference data.
 *   <li>Tests demonstrating the gap between "blank-node-aware merge is necessary" and "we have not
 *       implemented it yet" — see {@code BlankNodeRenameCanonicalizerTest}.
 * </ol>
 *
 * <p>Refuses inputs that contain blank nodes — a noop on a blank-node-bearing graph would silently
 * produce a wrong result downstream (the substrate would treat structurally-equivalent graphs as
 * different commits). Throws {@link NonCanonicalizableException} instead, preserving the
 * fail-closed contract.
 */
public final class NoopCanonicalizer implements RdfCanonicalizer {

    public static final NoopCanonicalizer INSTANCE = new NoopCanonicalizer();

    @Override
    public List<QuadPattern> canonicalize(List<QuadPattern> quads) {
        for (QuadPattern q : quads) {
            if (RdfCanonicalizer.isBlankNode(q.s().value())
                    || RdfCanonicalizer.isBlankNode(q.o().value())) {
                throw new NonCanonicalizableException(
                        "NoopCanonicalizer cannot canonicalize blank-node-bearing input; "
                                + "use SimpleFirstDegreeCanonicalizer or UrdnaCanonicalizer");
            }
        }
        return quads;
    }
}
