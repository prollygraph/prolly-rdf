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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link MerkleRadixDictionary} study candidate. The load-bearing property is history
 * independence the STRONG way: folding path-copying inserts in random orders must yield a root
 * byte-identical to the canonical batch build — any structural divergence in either code path
 * breaks the equality (the same pin the Python reference carries via hypothesis).
 */
class MerkleRadixDictionaryTest {

    private static TreeMap<byte[], Long> randomEntries(SplittableRandom rnd, int n) {
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < n; i++) {
            int len = 1 + rnd.nextInt(30);
            byte[] k = new byte[len];
            for (int j = 0; j < len; j++) k[j] = (byte) rnd.nextInt(("z".charAt(0)) - 'a' + 5);
            entries.put(k, rnd.nextLong(1L << 62));
        }
        return entries;
    }

    @Test
    void insertAnyOrderEqualsBatchBuild() {
        for (int bucketSize : new int[] {1, 4, 64}) {
            SplittableRandom rnd = new SplittableRandom(7);
            for (int trial = 0; trial < 60; trial++) {
                TreeMap<byte[], Long> entries = randomEntries(rnd, 1 + rnd.nextInt(120));
                MerkleRadixDictionary batch = new MerkleRadixDictionary(bucketSize);
                MerkleRadixDictionary.Addr batchRoot = batch.build(entries);

                List<Map.Entry<byte[], Long>> items = new ArrayList<>(entries.entrySet());
                Collections.shuffle(items, new java.util.Random(trial));
                MerkleRadixDictionary folded = new MerkleRadixDictionary(bucketSize);
                MerkleRadixDictionary.Addr root =
                        folded.singleton(items.get(0).getKey(), items.get(0).getValue());
                for (int i = 1; i < items.size(); i++) {
                    root = folded.insert(root, items.get(i).getKey(), items.get(i).getValue());
                }
                assertEquals(batchRoot, root, "bucketSize " + bucketSize + " trial " + trial);
            }
        }
    }

    @Test
    void insertAllInRandomChunkingsEqualsBatchBuild() {
        for (int bucketSize : new int[] {1, 64}) {
            SplittableRandom rnd = new SplittableRandom(23);
            for (int trial = 0; trial < 40; trial++) {
                TreeMap<byte[], Long> entries = randomEntries(rnd, 2 + rnd.nextInt(200));
                MerkleRadixDictionary batch = new MerkleRadixDictionary(bucketSize);
                MerkleRadixDictionary.Addr batchRoot = batch.build(entries);

                List<Map.Entry<byte[], Long>> items = new ArrayList<>(entries.entrySet());
                Collections.shuffle(items, new java.util.Random(trial));
                MerkleRadixDictionary dict = new MerkleRadixDictionary(bucketSize);
                TreeMap<byte[], Long> first = new TreeMap<>(Arrays::compareUnsigned);
                first.put(items.get(0).getKey(), items.get(0).getValue());
                MerkleRadixDictionary.Addr root = dict.build(first);
                int i = 1;
                while (i < items.size()) {
                    int chunk = 1 + rnd.nextInt(40);
                    TreeMap<byte[], Long> part = new TreeMap<>(Arrays::compareUnsigned);
                    for (int j = i; j < Math.min(i + chunk, items.size()); j++) {
                        part.put(items.get(j).getKey(), items.get(j).getValue());
                    }
                    root = dict.insertAll(root, part);
                    i += chunk;
                }
                assertEquals(batchRoot, root, "bucketSize " + bucketSize + " trial " + trial);
            }
        }
    }

    @Test
    void subtreeAddressingResolvesEnumeratesAndPrunes() {
        // NCIt-shaped mini corpus: two namespaces + literals.
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        long id = 0;
        for (int i = 0; i < 2_000; i++) {
            entries.put(MerkleRadixDictionary.utf8("http://x.org/obo/NCIT_C" + (10_000 + i)), id++);
        }
        for (int i = 0; i < 300; i++) {
            entries.put(MerkleRadixDictionary.utf8("http://x.org/obo/GO_" + (5_000 + i)), id++);
        }
        for (int i = 0; i < 100; i++) {
            entries.put(MerkleRadixDictionary.utf8("label " + i), id++);
        }
        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr root = dict.build(entries);

        byte[] ncit = MerkleRadixDictionary.utf8("http://x.org/obo/NCIT_C");
        byte[] go = MerkleRadixDictionary.utf8("http://x.org/obo/GO_");

        // Enumeration under a prefix equals the filtered ground truth.
        TreeMap<byte[], Long> expect = new TreeMap<>(Arrays::compareUnsigned);
        for (Map.Entry<byte[], Long> e : entries.entrySet()) {
            byte[] k = e.getKey();
            if (k.length >= ncit.length && Arrays.equals(k, 0, ncit.length, ncit, 0, ncit.length)) {
                expect.put(k, e.getValue());
            }
        }
        assertEquals(expect, dict.entriesUnder(root, ncit));
        assertEquals(2_000, dict.entriesUnder(root, ncit).size());

        // Absent prefix → null address, empty enumeration.
        byte[] absent = MerkleRadixDictionary.utf8("http://nowhere/");
        assertEquals(null, dict.subtreeAddress(root, absent));
        assertEquals(0, dict.entriesUnder(root, absent).size());

        // The Merkle payoff: edits OUTSIDE a namespace leave its subtree address
        // untouched (one address compare proves 2000 terms identical); edits
        // INSIDE change it.
        MerkleRadixDictionary.Addr ncitBefore = dict.subtreeAddress(root, ncit);
        MerkleRadixDictionary.Addr goBefore = dict.subtreeAddress(root, go);

        TreeMap<byte[], Long> goBatch = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < 50; i++) {
            goBatch.put(MerkleRadixDictionary.utf8("http://x.org/obo/GO_" + (9_000 + i)), id++);
        }
        MerkleRadixDictionary.Addr root2 = dict.insertAll(root, goBatch);
        assertEquals(ncitBefore, dict.subtreeAddress(root2, ncit)); // pruned in O(1)
        assertNotEquals(goBefore, dict.subtreeAddress(root2, go));

        TreeMap<byte[], Long> ncitBatch = new TreeMap<>(Arrays::compareUnsigned);
        ncitBatch.put(MerkleRadixDictionary.utf8("http://x.org/obo/NCIT_C99999"), id++);
        MerkleRadixDictionary.Addr root3 = dict.insertAll(root2, ncitBatch);
        assertNotEquals(ncitBefore, dict.subtreeAddress(root3, ncit));
    }

    @Test
    void bucketVariantLookupAndSerializedAgree() {
        SplittableRandom rnd = new SplittableRandom(17);
        TreeMap<byte[], Long> entries = randomEntries(rnd, 600);
        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr root = dict.build(entries);
        MerkleRadixDictionary.SerializedPool pool = MerkleRadixDictionary.SerializedPool.of(dict);
        FlatbufferPool fbPool = FlatbufferPool.of(dict, root);
        for (Map.Entry<byte[], Long> e : entries.entrySet()) {
            assertEquals((long) e.getValue(), dict.get(root, e.getKey()));
            assertEquals((long) e.getValue(), pool.get(root, e.getKey()));
            assertEquals((long) e.getValue(), fbPool.get(root, e.getKey()));
        }
        assertEquals(-1, pool.get(root, MerkleRadixDictionary.utf8("ÿabsent")));
        assertEquals(-1, fbPool.get(root, MerkleRadixDictionary.utf8("ÿabsent")));
    }

    @Test
    void lookupFindsEveryEntryAndOnlyThem() {
        SplittableRandom rnd = new SplittableRandom(11);
        TreeMap<byte[], Long> entries = randomEntries(rnd, 400);
        MerkleRadixDictionary dict = new MerkleRadixDictionary();
        MerkleRadixDictionary.Addr root = dict.build(entries);
        for (Map.Entry<byte[], Long> e : entries.entrySet()) {
            assertEquals((long) e.getValue(), dict.get(root, e.getKey()));
        }
        assertEquals(-1, dict.get(root, MerkleRadixDictionary.utf8("ÿnot-a-key")));
    }

    @Test
    void prefixOfAnotherKey() {
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        entries.put(MerkleRadixDictionary.utf8("http://a"), 1L);
        entries.put(MerkleRadixDictionary.utf8("http://a/b"), 2L);
        entries.put(MerkleRadixDictionary.utf8("http://a/b/c"), 3L);
        MerkleRadixDictionary dict = new MerkleRadixDictionary();
        MerkleRadixDictionary.Addr root = dict.build(entries);
        assertEquals(1, dict.get(root, MerkleRadixDictionary.utf8("http://a")));
        assertEquals(2, dict.get(root, MerkleRadixDictionary.utf8("http://a/b")));
        assertEquals(3, dict.get(root, MerkleRadixDictionary.utf8("http://a/b/c")));
    }

    @Test
    void serializedProbeAgreesWithObjectProbe() {
        SplittableRandom rnd = new SplittableRandom(13);
        TreeMap<byte[], Long> entries = randomEntries(rnd, 500);
        MerkleRadixDictionary dict = new MerkleRadixDictionary();
        MerkleRadixDictionary.Addr root = dict.build(entries);
        MerkleRadixDictionary.SerializedPool pool = MerkleRadixDictionary.SerializedPool.of(dict);
        for (Map.Entry<byte[], Long> e : entries.entrySet()) {
            assertEquals(dict.get(root, e.getKey()), pool.get(root, e.getKey()));
            assertEquals((long) e.getValue(), pool.get(root, e.getKey()));
        }
        assertEquals(-1, pool.get(root, MerkleRadixDictionary.utf8("ÿabsent")));
    }

    @Test
    void supernodePagerAgreesAndIsCanonical() {
        byte[][] corpus = DictionaryBench.ncitCorpus();
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);
        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr root = dict.build(entries);

        SupernodePager pager = SupernodePager.of(dict, root);
        SupernodePager.PageSource mem = pager.inMemory();
        int[] fetches = new int[1];
        SplittableRandom rnd = new SplittableRandom(5);
        for (int i = 0; i < 5_000; i++) {
            int idx = rnd.nextInt(corpus.length);
            assertEquals(idx, SupernodePager.get(mem, pager.rootPage, corpus[idx], fetches));
        }
        assertEquals(
                -1,
                SupernodePager.get(mem, pager.rootPage, MerkleRadixDictionary.utf8("ÿnope"), null));
        double meanFetches = fetches[0] / 5_000.0;
        int[] sizes = pager.pages.values().stream().mapToInt(b -> b.length).toArray();
        System.out.printf(
                "supernode pages: %d  mean %d B  max %d B  mean page-fetches/lookup %.2f%n",
                sizes.length,
                (int) Arrays.stream(sizes).average().orElse(0),
                Arrays.stream(sizes).max().orElse(0),
                meanFetches);
        assertTrue(meanFetches <= 3.5, "expected few page fetches, got " + meanFetches);

        // Canonical: rebuilding pages yields the identical page set + root page address.
        SupernodePager again = SupernodePager.of(dict, root);
        assertEquals(pager.rootPage, again.rootPage);
        assertEquals(pager.pages.keySet(), again.pages.keySet());
    }

    @Test
    void updateChangesRoot() {
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < 300; i++)
            entries.put(MerkleRadixDictionary.utf8("http://x/" + i), (long) i);
        MerkleRadixDictionary dict = new MerkleRadixDictionary();
        MerkleRadixDictionary.Addr root = dict.build(entries);
        MerkleRadixDictionary.Addr root2 =
                dict.insert(root, MerkleRadixDictionary.utf8("http://x/17"), 9999L);
        assertNotEquals(root, root2);
        assertEquals(9999, dict.get(root2, MerkleRadixDictionary.utf8("http://x/17")));
        assertEquals(17, dict.get(root, MerkleRadixDictionary.utf8("http://x/17")));
    }
}
