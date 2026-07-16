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

import com.earasoft.prolly.semantic.canon.NonCanonicalizableException;
import com.earasoft.prolly.semantic.canon.RdfCanonicalizer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Run an {@link RdfCanonicalizer} under a wall-clock budget, fail-closed.
 *
 * <p>Extracted from the (retired) {@code CanonicalizingQuadStore} so the budget/fail-closed
 * contract is store-independent and reusable by the RDF4J {@link CanonicalizingProllySail} wrapper
 * (ADR-0037 D-5). On overrun the work is cancelled and a {@link NonCanonicalizableException} is
 * thrown — never a best-effort labelling. This is whitepaper §5.2, codified at the substrate
 * boundary.
 */
public final class CanonicalizationBudget {

    /** Default time budget for canonicalization (whitepaper §5.2 default). */
    public static final Duration DEFAULT_TIME_BUDGET = Duration.ofMillis(200);

    private CanonicalizationBudget() {}

    /**
     * Canonicalize {@code input} with {@code canonicalizer} under {@code timeBudget}. Empty/null
     * input is identity. Throws {@link NonCanonicalizableException} on timeout, collision, or any
     * canonicalizer failure.
     */
    public static List<QuadPattern> apply(
            RdfCanonicalizer canonicalizer, Duration timeBudget, List<QuadPattern> input) {
        if (canonicalizer == null) throw new IllegalArgumentException("canonicalizer is required");
        if (timeBudget == null || timeBudget.isNegative() || timeBudget.isZero()) {
            throw new IllegalArgumentException("timeBudget must be positive");
        }
        if (input == null || input.isEmpty()) return input;

        // Single-thread executor per call. The canonicalizer is stateless; pool reuse
        // would help under high commit volume but adds lifecycle concerns we don't need at v1.
        ExecutorService executor =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "prolly-canon");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            Callable<List<QuadPattern>> task = () -> canonicalizer.canonicalize(input);
            Future<List<QuadPattern>> future = executor.submit(task);
            try {
                return future.get(timeBudget.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new NonCanonicalizableException(
                        "canonicalization exceeded time budget of "
                                + timeBudget.toMillis()
                                + "ms; "
                                + "fail-closed rather than emit a non-canonical commit.",
                        e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NonCanonicalizableException nce) throw nce;
                if (cause instanceof RuntimeException re) throw re;
                throw new NonCanonicalizableException("canonicalization failed: " + cause, cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NonCanonicalizableException("canonicalization interrupted", e);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
