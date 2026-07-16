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
package com.earasoft.prolly.rdf4j.gen;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Tuple;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;

/**
 * Phase 0 Step 2 of {@code prolly-rdf4j-test-strategy.md} — generated mutation op-streams (add /
 * remove / clear / commit / rollback). The differential harness (Step 3) replays an {@code
 * OpStream} against a {@code ProllySail} and an RDF4J {@code MemoryStore} in lockstep; the property
 * phases (S-2/S-4/S-5/S-6) assert equality after each commit. Weighted toward ADD so streams build
 * a non-trivial dataset; COMMIT/ROLLBACK punctuate transactions.
 */
public final class OpStreamGen {

    private OpStreamGen() {}

    /**
     * A single mutation step. {@code statement} is set for ADD/REMOVE; {@code context} is the CLEAR
     * target (null ⇒ clear all graphs).
     */
    public record Op(Kind kind, Statement statement, Resource context) {
        public enum Kind {
            ADD,
            REMOVE,
            CLEAR,
            COMMIT,
            ROLLBACK
        }
    }

    public static Arbitrary<Op> ops() {
        return ops(QuadGen.statements());
    }

    /**
     * Op stream for the differential oracle — lexically-stable statements only (see {@link
     * QuadGen#differentialStatements}).
     */
    public static Arbitrary<Op> differentialOps() {
        return ops(QuadGen.differentialStatements());
    }

    private static Arbitrary<Op> ops(Arbitrary<org.eclipse.rdf4j.model.Statement> stmts) {
        Arbitrary<Op> add = stmts.map(s -> new Op(Op.Kind.ADD, s, null));
        Arbitrary<Op> remove = stmts.map(s -> new Op(Op.Kind.REMOVE, s, null));
        Arbitrary<Op> clear = QuadGen.contexts().map(c -> new Op(Op.Kind.CLEAR, null, c));
        Arbitrary<Op> commit = Arbitraries.just(new Op(Op.Kind.COMMIT, null, null));
        Arbitrary<Op> rollback = Arbitraries.just(new Op(Op.Kind.ROLLBACK, null, null));
        return Arbitraries.frequencyOf(
                Tuple.of(12, add),
                Tuple.of(3, remove),
                Tuple.of(1, clear),
                Tuple.of(4, commit),
                Tuple.of(1, rollback));
    }

    public static Arbitrary<List<Op>> opStreams() {
        return ops().list().ofMinSize(1).ofMaxSize(40);
    }

    public static Arbitrary<List<Op>> differentialOpStreams() {
        return differentialOps().list().ofMinSize(1).ofMaxSize(40);
    }
}
