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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.semantic.canon.NonCanonicalizableException;
import com.earasoft.prolly.semantic.canon.NoopCanonicalizer;
import com.earasoft.prolly.semantic.canon.RdfCanonicalizer;
import com.earasoft.prolly.semantic.canon.SimpleFirstDegreeCanonicalizer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The canonicalize-under-budget, fail-closed contract — extracted from the retired {@code
 * CanonicalizingQuadStore} into {@link CanonicalizationBudget} (ADR-0037 D-5). Store-independent;
 * {@link CanonicalizingProllySailIntegrationTest} covers the Sail wrapper that uses it.
 */
class CanonicalizationBudgetTest {

    private static QuadPattern q(String s, String p, String o) {
        return QuadPattern.of(s, p, o, "g");
    }

    /** Two structurally-equivalent blank-node graphs canonicalize to byte-identical lists. */
    @Test
    void normalizesBlankNodeRename() {
        List<QuadPattern> graphA =
                List.of(q("_:x", "ex:knows", "ex:bob"), q("_:x", "ex:age", "30"));
        List<QuadPattern> graphB =
                List.of(q("_:y", "ex:knows", "ex:bob"), q("_:y", "ex:age", "30"));
        List<QuadPattern> cA =
                CanonicalizationBudget.apply(
                        SimpleFirstDegreeCanonicalizer.INSTANCE, Duration.ofSeconds(1), graphA);
        List<QuadPattern> cB =
                CanonicalizationBudget.apply(
                        SimpleFirstDegreeCanonicalizer.INSTANCE, Duration.ofSeconds(1), graphB);
        assertEquals(cA, cB);
        assertEquals("_:c14n0", cA.get(0).s().value());
    }

    @Test
    void emptyInputIsIdentity() {
        List<QuadPattern> empty = List.of();
        assertSame(
                empty,
                CanonicalizationBudget.apply(
                        SimpleFirstDegreeCanonicalizer.INSTANCE, Duration.ofSeconds(1), empty));
    }

    @Test
    void noopFailsClosedOnBlankNode() {
        assertThrows(
                NonCanonicalizableException.class,
                () ->
                        CanonicalizationBudget.apply(
                                NoopCanonicalizer.INSTANCE,
                                Duration.ofSeconds(1),
                                List.of(q("_:x", "ex:p", "ex:o"))));
    }

    @Test
    void timeoutFailsClosed() {
        RdfCanonicalizer slow =
                quads -> {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NonCanonicalizableException("interrupted", e);
                    }
                    return quads;
                };
        NonCanonicalizableException ex =
                assertThrows(
                        NonCanonicalizableException.class,
                        () ->
                                CanonicalizationBudget.apply(
                                        slow,
                                        Duration.ofMillis(50),
                                        List.of(q("ex:s", "ex:p", "ex:o"))));
        assertTrue(
                ex.getMessage().contains("time budget"),
                "expected time-budget diagnostic, got: " + ex.getMessage());
    }

    @Test
    void propagatesCollisionDiagnostic() {
        List<QuadPattern> cyclic =
                List.of(q("_:b1", "ex:knows", "_:b2"), q("_:b2", "ex:knows", "_:b1"));
        NonCanonicalizableException ex =
                assertThrows(
                        NonCanonicalizableException.class,
                        () ->
                                CanonicalizationBudget.apply(
                                        SimpleFirstDegreeCanonicalizer.INSTANCE,
                                        Duration.ofSeconds(1),
                                        cyclic));
        assertTrue(
                ex.getMessage().contains("first-degree hash collision"),
                "expected collision diagnostic from canonicalizer, got: " + ex.getMessage());
    }

    @Test
    void validatesArguments() {
        List<QuadPattern> graph = List.of(q("ex:s", "ex:p", "ex:o"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalizationBudget.apply(null, Duration.ofSeconds(1), graph));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalizationBudget.apply(
                                SimpleFirstDegreeCanonicalizer.INSTANCE, null, graph));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalizationBudget.apply(
                                SimpleFirstDegreeCanonicalizer.INSTANCE, Duration.ZERO, graph));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CanonicalizationBudget.apply(
                                SimpleFirstDegreeCanonicalizer.INSTANCE,
                                Duration.ofMillis(-1),
                                graph));
    }
}
