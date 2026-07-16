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
package com.earasoft.prolly.semantic;

import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.semantic.canon.RdfCanonicalizer;
import com.earasoft.prolly.semantic.canon.SimpleFirstDegreeCanonicalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.NotifyingSailConnection;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.helpers.NotifyingSailConnectionWrapper;
import org.eclipse.rdf4j.sail.helpers.NotifyingSailWrapper;

/**
 *
 *
 * <h3>Canonicalize-at-commit Sail wrapper around {@link ProllySail}.</h3>
 *
 * <p>The RDF4J replacement for the retired {@code CanonicalizingQuadStore} (ADR-0037 D-5). Buffers
 * statements added on a connection and, at {@code commit()}, runs the configured {@link
 * RdfCanonicalizer} over the delta under a wall-clock budget (fail-closed via {@link
 * CanonicalizationBudget}) before writing them to the delegate {@link ProllySail}. Two connections
 * that commit structurally-equivalent blank-node graphs land byte-identical Prolly-tree contents,
 * so the substrate's three-way merge handles them without further help.
 *
 * <h4>Scope (unchanged from the native wrapper)</h4>
 *
 * <ul>
 *   <li>Canonicalization applies to <b>added</b> statements (the blank-node-determinism case).
 *       Removals pass straight through — pattern-based removal of canonical labels is the
 *       cross-commit problem the native wrapper also punted (whitepaper note).
 *   <li>The default {@link SimpleFirstDegreeCanonicalizer} renames blank nodes; named-IRI data is
 *       an identity transform.
 *   <li>Buffered adds are not visible to read-your-writes within the same transaction (they
 *       materialize at commit) — acceptable for the canonicalize-then-commit flow.
 * </ul>
 */
public class CanonicalizingProllySail extends NotifyingSailWrapper {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * Sentinel graph IRI standing in for the default graph when bridging to {@link QuadPattern}.
     */
    private static final String DEFAULT_CTX = "urn:prolly:canon/default-graph";

    private final RdfCanonicalizer canonicalizer;
    private final Duration timeBudget;

    /** Default {@link SimpleFirstDegreeCanonicalizer} + 200ms budget. */
    public CanonicalizingProllySail(ProllySail delegate) {
        this(
                delegate,
                SimpleFirstDegreeCanonicalizer.INSTANCE,
                CanonicalizationBudget.DEFAULT_TIME_BUDGET);
    }

    public CanonicalizingProllySail(
            ProllySail delegate, RdfCanonicalizer canonicalizer, Duration timeBudget) {
        super(delegate);
        if (canonicalizer == null) throw new IllegalArgumentException("canonicalizer is required");
        if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalArgumentException("timeBudget must be positive");
        }
        this.canonicalizer = canonicalizer;
        this.timeBudget = timeBudget;
    }

    public RdfCanonicalizer canonicalizer() {
        return canonicalizer;
    }

    public Duration timeBudget() {
        return timeBudget;
    }

    @Override
    public NotifyingSailConnection getConnection() throws SailException {
        return new CanonicalizingConnection(super.getConnection());
    }

    /** Buffers adds and canonicalizes the delta at commit before writing to the delegate. */
    private final class CanonicalizingConnection extends NotifyingSailConnectionWrapper {

        private final List<Statement> pendingAdd = new ArrayList<>();

        CanonicalizingConnection(NotifyingSailConnection wrapped) {
            super(wrapped);
        }

        @Override
        public void addStatement(Resource subj, IRI pred, Value obj, Resource... contexts) {
            if (contexts == null || contexts.length == 0) {
                pendingAdd.add(VF.createStatement(subj, pred, obj));
            } else {
                for (Resource c : contexts) {
                    pendingAdd.add(
                            c == null
                                    ? VF.createStatement(subj, pred, obj)
                                    : VF.createStatement(subj, pred, obj, c));
                }
            }
        }

        @Override
        public void flush() {
            // flush() is the first hook in SailRepositoryConnection.commit()'s
            // flush() → prepare() → commit() sequence. We apply the canonical delta
            // here: the implicit (null) update-op is still live, so addStatement is
            // permitted — by commit() time prepare() has already torn it down.
            flushCanonical();
            super.flush();
        }

        @Override
        public void rollback() {
            pendingAdd.clear();
            super.rollback();
        }

        private void flushCanonical() {
            if (pendingAdd.isEmpty()) return;
            List<QuadPattern> quads = new ArrayList<>(pendingAdd.size());
            for (Statement st : pendingAdd) quads.add(toQuad(st));
            List<QuadPattern> canon =
                    CanonicalizationBudget.apply(canonicalizer, timeBudget, quads);
            for (QuadPattern q : canon) {
                Resource subj = res(q.s().value());
                IRI pred = VF.createIRI(q.p().value());
                Value obj = term(q.o().value());
                if (DEFAULT_CTX.equals(q.c())) {
                    super.addStatement(subj, pred, obj);
                } else {
                    super.addStatement(subj, pred, obj, VF.createIRI(q.c()));
                }
            }
            pendingAdd.clear();
        }
    }

    private static QuadPattern toQuad(Statement st) {
        String ctx = st.getContext() == null ? DEFAULT_CTX : st.getContext().stringValue();
        return QuadPattern.of(
                termStr(st.getSubject()),
                st.getPredicate().stringValue(),
                termStr(st.getObject()),
                ctx);
    }

    /**
     * RDF4J {@code BNode.stringValue()} is the bare id; the canonicalizer needs the {@code _:}
     * label form.
     */
    private static String termStr(Value v) {
        return v instanceof BNode b ? "_:" + b.getID() : v.stringValue();
    }

    /**
     * Reconstruct a subject/object term: {@code _:x} → blank node, else IRI (the native raw-string
     * path).
     */
    private static Resource res(String v) {
        return v.startsWith("_:") ? VF.createBNode(v.substring(2)) : VF.createIRI(v);
    }

    private static Value term(String v) {
        return v.startsWith("_:") ? VF.createBNode(v.substring(2)) : VF.createIRI(v);
    }
}
