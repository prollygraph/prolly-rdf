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
package com.earasoft.prolly.rdf4j.value;

import com.earasoft.prolly.rdf4j.term.TermId;

/**
 * Resolves a {@link TermId} back to its {@link ProllyValue}.
 *
 * <p>Two implementations:
 *
 * <ul>
 *   <li>A {@code Dictionary}-backed resolver (Phase 2): reads encoded bytes from the dict tree and
 *       wraps them in the appropriate {@code ProllyValue}.
 *   <li>A {@code Map<TermId, ProllyValue>}-backed resolver (used by {@link ProllyValueFactory} for
 *       free-standing values): keeps the wrapped values in a heap map so {@link ProllyTriple}'s
 *       component accessors return the original values.
 * </ul>
 */
@FunctionalInterface
public interface TermResolver {
    ProllyValue resolve(TermId id);
}
