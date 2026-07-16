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

import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.jspecify.annotations.Nullable;

/**
 * RDF4J {@link IRI} backed by a {@link MemorySegment} holding an encoded-term payload (tag {@code
 * 0x80}, {@code 0x81}, or {@code 0x82}).
 *
 * <p>Resolution is lazy: {@link #stringValue()} materializes the UTF-8 string on first call and
 * caches the result on the heap.
 *
 * <p>Equality is RDF-semantic: matches the string value of any {@code IRI} implementation
 * (symmetric with RDF4J's {@code SimpleIRI}).
 */
public final class ProllyIRI implements ProllyValue, IRI {

    private final MemorySegment encoded;
    private final PrefixTable prefixes;
    // @Nullable lazy caches: materialized on first stringValue()/split() call, then memoized.
    private @Nullable String cachedString;
    private @Nullable String cachedNamespace;
    private @Nullable String cachedLocalName;

    public ProllyIRI(MemorySegment encoded, PrefixTable prefixes) {
        this.encoded = encoded;
        this.prefixes = prefixes;
    }

    @Override
    public String stringValue() {
        if (cachedString != null) return cachedString;
        byte tag = TermCodec.tagOf(encoded);
        MemorySegment payload = TermCodec.payloadOf(encoded);
        cachedString =
                switch (tag) {
                    case TermCodec.TAG_IRI_FULL -> TermCodec.decodeFullIri(payload);
                    case TermCodec.TAG_IRI_SHORT_PREFIX -> {
                        TermCodec.ShortPrefixIri sp = TermCodec.decodeShortPrefixIri(payload);
                        String ns =
                                prefixes.lookupNamespaceAsString(sp.prefixId())
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "ProllyIRI references unknown prefix-id "
                                                                        + sp.prefixId()));
                        yield ns + sp.localPart();
                    }
                    case TermCodec.TAG_IRI_LONG_PREFIX -> {
                        TermCodec.LongPrefixIri lp = TermCodec.decodeLongPrefixIri(payload);
                        String ns1 =
                                prefixes.lookupNamespaceAsString(lp.prefixId1())
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "ProllyIRI long-prefix references unknown id1 "
                                                                        + lp.prefixId1()));
                        String ns2 =
                                prefixes.lookupNamespaceAsString(lp.prefixId2())
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "ProllyIRI long-prefix references unknown id2 "
                                                                        + lp.prefixId2()));
                        yield ns1 + ns2 + lp.localPart();
                    }
                    default ->
                            throw new IllegalStateException(
                                    "ProllyIRI wrapping non-IRI tag 0x"
                                            + Integer.toHexString(tag & 0xFF));
                };
        return cachedString;
    }

    @Override
    public String getNamespace() {
        if (cachedNamespace == null) split();
        // split() assigns both caches on every path; requireNonNull pins that invariant (the call
        // clears NullAway's field narrowing).
        return Objects.requireNonNull(cachedNamespace);
    }

    @Override
    public String getLocalName() {
        if (cachedLocalName == null) split();
        return Objects.requireNonNull(cachedLocalName);
    }

    private void split() {
        String s = stringValue();
        int hash = s.lastIndexOf('#');
        int slash = s.lastIndexOf('/');
        int idx = Math.max(hash, slash);
        if (idx < 0) {
            cachedNamespace = "";
            cachedLocalName = s;
        } else {
            cachedNamespace = s.substring(0, idx + 1);
            cachedLocalName = s.substring(idx + 1);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof IRI other)) return false;
        return stringValue().equals(other.stringValue());
    }

    @Override
    public int hashCode() {
        return stringValue().hashCode();
    }

    @Override
    public String toString() {
        return stringValue();
    }

    /**
     * Serialization proxy: RDF4J's {@code Value} contract extends {@link java.io.Serializable}, but
     * this instance is backed by a non-serializable {@link MemorySegment}. Replace it on write with
     * the plain, fully-serializable {@link SimpleValueFactory} equivalent (the segment is never
     * written). See {@code bugs/rdf4j-repository-connection-contract-triage.md}.
     */
    private Object writeReplace() {
        return SimpleValueFactory.getInstance().createIRI(stringValue());
    }
}
