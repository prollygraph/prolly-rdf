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
package com.earasoft.prolly.rdf4j.examples;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks {@link VersioningDemo} into CI — the demo's narrative would silently rot the moment the
 * versioning / time-travel API shifts.
 *
 * <p>Runs the <b>same</b> narrative over <b>every</b> {@link VersioningDemo.StoreKind} backend.
 * That is the point of the demo's {@code --store=} flag made into a test: {@code ProllySail} is
 * typed to the {@code NodeStore} interface, so a git-loose-objects {@code FileNodeStore} and a
 * packed {@code RocksNodeStore} must produce the identical commit / time-travel / branch-merge /
 * diff story. Each backend gets its own sub-directory — they cannot share the {@code beside(dir)}
 * control-plane files ({@code CommitLog} / {@code RefsStore} / {@code RootMetaTree}), whose refs
 * point at backend-specific chunk hashes.
 */
class VersioningDemoTest {

    @TestFactory
    Stream<DynamicTest> demoNarrativeHoldsOverEveryBackend(@TempDir Path tmp) {
        return Stream.of(VersioningDemo.StoreKind.values())
                .map(
                        kind ->
                                DynamicTest.dynamicTest(
                                        "backend: " + kind,
                                        () -> {
                                            Path dir = tmp.resolve(kind.name());
                                            Files.createDirectories(dir);
                                            ByteArrayOutputStream buf = new ByteArrayOutputStream();
                                            VersioningDemo.run(
                                                    dir,
                                                    kind,
                                                    new PrintStream(
                                                            buf, true, StandardCharsets.UTF_8));
                                            assertDemoNarrative(
                                                    buf.toString(StandardCharsets.UTF_8));
                                        }));
    }

    /** The full versioning / time-travel / merge / diff narrative — must hold over any backend. */
    private static void assertDemoNarrative(String output) {
        // All three commit messages appear in the printed history.
        assertTrue(output.contains("Catalog Dune"), () -> output);
        assertTrue(output.contains("Add Foundation"), () -> output);
        assertTrue(output.contains("Fix Dune year"), () -> output);

        // Time-travel: commit 1 recorded the wrong year, HEAD the corrected one.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("commit 1") && l.contains("1965")),
                () -> "commit 1 should show the original year 1965:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("HEAD") && l.contains("1966")),
                () -> "HEAD should show the corrected year 1966:\n" + output);

        // Time-travel: the catalog grew from 1 book to 2 across the commits.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("commit 1") && l.contains("1 book")),
                () -> "commit 1 should hold 1 book:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("HEAD") && l.contains("2 book")),
                () -> "HEAD should hold 2 books:\n" + output);

        // Branch & merge: the merged HEAD holds books from BOTH branches (conflict-free union).
        assertTrue(
                output.contains("Neuromancer"),
                () -> "the merge should bring in the branch's book (Neuromancer):\n" + output);
        assertTrue(
                output.contains("Hyperion"),
                () -> "the merge should keep main's divergent book (Hyperion):\n" + output);

        // A conflicting merge (same prefix → two IRIs) is detected and refused, installing nothing.
        assertTrue(
                output.lines().anyMatch(l -> l.contains("structural merge result: CONFLICT")),
                () -> "the prefix-remap merge should report a CONFLICT:\n" + output);
        assertTrue(
                output.contains("conflict on prefix 'shelf'"),
                () -> "the conflict should name the clashing prefix:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("HEAD unchanged: true")),
                () -> "a conflicting merge must not advance HEAD:\n" + output);

        // Squash merge: a multi-commit feature branch collapses to one commit; both its books land.
        assertTrue(
                output.contains("Snow Crash"),
                () ->
                        "squash merge should bring in the branch's first book (Snow Crash):\n"
                                + output);
        assertTrue(
                output.contains("Anathem"),
                () ->
                        "squash merge should bring in the branch's second book (Anathem):\n"
                                + output);

        // Diff: the +/- delta surfaces the Foundation add (1→2) and Dune's year change as -/+
        // (2→3).
        assertTrue(
                output.contains("+ Foundation (1951)"),
                () -> "the 1→2 diff should show Foundation added:\n" + output);
        assertTrue(
                output.lines().anyMatch(l -> l.contains("- Dune (1965)"))
                        && output.lines().anyMatch(l -> l.contains("+ Dune (1966)")),
                () -> "the 2→3 diff should show Dune's year change as a -/+ pair:\n" + output);

        assertTrue(
                output.contains("independently queryable"),
                () -> "expected the final banner:\n" + output);
    }
}
