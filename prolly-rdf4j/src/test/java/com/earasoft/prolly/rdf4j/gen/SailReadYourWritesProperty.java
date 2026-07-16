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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterTry;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Phase 3 Step 12 of {@code prolly-rdf4j-test-strategy.md} (S-4) — the <b>read-your-writes</b>
 * property: within a single open transaction, every read must reflect that transaction's own
 * <i>uncommitted</i> writes.
 *
 * <p><b>What a "mid-open-transaction differential" is.</b> A <i>differential</i> test replays one
 * identical op stream against the system under test ({@code ProllySail}) and a trusted reference
 * oracle (RDF4J's {@code MemoryStore}) and asserts their observable reads are identical — the
 * reference <i>defines</i> correct. The load-bearing variable is <b>when you read</b> relative to
 * the transaction boundary:
 *
 * <ul>
 *   <li>a <b>committed-state</b> differential reads <i>after</i> {@code COMMIT}, on the durable
 *       post-transaction state — it answers "do the two stores <i>persist</i> the same thing?" That
 *       is what {@link SailDifferentialProperty} asserts, and only at COMMIT boundaries.
 *   <li>a <b>mid-open-transaction</b> differential (this property) reads <i>while the transaction
 *       is still open</i> — after each mutation, before any commit, through the <i>same connection
 *       that did the writes</i>. It observes the <b>uncommitted working set</b> (the
 *       per-transaction buffer), so it answers a different question: "does the store's
 *       <i>in-progress</i> view match the reference's?" — i.e. does it honour read-your-writes.
 * </ul>
 *
 * <p><b>Why the distinction has teeth.</b> A store can be correct at COMMIT yet wrong
 * mid-transaction, and a committed-only oracle cannot tell. That is precisely the flush-before-scan
 * bug this regression-proofs: before its fix a same-transaction add was invisible to in-transaction
 * {@code size}/{@code getStatements} (the scan read the committed index and skipped the pending
 * buffer) yet {@code commit} still persisted it — so a committed-state oracle stays <i>green</i>
 * while read-your-writes is broken, because it never reads the dimension where the bug lives. The
 * mid-open-transaction differential is the only one of the two that can fail on it. (Same shape as
 * the rest of this suite: match the instrument to the invariant.)
 *
 * <p><b>How this property reads buffered state.</b> The rig is the Step-5 {@link
 * SailDifferentialHarness}, which keeps <i>one persistent connection per store</i> and runs its
 * comparators through it — so calling them while a transaction is open reads that connection's
 * buffered view on both sides. The stream is <b>ADD/REMOVE-only</b>, so a single transaction stays
 * open the whole time and is <i>never committed</i>; agreement ({@code size}, {@code
 * getStatements}, {@code getContextIDs}, and all 16 wildcard masks) is asserted after <b>every
 * mutation</b>. It generalizes the seven deterministic examples in {@code
 * ProllySailReadYourWritesTest} (which stay as the pinned specific cases) and the flush-before-scan
 * fix in {@code ProllySailConnection.getStatementsInternal} / {@code sizeInternal} / {@code
 * getContextIDsInternal} into a property.
 *
 * <p><b>The regime, forced on purpose — and what it caught.</b> Statements are drawn from a
 * <b>small fixed pool</b>, not the full term space. The variable under test is the buffered read
 * after a mutation, and its sharpest case is a REMOVE (or a re-ADD) landing on a statement written
 * <i>earlier in the same transaction</i>. Over a large term space a generated remove almost never
 * hits a buffered add, so that path goes unexercised — a false-negative-shaped clean result. A
 * small pool makes ADD/REMOVE collide often, entering the regime where the variable acts
 * (CLAUDE.md: "name the regime where the variable can act"). It earned its keep immediately: it
 * surfaced a latent <b>all-contexts remove</b> bug — a bound {@code removeStatements(s,p,o)} with
 * no contexts deleted only the default-graph copy, silently leaving named-graph copies — which the
 * large-term-space committed oracle structurally never reached (it never generated the same {@code
 * (s,p,o)} in a named graph plus a contextless remove of it). Fixed in {@code
 * removeStatementsInternal}; pinned by {@code ProllySailRemoveAllContextsTest}; written up in
 * {@code blog/build-log-the-regime-the-big-oracle-couldnt-reach.md}. The pool uses only
 * differential-safe terms (IRIs, plain and language literals — no RDF-star, no typed literals), the
 * surface {@link QuadGen#differentialStatements} restricts the structural oracle to.
 */
class SailReadYourWritesProperty {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * A deliberately tiny, differential-safe statement pool (24 statements over the default graph
     * plus one named graph) — small so generated ADD/REMOVE ops collide often and a remove hits a
     * same-transaction add.
     */
    private static final List<Statement> POOL = pool();

    private static IRI iri(String s) {
        return VF.createIRI("urn:test:" + s);
    }

    private static List<Statement> pool() {
        List<Statement> out = new ArrayList<>();
        IRI[] subs = {iri("s1"), iri("s2")};
        IRI[] preds = {iri("p1"), iri("p2")};
        Value[] objs = {iri("o1"), VF.createLiteral("lit"), VF.createLiteral("v", "en")};
        Resource[] ctxs = {null, iri("g1")};
        for (IRI s : subs) {
            for (IRI p : preds) {
                for (Value o : objs) {
                    for (Resource c : ctxs) {
                        out.add(
                                c == null
                                        ? VF.createStatement(s, p, o)
                                        : VF.createStatement(s, p, o, c));
                    }
                }
            }
        }
        return out; // 2 × 2 × 3 × 2 = 24
    }

    private final List<Path> tempDirs = new ArrayList<>();

    @Property(tries = 150)
    void buffered_reads_reflect_same_tx_writes(@ForAll @From("rywStreams") List<Op> ops)
            throws IOException {
        Path dir = Files.createTempDirectory("sail-ryw-");
        tempDirs.add(dir);
        try (SailDifferentialHarness h = new SailDifferentialHarness(dir)) {
            for (Op op : ops) {
                h.apply(op); // ADD/REMOVE only ⇒ one open txn, never committed
                assertBufferedAgree(h, ops); // every read must see this txn's own prior writes
            }
            // No commit: close() rolls back. The assurance is purely about
            // uncommitted, within-transaction reads.
        }
    }

    private static void assertBufferedAgree(SailDifferentialHarness h, List<Op> ops) {
        assertTrue(
                h.sizeAgrees(),
                () -> "size() did not reflect buffered writes for op stream:\n" + render(ops));
        assertTrue(
                h.statementsAgree(),
                () ->
                        "getStatements(*,*,*) did not reflect buffered writes for op stream:\n"
                                + render(ops));
        assertTrue(
                h.contextsAgree(),
                () ->
                        "getContextIDs() did not reflect buffered writes for op stream:\n"
                                + render(ops));
        assertTrue(
                h.patternsAgree(),
                () ->
                        "a wildcard getStatements pattern did not reflect buffered writes for op stream:\n"
                                + render(ops));
    }

    private static String render(List<Op> ops) {
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
            sb.append("  ").append(op.kind());
            if (op.statement() != null) sb.append(' ').append(op.statement());
            sb.append('\n');
        }
        return sb.toString();
    }

    @Provide
    Arbitrary<List<Op>> rywStreams() {
        Arbitrary<Statement> stmt = Arbitraries.of(POOL);
        Arbitrary<Op> add = stmt.map(s -> new Op(Op.Kind.ADD, s, null));
        Arbitrary<Op> remove = stmt.map(s -> new Op(Op.Kind.REMOVE, s, null));
        // ADD-dominant to build state, but REMOVE frequent (3:2, vs the differential
        // oracle's 4:1) because the buffered remove is the sharp read-your-writes case.
        Arbitrary<Op> op = Arbitraries.frequencyOf(Tuple.of(3, add), Tuple.of(2, remove));
        return op.list().ofMinSize(1).ofMaxSize(24);
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
        tempDirs.clear();
    }
}
