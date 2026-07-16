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

import org.jspecify.annotations.Nullable;

/**
 * Thrown when an {@link RdfCanonicalizer} cannot produce a canonical labelling for an input graph.
 * Fail-closed signal: callers MUST NOT fall back to a non-canonical labelling on this exception.
 *
 * <p>Common causes:
 *
 * <ul>
 *   <li>First-degree-only canonicalizer: two distinct blank nodes have the same first-degree
 *       structural hash.
 *   <li>URDNA2015: time-budget exhausted on adversarial cyclic blank-node graphs.
 * </ul>
 *
 * <p>Resolution paths: try a stronger canonicalizer (full URDNA2015 over a first-degree-only one);
 * raise the time budget; or refuse the commit and surface the failure to the operator.
 */
public class NonCanonicalizableException extends RuntimeException {
    public NonCanonicalizableException(String message) {
        super(message);
    }

    public NonCanonicalizableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
