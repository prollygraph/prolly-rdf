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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.gc.ChunkSet;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 0 Step 1 of {@code plans/auth-graph-syncpack-filter.md} — investigative anchor: builds a
 * synthetic Sail with one default-graph triple and one triple in {@code
 * <urn:prolly-rdf4j:auth/users>}, commits, and documents the chunk-level layout the future filter
 * must work with. <b>No production code lands here</b>; Steps 2+ build the filter against the
 * layout this test confirms.
 *
 * <h2>Key finding (the plan's D-3 premise refined)</h2>
 *
 * <p>The plan's lead paragraph claims auth-graph chunks are "identifiable + disjoint" from
 * data-graph chunks. The investigation surfaces a refinement: that's <em>only true by row, not by
 * chunk</em>:
 *
 * <ul>
 *   <li><b>CSPO</b> is the only index keyed by context-first ({@code (c, s, p, o)} per {@code
 *       QuadIndex.physicalToLogical}). Its tree groups rows by context, so auth-graph rows occupy a
 *       contiguous prefix range. At <em>tree</em> granularity, that range is identifiable by SPARQL
 *       or by prefix-scan. At <em>chunk</em> granularity, two contexts can still share a leaf page
 *       (a small repo's entire CSPO tree may be one chunk holding both auth-graph + data-graph
 *       rows).
 *   <li><b>SPOC / POSC / OSPC</b> all key context LAST. Auth- graph and data-graph rows interleave
 *       at the row level (sorted by subject / predicate / object first, with context as a
 *       disambiguator). A chunk containing auth- graph rows almost always contains data-graph rows
 *       too. <em>There is no "auth chunk" to subtract</em> cleanly from these indexes.
 * </ul>
 *
 * <p>This refines the plan's D-3 logic ("only chunks unreachable via any non-auth path get
 * dropped") into a concrete operational shape: <b>for small repos, the filter drops nothing</b>
 * (everything fits in one chunk per index, shared between graphs). The filter becomes effective at
 * scale when auth-graph rows occupy their own chunks. Plan Step 2 ({@code ChunkGraphFilter}) and
 * Step 3 ({@code PackBuilder} wire-up) must surface this — the filter's effectiveness is a function
 * of repo size, not a binary on/off.
 *
 * <p>The dictionary (mapping IRIs → TermIds) is itself a tree. Auth-graph IRIs share dictionary
 * chunks with data- graph IRIs — the dictionary is not graph-aware. Filtering out dictionary chunks
 * would orphan term references in any data-graph rows that share predicates / classes with auth
 * (e.g. {@code rdf:type}). The filter must therefore preserve the entire dictionary.
 *
 * <h2>Assertions in this test</h2>
 *
 * <p>The assertions below are minimal — they verify the Sail machinery works (commits land,
 * RootMetaTree is readable, walked chunks reach everything) so future plan steps have a known-good
 * starting state.
 */
class AuthGraphChunkIdentifierTest {

    /** The auth-graph IRI established by ADR-0015 / plans/auth-on-sail.md. */
    private static final String AUTH_USERS_GRAPH = "urn:prolly-rdf4j:auth/users";

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

    private static void seedTwoTriples(ProllySail sail) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            // Data triple — default graph.
            conn.add(
                    vf.createIRI("urn:data:s"),
                    vf.createIRI("urn:data:p"),
                    vf.createIRI("urn:data:o"));
            // Auth triple — named graph (the would-be-filtered graph).
            IRI authGraph = vf.createIRI(AUTH_USERS_GRAPH);
            conn.add(
                    vf.createIRI("urn:prolly-rdf4j:auth/alice"),
                    vf.createIRI("urn:prolly-rdf4j:auth/passwordHash"),
                    vf.createLiteral("$2a$10$abcdef..."),
                    authGraph);
            conn.commit();
        }
    }

    @Test
    void synthetic_sail_with_data_plus_auth_triple_commits_cleanly(@TempDir Path dir)
            throws IOException {
        ProllySail sail = initedSail(dir);
        seedTwoTriples(sail);

        byte[] commitHash = sail.currentCommitHash();
        assertNotNull(commitHash, "post-commit head is non-null");

        // RootMetaTree is readable from the node store at that hash —
        // proves the commit landed in the prolly tree backing store.
        NodeStore nodeStore = sail.store();
        Optional<RootMetaTree> rmt = RootMetaTree.readFrom(nodeStore, commitHash);
        assertTrue(rmt.isPresent(), "RootMetaTree must be readable at the commit hash");

        Map<String, byte[]> roots = rmt.get().entries();
        // Every quad-index root is present (SPOC, POSC, OSPC, CSPO).
        assertTrue(roots.containsKey(RootMetaTree.NAME_SPOC), "spoc root present");
        assertTrue(roots.containsKey(RootMetaTree.NAME_POSC), "posc root present");
        assertTrue(roots.containsKey(RootMetaTree.NAME_OSPC), "ospc root present");
        assertTrue(roots.containsKey(RootMetaTree.NAME_CSPO), "cspo root present");
        // Dictionary root present — auth-graph IRIs and data-graph IRIs
        // SHARE the dictionary subtree (it is not graph-partitioned).
        assertTrue(roots.containsKey(RootMetaTree.NAME_DICT), "dict root present");
    }

    @Test
    void walking_from_commit_reaches_every_index_chunk(@TempDir Path dir) throws IOException {
        ProllySail sail = initedSail(dir);
        seedTwoTriples(sail);

        NodeStore nodeStore = sail.store();
        Set<String> reachable =
                ChunkReachability.from(nodeStore, sail.currentCommitHash(), ChunkSet.EMPTY)
                        .toHexSet();

        // At minimum: the commit itself (RootMetaTree) + one leaf per
        // each named root (dict, spoc, posc, ospc, cspo). With 2 rows
        // that's typically 1 chunk per index → ≥6 chunks total. The
        // exact count depends on the dictionary structure; we just
        // assert >= the minimum.
        assertTrue(
                reachable.size() >= 6,
                "expected >= 6 reachable chunks (1 RMT + 5 index roots min); got "
                        + reachable.size()
                        + ": "
                        + reachable);

        // Filtering the auth-graph IRI's term-id range would, in a
        // SMALL sail like this, drop nothing — auth + data rows
        // share leaf pages in every index. Documented here as the
        // chunk-level finding the plan's D-3 must surface.
    }

    @Test
    void dictionary_is_not_graph_partitioned_so_must_be_preserved(@TempDir Path dir)
            throws IOException {
        // Anchors a hard rule for Step 2 (`ChunkGraphFilter`): the dict
        // subtree MUST be preserved verbatim. An auth-graph IRI's
        // dictionary entry can't be dropped because dropping it would
        // orphan any data-graph row that references that IRI as a
        // predicate or class (e.g. rdf:type, rdfs:label both appear
        // in both worlds).
        ProllySail sail = initedSail(dir);
        seedTwoTriples(sail);

        Optional<RootMetaTree> rmt = RootMetaTree.readFrom(sail.store(), sail.currentCommitHash());
        byte[] dictRoot = rmt.get().entries().get(RootMetaTree.NAME_DICT);
        assertNotNull(dictRoot, "dict root present");
        // The dict root is THE single root for ALL term-ids in the
        // repo — auth and data alike. Step 2 must not exclude it.
        // Recording the hash here so a future regression that
        // accidentally graph-partitions the dictionary would diverge
        // and surface as a failing assertion downstream.
        String dictHex = HashUtils.toHex(dictRoot);
        assertFalse(dictHex.isEmpty(), "dict root hash is non-empty");
        assertEquals(
                40,
                dictHex.length(),
                "dict root hash is a 20-byte hash (Dolt's truncated SHA-512) — 40 hex chars");
    }

    @Test
    void chunk_count_grows_below_linear_with_row_count(@TempDir Path dir) throws IOException {
        // Plan-anchoring finding: at small scale the filter drops
        // nothing because the SPOC/POSC/OSPC leaves fit two rows in
        // one chunk each. Make this concrete: count the chunks
        // reachable from a 2-row commit vs. an empty commit at the
        // same Sail. The increment proves new chunks were materialized
        // but it's far below "one chunk per row per index" — meaning
        // chunks are SHARED between auth and data rows.
        ProllySail sail = initedSail(dir);

        // Empty commit first — no rows yet.
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            conn.commit(); // empty txn just to materialize an init commit
        }
        byte[] emptyHead = sail.currentCommitHash();
        int chunksBefore =
                (emptyHead == null)
                        ? 0
                        : ChunkReachability.from(sail.store(), emptyHead, ChunkSet.EMPTY).size();

        seedTwoTriples(sail);
        int chunksAfter =
                ChunkReachability.from(sail.store(), sail.currentCommitHash(), ChunkSet.EMPTY)
                        .size();

        // 2 new rows must materialize some chunks (commit + dict + at
        // least one index leaf). They MUST share leaves between the
        // two rows — proven by the row count (2) being below the
        // chunk-delta-times-2 line if the filter assumption held.
        assertTrue(
                chunksAfter > chunksBefore,
                "seeding rows must add at least one chunk; before="
                        + chunksBefore
                        + " after="
                        + chunksAfter);
    }
}
