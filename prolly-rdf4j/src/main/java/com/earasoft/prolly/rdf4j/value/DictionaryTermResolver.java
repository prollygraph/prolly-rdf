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

import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.jspecify.annotations.Nullable;

/**
 * {@link TermResolver} that reads encoded bytes from a {@link Dictionary} and wraps them in the
 * matching {@link ProllyValue} variant based on the term's leading tag byte.
 *
 * <p>Used by:
 *
 * <ul>
 *   <li>{@link ProllyStatement} to materialize its subject/predicate/object/context.
 *   <li>{@link ProllyTriple} when read back from an index (Phase 2).
 * </ul>
 *
 * @implNote <b>Optional decode cache</b> ({@code prolly.rdf4j.term-cache-size}, Step 3 of {@code
 *     prolly-rdf4j/plans/read-path-cache-and-zerocopy.md}; default OFF). When constructed with a
 *     positive {@code cacheSize}, a {@link TermId}→{@link ProllyValue} least-recently-used map
 *     intercepts {@link #resolve(TermId)} for hot terms — skipping <em>both</em> the {@link
 *     Dictionary#decode} {@code StaticMap} tree-walk <em>and</em> the {@link #wrap} byte→{@code
 *     ProllyValue} materialization (the layer the read-path node cache alone cannot remove: the
 *     node cache makes each node fetch cheap, but the descent's comparisons + cursor work + the
 *     wrap still run on every uncached decode). <b>Correctness:</b> {@code TermId = hash(term)} is
 *     append-only / never remapped (a content address), so a cached {@code (id → value)} pair is
 *     correct forever and needs no invalidation (D-3) — including within a transaction
 *     (read-your-writes is safe; the cache can never hold a stale mapping because no mapping ever
 *     changes). Only <em>present</em> decodes are cached; a miss throws and is never memoized.
 *     <b>Retention safety:</b> the decoded segment may view an off-heap node buffer, so on a miss
 *     the bytes are copied to a heap {@link MemorySegment} <em>before</em> wrapping — the cached
 *     value pins only GC-managed heap, never an off-heap buffer (the retention hazard documented in
 *     the plan's Step 4 correction). <b>Threading:</b> the cache is unsynchronized because a
 *     resolver is per-connection (its {@link Dictionary} is "one per open connection, not
 *     thread-safe"); a future <em>shared</em> cross-repo cache (D-7) is a deferred ship-gate that
 *     must address cross-thread {@code ProllyValue} publication. <b>Measure-first:</b> the win is
 *     only the <em>marginal</em> descent+wrap saved on top of the already-wired node cache, so the
 *     lever is bench-measured paired (node cache ON in both arms, term-cache off vs on) and ships
 *     only if its delta clears the noise floor (D-1/D-8).
 */
public final class DictionaryTermResolver implements TermResolver {

    private final Dictionary dict;
    private final PrefixTable prefixes;

    /** {@code TermId}→{@code ProllyValue} LRU, or {@code null} when the decode cache is off. */
    private final @Nullable Map<TermId, ProllyValue> cache;

    public DictionaryTermResolver(Dictionary dict, PrefixTable prefixes) {
        this(dict, prefixes, 0);
    }

    /**
     * @param cacheSize max entries in the decode cache; {@code 0} (or negative) disables it (zero
     *     overhead — the {@link #resolve} path is byte-identical to the no-cache build).
     */
    public DictionaryTermResolver(Dictionary dict, PrefixTable prefixes, int cacheSize) {
        this.dict = dict;
        this.prefixes = prefixes;
        this.cache =
                cacheSize <= 0
                        ? null
                        : new LinkedHashMap<>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<TermId, ProllyValue> eldest) {
                                return size() > cacheSize;
                            }
                        };
    }

    @Override
    public ProllyValue resolve(TermId id) {
        if (cache != null) {
            ProllyValue hit = cache.get(id);
            if (hit != null) {
                return hit;
            }
        }
        MemorySegment encoded =
                dict.decode(id)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "TermId " + id + " not in dictionary"));
        if (cache == null) {
            return wrap(encoded);
        }
        // Cache on: own the bytes on-heap before wrapping so the cached value never pins an
        // off-heap node buffer (the Step 4 retention hazard); the copy is paid once, on a miss.
        MemorySegment owned = MemorySegment.ofArray(encoded.toArray(ValueLayout.JAVA_BYTE));
        ProllyValue value = wrap(owned);
        cache.put(id, value);
        return value;
    }

    /** Wrap an already-fetched encoded segment in the matching ProllyValue variant. */
    public ProllyValue wrap(MemorySegment encoded) {
        byte tag = TermCodec.tagOf(encoded);
        // High nibble selects the family
        int family = (tag & 0xF0);
        return switch (family) {
            case 0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60 -> new ProllyLiteral(encoded);
            case 0xE0 -> {
                // Custom-datatype literal: its datatype IRI is stored as a TermId. Resolve it here
                // —
                // the resolver holds the Dictionary; the ProllyLiteral byte-wrapper does not — and
                // hand
                // the IRI to the 2-arg constructor so getDatatype() returns it. (ADR-0043 6c.)
                TermCodec.CustomLiteral custom =
                        TermCodec.decodeCustomLiteral(TermCodec.payloadOf(encoded));
                IRI datatype = (IRI) resolve(custom.datatypeIri());
                yield new ProllyLiteral(encoded, datatype);
            }
            case 0x80, 0x90 -> new ProllyIRI(encoded, prefixes);
            case 0xA0 -> new ProllyBNode(encoded);
            case 0xC0 -> new ProllyTriple(encoded, this);
            default ->
                    throw new IllegalStateException(
                            "unknown tag family 0x"
                                    + Integer.toHexString(family)
                                    + " for tag 0x"
                                    + Integer.toHexString(tag & 0xFF));
        };
    }
}
