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

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 *
 *
 * <h3>Cascade canonicalizer — try cheap-and-fast, escalate when needed.</h3>
 *
 * <p>Composes a list of canonicalizers in order from cheapest to strongest. Each call attempts
 * level 0 first; on {@link NonCanonicalizableException} it falls through to level 1, then level 2,
 * etc. The first level that succeeds wins; if none resolves, the wrapper throws with a diagnostic
 * listing the levels that were tried.
 *
 * <h4>Default cascade</h4>
 *
 * <p>{@link #INSTANCE} uses, in order:
 *
 * <ol>
 *   <li>{@link SimpleFirstDegreeCanonicalizer} (iter 2) — cheapest; handles the blank-node-rename
 *       case.
 *   <li>{@link SecondDegreeCanonicalizer} (iter 4) — closes the additional case of two blank nodes
 *       with identical first-degree shape but distinguishable blank-node neighbours.
 *   <li>(Future iter 6+: full URDNA2015 / RDFC-1.0 N-degree algorithm; reserved slot.)
 * </ol>
 *
 * <h4>Why a cascade beats a single stronger canonicalizer</h4>
 *
 * <p>Cost is order-of-magnitude different across levels. SimpleFirstDegreeCanonicalizer is
 * O(|blanks| × |quads|); SecondDegreeCanonicalizer adds an adjacency pass plus a re-hash; full
 * URDNA2015 has worst-case super-polynomial behaviour. Trying the cheap one first means the common
 * case (no blank-node collisions or shallow distinguishability) pays only the cheap cost. Only
 * graphs that actually require deeper analysis incur the deeper cost.
 *
 * <h4>Level tracking</h4>
 *
 * <p>An optional {@link IntConsumer} callback receives the zero-based level index that resolved the
 * input. Useful for benchmarking ("what fraction of commits resolved at level 0?") and capacity
 * planning. Defaults to a no-op.
 */
public final class CascadeCanonicalizer implements RdfCanonicalizer {

    /**
     * Default cascade: SimpleFirstDegree → SecondDegree → URDNA2015.
     *
     * <p>Iter 12 (= 6g) added {@link UrdnaCanonicalizer} at level 2. Graphs that need the full W3C
     * RDFC-1.0 algorithm now resolve here instead of defeating the cascade. Fast-path performance
     * for graphs that resolve at level 0 or 1 is unchanged.
     */
    public static final CascadeCanonicalizer INSTANCE =
            new CascadeCanonicalizer(
                    List.of(
                            SimpleFirstDegreeCanonicalizer.INSTANCE,
                            SecondDegreeCanonicalizer.INSTANCE,
                            UrdnaCanonicalizer.INSTANCE),
                    i -> {});

    private final List<RdfCanonicalizer> cascade;
    private final IntConsumer levelCallback;

    /** Custom cascade with a no-op level callback. */
    public CascadeCanonicalizer(List<RdfCanonicalizer> cascade) {
        this(cascade, i -> {});
    }

    /**
     * Custom cascade with a level callback. The callback is invoked with the zero-based index of
     * the level that resolved the input (or never invoked if the cascade fails through).
     */
    public CascadeCanonicalizer(List<RdfCanonicalizer> cascade, IntConsumer levelCallback) {
        if (cascade == null || cascade.isEmpty()) {
            throw new IllegalArgumentException("cascade must be non-empty");
        }
        if (levelCallback == null) {
            throw new IllegalArgumentException(
                    "levelCallback must be non-null (use i -> {} for no-op)");
        }
        this.cascade = List.copyOf(cascade);
        this.levelCallback = levelCallback;
    }

    @Override
    public List<QuadPattern> canonicalize(List<QuadPattern> quads) {
        List<String> diagnostics = new ArrayList<>(cascade.size());
        for (int i = 0; i < cascade.size(); i++) {
            try {
                List<QuadPattern> result = cascade.get(i).canonicalize(quads);
                levelCallback.accept(i);
                return result;
            } catch (NonCanonicalizableException e) {
                diagnostics.add(
                        "level "
                                + i
                                + " ("
                                + cascade.get(i).getClass().getSimpleName()
                                + "): "
                                + e.getMessage());
            }
        }
        throw new NonCanonicalizableException(
                "no canonicalizer in the cascade resolved the input. Tried: "
                        + String.join(" | ", diagnostics));
    }

    /** The cascade's length, useful for tests and diagnostics. */
    public int levels() {
        return cascade.size();
    }
}
