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

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.sail.SailException;

/**
 * {@link TripleSource} adapter that lets RDF4J's standard SPARQL evaluation strategy ({@code
 * DefaultEvaluationStrategy}) drive its scans through a {@link ProllySailConnection}'s {@code
 * getStatements}.
 *
 * <p>This is the minimal bridge: the evaluator walks the parsed {@code TupleExpr}, generating
 * triple-pattern queries that come back here, and we route them straight to the connection's read
 * path (which goes through the planner + index). Joins, FILTERs, OPTIONALs, etc. are all handled by
 * RDF4J's algebra-level evaluator on top of our scans.
 *
 * <p>{@code includeInferred} is respected at the call site (we don't do reasoning ourselves) — the
 * flag is plumbed through unchanged.
 */
final class SailConnectionTripleSource implements TripleSource {

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
    public ValueFactory getValueFactory() {
        return vf;
    }
}
