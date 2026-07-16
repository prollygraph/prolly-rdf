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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;

/**
 * Step 2 of {@code plans/streaming-commit-diff.md} — the differential gate that pins {@link
 * CommitDiffStream} behaviour-identical to the buffer-and-diff path it replaces.
 *
 * <p><b>The oracle.</b> {@code SparqlController.provenanceByCommit} computes a commit's
 * INSERT/DELETE events by reading <em>both</em> the commit's and its first parent's full snapshots
 * into heap maps (via the SAIL — {@code openSnapshotAt} + {@code getStatements}) and diffing them
 * by membership. {@link #bufferAndDiff} replicates exactly that logic; {@link #streamed} runs the
 * new streaming primitive. The property asserts the two yield the <b>same event set</b> for
 * <b>every</b> commit in a randomly-generated history.
 *
 * <p><b>Why this is the safety net.</b> The streaming decode reaches into subtle versioned-read
 * internals — two dictionaries (INSERT→here, DELETE→parent), the {@code TermId.ZERO} default-graph
 * sentinel, and the literal datatype/language decode. The {@link #term} key is deliberately
 * sensitive to a literal's datatype and language tag, so a decode that silently drops either fails
 * here rather than shipping a wrong wire shape. Random adds/removes over a small, overlapping id +
 * graph + object-type space drive genesis, pure-insert, pure-delete, mixed, named-graph, and
 * literal-object commits.
 */
class CommitDiffStreamDifferentialProperty {

    private Path dir;

    @AfterTry
    void cleanup() {
        if (dir == null) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignore) {
                                    // best-effort temp cleanup
                                }
                            });
        } catch (IOException ignore) {
            // best-effort temp cleanup
        }
        dir = null;
    }

    @Property(tries = 100)
    void streamingEqualsBufferAndDiff(@ForAll("scenarios") List<List<int[]>> scenario)
            throws IOException {
        dir = Files.createTempDirectory("commit-diff-prop-");
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();
        try {
            for (List<int[]> commit : scenario) {
                try (RepositoryConnection c = repo.getConnection()) {
                    c.begin();
                    for (int[] op : commit) apply(c, op);
                    c.commit();
                }
            }
            List<CommitLog.Entry> entries = sail.commitLog().orElseThrow().entries();
            for (CommitLog.Entry e : entries) {
                // ADR-0071: a commit's identity is its commit id, and parents() hold parent commit
                // ids (not tree hashes). CommitDiffStream.stream takes commit ids (it resolves each
                // to a tree hash internally via treeHashOf), so drive both paths with commit ids.
                byte[] here = e.id();
                byte[] parent = e.parents().isEmpty() ? null : e.parents().get(0);
                Diff oracle = bufferAndDiff(sail, here, parent);
                Diff streamed = streamed(sail, here, parent);
                String at = " for commit " + HashUtils.toHex(here);
                // Ordered (not just set) equality — pins D-2: the endpoints emit INSERTs then
                // DELETEs, each in scan order, and the streaming walk must reproduce that sequence
                // byte-for-byte, not merely the same membership.
                assertEquals(oracle.inserts(), streamed.inserts(), "INSERT order must match" + at);
                assertEquals(oracle.deletes(), streamed.deletes(), "DELETE order must match" + at);
            }
        } finally {
            repo.shutDown();
        }
    }

    @Provide
    Arbitrary<List<List<int[]>>> scenarios() {
        Arbitrary<int[]> op =
                Combinators.combine(
                                Arbitraries.of(0, 1), // 0 = ADD, 1 = DEL
                                Arbitraries.integers().between(0, 6), // subject/object id
                                Arbitraries.integers()
                                        .between(0, 2), // graph: 0 = default, 1/2 named
                                Arbitraries.integers()
                                        .between(0, 3)) // object: 0 IRI, 1 plain, 2 lang, 3 typed
                        .as((k, s, g, ot) -> new int[] {k, s, g, ot});
        return op.list().ofMaxSize(5).list().ofMinSize(1).ofMaxSize(5);
    }

    // ---- the two computations under comparison -------------------------------

    /** A commit's changes split into the two ordered groups the endpoints serialize. */
    private record Diff(List<String> inserts, List<String> deletes) {}

    /** The streaming primitive under test, grouped INSERT/DELETE in emission order. */
    private static Diff streamed(ProllySail live, byte[] here, byte[] parent) {
        List<String> ins = new ArrayList<>();
        List<String> del = new ArrayList<>();
        new CommitDiffStream(live)
                .stream(
                        here,
                        parent,
                        e -> {
                            if (e.kind() == CommitDiffStream.Kind.INSERT)
                                ins.add(key(e.statement()));
                            else del.add(key(e.statement()));
                        });
        return new Diff(ins, del);
    }

    /**
     * The oracle — exactly {@code provenanceByCommit}'s read-both-snapshots membership diff. {@code
     * here}/{@code parent} are commit ids (ADR-0071); {@link #readAll} resolves each to its tree
     * hash before opening the snapshot.
     */
    private static Diff bufferAndDiff(ProllySail live, byte[] here, byte[] parent) {
        Map<String, Statement> h = readAll(live, here);
        Map<String, Statement> p = parent == null ? Map.of() : readAll(live, parent);
        List<String> ins = new ArrayList<>();
        List<String> del = new ArrayList<>();
        for (var en : h.entrySet()) if (!p.containsKey(en.getKey())) ins.add(en.getKey());
        for (var en : p.entrySet()) if (!h.containsKey(en.getKey())) del.add(en.getKey());
        return new Diff(ins, del);
    }

    /**
     * Buffer a whole snapshot into a {@code tripleKey -> Statement} map, via the SAIL (the oracle).
     * {@code commitId} is a commit id (ADR-0071); resolve it to the RootMetaTree (tree) hash that
     * {@code openSnapshotAt} reads from.
     */
    private static Map<String, Statement> readAll(ProllySail live, byte[] commitId) {
        ProllySail snap =
                ProllySail.openSnapshotAt(
                        live.store(),
                        live.pool(),
                        new CompositeMeterRegistry(),
                        live.treeHashOf(commitId));
        SailRepository repo = new SailRepository(snap);
        repo.init();
        try (RepositoryConnection c = repo.getConnection();
                var it = c.getStatements(null, null, null, false)) {
            Map<String, Statement> out = new LinkedHashMap<>();
            while (it.hasNext()) {
                Statement st = it.next();
                out.put(key(st), st);
            }
            return out;
        } finally {
            repo.shutDown();
        }
    }

    // ---- canonical triple key (datatype/language-sensitive) ------------------

    private static String key(Statement st) {
        return term(st.getSubject())
                + "|"
                + term(st.getPredicate())
                + "|"
                + term(st.getObject())
                + "|"
                + (st.getContext() == null ? "" : term(st.getContext()));
    }

    private static String term(Value v) {
        if (v instanceof Literal lit) {
            String base = "lit:" + lit.getLabel();
            if (lit.getLanguage().isPresent()) return base + "@" + lit.getLanguage().get();
            if (lit.getDatatype() != null) return base + "^^" + lit.getDatatype().stringValue();
            return base;
        }
        return v.stringValue();
    }

    // ---- scenario application ------------------------------------------------

    private static void apply(RepositoryConnection c, int[] op) {
        ValueFactory vf = c.getValueFactory();
        Resource s = vf.createIRI("urn:t:s" + op[1]);
        IRI p = vf.createIRI("urn:t:p");
        Value o = object(vf, op[1], op[3]);
        Resource g = op[2] == 0 ? null : vf.createIRI("urn:t:g" + op[2]);
        boolean add = op[0] == 0;
        if (g == null) {
            if (add) c.add(s, p, o);
            else c.remove(s, p, o);
        } else {
            if (add) c.add(s, p, o, g);
            else c.remove(s, p, o, g);
        }
    }

    private static Value object(ValueFactory vf, int sid, int otype) {
        return switch (otype) {
            case 1 -> vf.createLiteral("val" + sid);
            case 2 -> vf.createLiteral("val" + sid, "en");
            case 3 -> vf.createLiteral(Integer.toString(sid), XSD.INTEGER);
            default -> vf.createIRI("urn:t:o" + sid);
        };
    }
}
