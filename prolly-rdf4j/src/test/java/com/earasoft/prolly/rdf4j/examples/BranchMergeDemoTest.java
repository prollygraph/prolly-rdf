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
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks {@link BranchMergeDemo} into CI — the demo's branch/merge narrative would silently rot the
 * moment the versioning API shifts.
 */
class BranchMergeDemoTest {

    @Test
    void demoForksAndMergesABranch(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BranchMergeDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        assertTrue(
                output.contains("result: OK"),
                () -> "expected a successful three-way merge:\n" + output);

        // After the merge, main HEAD holds all three teammates: Alice (base),
        // Carol (main's divergent commit), Dave (merged in from 'feature').
        assertTrue(output.contains("urn:team:alice"), () -> output);
        assertTrue(output.contains("urn:team:carol"), () -> output);
        assertTrue(output.contains("urn:team:dave"), () -> output);

        assertTrue(
                output.contains("feature"),
                () -> "the feature branch should be listed:\n" + output);
        assertTrue(
                output.contains("converged via a 3-way merge"),
                () -> "expected the final banner:\n" + output);
    }

    @Test
    void daveIsAbsentBeforeTheMergeAndPresentAfter(@TempDir Path tmp) throws Exception {
        // The sharpest proof the merge did real work: Dave is committed only on
        // the feature branch, so he must be missing from main's pre-merge
        // roster and present in the post-merge one.
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        BranchMergeDemo.run(tmp, new PrintStream(buf, true, StandardCharsets.UTF_8));
        String output = buf.toString(StandardCharsets.UTF_8);

        int beforeIdx = output.indexOf("[4] Before merge");
        int mergeIdx = output.indexOf("[5] Merging");
        int afterIdx = output.indexOf("[6] After merge");
        assertTrue(
                beforeIdx >= 0 && mergeIdx > beforeIdx && afterIdx > mergeIdx,
                () -> "expected the demo's [4]/[5]/[6] sections in order:\n" + output);

        String beforeRoster = output.substring(beforeIdx, mergeIdx);
        String afterRoster = output.substring(afterIdx);
        assertFalse(
                beforeRoster.contains("urn:team:dave"),
                () -> "Dave must NOT be in main's pre-merge roster:\n" + output);
        assertTrue(
                afterRoster.contains("urn:team:dave"),
                () -> "Dave must appear in main's post-merge roster:\n" + output);
    }
}
