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
package com.earasoft.prolly.rdf4j.sail.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Step 4 of plans/triejoin-evaluation-wiring.md — the GYO α-acyclicity routing predicate. */
class CyclicBgpDetectorTest {

    private static Set<String> e(String... v) {
        return new HashSet<>(List.of(v));
    }

    @Test
    void triangleIsCyclic() { // ?x e ?y . ?y e ?z . ?z e ?x  — the WCOJ win case
        assertTrue(CyclicBgpDetector.isCyclic(List.of(e("x", "y"), e("y", "z"), e("z", "x"))));
    }

    @Test
    void fourCycleIsCyclic() {
        assertTrue(
                CyclicBgpDetector.isCyclic(
                        List.of(e("a", "b"), e("b", "c"), e("c", "d"), e("d", "a"))));
    }

    @Test
    void twoDisjointTrianglesAreCyclic() {
        assertTrue(
                CyclicBgpDetector.isCyclic(
                        List.of(
                                e("a", "b"),
                                e("b", "c"),
                                e("c", "a"),
                                e("d", "f"),
                                e("f", "g"),
                                e("g", "d"))));
    }

    @Test
    void trianglePlusPendantStillCyclic() { // the cyclic core survives an ear
        assertTrue(
                CyclicBgpDetector.isCyclic(
                        List.of(e("x", "y"), e("y", "z"), e("z", "x"), e("z", "w"))));
    }

    @Test
    void path2IsAcyclic() {
        assertFalse(CyclicBgpDetector.isCyclic(List.of(e("x", "y"), e("y", "z"))));
    }

    @Test
    void starIsAcyclic() { // hub + spokes — all spokes are ears
        assertFalse(CyclicBgpDetector.isCyclic(List.of(e("h", "a"), e("h", "b"), e("h", "c"))));
    }

    @Test
    void singlePatternIsAcyclic() {
        assertFalse(CyclicBgpDetector.isCyclic(List.of(e("x", "y"))));
    }

    @Test
    void cartesianTwoEdgesIsAcyclic() { // disconnected — no join cycle
        assertFalse(CyclicBgpDetector.isCyclic(List.of(e("a", "b"), e("c", "d"))));
    }

    @Test
    void duplicateEdgesAreAcyclic() { // containment collapses them
        assertFalse(CyclicBgpDetector.isCyclic(List.of(e("x", "y"), e("x", "y"))));
    }

    @Test
    void emptyIsAcyclic() {
        assertFalse(CyclicBgpDetector.isCyclic(List.of()));
    }
}
