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
package com.earasoft.prolly.semantic.canon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Verifies the cascade behaviour of {@link CascadeCanonicalizer}:
 *
 * <ol>
 *   <li>Cheap input resolves at level 0; deeper levels aren't tried.
 *   <li>Input needing second-degree falls through level 0, resolves at level 1.
 *   <li>Input that defeats the whole cascade throws with a multi-level diagnostic.
 *   <li>Constructor validates a non-empty cascade.
 *   <li>Level callback fires with the correct level index.
 * </ol>
 */
class CascadeCanonicalizerTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    /**
     * Simple blank-node rename: first-degree alone resolves it. Cascade returns at level 0 without
     * invoking the second-degree canonicalizer.
     */
    @Test
    void simpleCase_resolvesAtLevel0() {
        List<QuadPattern> graph = List.of(q("_:x", "ex:knows", "ex:bob"));

        AtomicInteger resolved = new AtomicInteger(-1);
        CascadeCanonicalizer cc =
                new CascadeCanonicalizer(
                        List.of(
                                SimpleFirstDegreeCanonicalizer.INSTANCE,
                                SecondDegreeCanonicalizer.INSTANCE),
                        resolved::set);

        List<QuadPattern> result = cc.canonicalize(graph);

        assertEquals(0, resolved.get(), "expected level 0 to resolve");
        assertEquals("_:c14n0", result.get(0).s().value());
    }

    /**
     * Two blank nodes sharing first-degree but distinguishable at second-degree: level 0 throws,
     * level 1 succeeds.
     */
    @Test
    void neighbourDistinguishableCase_resolvesAtLevel1() {
        List<QuadPattern> graph =
                List.of(
                        q("_:p1", "ex:follows", "_:friend1"),
                        q("_:friend1", "ex:knows", "ex:alice"),
                        q("_:p2", "ex:follows", "_:friend2"),
                        q("_:friend2", "ex:knows", "ex:bob"));
        // Confirm the failure mode at level 0 for sanity.
        assertThrows(
                NonCanonicalizableException.class,
                () -> SimpleFirstDegreeCanonicalizer.INSTANCE.canonicalize(graph));

        AtomicInteger resolved = new AtomicInteger(-1);
        CascadeCanonicalizer cc =
                new CascadeCanonicalizer(
                        List.of(
                                SimpleFirstDegreeCanonicalizer.INSTANCE,
                                SecondDegreeCanonicalizer.INSTANCE),
                        resolved::set);

        List<QuadPattern> result = cc.canonicalize(graph);

        assertEquals(1, resolved.get(), "expected level 1 to resolve");
        long blanksWithCanonical =
                result.stream()
                        .flatMap(p -> java.util.stream.Stream.of(p.s().value(), p.o().value()))
                        .filter(s -> s.startsWith("_:c14n"))
                        .distinct()
                        .count();
        assertEquals(4, blanksWithCanonical);
    }

    /**
     * Cyclic pair defeats both first-degree and second-degree; with URDNA2015 at level 2 (iter 6g),
     * it now resolves there. Each blank gets a distinct canonical name; cycle preserved.
     */
    @Test
    void cyclicCase_resolvesAtLevel2() {
        List<QuadPattern> graph =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));

        AtomicInteger resolved = new AtomicInteger(-1);
        CascadeCanonicalizer cc =
                new CascadeCanonicalizer(
                        List.of(
                                SimpleFirstDegreeCanonicalizer.INSTANCE,
                                SecondDegreeCanonicalizer.INSTANCE,
                                UrdnaCanonicalizer.INSTANCE),
                        resolved::set);

        List<QuadPattern> canon = cc.canonicalize(graph);
        assertEquals(2, resolved.get(), "expected level 2 (URDNA2015) to resolve");
        assertEquals(2, canon.size());
    }

    /**
     * A truly impossible case for the default cascade is hard to construct since URDNA2015 resolves
     * any well-formed RDF graph. We synthesise one by stripping URDNA2015 from the cascade and
     * passing the cyclic input — the surviving two levels both fail.
     */
    @Test
    void degenerateCascade_propagatesMultiLevelDiagnostic() {
        List<QuadPattern> graph =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        CascadeCanonicalizer twoLevels =
                new CascadeCanonicalizer(
                        List.of(
                                SimpleFirstDegreeCanonicalizer.INSTANCE,
                                SecondDegreeCanonicalizer.INSTANCE));
        NonCanonicalizableException ex =
                assertThrows(
                        NonCanonicalizableException.class, () -> twoLevels.canonicalize(graph));

        assertTrue(ex.getMessage().contains("level 0"));
        assertTrue(ex.getMessage().contains("level 1"));
        assertTrue(ex.getMessage().contains("SimpleFirstDegreeCanonicalizer"));
        assertTrue(ex.getMessage().contains("SecondDegreeCanonicalizer"));
    }

    /** No-blanks input is identity pass-through at level 0. */
    @Test
    void noBlankNodes_passesThroughAtLevel0() {
        List<QuadPattern> input = List.of(q("ex:alice", "ex:knows", "ex:bob"));
        AtomicInteger resolved = new AtomicInteger(-1);
        CascadeCanonicalizer cc =
                new CascadeCanonicalizer(
                        List.of(
                                SimpleFirstDegreeCanonicalizer.INSTANCE,
                                SecondDegreeCanonicalizer.INSTANCE),
                        resolved::set);
        assertSame(input, cc.canonicalize(input));
        assertEquals(0, resolved.get());
    }

    /** Default INSTANCE has three levels after iter 6g cascade integration. */
    @Test
    void defaultInstance_hasThreeLevels() {
        assertEquals(3, CascadeCanonicalizer.INSTANCE.levels());
    }

    /** Constructor rejects empty/null cascade and null callback. */
    @Test
    void constructor_validatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new CascadeCanonicalizer(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new CascadeCanonicalizer(null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CascadeCanonicalizer(
                                List.of(SimpleFirstDegreeCanonicalizer.INSTANCE), null));
    }

    /**
     * Single-level cascade with a canonicalizer that always throws: surfaces the underlying
     * NonCanonicalizableException through the cascade-wrapper's diagnostic.
     */
    @Test
    void singleLevelCascade_propagatesFailureWithDiagnostic() {
        RdfCanonicalizer alwaysThrows =
                quads -> {
                    throw new NonCanonicalizableException("test-failure");
                };
        var cc = new CascadeCanonicalizer(List.of(alwaysThrows));
        NonCanonicalizableException ex =
                assertThrows(
                        NonCanonicalizableException.class,
                        () -> cc.canonicalize(List.of(q("_:x", "ex:p", "ex:o"))));
        assertTrue(ex.getMessage().contains("test-failure"));
    }
}
