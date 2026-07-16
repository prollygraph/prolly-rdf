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

import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.jspecify.annotations.Nullable;

/**
 * RDF-star quoted triple, backed by a {@link MemorySegment} holding tag {@code 0xC0} (asserted) or
 * {@code 0xC1} (unasserted).
 *
 * <p>Components are stored as {@link com.earasoft.prolly.rdf4j.term.TermId} references; {@link
 * #getSubject}/{@link #getPredicate}/{@link #getObject} resolve them lazily via a {@link
 * TermResolver}.
 *
 * <p>Equality is RDF-semantic: matches any {@code Triple} implementation with the same subject /
 * predicate / object.
 */
public final class ProllyTriple implements ProllyValue, Triple {

    private final MemorySegment encoded;
    private final TermResolver resolver;
    // @Nullable lazy cache: decoded on first decoded() call, then memoized.
    private TermCodec.@Nullable QuotedTriple cachedDecoded;

    public ProllyTriple(MemorySegment encoded, TermResolver resolver) {
        this.encoded = encoded;
        this.resolver = resolver;
    }

    private TermCodec.QuotedTriple decoded() {
        if (cachedDecoded == null) cachedDecoded = TermCodec.decodeQuotedTriple(encoded);
        return cachedDecoded;
    }

    /** Whether the quoted triple is asserted (tag 0xC0) or unasserted (0xC1). */
    public boolean isAsserted() {
        return decoded().asserted();
    }

    @Override
    public Resource getSubject() {
        ProllyValue v = resolver.resolve(decoded().s());
        if (v instanceof Resource r) return r;
        throw new IllegalStateException(
                "quoted-triple subject must be Resource (IRI/BNode/Triple) but resolver returned "
                        + v);
    }

    @Override
    public IRI getPredicate() {
        ProllyValue v = resolver.resolve(decoded().p());
        if (v instanceof IRI iri) return iri;
        throw new IllegalStateException(
                "quoted-triple predicate must be IRI but resolver returned " + v);
    }

    @Override
    public Value getObject() {
        return resolver.resolve(decoded().o());
    }

    @Override
    public String stringValue() {
        // Turtle-star: << s p o >>
        return "<<" + getSubject() + " " + getPredicate() + " " + getObject() + ">>";
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Triple other)) return false;
        return Objects.equals(getSubject(), other.getSubject())
                && Objects.equals(getPredicate(), other.getPredicate())
                && Objects.equals(getObject(), other.getObject());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSubject(), getPredicate(), getObject());
    }

    @Override
    public String toString() {
        return stringValue();
    }

    /**
     * Serialization proxy: RDF4J's {@code Value} contract extends {@link java.io.Serializable}, but
     * this instance is backed by a non-serializable {@link MemorySegment}. Replace it on write with
     * a plain {@link SimpleValueFactory} {@code Triple}; the component subject/predicate/object are
     * themselves {@code ProllyValue}s, so each is replaced by its own {@code writeReplace} during
     * serialization (the segment is never written). See {@code
     * bugs/rdf4j-repository-connection-contract-triage.md}.
     */
    private Object writeReplace() {
        return SimpleValueFactory.getInstance()
                .createTriple(getSubject(), getPredicate(), getObject());
    }
}
