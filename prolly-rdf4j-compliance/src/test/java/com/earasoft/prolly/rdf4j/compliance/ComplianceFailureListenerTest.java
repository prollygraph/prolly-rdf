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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * The ratchet's own instrument, finally under test (hardening round 1 — the inventory noted a
 * regression in {@link ComplianceFailureListener} would silently weaken every drift diagnosis that
 * diffs {@code target/compliance-failures.txt} against the baselines). Drives a real JUnit Platform
 * launcher over a hidden fixture class (no {@code *Test} suffix, so surefire never picks it up)
 * with one failing and one passing test, and asserts the listener records exactly the failure, by
 * display name, in the file the drift workflow reads.
 */
class ComplianceFailureListenerTest {

    /** Fixture: invisible to surefire (name), explicitly selected by the launcher below. */
    static class ListenerFixture {
        @Test
        void thisOneFails() {
            throw new AssertionError("deliberate fixture failure");
        }

        @Test
        void thisOnePasses() {}
    }

    @Test
    void recordsExactlyTheFailedTestsForTheDriftFile() throws Exception {
        ComplianceFailureListener listener = new ComplianceFailureListener();
        Launcher launcher = LauncherFactory.create();
        launcher.execute(
                LauncherDiscoveryRequestBuilder.request()
                        .selectors(selectClass(ListenerFixture.class))
                        .build(),
                listener);
        Path out = Path.of("target", "compliance-failures.txt");
        assertTrue(Files.exists(out), "the listener writes the drift file at plan finish");
        List<String> lines = Files.readAllLines(out);
        assertTrue(
                lines.stream().anyMatch(l -> l.contains("thisOneFails")),
                "the failed test is recorded by display name: " + lines);
        assertFalse(
                lines.stream().anyMatch(l -> l.contains("thisOnePasses")),
                "passing tests are NOT recorded: " + lines);
    }
}
