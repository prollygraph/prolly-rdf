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

import java.util.List;

/**
 * Phase 4 Step 13 of {@code multi-variable-leapfrog-triejoin.md} — a <b>pluggable</b> strategy for
 * choosing the triejoin's global variable order.
 *
 * <p>The triejoin is correct under <i>any</i> variable order (including cyclic queries — Step 8),
 * so the order is purely a performance lever: the variable bound first is the outermost loop, so
 * binding the most-constrained variable first minimises the level-0 fan-out and the work below it.
 * This interface lets the order be supplied by a heuristic ({@link SelectivityVariableOrder}, the
 * default) or overridden — e.g. fixed for tests, or a future cost-based planner.
 */
public interface VariableOrderHeuristic {

    /** A global order over every variable appearing in {@code patterns}. */
    List<String> order(List<QuadPattern> patterns);
}
