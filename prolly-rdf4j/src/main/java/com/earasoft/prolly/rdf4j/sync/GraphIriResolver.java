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
package com.earasoft.prolly.rdf4j.sync;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Phase 0 Step 4 of plans/auth-graph-syncpack-filter.md — adapter between the {@code Set<String>}
 * graph IRI surface that the REST controllers + plan documents use, and the {@code Set<Long>}
 * {@link TermId}-value surface that {@link ChunkGraphFilter} + {@link PackBuilder} take internally.
 *
 * <p>Lives in {@code prolly-rdf4j} (not {@code prolly-rdf4j-rest}) so unit tests of the full filter
 * pipeline can exercise it without a Spring context. The REST surface ({@link SyncController} in a
 * future Step 5) consumes this adapter when {@code prolly.rdf4j.auth.backend=sparql} is active to
 * build the default-DENY excluded set per ADR-0015.
 *
 * <h2>Resolution semantics</h2>
 *
 * <p>An IRI absent from the dictionary resolves to {@link Optional#empty()}, and is <em>silently
 * dropped</em> from the result. The filter just won't drop chunks for that graph — same "unknown
 * TermIds drop nothing" contract {@link ChunkGraphFilter} already documents. Rationale: an operator
 * misconfigures the auth-graph IRIs (typo, wrong namespace), the sync still works but the filter is
 * a partial no-op for that subscriber. Better than failing the sync entirely.
 */
public final class GraphIriResolver {

    private GraphIriResolver() {}

    /**
     * The two auth-graph IRIs the syncpack filter excludes by default when {@code
     * auth.backend=sparql} (per ADR-0015 + plans/auth-on-sail.md).
     */
    public static final Set<String> DEFAULT_AUTH_GRAPHS =
            Set.of("urn:prolly-rdf4j:auth/users", "urn:prolly-rdf4j:auth/pseudonyms");

    /**
     * Resolve a set of graph IRI strings to the underlying {@link TermId} numeric values for the
     * given Sail's current dictionary state. IRIs absent from the dict (never inserted, typos,
     * wrong namespace) are dropped.
     *
     * @return a fresh {@link HashSet}; empty when {@code graphIris} is empty or every IRI is absent
     *     from the dict.
     */
    public static Set<Long> resolve(ProllySail sail, Set<String> graphIris) {
        Set<Long> out = new HashSet<>();
        if (graphIris == null || graphIris.isEmpty()) return out;
        if (sail.dictRoot() == null) return out;
        ValueFactory vf = SimpleValueFactory.getInstance();
        Dictionary dict =
                new Dictionary(
                        sail.store(), sail.pool(), HashFunctions.defaultHash(), sail.dictRoot());
        try (Arena arena = Arena.ofShared()) {
            for (String iri : graphIris) {
                if (iri == null || iri.isBlank()) continue;
                IRI rdfIri = vf.createIRI(iri);
                Optional<TermId> id = dict.findTermId(TermEncoder.encode(rdfIri, arena));
                id.ifPresent(t -> out.add(t.value()));
            }
        }
        return out;
    }
}
