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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.sail.SailFaultInjector.Decision;
import com.earasoft.prolly.rdf4j.sail.SailFaultInjector.FaultPoint;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 Step 22 — the {@link SailFaultInjector} self-test: pins the three policies and, above
 * all, the <b>bit-for-bit replay</b> contract the step asks for (a failing seeded run is
 * reproducible from its seed). The injector is the brain the {@link FaultInjectingNodeStore} seam
 * consults; here it is driven directly so the decision logic is pinned independent of any store.
 */
class SailFaultInjectorTest {

    /**
     * Drive a fixed call sequence and return the decisions — the shared workload for the replay
     * tests.
     */
    private static List<Decision> drive(SailFaultInjector inj) {
        for (int i = 0; i < 5; i++) {
            inj.shouldFail(FaultPoint.STORE_WRITE);
            inj.shouldFail(FaultPoint.STORE_READ);
        }
        return inj.decisions();
    }

    @Test
    void none_never_fails_and_still_records_every_decision() {
        SailFaultInjector inj = SailFaultInjector.none();
        List<Decision> log = drive(inj);
        assertEquals(10, log.size(), "every consultation is recorded, even when it does not fail");
        assertEquals(0, inj.faultsInjected(), "the control arm injects no failure");
        // Ordinals: global 1..10; per-point 1..5 for each of the two points.
        assertEquals(1, log.get(0).globalOrdinal());
        assertEquals(FaultPoint.STORE_WRITE, log.get(0).point());
        assertEquals(1, log.get(0).pointOrdinal());
        assertEquals(
                5, log.get(8).pointOrdinal(), "the 5th STORE_WRITE (index 8) is the point's 5th");
        assertEquals(FaultPoint.STORE_WRITE, log.get(8).point());
    }

    @Test
    void failNth_fails_exactly_the_nth_decision_at_that_point_and_no_other() {
        SailFaultInjector inj = SailFaultInjector.failNth(FaultPoint.STORE_WRITE, 3);
        List<Decision> log = drive(inj);
        assertEquals(1, inj.faultsInjected(), "exactly one failure across the whole run");
        for (Decision d : log) {
            boolean expected = d.point() == FaultPoint.STORE_WRITE && d.pointOrdinal() == 3;
            assertEquals(expected, d.fail(), "only the 3rd STORE_WRITE fails; " + d + " disagreed");
        }
        // A STORE_READ at the same per-point ordinal must NOT fail (the point discriminates).
        assertFalse(
                SailFaultInjector.failNth(FaultPoint.STORE_WRITE, 3)
                        .shouldFail(FaultPoint.STORE_READ),
                "failNth(STORE_WRITE,3) must not fire on a STORE_READ");
    }

    @Test
    void seeded_is_bit_for_bit_replayable_from_the_same_seed() {
        List<Decision> a = drive(SailFaultInjector.seeded(2026L, 0.5));
        List<Decision> b = drive(SailFaultInjector.seeded(2026L, 0.5));
        assertEquals(
                a,
                b,
                "same seed + same call sequence => identical decision log (the replay contract)");
        // And it actually exercises both outcomes at p=0.5 over 10 draws (not all-true /
        // all-false).
        long fails = a.stream().filter(Decision::fail).count();
        assertTrue(
                fails > 0 && fails < 10, "p=0.5 over 10 draws should mix fail/not — got " + fails);
    }

    @Test
    void different_seeds_generally_diverge() {
        List<Decision> a = drive(SailFaultInjector.seeded(1L, 0.5));
        List<Decision> b = drive(SailFaultInjector.seeded(999_983L, 0.5));
        assertFalse(
                a.equals(b),
                "two unrelated seeds should not produce the identical 10-decision sequence");
    }
}
