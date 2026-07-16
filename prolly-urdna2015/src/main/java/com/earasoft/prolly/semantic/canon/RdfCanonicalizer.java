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
 *
 *
 * <h3>RDF Dataset Canonicalizer SPI.</h3>
 *
 * <p>Produces a canonical labelling of blank nodes for an input graph so that two
 * structurally-equivalent graphs serialise to byte-identical quad sequences. The substrate's
 * chunk-level content addressing then collapses equivalent commits to the same root hash, and
 * three-way merges over byte-tuples Just Work for blank-node-bearing data.
 *
 * <p>Contract:
 *
 * <ol>
 *   <li>Determinism. Same input quads in any order produce the same output (modulo input ordering
 *       being preserved).
 *   <li>Idempotence. Canonicalising an already-canonical graph returns it unchanged (modulo
 *       blank-node label assignments being stable).
 *   <li>Equivalence preservation. The output graph is RDF-equivalent to the input — same triples up
 *       to blank-node renaming.
 *   <li>Failure mode. Implementations that cannot canonicalise (e.g. hash collisions in a
 *       first-degree-only impl, time budget exhausted in URDNA2015) MUST throw {@link
 *       NonCanonicalizableException}, never produce a best-effort labelling. Fail-closed is the
 *       contract — see whitepaper §5.2.
 * </ol>
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link NoopCanonicalizer} — pass-through. Use when the graph is known to have no blank
 *       nodes, or as a baseline for tests demonstrating the gap.
 *   <li>SimpleFirstDegreeCanonicalizer (planned) — handles the blank-node-rename case where every
 *       blank node has a unique first-degree structural hash; fails closed on collisions.
 *   <li>UrdnaCanonicalizer (planned) — full W3C RDFC-1.0 implementation; handles cyclic and
 *       symmetric blank nodes. Time-budgeted; fails closed on overrun.
 * </ul>
 *
 * <p>See {@code prolly-audit/design/HASHING_CANONICALIZATION.md} §4 for the engine-side time-budget
 * machinery; that doc also spells out why the audit graph and the data graph need separate
 * canonicalization budgets.
 */
public interface RdfCanonicalizer {

    /**
     * Return a canonical labelling of the given quads.
     *
     * @param quads input list; not mutated
     * @return new list with blank-node labels rewritten to canonical names ({@code _:c14n0}, {@code
     *     _:c14n1}, ...)
     * @throws NonCanonicalizableException when the implementation cannot produce a canonical
     *     labelling — never best-effort
     */
    List<QuadPattern> canonicalize(List<QuadPattern> quads);

    /**
     * Returns true if {@code value} is a blank-node label per the convention used in this codebase:
     * a value whose first two characters are {@code _:}.
     */
    static boolean isBlankNode(String value) {
        return value != null
                && value.length() >= 2
                && value.charAt(0) == '_'
                && value.charAt(1) == ':';
    }
}
