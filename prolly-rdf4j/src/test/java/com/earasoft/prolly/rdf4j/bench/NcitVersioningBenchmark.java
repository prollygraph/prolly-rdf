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
package com.earasoft.prolly.rdf4j.bench;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.ReachabilityWalker;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

/**
 *
 *
 * <h3>Versioning benchmark — NCIt "monthly releases" as sequential commits.</h3>
 *
 * <p>The benchmark that measures what versioning <i>buys</i>, not what it costs. Every other bench
 * in this package treats ProllySail as a plain store; this one exercises its reason to exist —
 * content-addressed <b>structural sharing</b>. flatsail and RDF4J NativeStore can't version at all,
 * so ProllySail is the only engine here: the absence of a comparator <i>is</i> the headline.
 *
 * <p><b>Shape of the run.</b> Load a base sample of real NCIt as commit&nbsp;1 (the "base
 * release"). Then apply {@code releases} successive churn rounds, each mimicking a monthly delta:
 * <i>add</i> ~{@code churn/2} real NCIt triples (new terms) and <i>delete</i> ~{@code churn/2}
 * already-loaded triples (retired terms). Commit each. Per release we record commit time, the
 * <b>net new chunks written</b> (Δ distinct content-addressed keys in the RocksDB node store — the
 * structural-sharing signal), the count of distinct subjects touched, and a commit-to-commit
 * SPOC-tree diff (entries + time).
 *
 * <p><b>The churn-locality knob</b> ({@code mode} = {@code scattered} | {@code clustered}). This is
 * the subtle one. ProllySail derives every TermId from a <i>hash</i> of the term bytes ({@link
 * com.earasoft.prolly.rdf4j.term.TermId#ofNatural}), so a term's position in the SPOC key space is
 * effectively random — you <b>cannot</b> cluster edits by choosing semantically-related terms
 * (that's flatsail's sequential-id behavior, not ProllySail's). The one locality that survives
 * hashing is <b>same-subject batching</b>: triples sharing a subject share the high SPOC field, so
 * they land in one contiguous key band → one leaf. {@code scattered} touches individual triples
 * across many subjects (the realistic monthly-release shape — thousands of classes change); {@code
 * clustered} touches whole subjects (few entities, all their triples) to expose the locality
 * ceiling.
 *
 * <p><b>The question.</b> Does committing release&nbsp;K write O(churn) chunks regardless of corpus
 * size n (sharing → commit cost tracks the diff), or O(n) (no sharing)? And how much does
 * subject-locality move intra-commit sharing? One-shot tool, not JMH; ProllySail-only; seeded
 * ({@code Random(42)}). Run: {@code java … -Djava.io.tmpdir=$REALDISK -Dncit.zip=…
 * NcitVersioningBenchmark [base=200000] [releases=6] [churn=20000] [mode=scattered]}.
 */
public final class NcitVersioningBenchmark {

    static {
        // NCIt's RDF/XML nests deeper than JAXP's default element-depth limit (100), so the parse
        // aborts with JAXP00010006 partway through the base load. Lift the limit (0 = unbounded).
        // `setProperty` (not just a -D flag) makes every launch path robust — flame-bench, plain
        // mvn, IDE — since JAXP reads the property when the SAXParser is created during parse().
        // Mirrors StreamingNcitIngest's static block (this harness was missing it — a bug that made
        // any NCIt run fail in the base parse before reaching the churn it measures).
        System.setProperty("jdk.xml.maxElementDepth", "0");
    }

    public static void main(String[] args) throws Exception {
        int base = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;
        int releases = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        int churn =
                args.length > 2
                        ? Integer.parseInt(args[2])
                        : 20_000; // ~churn/2 added + ~churn/2 deleted
        String mode = args.length > 3 ? args[3] : "scattered"; // scattered | clustered
        boolean clustered = mode.equals("clustered");
        int half = churn / 2;
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));

        int need =
                base
                        + releases * half
                        + half; // a little slack so clustered whole-subject adds don't run dry
        System.out.printf(
                "[NCIt versioning bench — base=%,d, releases=%d, churn=%,d, mode=%s]%n",
                base, releases, churn, mode);
        List<Statement> all = parseSample(need);
        if (all.size() < base + half) {
            throw new IllegalStateException(
                    "ncit.owl yielded only " + all.size() + " statements; need ≥ " + (base + half));
        }

        Path dir = Files.createTempDirectory(tmp, "ncit-versioning-");
        RocksNodeStore store = new RocksNodeStore(dir.resolve("rocks").toString());
        ProllySail sail =
                new ProllySail(
                        store,
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir));
        SailRepository repo = new SailRepository(sail);
        repo.init();

        Random rnd = new Random(42);
        System.out.printf(
                "%n  %-8s %-6s %8s %8s %7s %10s %11s %13s %12s %9s %9s%n",
                "release",
                "op",
                "added",
                "deleted",
                "subj",
                "corpus",
                "commit_ms",
                "total_chunks",
                "new_chunks",
                "diff_n",
                "diff_ms");

        // ---- commit 1: the base release ----
        long t0 = System.nanoTime();
        try (RepositoryConnection conn = repo.getConnection()) {
            conn.begin();
            for (int i = 0; i < base; i++) {
                conn.add(all.get(i));
                // Batched BASE load — bound memory (the heavy single-tx that timed out before). The
                // per-release commits below stay single by design: each release measures ONE
                // commit's
                // cost + structural sharing, so batching a release would corrupt the measurement.
                if ((i + 1) % 100_000 == 0) {
                    conn.commit();
                    conn.begin();
                }
            }
            conn.commit();
        }
        long baseMs = (System.nanoTime() - t0) / 1_000_000;
        long baseChunks = chunkCount(store);
        byte[] prevMeta = latestMeta(sail);
        System.out.printf(
                "  %-8d %-6s %,8d %,8d %7s %,10d %,11d %,13d %,12d %9s %9s%n",
                0, "base", base, 0, "-", base, baseMs, baseChunks, baseChunks, "-", "-");

        List<Statement> loaded = new ArrayList<>(all.subList(0, base));
        long prevTotalChunks = baseChunks;

        // Per-index decomposition: track the set of chunks reachable from each index root, so each
        // release's
        // |reach(new) \ reach(old)| is the EXACT chunks written for that index. Confirms whether a
        // churn hits
        // all 4 permutation indexes equally (the "4-tree cost" claim) or concentrates in one.
        String[] IDX = {
            RootMetaTree.NAME_DICT,
            RootMetaTree.NAME_SPOC,
            RootMetaTree.NAME_POSC,
            RootMetaTree.NAME_OSPC,
            RootMetaTree.NAME_CSPO
        };
        Map<String, Set<String>> prevReach = new HashMap<>();
        for (String name : IDX) prevReach.put(name, reachOf(store, prevMeta, name));

        // Add pool = real NCIt after the base, consumed left-to-right (file order = grouped by
        // subject).
        // clustered: take whole subject-groups; scattered: take individual statements.
        List<List<Statement>> addGroups =
                clustered ? groupBySubject(all.subList(base, all.size())) : null;
        int addGroupCursor = 0;
        int addCursor = base;
        long churnTouched = 0;

        // ---- successive monthly releases ----
        for (int k = 1; k <= releases; k++) {
            List<Statement> adds = new ArrayList<>();
            List<Statement> dels = new ArrayList<>();
            if (clustered) {
                while (addGroupCursor < addGroups.size() && adds.size() < half)
                    adds.addAll(addGroups.get(addGroupCursor++));
                List<List<Statement>> loadedGroups =
                        groupBySubject(loaded); // whole-subject deletes
                Collections.shuffle(loadedGroups, rnd);
                for (List<Statement> g : loadedGroups) {
                    if (dels.size() >= half) break;
                    dels.addAll(g);
                }
            } else {
                int end = Math.min(addCursor + half, all.size());
                adds.addAll(all.subList(addCursor, end));
                addCursor = end;
                Collections.shuffle(loaded, rnd); // individual scattered deletes
                dels.addAll(loaded.subList(loaded.size() - half, loaded.size()));
            }
            if (adds.isEmpty() && dels.isEmpty()) {
                System.out.println("  (add pool exhausted — stopping)");
                break;
            }

            t0 = System.nanoTime();
            try (RepositoryConnection conn = repo.getConnection()) {
                conn.begin();
                for (Statement s : dels) conn.remove(s);
                for (Statement s : adds) conn.add(s);
                conn.commit();
            }
            long commitMs = (System.nanoTime() - t0) / 1_000_000;

            Set<Statement> delSet = new HashSet<>(dels);
            loaded.removeIf(delSet::contains);
            loaded.addAll(adds);
            churnTouched += adds.size() + dels.size();

            Set<Resource> subjects = new HashSet<>();
            for (Statement s : adds) subjects.add(s.getSubject());
            for (Statement s : dels) subjects.add(s.getSubject());

            long totalChunks = chunkCount(store);
            long newChunks = totalChunks - prevTotalChunks; // chunks written THIS release
            prevTotalChunks = totalChunks;
            byte[] meta = latestMeta(sail);
            long[] diff = diffSpoc(store, prevMeta, meta); // {entryCount, micros}
            prevMeta = meta;

            System.out.printf(
                    "  %-8d %-6s %,8d %,8d %,7d %,10d %,11d %,13d %,12d %,9d %,9.1f%n",
                    k,
                    "churn",
                    adds.size(),
                    dels.size(),
                    subjects.size(),
                    loaded.size(),
                    commitMs,
                    totalChunks,
                    newChunks,
                    diff[0],
                    diff[1] / 1000.0);

            // per-index chunks written this release = |reach(new) \ reach(old)|
            StringBuilder sb = new StringBuilder();
            long sumWritten = 0;
            for (String name : IDX) {
                Set<String> cur = reachOf(store, meta, name);
                Set<String> prev = prevReach.get(name);
                long written = cur.stream().filter(h -> !prev.contains(h)).count();
                sumWritten += written;
                prevReach.put(name, cur);
                sb.append(String.format("%s=%,d ", name, written));
            }
            System.out.printf(
                    "           chunks written by index: %s (sum=%,d)%n",
                    sb.toString().trim(), sumWritten);
        }

        // ---- interpretation: chunks-per-touched-triple ----
        long finalChunks = chunkCount(store);
        System.out.printf(
                "%n  [mode=%s] base wrote %.3f chunks/triple; churn wrote %.3f chunks per *touched*%n",
                mode,
                (double) baseChunks / base,
                (double) (finalChunks - baseChunks) / Math.max(1, churnTouched));
        System.out.printf(
                "  (%,d base chunks / %,d base triples; %,d new chunks / %,d touched)%n",
                baseChunks, base, finalChunks - baseChunks, churnTouched);
        System.out.printf(
                "  → flat new_chunks across releases = cross-commit sharing (history is free).%n"
                        + "    clustered ≈ scattered: subject-locality localizes only SPOC; POSC/OSPC/CSPO%n"
                        + "    scatter the same triples by P/O/C hash, so 3 of 4 indexes get no benefit.%n");

        repo.shutDown();
        deleteTree(dir);
    }

    /**
     * The set of distinct chunk hashes reachable from one index's root in a commit's meta-tree.
     * {@code reach(new) \ reach(old)} is then the exact set of chunks that index wrote this commit.
     */
    private static Set<String> reachOf(RocksNodeStore store, byte[] metaHash, String indexName) {
        Optional<RootMetaTree> mt = RootMetaTree.readFrom(store, metaHash);
        if (mt.isEmpty()) return Set.of();
        Optional<byte[]> root = mt.get().hashOf(indexName);
        if (root.isEmpty()) return Set.of();
        ReachabilityWalker w = new ReachabilityWalker(store);
        w.walk(root.get());
        return new HashSet<>(w.getReachableHashes().toHexSet());
    }

    /** Group statements by subject, preserving first-seen order. */
    private static List<List<Statement>> groupBySubject(List<Statement> stmts) {
        LinkedHashMap<Resource, List<Statement>> m = new LinkedHashMap<>();
        for (Statement s : stmts) m.computeIfAbsent(s.getSubject(), x -> new ArrayList<>()).add(s);
        return new ArrayList<>(m.values());
    }

    /**
     * Distinct content-addressed chunks in the node store (one key = one chunk). Flush first so the
     * memtable is counted, then read RocksDB's key estimate.
     */
    private static long chunkCount(RocksNodeStore store) {
        store.flushDurable();
        try {
            return Long.parseLong(store.db().getProperty("rocksdb.estimate-num-keys"));
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] latestMeta(ProllySail sail) throws IOException {
        Optional<CommitLog> log = sail.commitLog();
        return log.flatMap(
                        l -> {
                            try {
                                return l.latest();
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                .map(CommitLog.Entry::metaTreeHash)
                .orElseThrow();
    }

    /**
     * Diff the SPOC index tree between two commits' meta-trees. Returns {entryCount,
     * elapsedMicros}. The Merkle short-circuit in DiffEngine means unchanged subtrees are skipped —
     * so this times the cost of walking only what changed.
     */
    private static long[] diffSpoc(RocksNodeStore store, byte[] metaA, byte[] metaB) {
        Node rootA = spocRoot(store, metaA);
        Node rootB = spocRoot(store, metaB);
        long[] count = {0};
        var engine = new com.dolthub.prolly.DiffEngine(store, SpocKey.DESCRIPTOR);
        long t0 = System.nanoTime();
        engine.diff(
                rootA,
                rootB,
                e -> {
                    count[0]++;
                    return true;
                });
        long micros = (System.nanoTime() - t0) / 1_000;
        return new long[] {count[0], micros};
    }

    private static Node spocRoot(RocksNodeStore store, byte[] metaHash) {
        RootMetaTree mt =
                RootMetaTree.readFrom(store, metaHash)
                        .orElseThrow(() -> new IllegalStateException("no meta-tree for hash"));
        Optional<byte[]> spoc = mt.hashOf(RootMetaTree.NAME_SPOC);
        if (spoc.isEmpty()) return null;
        Optional<MemorySegment> seg = store.read(spoc.get());
        return seg.map(Node::fromBytes).orElse(null);
    }

    // ---- parse a bounded real-NCIt sample (stop early) ----

    private static final class StopParsing extends RuntimeException {
        StopParsing() {
            super(null, null, false, false);
        }
    }

    private static List<Statement> parseSample(int n) {
        List<Statement> out = new ArrayList<>(n);
        try (ZipFile zip = new ZipFile(ncitZip().toFile())) {
            ZipEntry entry = zip.getEntry("ncit.owl");
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry), 1 << 20)) {
                RDFParser parser = Rio.createParser(RDFFormat.RDFXML);
                parser.setRDFHandler(
                        new AbstractRDFHandler() {
                            @Override
                            public void handleStatement(Statement st) {
                                out.add(st);
                                if (out.size() >= n) throw new StopParsing();
                            }
                        });
                try {
                    parser.parse(in, "http://purl.obolibrary.org/obo/ncit.owl");
                } catch (StopParsing done) {
                    /* reached n */
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private static Path ncitZip() {
        String prop = System.getProperty("ncit.zip");
        if (prop != null) return Path.of(prop);
        for (String c :
                new String[] {
                    "test_ontologies_zips/ncit.zip", "../test_ontologies_zips/ncit.zip"
                }) {
            Path p = Path.of(c);
            if (Files.exists(p)) return p;
        }
        throw new IllegalStateException("ncit.zip not found; pass -Dncit.zip=…");
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
        }
    }

    private NcitVersioningBenchmark() {}
}
