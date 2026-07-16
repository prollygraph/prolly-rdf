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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Coverage for plan Step 19 — the two end-state guarantees of a fetch:
 *
 * <ol>
 *   <li>The remote's <i>original commit timestamps</i> are what land in the local {@link CommitLog}
 *       — not the local clock at fetch time. This is the property that makes {@code
 *       /sparql/commits} and the provenance log agree across peers.
 *   <li>The {@code remotes/<remoteName>/<branch>} ref namespace is populated by {@link
 *       RepoSync#fetch fetch} and observable via {@code RefsStore.list} and the {@link
 *       RepoSync#trackingRefs() trackingRefs} convenience.
 * </ol>
 *
 * <p>The wire path is exercised in-process (no HTTP) — the on-disk round trip for timestamps is
 * covered separately by {@code CommitLogSyncTest}; this test pins the <i>fetch-side semantics</i> a
 * downstream caller relies on.
 *
 * <p>Post-ADR-0071 a remote advertises (and a tracking ref stores) the head <b>commit id</b>, not
 * the head's tree hash. So the expected tracking-ref value is {@link ProllySail#currentCommitId()},
 * which is what {@code refs/main} on the remote holds and what {@code advertiseRefs} returns.
 */
class RemoteTrackingAndTimestampsTest {

    private static ProllySail initedSail(Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        new SailRepository(sail).init();
        return sail;
    }

    private static void commitTriple(ProllySail sail, String s, String p, String o) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:" + s), vf.createIRI("urn:" + p), vf.createIRI("urn:" + o));
            conn.commit();
        }
    }

    @Test
    void fetched_commits_carry_the_remotes_original_timestamps(
            @TempDir Path aDir, @TempDir Path bDir) throws IOException, InterruptedException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        commitTriple(a, "y", "p", "2");

        // The remote's recorded timestamps — captured *before* the fetch, so we
        // can prove what lands locally is the remote's record, not "now".
        var remoteTimes =
                a.commitLog().orElseThrow().entries().stream()
                        .map(CommitLog.Entry::timestamp)
                        .toList();
        assertEquals(2, remoteTimes.size());

        // Sleep enough that "now" diverges visibly from the remote's record —
        // millisecond resolution is enough to detect a clock-substitution bug.
        Thread.sleep(20);

        ProllySail b = initedSail(bDir);
        Instant beforeFetch = Instant.now();
        new RepoSync(b).fetch(new InProcessRemoteRepository(a), "origin", "main");
        Instant afterFetch = Instant.now();

        var localTimes =
                b.commitLog().orElseThrow().entries().stream()
                        .map(CommitLog.Entry::timestamp)
                        .toList();

        // Same count, same instants, same order — the remote's record made it
        // across verbatim. (And — critically — none of them are in the
        // [beforeFetch, afterFetch] window, which is what a "local clock at
        // fetch time" implementation would produce.)
        assertEquals(
                remoteTimes,
                localTimes,
                "the fetched CommitLog entries carry the remote's original timestamps");
        for (Instant t : localTimes) {
            assertTrue(
                    t.isBefore(beforeFetch),
                    "every synced timestamp predates the fetch — proving the local clock didn't overwrite it: "
                            + t
                            + " vs window ["
                            + beforeFetch
                            + ", "
                            + afterFetch
                            + "]");
        }
    }

    @Test
    void fetch_populates_the_remote_tracking_ref_namespace(@TempDir Path aDir, @TempDir Path bDir)
            throws IOException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        // The remote advertises — and the tracking ref stores — the head COMMIT ID (ADR-0071),
        // the same value refs/main holds on the remote.
        byte[] headA = a.currentCommitId();

        ProllySail b = initedSail(bDir);
        RepoSync sync = new RepoSync(b);
        sync.fetch(new InProcessRemoteRepository(a), "origin", "main");

        // The raw ref lives under the conventional namespace.
        RefsStore refs = b.refsStore().orElseThrow();
        assertArrayEquals(
                headA,
                refs.get("remotes/origin/main").orElseThrow(),
                "the remote-tracking ref points at the fetched remote head");

        // RefsStore.list shows it alongside local branches.
        Map<String, byte[]> all = refs.list();
        assertTrue(
                all.containsKey("remotes/origin/main"),
                "the tracking ref appears in RefsStore.list(): " + all.keySet());

        // RepoSync's trackingRefs() filters and strips the prefix.
        Map<String, byte[]> tracking = sync.trackingRefs();
        assertEquals(1, tracking.size());
        assertArrayEquals(headA, tracking.get("origin/main"));

        // …and the per-remote variant keys by branch only.
        Map<String, byte[]> originRefs = sync.trackingRefs("origin");
        assertEquals(1, originRefs.size());
        assertArrayEquals(headA, originRefs.get("main"));

        // An unknown remote yields an empty map, not an error — symmetric with
        // remoteList semantics.
        assertTrue(
                sync.trackingRefs("nope").isEmpty(),
                "an unknown remote yields an empty tracking-refs map");
    }

    @Test
    void multiple_remotes_keep_their_tracking_refs_separate(
            @TempDir Path aDir, @TempDir Path cDir, @TempDir Path bDir) throws IOException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        // Tracking refs carry head COMMIT IDS (ADR-0071), not tree hashes.
        byte[] headA = a.currentCommitId();

        ProllySail c = initedSail(cDir);
        commitTriple(c, "y", "p", "2");
        byte[] headC = c.currentCommitId();

        ProllySail b = initedSail(bDir);
        RepoSync sync = new RepoSync(b);
        sync.fetch(new InProcessRemoteRepository(a), "origin", "main");
        sync.fetch(new InProcessRemoteRepository(c), "upstream", "main");

        Map<String, byte[]> all = sync.trackingRefs();
        assertEquals(2, all.size(), "both remotes are tracked: " + all.keySet());
        assertArrayEquals(headA, all.get("origin/main"));
        assertArrayEquals(headC, all.get("upstream/main"));

        // Per-remote views isolate cleanly.
        assertNotNull(sync.trackingRefs("origin").get("main"));
        assertEquals(1, sync.trackingRefs("origin").size());
        assertEquals(1, sync.trackingRefs("upstream").size());
    }
}
