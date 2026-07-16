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
package com.earasoft.prolly.rdf4j.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

/**
 * Phase 3 Step 6 of {@code plans/model-based-testing-rollout.md} — <b>stateful model-based</b>
 * property for {@link QuadIndex}, the permutation wrapper that turns a logical {@code (s,p,o,c)}
 * quad into a physical {@link SpocKey} under one of four {@link QuadOrder}s and back. {@link
 * SpocIndex}'s own state machine is already model-tested ({@code SpocIndexModelProperty}); what
 * {@code QuadIndex} <i>adds</i> and this pins is (a) the logical→physical permutation
 * round-tripping through insert/contains/scan, and (b) {@code scan}'s wildcard semantics — checked
 * across <b>all four orders</b> (each chain is bound to one order).
 *
 * <p><b>The {@code scan} contract — a deliberate superset, not an exact match (read first):</b>
 * {@link QuadIndex#scan} keys only on the <i>leading bound physical columns</i> (capped at 3) and
 * does <b>not</b> post-filter the rest — so {@code scan(1, null, 3, null)} on an SPOC index returns
 * every quad with subject 1 <i>regardless of object</i>, leaving the {@code o == 3} residue for the
 * caller to filter (the planner picks an order where the bound columns <i>are</i> a leading
 * prefix). Pinning {@code scan == exact matches} would be wrong (it would fail on the documented
 * slack) <i>and</i> pinning it as {@code == leading-prefix matches} would mean re-implementing the
 * private {@code physicalToLogical} in the test — a tautology that hides a permutation bug. So the
 * oracle asserts the two guarantees a caller actually relies on, using only the public {@link
 * QuadOrder#keyOf} (never the private inverse):
 *
 * <ul>
 *   <li><b>soundness</b> — every key {@code scan} returns is a currently-committed quad (no phantom
 *       rows);
 *   <li><b>completeness</b> — every committed quad matching <i>all</i> bound positions of the
 *       pattern appears in {@code scan}'s output (the leading-prefix filter is a superset of the
 *       exact matches, so it can never drop one). A {@code leadingPrefixLength} bug that dropped
 *       rows, or a wrong permutation, breaks one of these.
 * </ul>
 *
 * The deliberate slack — {@code scan} may return extra rows matching only the leading prefix — is
 * left unasserted, because it is the documented, planner-handled behaviour, not a correctness
 * property.
 *
 * <p>Read-visibility is inherited from {@link SpocIndex}: {@code contains} reads the live overlay,
 * {@code scan}/{@code iter} read the committed base only. The model keeps {@code live} and {@code
 * committed} accordingly. Regime: the same tiny {@code {1,2,3}} alphabet so quads collide and
 * wildcard patterns match several committed quads.
 */
class QuadIndexModelProperty {

    @Property(tries = 500)
    void quadIndexMatchesModelAcrossOrdersAndChains(@ForAll("chains") ActionChain<Model> chain) {
        chain.run();
    }

    /**
     * Pins the <i>surprising</i> half of the {@code scan} contract deterministically: it keys only
     * on the leading bound physical columns and does <b>not</b> post-filter the rest, so {@code
     * scan} returns a superset. On an SPOC index {@code scan(s=1, p=*, o=2, c=*)} has leading
     * prefix {@code [s=1]} (object is not contiguous with subject), so it returns <i>every</i>
     * subject-1 quad — including one with object 9. A well-meaning "fix" that post-filtered on
     * {@code o} would pass the soundness/completeness property (completeness only requires a
     * superset) yet silently break the semantics the planner relies on; this guard catches that by
     * asserting the extra row is present.
     */
    @org.junit.jupiter.api.Test
    void scan_does_not_post_filter_non_leading_bound_positions() {
        QuadIndex idx =
                new QuadIndex(QuadOrder.SPOC, new InMemoryNodeStore(), new HeapBufferPool());
        idx.insert(TermId.of(1), TermId.of(2), TermId.of(2), TermId.of(4)); // (1,2,2,4)
        idx.insert(
                TermId.of(1),
                TermId.of(9),
                TermId.of(9),
                TermId.of(4)); // (1,9,9,4) — same subject, different object
        idx.commit();
        Set<SpocKey> got =
                drain(
                        idx.scan(
                                TermId.of(1),
                                null,
                                TermId.of(2),
                                null)); // s=1, o=2 bound; p,c wildcard
        org.junit.jupiter.api.Assertions.assertEquals(
                2,
                got.size(),
                "scan keys on the leading prefix [s=1] only — o=2 is NOT post-filtered, so both subject-1 quads return");
        org.junit.jupiter.api.Assertions.assertTrue(
                got.contains(
                        QuadOrder.SPOC.keyOf(
                                TermId.of(1), TermId.of(9), TermId.of(9), TermId.of(4))),
                "the o=9 quad must still be returned (documents the superset slack the planner filters)");
    }

    /**
     * One chain per run, bound to a randomly chosen {@link QuadOrder} so all four permutations are
     * exercised.
     */
    @Provide
    Arbitrary<ActionChain<Model>> chains() {
        return Arbitraries.of(QuadOrder.values())
                .flatMap(
                        order ->
                                ActionChain.startWith(() -> new Model(order))
                                        .withAction(insert())
                                        .withAction(insert())
                                        .withAction(insert())
                                        .withAction(delete())
                                        .withAction(delete())
                                        .withAction(contains())
                                        .withAction(contains())
                                        .withAction(scan())
                                        .withAction(scan())
                                        .withAction(iterAll())
                                        .withAction(commit())
                                        .withMaxTransformations(160));
    }

    private Action.Independent<Model> insert() {
        return () -> quad().map(q -> Transformer.mutate("insert " + q, m -> m.insert(q)));
    }

    private Action.Independent<Model> delete() {
        return () -> quad().map(q -> Transformer.mutate("delete " + q, m -> m.delete(q)));
    }

    private Action.Independent<Model> contains() {
        return () -> quad().map(q -> Transformer.mutate("contains " + q, m -> m.assertContains(q)));
    }

    private Action.Independent<Model> scan() {
        return () -> pattern().map(p -> Transformer.mutate("scan " + p, m -> m.assertScan(p)));
    }

    private Action.Independent<Model> iterAll() {
        return () -> Arbitraries.just(Transformer.mutate("iter", Model::assertIterAll));
    }

    private Action.Independent<Model> commit() {
        return () -> Arbitraries.just(Transformer.mutate("commit", Model::commitAndReverify));
    }

    /**
     * A quad from the small {1,2,3} alphabet — heavy overlap so wildcard scans match several quads.
     */
    private static Arbitrary<Quad> quad() {
        Arbitrary<Long> col = Arbitraries.longs().between(1, 3);
        return Combinators.combine(col, col, col, col).as(Quad::new);
    }

    /** A scan pattern: each of s,p,o,c is either a bound value (1–3) or {@code null} (wildcard). */
    private static Arbitrary<Pattern> pattern() {
        Arbitrary<Long> posOrWild = Arbitraries.longs().between(0, 3); // 0 → wildcard, 1–3 → bound
        return Combinators.combine(posOrWild, posOrWild, posOrWild, posOrWild).as(Pattern::new);
    }

    /** A logical quad (the model's currency); equality is value-based for set membership. */
    record Quad(long s, long p, long o, long c) {
        @Override
        public String toString() {
            return "(" + s + "," + p + "," + o + "," + c + ")";
        }
    }

    /** A scan pattern: a {@code 0} component means wildcard (null), 1–3 means bound. */
    record Pattern(long s, long p, long o, long c) {
        TermId t(long v) {
            return v == 0 ? null : TermId.of(v);
        }

        boolean matchesAll(Quad q) {
            return (s == 0 || s == q.s())
                    && (p == 0 || p == q.p())
                    && (o == 0 || o == q.o())
                    && (c == 0 || c == q.c());
        }

        @Override
        public String toString() {
            return "(" + w(s) + "," + w(p) + "," + w(o) + "," + w(c) + ")";
        }

        private static String w(long v) {
            return v == 0 ? "*" : Long.toString(v);
        }
    }

    /**
     * The {@link QuadIndex} under test (bound to one order) paired with logical live + committed
     * quad sets.
     */
    static final class Model {
        final QuadOrder order;
        final QuadIndex idx;
        final Set<Quad> live = new HashSet<>(); // committed + pending → what contains() sees
        final Set<Quad> committed = new HashSet<>(); // last committed root → what scan()/iter() see

        Model(QuadOrder order) {
            this.order = order;
            this.idx = new QuadIndex(order, new InMemoryNodeStore(), new HeapBufferPool());
        }

        void insert(Quad q) {
            idx.insert(TermId.of(q.s()), TermId.of(q.p()), TermId.of(q.o()), TermId.of(q.c()));
            live.add(q);
            assertContains(q);
        }

        void delete(Quad q) {
            idx.delete(TermId.of(q.s()), TermId.of(q.p()), TermId.of(q.o()), TermId.of(q.c()));
            live.remove(q);
            assertContains(q);
        }

        void assertContains(Quad q) {
            boolean got =
                    idx.contains(
                            TermId.of(q.s()), TermId.of(q.p()), TermId.of(q.o()), TermId.of(q.c()));
            assertEquals(
                    live.contains(q),
                    got,
                    "contains "
                            + q
                            + " under "
                            + order
                            + " (live overlay round-trips the permutation)");
        }

        void assertScan(Pattern p) {
            Set<SpocKey> got = drain(idx.scan(p.t(p.s()), p.t(p.p()), p.t(p.o()), p.t(p.c())));
            // soundness: every returned key is a currently-committed quad (mapped through the
            // public forward permutation)
            assertTrue(
                    committedPhysical().containsAll(got),
                    "scan "
                            + p
                            + " under "
                            + order
                            + " returned a key that is not a committed quad");
            // completeness: no committed quad matching ALL bound positions is dropped
            Set<SpocKey> mustInclude = new HashSet<>();
            for (Quad q : committed) if (p.matchesAll(q)) mustInclude.add(key(q));
            assertTrue(
                    got.containsAll(mustInclude),
                    "scan " + p + " under " + order + " dropped a fully-matching committed quad");
        }

        void assertIterAll() {
            assertEquals(
                    committedPhysical(),
                    drain(idx.iter()),
                    "iter() under "
                            + order
                            + " must equal the committed base (mapped to physical keys)");
        }

        void commitAndReverify() {
            idx.commit();
            committed.clear();
            committed.addAll(live);
            assertEquals(
                    committedPhysical(), drain(idx.iter()), "post-commit iter() under " + order);
        }

        private SpocKey key(Quad q) {
            return order.keyOf(
                    TermId.of(q.s()), TermId.of(q.p()), TermId.of(q.o()), TermId.of(q.c()));
        }

        private Set<SpocKey> committedPhysical() {
            Set<SpocKey> out = new HashSet<>();
            for (Quad q : committed) out.add(key(q));
            return out;
        }
    }

    private static Set<SpocKey> drain(Iterator<SpocKey> it) {
        Set<SpocKey> out = new HashSet<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
