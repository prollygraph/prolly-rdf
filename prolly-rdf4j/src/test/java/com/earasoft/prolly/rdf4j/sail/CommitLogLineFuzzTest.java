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

/**
 * Line-parser fuzzing of {@link CommitLog#parseThinRow(String)} (prolly-rdf test-strategy Step 33 /
 * D-8). The commit log is replayed from disk on boot; a torn write, a truncated trailing line, or a
 * garbage/hand-edited line must be rejected with a <b>controlled exception</b> — never an uncaught
 * crash/hang/OOM that aborts recovery.
 *
 * <p>Since ADR-0073 the row is thin — {@code "<RFC 1123 datetime> <hex commit id>"} — and the
 * parser is hardened: every internal failure (bad datetime, bad hex, wrong token count) funnels
 * into a single {@code IllegalStateException("malformed commit-log line: …")}. This harness pins
 * that contract — {@code parseThinRow} either succeeds or throws exactly {@code
 * IllegalStateException}; <i>any other</i> throwable (or a hang) on arbitrary input is a Jazzer
 * finding and a permanent regression seed.
 *
 * <p>Fast REGRESSION replay on every build; active fuzzing under {@code -Pfuzz}.
 */
class CommitLogLineFuzzTest {

    @FuzzTest(maxDuration = "60s") // only active under -Pfuzz (JAZZER_FUZZ); else regression-replay
    void parseRejectsMalformedLineWithControlledException(String line) {
        if (line == null) return;
        try {
            CommitLog.parseThinRow(line);
            // A successful parse is fine — it round-trips a well-formed thin row.
        } catch (IllegalStateException expected) {
            // The single, documented rejection for any malformed line.
        }
    }
}
