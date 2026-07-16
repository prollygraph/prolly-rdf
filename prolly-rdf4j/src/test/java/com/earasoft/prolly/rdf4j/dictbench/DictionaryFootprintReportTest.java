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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.dolthub.prolly.NodeStore;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Footprint report for the dictionary bench (not a gate — the assertions are sanity floors; the
 * printed numbers feed the write-up). Serialized bytes both sides: the current dictionary's total
 * chunk bytes written to its NodeStore vs the radix pool's total serialized node bytes, same
 * 50k-term NCIt-shaped corpus as {@link DictionaryBench}.
 */
class DictionaryFootprintReportTest {

    /** NodeStore delegate that sums the bytes of every chunk written. */
    static final class CountingNodeStore implements NodeStore {
        private final InMemoryNodeStore inner = new InMemoryNodeStore();
        long bytesWritten;
        int chunksWritten;

        @Override
        public Optional<MemorySegment> read(byte[] hash) {
            return inner.read(hash);
        }

        @Override
        public byte[] write(MemorySegment segment) {
            bytesWritten += segment.byteSize();
            chunksWritten++;
            return inner.write(segment);
        }

        @Override
        public byte[] write(byte[] data) {
            bytesWritten += data.length;
            chunksWritten++;
            return inner.write(data);
        }
    }

    @Test
    void reportFootprints() {
        byte[][] corpus = DictionaryBench.ncitCorpus();
        long rawBytes = Arrays.stream(corpus).mapToLong(b -> b.length).sum();

        CountingNodeStore store = new CountingNodeStore();
        Dictionary dict = new Dictionary(store, new HeapBufferPool(), HashFunctions.defaultHash());
        for (byte[] term : corpus) dict.encode(MemorySegment.ofArray(term));
        dict.commit();

        TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
        for (int i = 0; i < corpus.length; i++) entries.put(corpus[i], (long) i);

        System.out.printf("raw term bytes:            %,d%n", rawBytes);
        System.out.printf(
                "current (chunk bytes):     %,d in %,d chunks  (%.0f%% of raw)%n",
                store.bytesWritten, store.chunksWritten, 100.0 * store.bytesWritten / rawBytes);
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(0);
        int[] sample = new int[2000];
        for (int i = 0; i < sample.length; i++) sample[i] = rnd.nextInt(corpus.length);
        for (int bucketSize : new int[] {1, 64}) {
            MerkleRadixDictionary radix = new MerkleRadixDictionary(bucketSize);
            MerkleRadixDictionary.Addr root = radix.build(entries);
            double meanDepth = 0;
            for (int idx : sample) meanDepth += radix.depthOf(root, corpus[idx]);
            meanDepth /= sample.length;
            System.out.printf(
                    "radix K=%-3d (node bytes):  %,d in %,d nodes   (%.0f%% of raw)  mean depth %.1f%n",
                    bucketSize,
                    radix.storedBytes(),
                    radix.nodeCount(),
                    100.0 * radix.storedBytes() / rawBytes,
                    meanDepth);
            assertTrue(radix.storedBytes() > 0);
            FlatbufferPool fb = FlatbufferPool.of(radix, root);
            System.out.printf(
                    "  … flatbuffer-framed:     %,d bytes (%.0f%% of raw) — the framing tax%n",
                    fb.totalBytes(), 100.0 * fb.totalBytes() / rawBytes);
        }
        assertTrue(store.bytesWritten > 0);
    }
}
