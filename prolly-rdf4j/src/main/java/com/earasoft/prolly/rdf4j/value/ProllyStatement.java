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

import com.earasoft.prolly.rdf4j.index.QuadRole;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.Objects;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.jspecify.annotations.Nullable;

/**
 * An RDF4J {@link Statement} backed by a {@link SpocKey} (four TermIds) plus a {@link TermResolver}
 * that lazily materializes each position to its {@link ProllyValue}.
 *
 * <p>Used as the standard read-path return type from Sail {@code getStatements} — each row in a
 * SPOC/POSC/OSPC/CSPO scan becomes a ProllyStatement.
 *
 * <p>Equality follows RDF4J's Statement contract: subject + predicate + object + context (when
 * present) must all be equal across implementations.
 */
public final class ProllyStatement implements Statement {

    private final SpocKey key;
    private final TermResolver resolver;

    /** The position of subject/predicate/object/context within {@link #key}'s columns. */
    private final QuadRole roles;

    private final TermId defaultGraphSentinel;

    private @Nullable Resource cachedSubject;
    private @Nullable IRI cachedPredicate;
    private @Nullable Value cachedObject;
    private @Nullable Resource cachedContext;

    /**
     * Build a Statement from a SPOC-ordered key. {@code roles} declares which column is
     * subject/predicate/object/context — for the SPOC index it's {@code (0,1,2,3)}; for POSC it's
     * {@code (2,0,1,3)}; etc.
     *
     * <p>A context TermId equal to {@code defaultGraphSentinel} (typically {@link TermId#ZERO})
     * decodes as {@code null} context (the default graph).
     */
    public ProllyStatement(
            SpocKey key, QuadRole roles, TermResolver resolver, TermId defaultGraphSentinel) {
        this.key = key;
        this.roles = roles;
        this.resolver = resolver;
        this.defaultGraphSentinel = defaultGraphSentinel;
    }

    @Override
    public Resource getSubject() {
        if (cachedSubject == null) {
            ProllyValue v = resolver.resolve(roles.col(key, 0));
            if (!(v instanceof Resource r)) {
                throw new IllegalStateException("subject is not a Resource: " + v);
            }
            cachedSubject = r;
        }
        return cachedSubject;
    }

    @Override
    public IRI getPredicate() {
        if (cachedPredicate == null) {
            ProllyValue v = resolver.resolve(roles.col(key, 1));
            if (!(v instanceof IRI iri)) {
                throw new IllegalStateException("predicate is not an IRI: " + v);
            }
            cachedPredicate = iri;
        }
        return cachedPredicate;
    }

    @Override
    public Value getObject() {
        if (cachedObject == null) {
            cachedObject = resolver.resolve(roles.col(key, 2));
        }
        return cachedObject;
    }

    @Override
    public @Nullable Resource getContext() {
        if (cachedContext != null) return cachedContext;
        TermId ctxId = roles.col(key, 3);
        if (ctxId.equals(defaultGraphSentinel)) return null;
        ProllyValue v = resolver.resolve(ctxId);
        if (!(v instanceof Resource r)) {
            throw new IllegalStateException("context is not a Resource: " + v);
        }
        cachedContext = r;
        return cachedContext;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Statement other)) return false;
        return Objects.equals(getSubject(), other.getSubject())
                && Objects.equals(getPredicate(), other.getPredicate())
                && Objects.equals(getObject(), other.getObject())
                && Objects.equals(getContext(), other.getContext());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getSubject(), getPredicate(), getObject(), getContext());
    }

    @Override
    public String toString() {
        return "("
                + getSubject()
                + " "
                + getPredicate()
                + " "
                + getObject()
                + (getContext() == null ? "" : " " + getContext())
                + ")";
    }

    /**
     * Serialization proxy: RDF4J's {@code Statement} contract extends {@link java.io.Serializable},
     * but this instance lazily resolves MemorySegment-backed (non-serializable) values. Replace it
     * on write with a plain {@link SimpleValueFactory} {@code Statement}; its component values are
     * themselves {@code ProllyValue}s, so each is replaced by its own {@code writeReplace} during
     * serialization. See {@code bugs/rdf4j-repository-connection-contract-triage.md}.
     */
    private Object writeReplace() {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        Resource ctx = getContext();
        return ctx == null
                ? vf.createStatement(getSubject(), getPredicate(), getObject())
                : vf.createStatement(getSubject(), getPredicate(), getObject(), ctx);
    }
}
