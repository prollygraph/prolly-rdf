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

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.gc.GcReachabilityContributor;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Set;

/**
 * The RDF face's {@link GcReachabilityContributor} (ADR-0074): the Sail keeps its refs in its own
 * {@code RefsStore} and its history in {@code commits.log} — none of it visible to the engine
 * collector's commit-graph walk — so THIS is what makes garbage collection safe on a store the Sail
 * shares. It claims, for every commit in the log: the commit-object chunk (commits are chunks too,
 * ADR-0073) and the full {@link ChunkReachability} closure of the commit's {@code RootMetaTree}
 * (the four SPOC permutation index roots plus the provenance / event-sink / prefix / term-stats /
 * namespace roots, and every tree chunk under them).
 *
 * @apiNote <b>Safety-critical</b> (the ADR-0074 trust class): under-reporting here is deletion of
 *     live Sail data. The positive pin is {@code SailGcReachabilityTest} — a real Sail store is
 *     collected with this contributor registered and every surface must still read. Do not run the
 *     engine collector on a Sail-shared store without this contributor registered — and only with
 *     the Sail QUIESCED: the collector's gcLock coordinates engine-{@code Database} writers only;
 *     Sail writers never hold it, so concurrent Sail commits could be swept mid-flush (ADR-0074's
 *     offline-collection constraint).
 * @implNote Walks EVERY log entry, not just branch heads — the Sail's time-travel surfaces
 *     (snapshot reads, blame, diff) address any historical commit, so every commit's closure is
 *     live by definition. History truncation would be its own decision with its own ADR, not a
 *     garbage-collection side effect.
 */
public final class SailGcReachability implements GcReachabilityContributor {

    private final CommitLog commitLog;

    public SailGcReachability(CommitLog commitLog) {
        this.commitLog = commitLog;
    }

    @Override
    public Set<String> reachableHexes(NodeStore store) {
        Set<String> out = new HashSet<>();
        try {
            for (CommitLog.Entry entry : commitLog.entries()) {
                // The commit object itself is a chunk at the commit id (ADR-0073).
                out.add(HashUtils.toHex(entry.id()));
                // The RootMetaTree + everything under every root it names. Passing the
                // already-claimed set as the exclusion prunes shared subtrees across commits
                // (structural sharing makes this near-incremental per entry).
                out.addAll(ChunkReachability.from(store, entry.metaTreeHash(), out));
            }
        } catch (IOException e) {
            // Failing OPEN (returning a partial set) would let the sweep delete live data —
            // the one unacceptable outcome. Abort the collection instead.
            throw new UncheckedIOException("commits.log unreadable — aborting collection", e);
        }
        return out;
    }
}
