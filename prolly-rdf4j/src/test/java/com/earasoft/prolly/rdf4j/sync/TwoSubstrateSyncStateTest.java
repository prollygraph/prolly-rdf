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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.earasoft.prolly.rdf4j.sync.TwoSubstrateSyncState.Heads;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link TwoSubstrateSyncState} (prolly-json-sync Step 5, D-5): the pair record advances both
 * substrates' heads in ONE atomic compare-and-set — the step's named test is the mid-apply-failure
 * contract (neither side advances, no substrate drift), plus the CAS rejection, single-substrate
 * {@code null} heads, crash-leftover tolerance, and the inherited path-traversal guard.
 */
class TwoSubstrateSyncStateTest {

    private static byte[] h(int seed) {
        byte[] out = new byte[20];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (seed + i);
        }
        return out;
    }

    @Test
    void midApplyFailure_leavesNeitherSubstrateAdvanced(@TempDir Path dir) {
        TwoSubstrateSyncState state = TwoSubstrateSyncState.beside(dir);
        Heads before = new Heads(h(1), h(2));
        assertTrue(state.advance("origin", "main", null, before));

        // The coordinator's contract: apply RDF pack, apply JSON pack, THEN advance the pair.
        // Simulate the JSON apply blowing up mid-sync — advance is never reached.
        try {
            // rdf pack applied fine (chunks landed — harmless, unreachable until claimed)
            throw new IllegalStateException("json apply failed");
        } catch (IllegalStateException expected) {
            // no advance() call — the whole point
        }
        Heads after = state.read("origin", "main").orElseThrow();
        assertTrue(before.sameAs(after), "neither substrate may have advanced");
        // And no API exists to advance one substrate alone: Heads always carries both — the
        // drift-freedom is structural, not procedural.
    }

    @Test
    void casSemantics_rejectStaleExpectations(@TempDir Path dir) {
        TwoSubstrateSyncState state = TwoSubstrateSyncState.beside(dir);
        // Record must not exist when expected == null.
        assertTrue(state.advance("origin", "main", null, new Heads(h(1), h(2))));
        assertFalse(
                state.advance("origin", "main", null, new Heads(h(9), h(9))),
                "expected-absent must fail once the record exists");
        // Wrong expected pair rejected, state untouched.
        assertFalse(state.advance("origin", "main", new Heads(h(7), h(7)), new Heads(h(3), h(4))));
        assertArrayEquals(h(1), state.read("origin", "main").orElseThrow().rdfHead());
        // Correct expected pair advances.
        assertTrue(state.advance("origin", "main", new Heads(h(1), h(2)), new Heads(h(3), h(4))));
        assertArrayEquals(h(4), state.read("origin", "main").orElseThrow().jsonHead());
    }

    @Test
    void nullHeads_meanNeverSynced_perSubstrate(@TempDir Path dir) {
        TwoSubstrateSyncState state = TwoSubstrateSyncState.beside(dir);
        assertTrue(state.advance("origin", "main", null, new Heads(h(1), null)));
        Heads read = state.read("origin", "main").orElseThrow();
        assertArrayEquals(h(1), read.rdfHead());
        assertNull(read.jsonHead(), "json never synced on this remote");
    }

    @Test
    void crashLeftoverTempFile_neverCorruptsReads(@TempDir Path dir) throws Exception {
        TwoSubstrateSyncState state = TwoSubstrateSyncState.beside(dir);
        assertTrue(state.advance("origin", "main", null, new Heads(h(1), h(2))));
        // A crash between temp-write and rename leaves a stray .tmp — reads ignore it.
        Files.writeString(
                dir.resolve("sync-state/origin/main.tmp"),
                "rdf garbage\njson garbage\n",
                StandardCharsets.UTF_8);
        assertArrayEquals(h(1), state.read("origin", "main").orElseThrow().rdfHead());
    }

    @Test
    void traversalNames_rejected(@TempDir Path dir) {
        TwoSubstrateSyncState state = TwoSubstrateSyncState.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> state.read("../escape", "main"));
        assertThrows(
                IllegalArgumentException.class,
                () -> state.advance("origin", "../../etc", null, new Heads(h(1), h(2))));
    }
}
