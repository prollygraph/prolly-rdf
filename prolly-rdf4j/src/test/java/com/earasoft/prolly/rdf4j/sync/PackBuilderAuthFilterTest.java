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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncPack;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 0 Step 3 of plans/auth-graph-syncpack-filter.md — pins the new 5-arg {@code
 * PackBuilder.build} overload's contract:
 *
 * <ul>
 *   <li>Empty / null filter set ⇒ identical pack to the 4-arg legacy overload (regression-safety
 *       for the no-BC code path that ships when {@code auth.backend=rocksdb}).
 *   <li>Non-empty filter set with TermIds that match no rows ⇒ still identical pack (defensive —
 *       operator passing wrong IRIs doesn't break sync).
 *   <li>Filter set unioned with the receiver's {@code have} set ⇒ chunks already on the receiver
 *       still pruned, just as in the 4-arg legacy path.
 * </ul>
 *
 * <p>Post-ADR-0071 the {@code want}/{@code have} arguments {@link PackBuilder#build} takes are
 * commit <b>ids</b> (the graph handle {@link CommitClosure} walks by), not the RootMetaTree (tree)
 * hash — so every head handed to the builder is {@link ProllySail#currentCommitId()}, not {@code
 * currentCommitHash()}. The filtering contract under test is unchanged.
 *
 * <p>End-to-end auth-graph filtering (with real IRI → TermId resolution and an actual auth-graph
 * row dropped) is out of scope for this Phase 0 test — it lives in Phase 1's full sync round-trip
 * spec, once the IRI-resolver adapter is wired up.
 */
class PackBuilderAuthFilterTest {

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

    private static void seedOneTriple(ProllySail sail) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s"), vf.createIRI("urn:p"), vf.createIRI("urn:o"));
            conn.commit();
        }
    }

    @Test
    void empty_filter_set_matches_legacy_4arg_pack(@TempDir Path dir) throws IOException {
        ProllySail sail = initedSail(dir);
        seedOneTriple(sail);
        byte[] head = sail.currentCommitId();
        CommitLog log = sail.commitLog().orElseThrow();

        SyncPack legacy = PackBuilder.build(sail.store(), log, head, List.of());
        SyncPack filtered = PackBuilder.build(sail.store(), log, head, List.of(), Set.of());

        assertEquals(legacy.commits().size(), filtered.commits().size(), "commit count matches");
        assertEquals(legacy.chunks().size(), filtered.chunks().size(), "chunk count matches");
    }

    @Test
    void null_filter_set_treated_as_empty(@TempDir Path dir) throws IOException {
        ProllySail sail = initedSail(dir);
        seedOneTriple(sail);
        byte[] head = sail.currentCommitId();
        CommitLog log = sail.commitLog().orElseThrow();

        SyncPack legacy = PackBuilder.build(sail.store(), log, head, List.of());
        SyncPack nullFiltered = PackBuilder.build(sail.store(), log, head, List.of(), null);

        assertEquals(
                legacy.chunks().size(),
                nullFiltered.chunks().size(),
                "null filter set MUST equal empty filter set MUST equal legacy");
    }

    @Test
    void filter_with_unmatched_termids_matches_legacy_pack(@TempDir Path dir) throws IOException {
        // TermIds that match no row in CSPO drop nothing. The pack
        // must be byte-identical to the legacy 4-arg path.
        ProllySail sail = initedSail(dir);
        seedOneTriple(sail);
        byte[] head = sail.currentCommitId();
        CommitLog log = sail.commitLog().orElseThrow();

        SyncPack legacy = PackBuilder.build(sail.store(), log, head, List.of());
        SyncPack filtered =
                PackBuilder.build(
                        sail.store(),
                        log,
                        head,
                        List.of(),
                        Set.of(0xDEADBEEFL, 0xCAFEBABEL)); // not in the dict

        assertEquals(
                legacy.chunks().size(),
                filtered.chunks().size(),
                "unknown filter TermIds must not drop chunks");
    }

    @Test
    void filter_and_have_set_compose_correctly(@TempDir Path dir) throws IOException {
        // Commit twice, then build with the first commit in `have`.
        // Filter is empty. Verify the pack contains only the delta —
        // confirms the filter overload still honors the legacy
        // `have`-prunes-already-seen contract.
        ProllySail sail = initedSail(dir);
        // Commit 1
        seedOneTriple(sail);
        byte[] first = sail.currentCommitId();
        // Commit 2 — a different triple
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:s2"), vf.createIRI("urn:p2"), vf.createIRI("urn:o2"));
            conn.commit();
        }
        byte[] second = sail.currentCommitId();
        CommitLog log = sail.commitLog().orElseThrow();

        SyncPack legacy = PackBuilder.build(sail.store(), log, second, List.of(first));
        SyncPack filtered = PackBuilder.build(sail.store(), log, second, List.of(first), Set.of());

        assertNotNull(legacy);
        assertEquals(
                legacy.commits().size(),
                filtered.commits().size(),
                "delta commits match between legacy and filter overload");
        // The two packs SHOULD have identical chunks since filter is
        // empty. If they differ, the overload's logic broke `have`.
        assertEquals(
                legacy.chunks().size(),
                filtered.chunks().size(),
                "chunk delta matches legacy when filter is empty");
    }

    @Test
    void empty_pack_when_want_equals_have(@TempDir Path dir) throws IOException {
        // want == have edge case — both legacy and filter overload
        // return empty commit lists.
        ProllySail sail = initedSail(dir);
        seedOneTriple(sail);
        byte[] head = sail.currentCommitId();
        CommitLog log = sail.commitLog().orElseThrow();

        SyncPack filtered =
                PackBuilder.build(sail.store(), log, head, List.of(head), Set.of(1L, 2L));

        assertTrue(filtered.commits().isEmpty(), "want==have: no delta commits");
    }
}
