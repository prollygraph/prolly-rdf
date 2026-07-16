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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Correctness + geometry + append-reuse for the reverse-index chunking strategies. The structural
 * claim under test: on an append-only ordinal log, every strategy is history-independent and
 * append-local — old chunk boundaries never move, so appending reuses every sealed chunk
 * byte-identically, and CDC's shift-healing has nothing to heal.
 */
class ReverseIndexReportTest {

    @Test
    void correctnessGeometryAndAppendReuse() {
        byte[][] corpus = DictionaryBench.ncitCorpus();
        record Named(String name, ReverseStores.Store store) {}
        List<Named> stores =
                List.of(
                        new Named("fixed-count 64", ReverseStores.fixedCount(corpus, 64)),
                        new Named("byte-budget 4096", ReverseStores.byteBudget(corpus, 4096)),
                        new Named("content-defined", ReverseStores.contentDefined(corpus)));

        // Correctness: every id resolves to its exact term bytes, in every strategy.
        for (Named n : stores) {
            for (int id = 0; id < corpus.length; id += 97) {
                assertArrayEquals(corpus[id], n.store().get(id), n.name() + " id " + id);
            }
        }

        // Geometry + append-reuse: extend by 5k terms and count reused chunk addresses.
        byte[][] extended = Arrays.copyOf(corpus, corpus.length + 5_000);
        for (int u = 0; u < 5_000; u++) {
            extended[corpus.length + u] =
                    MerkleRadixDictionary.utf8(
                            "http://purl.obolibrary.org/obo/NCIT_C" + (900_000 + u));
        }
        System.out.printf(
                "%-18s %7s %10s %8s %8s   %s%n",
                "strategy", "chunks", "mean B", "min B", "max B", "append +5k: new/reused chunks");
        for (Named n : stores) {
            ReverseStores.Store after =
                    switch (n.name()) {
                        case "fixed-count 64" -> ReverseStores.fixedCount(extended, 64);
                        case "byte-budget 4096" -> ReverseStores.byteBudget(extended, 4096);
                        default -> ReverseStores.contentDefined(extended);
                    };
            int[] sizes = n.store().chunks().stream().mapToInt(c -> c.bytes().length).toArray();
            Set<String> before = new HashSet<>(n.store().addresses());
            List<String> afterAddrs = after.addresses();
            long reused = afterAddrs.stream().filter(before::contains).count();
            System.out.printf(
                    "%-18s %7d %10.0f %8d %8d   %d new / %d reused of %d%n",
                    n.name(),
                    sizes.length,
                    Arrays.stream(sizes).average().orElse(0),
                    Arrays.stream(sizes).min().orElse(0),
                    Arrays.stream(sizes).max().orElse(0),
                    afterAddrs.size() - reused,
                    reused,
                    afterAddrs.size());
            // The append-only invariant: every previously sealed chunk is reused
            // byte-identically (only the previously-unsealed tail differs).
            assertTrue(reused >= n.store().chunks().size() - 1, n.name());
        }
        long raw = Arrays.stream(corpus).mapToLong(b -> b.length).sum();
        for (Named n : stores) {
            System.out.printf(
                    "%-18s total %,d bytes (%.1f%% of raw)%n",
                    n.name(), n.store().totalBytes(), 100.0 * n.store().totalBytes() / raw);
        }

        // Determinism: rebuild → identical addresses (history independence of the log).
        assertEquals(
                ReverseStores.contentDefined(corpus).addresses(),
                ReverseStores.contentDefined(corpus).addresses());
        assertEquals(
                ReverseStores.fixedCount(corpus, 64).addresses(),
                ReverseStores.fixedCount(corpus, 64).addresses());
    }
}
