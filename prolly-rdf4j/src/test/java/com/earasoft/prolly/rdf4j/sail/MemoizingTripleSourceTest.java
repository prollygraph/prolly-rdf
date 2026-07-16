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

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.junit.jupiter.api.Test;

/**
 * Direct unit test for {@link MemoizingTripleSource}'s bounds — the memory-safety behavior the
 * end-to-end {@link BindJoinMemoTest} can't reach: the <b>subject-unbound bypass</b> (driving scans
 * must never be materialized) and the <b>per-entry overflow cap</b> (a large result is served but
 * not retained). Uses a stub delegate that counts how many times it is actually consulted.
 */
class MemoizingTripleSourceTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();
    private static final IRI P = VF.createIRI("urn:p");

    /**
     * A {@link TripleSource} that returns {@code n} synthetic statements and counts each
     * consultation.
     */
    private static final class CountingSource implements TripleSource {
        int calls;
        final int n;

        CountingSource(int n) {
            this.n = n;
        }

        @Override
        public CloseableIteration<? extends Statement> getStatements(
                Resource s, IRI p, Value o, Resource... ctx) {
            calls++;
            List<Statement> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                out.add(VF.createStatement(VF.createIRI("urn:o:" + i), P, VF.createLiteral(i)));
            return new CloseableIteratorIteration<>(out.iterator());
        }

        @Override
        public ValueFactory getValueFactory() {
            return VF;
        }
    }

    private static long drain(CloseableIteration<? extends Statement> it) {
        long n = 0;
        try (it) {
            while (it.hasNext()) {
                it.next();
                n++;
            }
        }
        return n;
    }

    @Test
    void repeatedBoundLookupConsultsDelegateOnce() {
        CountingSource delegate = new CountingSource(3);
        MemoizingTripleSource memo =
                new MemoizingTripleSource(
                        delegate, new SimpleMeterRegistry(), 4096, 100_000, 1_000_000);
        IRI s = VF.createIRI("urn:s");
        assertEquals(3, drain(memo.getStatements(s, P, null)));
        assertEquals(3, drain(memo.getStatements(s, P, null))); // memo hit
        assertEquals(3, drain(memo.getStatements(s, P, null))); // memo hit
        assertEquals(1, delegate.calls, "s+p-bound lookup is materialized once, then replayed");
    }

    @Test
    void subjectUnboundBypassesMemo() {
        CountingSource delegate = new CountingSource(5);
        MemoizingTripleSource memo =
                new MemoizingTripleSource(
                        delegate, new SimpleMeterRegistry(), 4096, 100_000, 1_000_000);
        drain(memo.getStatements(null, P, null)); // a driving scan
        drain(memo.getStatements(null, P, null));
        assertEquals(2, delegate.calls, "unbound-subject scans stream through — never memoized");
    }

    @Test
    void overflowResultIsServedButNotRetained() {
        CountingSource delegate = new CountingSource(10);
        MemoizingTripleSource memo =
                new MemoizingTripleSource(
                        delegate, new SimpleMeterRegistry(), 4, 100_000, 1_000_000);
        IRI s = VF.createIRI("urn:big");
        assertEquals(
                10,
                drain(memo.getStatements(s, P, null)),
                "full result still returned despite overflow");
        assertEquals(
                10,
                drain(memo.getStatements(s, P, null)),
                "served again — correct, just not from memo");
        assertEquals(
                2,
                delegate.calls,
                "a result over the per-entry cap is not retained → re-consults delegate");
    }

    @Test
    void globalBudgetCapsTotalRetainedStatements() {
        CountingSource delegate = new CountingSource(3); // 3 statements per distinct key
        // budget = 5 statements → the first key (3) fits; a second key (would be 6) does not.
        MemoizingTripleSource memo =
                new MemoizingTripleSource(delegate, new SimpleMeterRegistry(), 4096, 100_000, 5);
        IRI s1 = VF.createIRI("urn:s1"), s2 = VF.createIRI("urn:s2");
        assertEquals(3, drain(memo.getStatements(s1, P, null))); // miss → retained 3, memoized
        assertEquals(3, drain(memo.getStatements(s2, P, null))); // miss → 3+3>5, NOT retained
        assertEquals(3, drain(memo.getStatements(s1, P, null))); // hit (s1 fit under budget)
        assertEquals(3, drain(memo.getStatements(s2, P, null))); // miss again (s2 never retained)
        assertEquals(
                3,
                delegate.calls,
                "global budget bounds retained statements; over-budget keys serve directly");
    }
}
