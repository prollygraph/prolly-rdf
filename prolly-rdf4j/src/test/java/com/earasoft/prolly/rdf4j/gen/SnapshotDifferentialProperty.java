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
package com.earasoft.prolly.rdf4j.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.gen.OpStreamGen.Op;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterTry;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;

/**
 * Phase 1 Step 7 of {@code prolly-rdf4j-test-strategy.md} — <b>differential under versioning</b>
 * (S-6 / S-2). A generated sequence of commit batches is applied to a live {@code ProllySail} and
 * an RDF4J {@code MemoryStore} in lockstep; after each commit we record the prolly commit hash
 * <i>and</i> the MemoryStore's state (the "replay then query" oracle). Then we <b>time-travel</b>:
 * for each recorded commit, {@link ProllySail#openSnapshotAt} reads that commit from the shared
 * store, and its statement set must equal the MemoryStore state captured at that point. Pins that a
 * read at commit C is deterministic + equals replaying history to C — the headline versioning
 * guarantee. jqwik shrinks any mismatch.
 *
 * <p>Lexically-stable terms only (same scoping as Step 5/6; typed-literal fidelity is S-3).
 */
class SnapshotDifferentialProperty {

    private final List<Path> tempDirs = new ArrayList<>();

    @Property(tries = 50)
    void snapshotReadEqualsReplay(@ForAll @From("commitBatches") List<List<Op>> batches)
            throws IOException {
        Path dir = Files.createTempDirectory("snap-diff-");
        tempDirs.add(dir);

        InMemoryNodeStore store = new InMemoryNodeStore();
        HeapBufferPool pool = new HeapBufferPool();
        ProllySail live =
                new ProllySail(
                        store,
                        pool,
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        Repository liveRepo = new SailRepository(live);
        Repository mem = new SailRepository(new MemoryStore());
        liveRepo.init();
        mem.init();

        List<byte[]> hashes = new ArrayList<>();
        List<Set<String>> expected = new ArrayList<>();

        try (RepositoryConnection lc = liveRepo.getConnection();
                RepositoryConnection mc = mem.getConnection()) {
            for (List<Op> batch : batches) {
                lc.begin();
                mc.begin();
                for (Op op : batch) {
                    if (op.kind() == Op.Kind.ADD) {
                        lc.add(op.statement());
                        mc.add(op.statement());
                    } else {
                        lc.remove(op.statement());
                        mc.remove(op.statement());
                    }
                }
                lc.commit();
                mc.commit();
                byte[] h = live.currentCommitHash();
                if (h != null) {
                    hashes.add(h.clone());
                    expected.add(keys(mc));
                }
            }
        }

        // Time-travel: each recorded commit, read via openSnapshotAt, must equal
        // the MemoryStore state captured right after that commit.
        for (int i = 0; i < hashes.size(); i++) {
            ProllySail snap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new io.micrometer.core.instrument.composite.CompositeMeterRegistry(),
                            hashes.get(i));
            Repository snapRepo = new SailRepository(snap);
            snapRepo.init();
            try (RepositoryConnection sc = snapRepo.getConnection()) {
                final int idx = i;
                assertEquals(
                        expected.get(i),
                        keys(sc),
                        () -> "snapshot read at commit #" + idx + " != replayed MemoryStore state");
            } finally {
                snapRepo.shutDown();
            }
        }
        liveRepo.shutDown();
        mem.shutDown();
    }

    private static Set<String> keys(RepositoryConnection c) {
        Set<String> out = new HashSet<>();
        try (var it = c.getStatements(null, null, null, false)) {
            while (it.hasNext()) out.add(key(it.next()));
        }
        return out;
    }

    private static String key(Statement s) {
        return term(s.getSubject())
                + '|'
                + term(s.getPredicate())
                + '|'
                + term(s.getObject())
                + '|'
                + (s.getContext() == null ? "" : term(s.getContext()));
    }

    private static String term(Value v) {
        if (v.isIRI()) return "I:" + v.stringValue();
        if (v.isBNode()) return "B:" + ((BNode) v).getID();
        if (v instanceof org.eclipse.rdf4j.model.Triple t) {
            // RDF-star joined the differential generators (round 3): canonicalize
            // a quoted triple by its components, recursively.
            return "T:<<"
                    + term(t.getSubject())
                    + '|'
                    + term(t.getPredicate())
                    + '|'
                    + term(t.getObject())
                    + ">>";
        }
        Literal l = (Literal) v;
        return "L:"
                + l.getLabel()
                + "^^"
                + l.getDatatype().stringValue()
                + l.getLanguage().map(x -> "@" + x).orElse("");
    }

    @Provide
    Arbitrary<List<List<Op>>> commitBatches() {
        Arbitrary<Op> add = QuadGen.differentialStatements().map(s -> new Op(Op.Kind.ADD, s, null));
        Arbitrary<Op> remove =
                QuadGen.differentialStatements().map(s -> new Op(Op.Kind.REMOVE, s, null));
        Arbitrary<Op> mut = Arbitraries.frequencyOf(Tuple.of(4, add), Tuple.of(1, remove));
        Arbitrary<List<Op>> batch = mut.list().ofMinSize(0).ofMaxSize(6);
        return batch.list().ofMinSize(1).ofMaxSize(8);
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
        tempDirs.clear();
    }
}
