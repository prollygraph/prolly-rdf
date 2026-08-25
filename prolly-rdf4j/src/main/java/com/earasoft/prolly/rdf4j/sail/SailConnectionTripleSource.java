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

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.RDFStarTripleSource;
import org.eclipse.rdf4j.sail.SailException;

/**
 * {@link RDFStarTripleSource} adapter that lets RDF4J's standard SPARQL evaluation strategy ({@code
 * DefaultEvaluationStrategy}) drive its scans through a {@link ProllySailConnection}'s {@code
 * getStatements}.
 *
 * <p>This is the minimal bridge: the evaluator walks the parsed {@code TupleExpr}, generating
 * triple-pattern queries that come back here, and we route them straight to the connection's read
 * path (which goes through the planner + index). Joins, FILTERs, OPTIONALs, etc. are all handled by
 * RDF4J's algebra-level evaluator on top of our scans.
 *
 * <p><b>RDF-star:</b> implementing {@link RDFStarTripleSource} routes SPARQL-star {@code TripleRef}
 * patterns ({@code <<?s ?p ?o>>}) through {@link #getRdfStarTriples} instead of the evaluator's
 * reification-vocabulary fallback (which cannot see native triple terms). Enumeration is
 * statement-driven: the triple terms OCCURRING in the queried data (as a statement's subject or
 * object) are collected from one full statement scan, deduplicated, and filtered by the bound
 * components — the same cost class as the reification fallback's scan, with no extra index. A
 * triple term nested inside another term but never occurring directly in a statement is not
 * enumerated (it cannot join with any statement pattern anyway); a dedicated triple-term index is
 * the frontier follow-up if SPARQL-star over very large stores ever matters.
 *
 * <p>{@code includeInferred} is respected at the call site (we don't do reasoning ourselves) — the
 * flag is plumbed through unchanged.
 */
final class SailConnectionTripleSource implements RDFStarTripleSource {

    private final ProllySailConnection conn;
    private final ValueFactory vf;
    private final boolean includeInferred;

    SailConnectionTripleSource(
            ProllySailConnection conn, ValueFactory vf, boolean includeInferred) {
        this.conn = conn;
        this.vf = vf;
        this.includeInferred = includeInferred;
    }

    @Override
    public CloseableIteration<? extends Statement> getStatements(
            Resource subj, IRI pred, Value obj, Resource... contexts)
            throws QueryEvaluationException {
        try {
            return conn.getStatements(subj, pred, obj, includeInferred, contexts);
        } catch (SailException e) {
            throw new QueryEvaluationException(e);
        }
    }

    @Override
    public CloseableIteration<? extends Triple> getRdfStarTriples(
            Resource subj, IRI pred, Value obj) throws QueryEvaluationException {
        Set<Triple> matches = new HashSet<>();
        try (CloseableIteration<? extends Statement> it =
                conn.getStatements(null, null, null, includeInferred)) {
            while (it.hasNext()) {
                Statement st = it.next();
                collectIfMatching(st.getSubject(), subj, pred, obj, matches);
                collectIfMatching(st.getObject(), subj, pred, obj, matches);
            }
        } catch (SailException e) {
            throw new QueryEvaluationException(e);
        }
        Iterator<Triple> result = matches.iterator();
        return new CloseableIteratorIteration<>(result);
    }

    /** Add {@code candidate} when it is a triple term whose components match the bound args. */
    private static void collectIfMatching(
            Value candidate, Resource subj, IRI pred, Value obj, Set<Triple> into) {
        if (candidate instanceof Triple t
                && (subj == null || subj.equals(t.getSubject()))
                && (pred == null || pred.equals(t.getPredicate()))
                && (obj == null || obj.equals(t.getObject()))) {
            into.add(t);
        }
    }

    @Override
    public ValueFactory getValueFactory() {
        return vf;
    }
}
