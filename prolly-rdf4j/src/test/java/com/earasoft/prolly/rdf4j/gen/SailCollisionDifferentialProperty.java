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
 * Audit probe (Phase 3 / S-4 follow-on) — the <b>committed-state</b> differential of {@link
 * SailDifferentialProperty}, re-run in the <b>forced-collision regime</b> that surfaced the
 * all-contexts-remove bug.
 *
 * <p>{@link SailDifferentialProperty} draws statements from the full term space, so the same {@code
 * (s,p,o)} essentially never appears in two graphs within one window — the regime where a
 * cross-context mutation bug lives is never entered, and the oracle is green-but-blind there (it is
 * how the all-contexts-remove bug hid for so long). This probe draws ADD / REMOVE / CLEAR ops from
 * a <b>small fixed pool</b> so the same triple recurs across the default graph and a named graph
 * constantly, then asserts {@code ProllySail == MemoryStore} after every COMMIT — exactly the
 * committed oracle, but inside the regime. Had it existed it would have caught the remove bug
 * independently of the read-your-writes property; going forward it guards the whole committed
 * mutation surface (add / remove / clear; default vs named vs all graphs) against the same class of
 * latent divergence. See {@code blog/build-log-the-regime-the-big-oracle-couldnt-reach.md} and the
 * sibling {@link SailReadYourWritesProperty} (the mid-transaction variant).
 */
class SailCollisionDifferentialProperty {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    private static IRI iri(String s) {
        return VF.createIRI("urn:test:" + s);
    }

    /**
     * The same tiny differential-safe pool the read-your-writes property uses (24 statements over
     * the default graph + one named graph).
     */
    private static final List<Statement> POOL = pool();

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
    void prollySailEqualsMemoryStore_inTheCollisionRegime(
            @ForAll @From("collisionStreams") List<Op> ops) throws IOException {
        Path dir = Files.createTempDirectory("sail-collide-");
        tempDirs.add(dir);
        try (SailDifferentialHarness h = new SailDifferentialHarness(dir)) {
            for (Op op : ops) {
                h.apply(op);
                if (op.kind() == Op.Kind.COMMIT) assertAgree(h, ops);
            }
            h.flush();
            assertAgree(h, ops);
        }
    }

    private static void assertAgree(SailDifferentialHarness h, List<Op> ops) {
        assertTrue(
                h.statementsAgree(),
                () -> "getStatements(*,*,*) diverged for op stream:\n" + render(ops));
        assertTrue(h.sizeAgrees(), () -> "size() diverged for op stream:\n" + render(ops));
        assertTrue(
                h.contextsAgree(), () -> "getContextIDs() diverged for op stream:\n" + render(ops));
        assertTrue(
                h.patternsAgree(),
                () -> "a wildcard getStatements pattern diverged for op stream:\n" + render(ops));
    }

    private static String render(List<Op> ops) {
        StringBuilder sb = new StringBuilder();
        for (Op op : ops) {
            sb.append("  ").append(op.kind());
            if (op.statement() != null) sb.append(' ').append(op.statement());
            if (op.context() != null) sb.append(" ctx=").append(op.context());
            sb.append('\n');
        }
        return sb.toString();
    }

    @Provide
    Arbitrary<List<Op>> collisionStreams() {
        Arbitrary<Statement> stmt = Arbitraries.of(POOL);
        Arbitrary<Op> add = stmt.map(s -> new Op(Op.Kind.ADD, s, null));
        Arbitrary<Op> remove = stmt.map(s -> new Op(Op.Kind.REMOVE, s, null));
        // CLEAR target: null ⇒ clear ALL graphs; g1 ⇒ clear the pool's named graph
        // (hits real data); g2 ⇒ a not-in-pool graph (a no-op both stores agree on).
        // All three exercise clearInternal's empty-vs-named context handling.
        Arbitrary<Resource> clearCtx =
                Arbitraries.of(iri("g1"), iri("g2")).map(i -> (Resource) i).injectNull(0.4);
        Arbitrary<Op> clear = clearCtx.map(c -> new Op(Op.Kind.CLEAR, null, c));
        Arbitrary<Op> commit = Arbitraries.just(new Op(Op.Kind.COMMIT, null, null));
        Arbitrary<Op> op =
                Arbitraries.frequencyOf(
                        Tuple.of(6, add),
                        Tuple.of(4, remove),
                        Tuple.of(2, clear),
                        Tuple.of(3, commit));
        return op.list().ofMinSize(1).ofMaxSize(30);
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
