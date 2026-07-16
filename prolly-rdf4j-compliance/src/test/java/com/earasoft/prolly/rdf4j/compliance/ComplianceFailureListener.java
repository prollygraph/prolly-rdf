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
package com.earasoft.prolly.rdf4j.compliance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Captures the display names of <em>failed</em> dynamic tests from the W3C conformance suites and
 * writes them, sorted, to {@code target/compliance-failures.txt}.
 *
 * <p>Surefire collapses every {@code @TestFactory}-generated dynamic test under the single name
 * {@code "tests"}, so its XML/text reports cannot say <em>which</em> W3C cases failed. This JUnit
 * Platform listener — registered via {@code META-INF/services} — sees the real per-test display
 * name, which is exactly the manifest test name that {@code
 * SPARQLComplianceTest.addIgnoredTest(String)} matches on.
 *
 * <p>The emitted file is the raw material for the file-backed known-failures baseline (plan 10,
 * §10.10): diff it against {@code known-failures/*.txt}.
 */
public final class ComplianceFailureListener implements TestExecutionListener {

    private final List<String> failures = new ArrayList<>();

    @Override
    public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest() && result.getStatus() == TestExecutionResult.Status.FAILED) {
            failures.add(id.getDisplayName());
        }
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        Path out = Path.of("target", "compliance-failures.txt");
        try {
            Files.createDirectories(out.getParent());
            failures.sort(String::compareTo);
            Files.write(out, failures, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + out, e);
        }
    }
}
