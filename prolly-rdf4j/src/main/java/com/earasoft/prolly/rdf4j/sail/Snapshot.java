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
package com.earasoft.prolly.rdf4j.sail;

import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable record of all Sail-level committed roots at one instant. Captured by {@link
 * ProllySailConnection} at transaction-fork time and used at commit time as the {@code expected}
 * argument to a future compare-and-set advance.
 *
 * <p>v2.0 single-writer: this class is plumbed but the CAS itself is a plain {@code volatile}
 * assignment that always wins. Phase 4 wires it through {@code Database.commit}'s {@code
 * expectedParentHash} CAS for multi-writer correctness. See {@code docs/cas-rebase.md} for the
 * design and {@code docs/cas-rebase-runbook.md} for the implementation plan.
 *
 * <p>Null {@code StaticMap} fields (and null index-map values) indicate "empty tree, no commits
 * yet" — the same convention as elsewhere in the codebase. The defensive copy uses an {@link
 * EnumMap} (not {@code Map.copyOf}) because the latter rejects null map values.
 */
public record Snapshot(
        @Nullable StaticMap dictRoot,
        Map<QuadOrder, @Nullable StaticMap> indexRoots,
        @Nullable StaticMap namespacesRoot,
        @Nullable StaticMap statsRoot) {
    /**
     * Explicit canonical constructor — the components (and the index-map values) are
     * {@code @Nullable} (null = empty tree, no commits yet), declared on the parameters here so
     * NullAway honors the null-accepting contract at every {@code new Snapshot(...)} call site (a
     * record's implicit canonical-constructor parameter does not reliably inherit the component
     * annotation).
     */
    public Snapshot(
            @Nullable StaticMap dictRoot,
            Map<QuadOrder, @Nullable StaticMap> indexRoots,
            @Nullable StaticMap namespacesRoot,
            @Nullable StaticMap statsRoot) {
        // Map.copyOf rejects null values. EnumMap + unmodifiableMap preserves
        // null-as-empty-tree semantics while still defending against caller mutation.
        EnumMap<QuadOrder, @Nullable StaticMap> copy = new EnumMap<>(QuadOrder.class);
        copy.putAll(indexRoots);
        this.dictRoot = dictRoot;
        this.indexRoots = Collections.unmodifiableMap(copy);
        this.namespacesRoot = namespacesRoot;
        this.statsRoot = statsRoot;
    }
}
