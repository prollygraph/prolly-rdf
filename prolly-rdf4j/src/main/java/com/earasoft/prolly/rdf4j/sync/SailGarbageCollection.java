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

import com.earasoft.prolly.GcResult;
import com.earasoft.prolly.rdf4j.sail.ProllySail;

/**
 * The safe front door for collecting a Sail-backed store (ADR-0074 productionization): composes the
 * Sail's OWN {@link SailGcReachability} from its commit log and runs the collection under the
 * Sail's single-writer lock via {@link ProllySail#collectGarbage}.
 *
 * @apiNote What this reclaims: ORPHANS (aborted/abandoned sync stages, crash residue). The
 *     contributor claims every commit's full closure — history is live by definition (the
 *     time-travel surfaces) — so this is not history compaction. Reads keep flowing during a
 *     collection; writes queue behind it.
 * @implNote Refuses (rather than under-claims) when the Sail has no commit log — a log-less Sail
 *     cannot enumerate its live closure, and collecting anyway would sweep it.
 */
public final class SailGarbageCollection {

    private SailGarbageCollection() {}

    public static GcResult collect(ProllySail sail) {
        var log =
                sail.commitLog()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "this Sail has no commit log — its live closure"
                                                        + " cannot be enumerated; refusing to collect"));
        return sail.collectGarbage(new SailGcReachability(log));
    }
}
