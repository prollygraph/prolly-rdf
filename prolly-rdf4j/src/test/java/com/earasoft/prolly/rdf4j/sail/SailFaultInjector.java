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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Phase 6 Step 22 of {@code prolly-rdf4j-test-strategy.md} (S-8) — a <b>seeded, recording</b> fault
 * decision oracle that unifies how the ad-hoc Sail fault-injection tests express "fail this
 * operation". It is the decision <i>brain</i>; the seams that consult it (the {@link
 * FaultInjectingNodeStore} decorator for the store points) are the <i>hands</i>.
 *
 * <p><b>What it gives over the bare {@code ErrorInjectingNodeStore} countdown:</b> (1) a <b>named
 * fault point</b> per decision (so a crash can be aimed at a specific layer, and the four-point
 * space is explicit for Step 23's enumerated-boundary crash test); (2) a <b>recorded decision
 * log</b> — the exact sequence of {@code (ordinal, point, fail?)} a run took — so a failing seeded
 * run is replayable and diagnosable bit-for-bit; and (3) <b>three policies</b>: {@link #none()}
 * (transparent control), {@link #failNth(FaultPoint, int)} (fail the n-th decision at a point — the
 * deterministic enumerated-boundary primitive), and {@link #seeded(long, double)} (each decision
 * fails with a fixed probability, deterministically derived from {@code (seed, ordinal)} — the
 * fuzzing primitive).
 *
 * <p><b>Scope, stated honestly (the {@code final}-class limit).</b> Only two of the four fault
 * points are wired today, because only the {@code NodeStore} is an interface the decorator can
 * wrap. {@code CommitLog} and {@code RootMetaTreeStore} are {@code final} classes — decorating them
 * would require an invasive production refactor for marginal gain, and their failure modes are
 * already covered by a <i>different</i> fault class: {@code CommitLogFaultInjectionTest} drives
 * torn-trailing-line / mid-file corruption (a format-recovery test, not an injected runtime
 * exception) and {@code SailAutoRestoreFaultInjectionTest} hand-builds a corrupt manifest. So
 * {@link FaultPoint#COMMIT_LOG_APPEND} and {@link FaultPoint#ROOT_META_PERSIST} are <b>reserved in
 * the enum</b> (named, for Step 23 and for a future seam) but not yet driven — this injector
 * unifies the <b>store seam</b> (commit-path writes + auto-restore reads), the points that dominate
 * crash-recovery.
 *
 * <p><b>Replayability.</b> The decision for {@link #failNth} and {@link #seeded} is a pure function
 * of the call sequence (and, for {@code seeded}, the seed) — so two injectors built the same way,
 * driven through the same {@link #shouldFail} calls, produce byte-identical {@link #decisions()}
 * logs. That is the bit-for-bit-replay contract Step 22 asks for, pinned by {@code
 * SailFaultInjectorTest}.
 *
 * <p><b>Thread-safety.</b> {@code shouldFail} is {@code synchronized} because the Sail's commit
 * flushes the seven per-transaction tables <i>in parallel</i> — so the {@link
 * FaultInjectingNodeStore} seam consults this injector from several threads at once. The lock keeps
 * the ordinal counters and the decision log consistent (an unsynchronized {@code ArrayList}/counter
 * would corrupt under that fan-out). Under a parallel flush the per-thread <i>arrival order</i> of
 * decisions is not itself deterministic, so on the concurrent commit path {@code failNth(point, n)}
 * means "the n-th decision to arrive at {@code point}" rather than a fixed chunk — which is exactly
 * what an enumerated crash boundary wants (n-1 writes durable before the crash, whichever chunks
 * those were).
 */
public final class SailFaultInjector {

    /**
     * The injectable points in the Sail's durable path. STORE_* are wired (decoratable); the other
     * two are reserved (their backing classes are {@code final} — see the class note).
     */
    public enum FaultPoint {
        STORE_READ,
        STORE_WRITE,
        COMMIT_LOG_APPEND,
        ROOT_META_PERSIST
    }

    /**
     * One recorded decision: its global order, the point, its per-point order, and whether it
     * failed.
     */
    public record Decision(int globalOrdinal, FaultPoint point, int pointOrdinal, boolean fail) {}

    /**
     * The decision rule. {@code pointOrdinal} counts decisions at this point (1-based); {@code
     * globalOrdinal} counts all decisions (1-based). Must be a pure function of its arguments (+
     * any captured seed) so the run is replayable.
     */
    @FunctionalInterface
    public interface Policy {
        boolean fail(FaultPoint point, int pointOrdinal, int globalOrdinal);
    }

    private final Policy policy;
    private int global;
    private final EnumMap<FaultPoint, Integer> perPoint = new EnumMap<>(FaultPoint.class);
    private final List<Decision> log = new ArrayList<>();

    public SailFaultInjector(Policy policy) {
        this.policy = policy;
    }

    /**
     * Consult the oracle for one operation at {@code point}: advance the ordinals, decide, record,
     * return. {@code synchronized} — the parallel commit flush consults this from several threads
     * at once.
     */
    public synchronized boolean shouldFail(FaultPoint point) {
        int g = ++global;
        int po = perPoint.merge(point, 1, Integer::sum);
        boolean fail = policy.fail(point, po, g);
        log.add(new Decision(g, point, po, fail));
        return fail;
    }

    /** The recorded decision log — the run's reproducer. */
    public synchronized List<Decision> decisions() {
        return List.copyOf(log);
    }

    /** How many decisions actually injected a failure. */
    public synchronized int faultsInjected() {
        return (int) log.stream().filter(Decision::fail).count();
    }

    // ---- policies ----

    /** Never fails — a transparent control arm (the decorator must be a no-op under it). */
    public static SailFaultInjector none() {
        return new SailFaultInjector((p, po, g) -> false);
    }

    /**
     * Fail exactly the {@code n}-th decision at {@code point} (1-based) — the enumerated-boundary
     * primitive.
     */
    public static SailFaultInjector failNth(FaultPoint point, int n) {
        return new SailFaultInjector((p, po, g) -> p == point && po == n);
    }

    /**
     * Each decision fails with probability {@code prob}, deterministically derived from {@code
     * (seed, globalOrdinal)} — the fuzzing primitive. Same seed → same decision sequence
     * (replayable).
     */
    public static SailFaultInjector seeded(long seed, double prob) {
        return new SailFaultInjector(
                (p, po, g) ->
                        new SplittableRandom(seed * 0x9E3779B97F4A7C15L + g).nextDouble() < prob);
    }
}
