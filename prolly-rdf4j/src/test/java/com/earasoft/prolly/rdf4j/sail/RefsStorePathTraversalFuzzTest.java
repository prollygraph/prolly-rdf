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

import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Generative path-traversal fuzzing of {@link RefsStore#validateName(String)} (prolly-rdf
 * test-strategy Step 33 / D-8 — the untrusted-bytes gate, security surface). Branch names arrive
 * from untrusted callers (REST {@code /sparql/branches}, sync ref advertisements) and are fed
 * straight into {@code dir.resolve(name)} for file read/write/delete. The 2026-05-15 security fix
 * added {@code validateName} to stop {@code ../}, absolute-path, and {@code /../}-segment escapes
 * that turned put/delete into an arbitrary-file write/delete.
 *
 * <p>{@link RefsStorePathTraversalTest} pins the <i>known</i> attack vectors deterministically.
 * This harness proves the <b>invariant across the whole input space</b>: for any string, either
 * {@code validateName} rejects it, or the resolved path stays strictly inside the refs directory. A
 * counterexample — a name Jazzer finds that is accepted yet escapes — is a real security regression
 * and becomes a permanent corpus seed.
 *
 * <p>Fast REGRESSION replay on every build; active coverage-guided fuzzing under {@code -Pfuzz}
 * ({@code JAZZER_FUZZ=1}).
 */
class RefsStorePathTraversalFuzzTest {

    // A synthetic, normalized base dir. No filesystem access — the property is
    // purely about path resolution, so this is hermetic and platform-stable.
    private static final Path REFS_DIR = Path.of("/srv/store/refs").normalize();

    @FuzzTest(maxDuration = "60s") // only active under -Pfuzz (JAZZER_FUZZ); else regression-replay
    void acceptedBranchNameNeverEscapesRefsDir(String name) {
        if (name == null) return;
        try {
            RefsStore.validateName(name);
        } catch (IllegalArgumentException rejected) {
            return; // hostile/invalid name refused — correct behavior
        }
        // validateName accepted it. The accepted name MUST resolve to a path
        // strictly within REFS_DIR; anything else is a traversal escape.
        Path resolved;
        try {
            resolved = REFS_DIR.resolve(name).normalize();
        } catch (InvalidPathException e) {
            // An accepted name that the platform cannot even turn into a path
            // is itself a validation gap (the NAME_PATTERN should have refused
            // it) — surface it rather than swallow.
            throw new AssertionError(
                    "validateName accepted a name that is not a " + "legal path: '" + name + "'",
                    e);
        }
        if (!resolved.startsWith(REFS_DIR)) {
            throw new AssertionError(
                    "PATH-TRAVERSAL: validateName accepted '"
                            + name
                            + "' but it resolves outside refs/ to "
                            + resolved);
        }
    }
}
