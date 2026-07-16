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
package com.earasoft.prolly.rdf4j.dictbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.DiffEngine;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Correctness of the GIT operations (version diff, identical-prune, replica convergence) for BOTH
 * dictionary designs, pinned three ways: against ground truth, against each other on the same
 * logical edit, and at scale.
 */
class GitOpsCorrectnessTest {

    @Test
    void radixDiffMatchesGroundTruthOnRandomEdits() {
        SplittableRandom rnd = new SplittableRandom(31);
        for (int trial = 0; trial < 40; trial++) {
            TreeMap<byte[], Long> base = new TreeMap<>(Arrays::compareUnsigned);
            for (int i = 0; i < 50 + rnd.nextInt(300); i++) {
                base.put(randKey(rnd), (long) i);
            }
            MerkleRadixDictionary dict = new MerkleRadixDictionary(rnd.nextBoolean() ? 1 : 64);
            MerkleRadixDictionary.Addr rootA = dict.build(base);

            // Random edit batch: additions + id updates of existing keys.
            TreeMap<byte[], Long> batch = new TreeMap<>(Arrays::compareUnsigned);
            for (int i = 0; i < 1 + rnd.nextInt(30); i++) batch.put(randKey(rnd), 100_000L + i);
            int updates = 0;
            for (byte[] k : base.keySet()) {
                if (updates++ % 17 == 0) batch.put(k, 200_000L + updates);
            }
            MerkleRadixDictionary.Addr rootB = dict.insertAll(rootA, batch);

            TreeMap<byte[], Long> expectedAfter = new TreeMap<>(Arrays::compareUnsigned);
            expectedAfter.putAll(base);
            expectedAfter.putAll(batch);

            MerkleRadixDictionary.DiffResult diff = dict.diff(rootA, rootB);
            // Ground truth: every differing (key, id) pair, with old/new sides correct.
            TreeMap<byte[], long[]> expected = new TreeMap<>(Arrays::compareUnsigned);
            for (Map.Entry<byte[], Long> e : expectedAfter.entrySet()) {
                Long old = base.get(e.getKey());
                if (old == null) expected.put(e.getKey(), new long[] {-1, e.getValue()});
                else if (!old.equals(e.getValue())) {
                    expected.put(e.getKey(), new long[] {old, e.getValue()});
                }
            }
            assertEquals(expected.size(), diff.changes().size(), "trial " + trial);
            for (Map.Entry<byte[], long[]> e : expected.entrySet()) {
                long[] got = diff.changes().get(e.getKey());
                assertTrue(got != null && Arrays.equals(got, e.getValue()), "trial " + trial);
            }
        }
    }

    @Test
    void identicalRootsDiffEmptyWithRootLevelPrune() {
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < 5_000; i++) {
            entries.put(("http://x/term/" + i).getBytes(StandardCharsets.UTF_8), (long) i);
        }
        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr root = dict.build(entries);
        MerkleRadixDictionary.DiffResult diff = dict.diff(root, root);
        assertEquals(0, diff.changes().size());
        assertEquals(0, diff.visited());
        assertEquals(1, diff.pruned()); // pruned at the root — O(1)
    }

    @Test
    void convergedReplicasDiffEmptyAtScale() {
        // Two replicas ingest the same 50k mapping along different histories; the
        // canonical structure makes their roots equal, so the git-diff is empty.
        byte[][] corpus = DictionaryBench.ncitCorpus();
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);

        MerkleRadixDictionary replicaA = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr rootA = replicaA.build(entries);

        MerkleRadixDictionary replicaB = new MerkleRadixDictionary(64);
        TreeMap<byte[], Long> half = new TreeMap<>(Arrays::compareUnsigned);
        int i = 0;
        for (Map.Entry<byte[], Long> e : entries.entrySet()) {
            if (i++ % 2 == 0) half.put(e.getKey(), e.getValue());
        }
        MerkleRadixDictionary.Addr rootB = replicaB.build(half);
        TreeMap<byte[], Long> rest = new TreeMap<>(Arrays::compareUnsigned);
        for (Map.Entry<byte[], Long> e : entries.entrySet()) {
            if (!half.containsKey(e.getKey())) rest.put(e.getKey(), e.getValue());
        }
        rootB = replicaB.insertAll(rootB, rest);
        assertEquals(rootA, rootB); // convergence
    }

    @Test
    void radixAndEngineAgreeOnTheSameLogicalEdit() {
        // The cross-implementation pin: 5k terms added to a 50k base; the radix diff and the
        // engine's DiffEngine must report the SAME term set as added.
        byte[][] corpus = DictionaryBench.ncitCorpus();
        byte[][] added = new byte[5_000][];
        for (int u = 0; u < added.length; u++) {
            added[u] =
                    ("http://purl.obolibrary.org/obo/NCIT_C" + (900_000 + u))
                            .getBytes(StandardCharsets.UTF_8);
        }

        // Radix side.
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);
        MerkleRadixDictionary radix = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr rootA = radix.build(entries);
        TreeMap<byte[], Long> batch = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < added.length; u++) batch.put(added[u], (long) (corpus.length + u));
        MerkleRadixDictionary.Addr rootB = radix.insertAll(rootA, batch);
        MerkleRadixDictionary.DiffResult radixDiff = radix.diff(rootA, rootB);

        Set<String> radixAdded = new HashSet<>();
        for (Map.Entry<byte[], long[]> e : radixDiff.changes().entrySet()) {
            assertEquals(-1, e.getValue()[0]); // pure additions
            radixAdded.add(new String(e.getKey(), StandardCharsets.UTF_8));
        }

        // Engine side: two commits over one store, diffed by the real DiffEngine.
        NodeStore store = new InMemoryNodeStore();
        Dictionary dict = new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash());
        for (byte[] term : corpus) dict.encode(MemorySegment.ofArray(term));
        StaticMap commitA = dict.commit();
        Dictionary dict2 =
                new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash(), commitA);
        for (byte[] term : added) dict2.encode(MemorySegment.ofArray(term));
        StaticMap commitB = dict2.commit();

        DiffEngine engine = new DiffEngine(store, commitA.descriptor());
        Set<String> engineAdded = new HashSet<>();
        Iterator<DiffEngine.DiffEntry> it = engine.diffIterator(commitA.root(), commitB.root());
        while (it.hasNext()) {
            DiffEngine.DiffEntry e = it.next();
            MemorySegment value = e.valueB();
            byte[] bytes = value.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            engineAdded.add(new String(bytes, StandardCharsets.UTF_8));
        }

        assertEquals(5_000, radixAdded.size());
        assertEquals(radixAdded, engineAdded); // both designs report the identical term set
    }

    @Test
    void threeWayMergeUnionSymmetryAndConflicts() {
        // Base 2k terms; ours adds A-range + updates one id; theirs adds B-range.
        TreeMap<byte[], Long> base = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < 2_000; i++) {
            base.put(
                    ("http://x/obo/NCIT_C" + (10_000 + i)).getBytes(StandardCharsets.UTF_8),
                    (long) i);
        }
        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr baseRoot = dict.build(base);

        TreeMap<byte[], Long> oursBatch = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < 250; i++) {
            oursBatch.put(
                    ("http://x/obo/GO_" + (5_000 + i)).getBytes(StandardCharsets.UTF_8),
                    10_000L + i);
        }
        byte[] updatedKey = "http://x/obo/NCIT_C10007".getBytes(StandardCharsets.UTF_8);
        oursBatch.put(updatedKey, 77_777L);
        MerkleRadixDictionary.Addr ours = dict.insertAll(baseRoot, oursBatch);

        TreeMap<byte[], Long> theirsBatch = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < 250; i++) {
            theirsBatch.put(
                    ("http://x/obo/CHEBI_" + (9_000 + i)).getBytes(StandardCharsets.UTF_8),
                    20_000L + i);
        }
        MerkleRadixDictionary.Addr theirs = dict.insertAll(baseRoot, theirsBatch);

        MerkleRadixDictionary.MergeOutcome merged = dict.merge(baseRoot, ours, theirs);
        assertTrue(merged.conflicts().isEmpty());

        // Union semantics: merged mapping == base ∪ ours-changes ∪ theirs-changes.
        TreeMap<byte[], Long> expected = new TreeMap<>(Arrays::compareUnsigned);
        expected.putAll(base);
        expected.putAll(oursBatch);
        expected.putAll(theirsBatch);
        assertEquals(expected, dict.entriesUnder(merged.root(), new byte[0]));

        // Symmetry: swapping ours/theirs yields the byte-identical root (canonicity).
        MerkleRadixDictionary.MergeOutcome swapped = dict.merge(baseRoot, theirs, ours);
        assertEquals(merged.root(), swapped.root());

        // Conflict: both sides change the same key to different ids.
        TreeMap<byte[], Long> clash = new TreeMap<>(Arrays::compareUnsigned);
        clash.put(updatedKey, 88_888L);
        MerkleRadixDictionary.Addr theirsClash = dict.insertAll(theirs, clash);
        MerkleRadixDictionary.MergeOutcome conflicted = dict.merge(baseRoot, ours, theirsClash);
        assertEquals(1, conflicted.conflicts().size());
        assertTrue(
                Arrays.equals(
                        new long[] {77_777L, 88_888L}, conflicted.conflicts().get(updatedKey)));

        // Same change on BOTH sides is not a conflict.
        MerkleRadixDictionary.Addr theirsSame = dict.insertAll(theirs, oursBatch);
        assertTrue(dict.merge(baseRoot, ours, theirsSame).conflicts().isEmpty());
    }

    @Test
    void mergeAgreesWithEngineMergeOnTheSameScenario() {
        byte[][] corpus = DictionaryBench.ncitCorpus();
        byte[][] oursAdd = new byte[2_500][];
        byte[][] theirsAdd = new byte[2_500][];
        for (int u = 0; u < 2_500; u++) {
            oursAdd[u] =
                    ("http://purl.obolibrary.org/obo/NCIT_C" + (900_000 + u))
                            .getBytes(StandardCharsets.UTF_8);
            theirsAdd[u] =
                    ("http://purl.obolibrary.org/obo/GO_" + (8_000_000 + u))
                            .getBytes(StandardCharsets.UTF_8);
        }

        // Radix: three roots, merge, verify the mapping.
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);
        MerkleRadixDictionary radix = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr base = radix.build(entries);
        TreeMap<byte[], Long> ob = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < oursAdd.length; u++) ob.put(oursAdd[u], (long) (corpus.length + u));
        TreeMap<byte[], Long> tb = new TreeMap<>(Arrays::compareUnsigned);
        for (int u = 0; u < theirsAdd.length; u++)
            tb.put(theirsAdd[u], (long) (corpus.length + 10_000 + u));
        MerkleRadixDictionary.MergeOutcome merged =
                radix.merge(base, radix.insertAll(base, ob), radix.insertAll(base, tb));
        assertTrue(merged.conflicts().isEmpty());
        Set<String> radixTerms = new HashSet<>();
        for (byte[] k : radix.entriesUnder(merged.root(), new byte[0]).keySet()) {
            radixTerms.add(new String(k, StandardCharsets.UTF_8));
        }
        assertEquals(corpus.length + 5_000, radixTerms.size());

        // Engine: three commits over one store, merged by the real MergeEngine.
        NodeStore store = new InMemoryNodeStore();
        Dictionary dict = new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash());
        for (byte[] term : corpus) dict.encode(MemorySegment.ofArray(term));
        StaticMap ancestor = dict.commit();
        Dictionary oursDict =
                new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash(), ancestor);
        for (byte[] term : oursAdd) oursDict.encode(MemorySegment.ofArray(term));
        StaticMap ours = oursDict.commit();
        Dictionary theirsDict =
                new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash(), ancestor);
        for (byte[] term : theirsAdd) theirsDict.encode(MemorySegment.ofArray(term));
        StaticMap theirs = theirsDict.commit();

        com.dolthub.prolly.MergeEngine engine =
                new com.dolthub.prolly.MergeEngine(
                        store, ancestor.descriptor(), new HeapBufferPool());
        com.dolthub.prolly.MergeEngine.MergeResult result =
                engine.merge(ancestor.root(), ours.root(), theirs.root());
        assertTrue(result.conflicts().isEmpty());

        // Cross-check: the engine's merged tree holds the same term SET.
        Set<String> engineTerms = new HashSet<>();
        StaticMap mergedMap = new StaticMap(store, result.root(), ancestor.descriptor());
        com.dolthub.prolly.MapIterator it = mergedMap.iter();
        while (it.next()) {
            engineTerms.add(
                    new String(
                            it.value().toArray(java.lang.foreign.ValueLayout.JAVA_BYTE),
                            StandardCharsets.UTF_8));
        }
        assertEquals(radixTerms, engineTerms);
    }

    private static byte[] randKey(SplittableRandom rnd) {
        int len = 1 + rnd.nextInt(24);
        byte[] k = new byte[len];
        for (int j = 0; j < len; j++) k[j] = (byte) (32 + rnd.nextInt(90));
        return k;
    }
}
