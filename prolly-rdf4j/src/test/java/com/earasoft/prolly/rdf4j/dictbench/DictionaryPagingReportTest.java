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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The storage-page layer for the radix dictionary: pack the canonical depth-first node sequence
 * into content-defined pages targeting ~4 KiB (MIN 2 KiB suppressed / forced close at 8 KiB /
 * natural close when a node's own address masks to zero past MIN) — the prolly chunk-size
 * discipline applied at the packing layer, where the radix's ~200-byte nodes actually need it.
 *
 * <p>The page partition is a pure function of the node sequence (addresses are content hashes and
 * the boundary rule reads only them), so two stores holding the same dictionary pack identical
 * pages, and an edit repages only the neighborhood of changed nodes. This report measures the size
 * distribution, pins determinism + bounds, and measures page-level edit locality under the bench's
 * update workloads.
 */
class DictionaryPagingReportTest {

    static final int MIN_PAGE = 2 * 1024;
    static final int MAX_PAGE = 8 * 1024;
    static final int MASK_BITS = 3; // p = 1/8 per node past MIN; with ~208 B nodes → ~4 KiB mean

    /** DFS canonical order of reachable node addresses (parent before children). */
    static List<MerkleRadixDictionary.Addr> canonicalOrder(
            MerkleRadixDictionary dict, MerkleRadixDictionary.Addr root) {
        List<MerkleRadixDictionary.Addr> out = new ArrayList<>();
        dfs(dict, root, out);
        return out;
    }

    private static void dfs(
            MerkleRadixDictionary dict,
            MerkleRadixDictionary.Addr addr,
            List<MerkleRadixDictionary.Addr> out) {
        out.add(addr);
        MerkleRadixDictionary.Node node = dict.node(addr);
        if (node instanceof MerkleRadixDictionary.Internal in) {
            for (MerkleRadixDictionary.Addr child : in.children()) dfs(dict, child, out);
        }
    }

    /** Pack serialized nodes into content-defined pages; returns page payloads. */
    static List<byte[]> packPages(MerkleRadixDictionary dict, MerkleRadixDictionary.Addr root) {
        List<byte[]> pages = new ArrayList<>();
        List<byte[]> current = new ArrayList<>();
        int currentBytes = 0;
        int mask = (1 << MASK_BITS) - 1;
        for (MerkleRadixDictionary.Addr addr : canonicalOrder(dict, root)) {
            byte[] serial = dict.node(addr).serialize();
            current.add(serial);
            currentBytes += serial.length;
            boolean natural = currentBytes >= MIN_PAGE && (addr.bytes()[19] & mask) == 0;
            if (natural || currentBytes >= MAX_PAGE) {
                pages.add(concat(current, currentBytes));
                current.clear();
                currentBytes = 0;
            }
        }
        if (!current.isEmpty()) pages.add(concat(current, currentBytes));
        return pages;
    }

    private static byte[] concat(List<byte[]> parts, int total) {
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, p, part.length);
            p += part.length;
        }
        return out;
    }

    private static Set<String> pageHashes(List<byte[]> pages) throws NoSuchAlgorithmException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        Set<String> out = new HashSet<>();
        for (byte[] page : pages) {
            out.add(java.util.HexFormat.of().formatHex(sha.digest(page)));
        }
        return out;
    }

    @Test
    void reportPagingDistributionAndEditLocality() throws Exception {
        byte[][] corpus = DictionaryBench.ncitCorpus();
        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);

        MerkleRadixDictionary dict = new MerkleRadixDictionary(64);
        MerkleRadixDictionary.Addr root = dict.build(entries);

        List<byte[]> pages = packPages(dict, root);
        int[] sizes = pages.stream().mapToInt(p -> p.length).toArray();
        int[] sorted = sizes.clone();
        Arrays.sort(sorted);
        double mean = Arrays.stream(sizes).average().orElse(0);
        System.out.printf(
                "pages: %d  mean %.0f B  p10 %d  p90 %d  min %d  max %d  (target 4096, clamp [%d, %d])%n",
                pages.size(),
                mean,
                sorted[(int) (sorted.length * 0.1)],
                sorted[(int) (sorted.length * 0.9)],
                sorted[0],
                sorted[sorted.length - 1],
                MIN_PAGE,
                MAX_PAGE);

        // Determinism: same dictionary → identical page set.
        assertEquals(pageHashes(pages), pageHashes(packPages(dict, root)));
        // Bounds: every closed page ≥ MIN; forced close means ≤ MAX + one node.
        for (int i = 0; i < sizes.length - 1; i++) {
            assertTrue(sizes[i] >= MIN_PAGE, "page " + i + " under MIN");
        }

        // Edit locality: the bench's two update shapes.
        for (int updates : new int[] {100, 5_000}) {
            MerkleRadixDictionary forked = dict.fork();
            TreeMap<byte[], Long> batch = new TreeMap<>(Arrays::compareUnsigned);
            for (int u = 0; u < updates; u++) {
                batch.put(
                        MerkleRadixDictionary.utf8(
                                "http://purl.obolibrary.org/obo/NCIT_C" + (900_000 + u)),
                        (long) (corpus.length + u));
            }
            MerkleRadixDictionary.Addr root2 = forked.insertAll(root, batch);
            List<byte[]> pages2 = packPages(forked, root2);
            Set<String> before = pageHashes(pages);
            Set<String> after = pageHashes(pages2);
            Set<String> shared = new HashSet<>(after);
            shared.retainAll(before);
            System.out.printf(
                    "+%d terms: %d pages → %d new, %d byte-identical to the old page set%n",
                    updates, pages2.size(), after.size() - shared.size(), shared.size());
        }
    }
}
