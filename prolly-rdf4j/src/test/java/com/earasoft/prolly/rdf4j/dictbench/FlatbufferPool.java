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

import com.earasoft.prolly.rdf4j.dictbench.fb.FbNode;
import com.google.flatbuffers.FlatBufferBuilder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The radix pool in the ENGINE's framing discipline: every node a FlatBuffers table (same
 * flatbuffers 23.5.26 runtime the engine's {@code FlatbufferNodeSerializer} uses), with the lookup
 * walking vtable-navigated accessors per hop — the framing-leveled counterpart to {@link
 * MerkleRadixDictionary.SerializedPool}'s hand-rolled fixed-offset layout.
 */
public final class FlatbufferPool {

    private final Map<MerkleRadixDictionary.Addr, byte[]> blocks = new HashMap<>();
    private long totalBytes;

    public long totalBytes() {
        return totalBytes;
    }

    public static FlatbufferPool of(MerkleRadixDictionary dict, MerkleRadixDictionary.Addr root) {
        FlatbufferPool pool = new FlatbufferPool();
        for (MerkleRadixDictionary.Addr addr :
                DictionaryPagingReportTest.canonicalOrder(dict, root)) {
            if (pool.blocks.containsKey(addr)) continue;
            byte[] fb = encode(dict.node(addr));
            pool.blocks.put(addr, fb);
            pool.totalBytes += fb.length;
        }
        return pool;
    }

    private static byte[] encode(MerkleRadixDictionary.Node node) {
        FlatBufferBuilder b = new FlatBufferBuilder(256);
        if (node instanceof MerkleRadixDictionary.Bucket(byte[][] suffixes, long[] ids)) {
            int n = suffixes.length;
            int[] offsets = new int[n + 1];
            int total = 0;
            for (int i = 0; i < n; i++) {
                offsets[i] = total;
                total += suffixes[i].length;
            }
            offsets[n] = total;
            byte[] payload = new byte[total];
            for (int i = 0; i < n; i++) {
                System.arraycopy(suffixes[i], 0, payload, offsets[i], suffixes[i].length);
            }
            int payloadVec = FbNode.createBucketPayloadVector(b, payload);
            int offsetsVec = FbNode.createBucketOffsetsVector(b, offsets);
            int idsVec = FbNode.createBucketIdsVector(b, ids);
            int prefixVec = FbNode.createPrefixVector(b, new byte[0]);
            FbNode.startFbNode(b);
            FbNode.addIsBucket(b, true);
            FbNode.addPrefix(b, prefixVec);
            FbNode.addBucketPayload(b, payloadVec);
            FbNode.addBucketOffsets(b, offsetsVec);
            FbNode.addBucketIds(b, idsVec);
            b.finish(FbNode.endFbNode(b));
            return b.sizedByteArray();
        }
        MerkleRadixDictionary.Internal in = (MerkleRadixDictionary.Internal) node;
        int n = in.edgeBytes().length;
        byte[] eb = new byte[n];
        byte[] ch = new byte[n * MerkleRadixDictionary.ADDR_LEN];
        for (int i = 0; i < n; i++) {
            eb[i] = (byte) in.edgeBytes()[i];
            System.arraycopy(
                    in.children()[i].bytes(),
                    0,
                    ch,
                    i * MerkleRadixDictionary.ADDR_LEN,
                    MerkleRadixDictionary.ADDR_LEN);
        }
        int prefixVec = FbNode.createPrefixVector(b, in.prefix());
        int ebVec = FbNode.createEdgeBytesVector(b, eb);
        int chVec = FbNode.createChildrenVector(b, ch);
        FbNode.startFbNode(b);
        FbNode.addIsBucket(b, false);
        FbNode.addPrefix(b, prefixVec);
        if (in.terminalId() >= 0) {
            FbNode.addHasTerminal(b, true);
            FbNode.addTerminalId(b, in.terminalId());
        }
        FbNode.addEdgeBytes(b, ebVec);
        FbNode.addChildren(b, chVec);
        b.finish(FbNode.endFbNode(b));
        return b.sizedByteArray();
    }

    /**
     * Incremental framing for an update: encode only nodes ABSENT from {@code prev} (novel
     * subtrees), reusing the previous store's blocks — the real framing cost of an update.
     */
    public static FlatbufferPool update(
            FlatbufferPool prev, MerkleRadixDictionary dict, MerkleRadixDictionary.Addr root) {
        FlatbufferPool pool = new FlatbufferPool();
        for (MerkleRadixDictionary.Addr addr :
                DictionaryPagingReportTest.canonicalOrder(dict, root)) {
            if (pool.blocks.containsKey(addr)) continue;
            byte[] known = prev.blocks.get(addr);
            byte[] fb = known != null ? known : encode(dict.node(addr));
            pool.blocks.put(addr, fb);
            pool.totalBytes += fb.length;
        }
        return pool;
    }

    /** key → id via vtable-navigated flatbuffer accessors at every hop; −1 when absent. */
    public long get(MerkleRadixDictionary.Addr root, byte[] key) {
        MerkleRadixDictionary.Addr cur = root;
        int at = 0;
        while (true) {
            FbNode node = FbNode.getRootAsFbNode(ByteBuffer.wrap(blocks.get(cur)));
            if (node.isBucket()) {
                int n = node.bucketIdsLength();
                int lo = 0;
                int hi = n - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int cmp = compareSuffix(node, mid, key, at);
                    if (cmp == 0) return node.bucketIds(mid);
                    if (cmp < 0) lo = mid + 1;
                    else hi = mid - 1;
                }
                return -1;
            }
            int plen = node.prefixLength();
            if (key.length - at < plen) return -1;
            for (int j = 0; j < plen; j++) {
                if ((byte) node.prefix(j) != key[at + j]) return -1;
            }
            at += plen;
            if (at == key.length) return node.hasTerminal() ? node.terminalId() : -1;
            int want = key[at] & 0xFF;
            int n = node.edgeBytesLength();
            int lo = 0;
            int hi = n - 1;
            int found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int b = node.edgeBytes(mid) & 0xFF;
                if (b == want) {
                    found = mid;
                    break;
                }
                if (b < want) lo = mid + 1;
                else hi = mid - 1;
            }
            if (found < 0) return -1;
            byte[] addr = new byte[MerkleRadixDictionary.ADDR_LEN];
            int base = found * MerkleRadixDictionary.ADDR_LEN;
            for (int j = 0; j < MerkleRadixDictionary.ADDR_LEN; j++) {
                addr[j] = (byte) node.children(base + j);
            }
            cur = new MerkleRadixDictionary.Addr(addr);
            at++;
        }
    }

    private static int compareSuffix(FbNode node, int idx, byte[] key, int at) {
        int start = node.bucketOffsets(idx);
        int end = node.bucketOffsets(idx + 1);
        int suffixLen = end - start;
        int keyLen = key.length - at;
        int n = Math.min(suffixLen, keyLen);
        for (int j = 0; j < n; j++) {
            int cmp = Byte.compareUnsigned((byte) node.bucketPayload(start + j), key[at + j]);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(suffixLen, keyLen);
    }

    private FlatbufferPool() {}
}
