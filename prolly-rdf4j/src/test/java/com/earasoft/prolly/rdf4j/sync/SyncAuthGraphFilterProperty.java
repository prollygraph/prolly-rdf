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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.rdf4j.term.Layouts;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Phase 7 Step 25 of {@code prolly-rdf4j-test-strategy.md} (S-9 / S-10 touchpoint), sub-property 3
 * of 3 — {@code ChunkGraphFilter} correctness as a property: excluding a graph's context drops
 * <b>exactly</b> its auth-only CSPO leaves and <b>nothing else</b>. Generalizes the example-based
 * {@code ChunkGraphFilterTest} (which only covers the no-drop directions on a one-triple repo) to
 * the <i>drop</i> direction over generated repo sizes.
 *
 * <p><b>Construction.</b> All {@code n} triples are committed into a single named graph {@code G},
 * so the CSPO index (the only context-first index, the only one the best-effort filter prunes) is
 * entirely in {@code G} — every CSPO leaf is auth-only. {@code G}'s context TermId is read straight
 * out of a CSPO leaf (column 0 of the 4-column tuple), because the IRI→TermId resolver adapter the
 * {@code PackBuilder} path will use is deferred (per {@code PackBuilderAuthFilterTest}); reading
 * the stored tuple is the resolver-independent way to learn a real context value.
 *
 * <p><b>Asserted.</b> Excluding {@code G}'s context: (1) yields a non-empty auth-only set (the drop
 * direction actually fires — what the examples never reached); (2) {@code filtered == full \
 * authOnly} exactly (it removes precisely those leaves); (3) drops something ({@code filtered ⊊
 * full}); and — the soundness / no-over-drop half — (4) the <b>dict</b> and <b>SPOC</b> roots are
 * <b>retained</b> (the filter touches only CSPO leaves; the shared dictionary and the context-last
 * indexes are never pruned, even when the only graph is excluded). And (5) excluding an absent
 * context drops nothing.
 *
 * <p><b>Deliberately out of scope</b> (the filter is best-effort, see its class note): the
 * <i>mixed-repo</i> soundness — dropping a graph's leaves while keeping a co-resident data graph's
 * leaves in the same repo — needs leaves large enough to separate by graph (a single small leaf
 * straddles graphs and is never auth-only), and the IRI-resolver end-to-end belongs to {@code
 * plans/auth-graph-syncpack-filter.md}, not this test-strategy step.
 */
class SyncAuthGraphFilterProperty {

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

    @Property(tries = 20)
    void excluding_a_pure_graphs_context_drops_only_its_cspo_leaves(
            @ForAll @IntRange(min = 1, max = 10) int n) throws IOException {
        Path dir = Files.createTempDirectory("prolly-authfilter-");
        ProllySail sail = null;
        try {
            sail = initedSail(dir);
            try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
                conn.begin();
                ValueFactory vf = conn.getValueFactory();
                IRI g = vf.createIRI("urn:g:auth");
                for (int i = 0; i < n; i++) {
                    conn.add(
                            vf.createIRI("urn:s" + i),
                            vf.createIRI("urn:p"),
                            vf.createIRI("urn:o" + i),
                            g);
                }
                conn.commit();
            }
            NodeStore store = sail.store();
            byte[] head = sail.currentCommitHash();
            RootMetaTree rmt = RootMetaTree.readFrom(store, head).orElseThrow();

            long gContext = firstCspoLeafContext(store, rmt);
            Set<String> full = ChunkReachability.from(store, head, Set.of());
            Set<String> authOnly = ChunkGraphFilter.authOnlyLeaves(store, head, Set.of(gContext));
            Set<String> filtered =
                    ChunkGraphFilter.chunksReachableExcludingGraphs(store, head, Set.of(gContext));

            // (1) the drop direction fires — a repo entirely in G has at least one auth-only CSPO
            // leaf.
            assertFalse(
                    authOnly.isEmpty(),
                    "n=" + n + ": a repo entirely in graph G must have an auth-only CSPO leaf");
            assertTrue(
                    full.containsAll(authOnly),
                    "the auth-only leaves were in the full reachable set");

            // (2) it removes precisely those leaves, and (3) actually drops something.
            Set<String> expected = new HashSet<>(full);
            expected.removeAll(authOnly);
            assertEquals(
                    expected,
                    filtered,
                    "filtered must equal full minus exactly the auth-only leaves");
            assertTrue(
                    filtered.size() < full.size(),
                    "n=" + n + ": excluding G's context must drop its CSPO leaves");

            // (4) soundness / no-over-drop: dict + SPOC roots are retained (only CSPO leaves are
            // pruned).
            assertTrue(
                    filtered.contains(HashUtils.toHex(rmt.entries().get(RootMetaTree.NAME_DICT))),
                    "the shared dictionary root must never be dropped");
            assertTrue(
                    filtered.contains(HashUtils.toHex(rmt.entries().get(RootMetaTree.NAME_SPOC))),
                    "the SPOC (context-last) index root must never be dropped");

            // (5) no-over-filter: excluding a context that appears in no row drops nothing.
            Set<String> absent =
                    ChunkGraphFilter.chunksReachableExcludingGraphs(
                            store, head, Set.of(gContext + 1_000_000L));
            assertEquals(full, absent, "excluding an absent context must drop nothing");
        } finally {
            if (sail != null) {
                try {
                    sail.shutDown();
                } catch (Exception ignored) {
                    // best-effort teardown
                }
            }
            deleteRecursively(dir);
        }
    }

    /**
     * The context TermId (tuple column 0) of the first CSPO leaf — a resolver-independent way to
     * learn a real context value to exclude. Descends leftmost to the first leaf; the repo has ≥1
     * row.
     */
    private static long firstCspoLeafContext(NodeStore store, RootMetaTree rmt) {
        byte[] cur = rmt.entries().get(RootMetaTree.NAME_CSPO);
        while (true) {
            Node node = Node.fromBytes(store.read(cur).orElseThrow());
            if (node.isLeaf()) {
                MemorySegment key = node.getKeySegment(0);
                return key.get(Layouts.LE64_U, 0);
            }
            cur = node.getValue(0); // leftmost child
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            pth -> {
                                try {
                                    Files.deleteIfExists(pth);
                                } catch (IOException ignored) {
                                    // best-effort temp cleanup
                                }
                            });
        }
    }
}
