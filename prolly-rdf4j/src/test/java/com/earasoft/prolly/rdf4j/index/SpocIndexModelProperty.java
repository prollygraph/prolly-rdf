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

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.ActionChainArbitrary;
import net.jqwik.api.state.Transformer;

/**
 * Phase 3 Step 6 of {@code plans/model-based-testing-rollout.md} — <b>stateful model-based</b>
 * property for {@link SpocIndex}, the key-only prolly map every quad index is built on. {@code
 * SpocIndexTest} pins the point behaviours (insert/contains, a prefix scan, a commit round-trip) by
 * example; this drives a long random interleaving of {@code insert / delete / contains / iter /
 * iterPrefix / commit} against two reference sets and checks the index's contract <b>after every
 * op</b>.
 *
 * <p><b>The contract this is built to pin — read-visibility is split:</b> {@link
 * SpocIndex#contains} reads the <i>live</i> overlay (committed base + buffered inserts/deletes),
 * but {@link SpocIndex#iter} and {@link SpocIndex#iterPrefix} scan the <i>committed base only</i>
 * (they call {@code buffer.base().iter()}, so a just-inserted, not-yet-committed key is invisible
 * to a scan). That split is a real, easy-to-break invariant: an "obvious" refactor that pointed
 * {@code iter()} at the buffer would pass every single-shot example test and silently change scan
 * semantics. The model therefore keeps <b>two</b> sets — {@code live} (what {@code contains} must
 * reflect) and {@code committed} (what a scan must reflect) — and asserts:
 *
 * <ul>
 *   <li>{@code contains(k)} == {@code live.contains(k)} — overlay visibility;
 *   <li>{@code drain(iter())} == {@code committed} — full scan sees the committed base, <i>not</i>
 *       pending edits;
 *   <li>{@code drain(iterPrefix(p))} == {@code committed} filtered to keys whose leading columns
 *       equal {@code p} — prefix membership + the boundary stop (the iterator must end the moment a
 *       row leaves the prefix);
 *   <li>after {@code commit()} the two sets coincide and a scan then reflects everything inserted
 *       since.
 * </ul>
 *
 * <p><b>Regime — a tiny TermId alphabet ({1,2,3} per column):</b> the key space is deliberately
 * small (3<sup>4</sup> = 81 keys) so insert/delete churn re-touches the same keys (last-write-wins,
 * tombstone shadowing) and — critically — so a 1–3 column prefix actually matches <i>several</i>
 * committed keys. A wide alphabet would make almost every prefix scan return 0–1 rows, never
 * exercising the iterator's multi-row advance or its prefix-boundary stop. Small keys put the scan
 * in the regime where its logic can act.
 */
class SpocIndexModelProperty {

    @Property(tries = 400)
    void spocIndexMatchesModelAcrossActionChains(@ForAll("chains") ActionChain<Model> chain) {
        chain.run();
    }

    /**
     * Pins the split-visibility contract the property is built around, deterministically — so a
     * passing property can't be a false negative (it would still pass if {@code live} and {@code
     * committed} never diverged at an {@code iter()}). An uncommitted insert is visible to {@code
     * contains} but invisible to a scan; {@code commit()} flips that. If {@code iter()} ever read
     * the buffer overlay instead of the committed base, this fails immediately.
     */
    @org.junit.jupiter.api.Test
    void uncommitted_insert_is_visible_to_contains_but_not_to_a_scan() {
        SpocIndex idx = new SpocIndex(new InMemoryNodeStore(), new HeapBufferPool());
        SpocKey k = new SpocKey(TermId.of(7), TermId.of(8), TermId.of(9), TermId.of(10));
        idx.insert(k);
        org.junit.jupiter.api.Assertions.assertTrue(
                idx.contains(k), "contains sees the buffered insert");
        org.junit.jupiter.api.Assertions.assertEquals(
                Set.of(),
                drain(idx.iter()),
                "iter() reads the committed base — the uncommitted insert must be invisible");
        idx.commit();
        org.junit.jupiter.api.Assertions.assertEquals(
                Set.of(k), drain(idx.iter()), "after commit the scan reflects the insert");
    }

    @Provide
    ActionChainArbitrary<Model> chains() {
        return ActionChain.startWith(Model::new)
                .withAction(insert())
                .withAction(insert())
                .withAction(insert())
                .withAction(delete())
                .withAction(delete())
                .withAction(contains())
                .withAction(contains())
                .withAction(iterAll())
                .withAction(iterPrefix())
                .withAction(commit())
                .withMaxTransformations(160);
    }

    private Action.Independent<Model> insert() {
        return () -> key().map(k -> Transformer.mutate("insert " + str(k), m -> m.insert(k)));
    }

    private Action.Independent<Model> delete() {
        return () -> key().map(k -> Transformer.mutate("delete " + str(k), m -> m.delete(k)));
    }

    private Action.Independent<Model> contains() {
        return () ->
                key().map(k -> Transformer.mutate("contains " + str(k), m -> m.assertContains(k)));
    }

    private Action.Independent<Model> iterAll() {
        return () -> Arbitraries.just(Transformer.mutate("iter", Model::assertIterAll));
    }

    private Action.Independent<Model> iterPrefix() {
        return () ->
                prefix().map(
                                p ->
                                        Transformer.mutate(
                                                "iterPrefix " + java.util.Arrays.toString(p),
                                                m -> m.assertIterPrefix(p)));
    }

    private Action.Independent<Model> commit() {
        return () -> Arbitraries.just(Transformer.mutate("commit", Model::commitAndReverify));
    }

    /** A 4-column key drawn from the small {1,2,3} alphabet (heavy overlap + prefix sharing). */
    private static Arbitrary<SpocKey> key() {
        Arbitrary<Long> col = Arbitraries.longs().between(1, 3);
        return net.jqwik.api.Combinators.combine(col, col, col, col)
                .as(
                        (a, b, c, d) ->
                                new SpocKey(
                                        TermId.of(a), TermId.of(b), TermId.of(c), TermId.of(d)));
    }

    /** A 1–3 column prefix from the same alphabet. */
    private static Arbitrary<TermId[]> prefix() {
        return Arbitraries.longs()
                .between(1, 3)
                .array(long[].class)
                .ofMinSize(1)
                .ofMaxSize(3)
                .map(
                        longs -> {
                            TermId[] out = new TermId[longs.length];
                            for (int i = 0; i < longs.length; i++) out[i] = TermId.of(longs[i]);
                            return out;
                        });
    }

    /** The {@link SpocIndex} under test paired with the two reference sets it must agree with. */
    static final class Model {
        final SpocIndex idx = new SpocIndex(new InMemoryNodeStore(), new HeapBufferPool());
        final Set<SpocKey> live =
                new HashSet<>(); // committed base + pending edits → what contains() sees
        final Set<SpocKey> committed =
                new HashSet<>(); // last committed root → what iter()/iterPrefix() see

        void insert(SpocKey k) {
            idx.insert(k);
            live.add(k);
            // insert is immediately visible to contains (the live overlay) but NOT yet to a scan
            assertContains(k);
        }

        void delete(SpocKey k) {
            idx.delete(k);
            live.remove(k);
            assertContains(k);
        }

        void assertContains(SpocKey k) {
            assertEquals(
                    live.contains(k), idx.contains(k), "contains " + str(k) + " (live overlay)");
        }

        void assertIterAll() {
            assertEquals(
                    committed,
                    drain(idx.iter()),
                    "iter() must reflect the committed base only, not pending edits");
        }

        void assertIterPrefix(TermId[] p) {
            assertEquals(
                    committedMatching(p),
                    drain(idx.iterPrefix(p)),
                    "iterPrefix "
                            + java.util.Arrays.toString(p)
                            + " must return the committed keys under that prefix");
        }

        /**
         * Flush pending edits to a new committed root; afterward a scan must reflect everything
         * inserted since.
         */
        void commitAndReverify() {
            idx.commit();
            committed.clear();
            committed.addAll(live);
            assertEquals(
                    committed, drain(idx.iter()), "post-commit iter() must equal the live set");
        }

        private Set<SpocKey> committedMatching(TermId[] prefix) {
            Set<SpocKey> out = new HashSet<>();
            for (SpocKey k : committed) if (matches(k, prefix)) out.add(k);
            return out;
        }

        private static boolean matches(SpocKey k, TermId[] prefix) {
            for (int i = 0; i < prefix.length; i++) {
                if (!prefix[i].equals(column(k, i))) return false;
            }
            return true;
        }

        private static TermId column(SpocKey k, int i) {
            return switch (i) {
                case 0 -> k.col0();
                case 1 -> k.col1();
                case 2 -> k.col2();
                default -> k.col3();
            };
        }
    }

    private static Set<SpocKey> drain(Iterator<SpocKey> it) {
        Set<SpocKey> out = new HashSet<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private static String str(SpocKey k) {
        return "("
                + k.col0().value()
                + ","
                + k.col1().value()
                + ","
                + k.col2().value()
                + ","
                + k.col3().value()
                + ")";
    }
}
