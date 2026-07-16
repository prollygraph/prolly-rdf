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

import com.dolthub.prolly.RollingHashSplitter;
import java.lang.foreign.MemorySegment;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chunking strategies for the id → term REVERSE index, racing three ways to slice an append-only
 * log of dense-ordinal entries into content-addressed chunks:
 *
 * <ul>
 *   <li>{@link #fixedCount}: every C entries — locate is {@code id / C}, O(1), no hashing of any
 *       kind at the boundary decision;
 *   <li>{@link #byteBudget}: close at ≥ targetBytes — page-sized chunks, fence-array locate;
 *   <li>{@link #contentDefined}: the production {@code RollingHashSplitter} over serialized entry
 *       bytes, consulted between entries — the classic prolly discipline.
 * </ul>
 *
 * <p>The structural point under test: an append-only ordinal-keyed log has NO shift problem — an
 * entry's position never changes, so every strategy above is history-independent (a pure function
 * of the id → term mapping) and append-local (old boundaries never move). CDC's edit healing — the
 * property that justifies its per-byte compute — has nothing to heal here.
 *
 * <p>Chunk layout: {@code count:int32, offsets:(count+1)×int32, payload}. Entry i of a chunk is
 * {@code payload[offsets[i], offsets[i+1])}.
 */
final class ReverseStores {

    record Chunk(byte[] bytes, int firstId, int count) {}

    /** A built reverse store: chunks + (for non-fixed strategies) a first-id fence array. */
    record Store(List<Chunk> chunks, int[] fences, int fixedCount, long totalBytes) {

        byte[] get(int id) {
            int chunkIdx;
            if (fixedCount > 0) {
                chunkIdx = id / fixedCount;
            } else {
                int lo = 0;
                int hi = fences.length - 1;
                while (lo < hi) {
                    int mid = (lo + hi + 1) >>> 1;
                    if (fences[mid] <= id) lo = mid;
                    else hi = mid - 1;
                }
                chunkIdx = lo;
            }
            Chunk c = chunks.get(chunkIdx);
            int i = id - c.firstId();
            int count = readInt(c.bytes(), 0);
            if (i < 0 || i >= count) return null;
            int off = readInt(c.bytes(), 4 + 4 * i);
            int end = readInt(c.bytes(), 4 + 4 * (i + 1));
            int payloadBase = 4 + 4 * (count + 1);
            return Arrays.copyOfRange(c.bytes(), payloadBase + off, payloadBase + end);
        }

        /** Content addresses of every chunk — for append-reuse accounting. */
        List<String> addresses() {
            try {
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                List<String> out = new ArrayList<>(chunks.size());
                for (Chunk c : chunks) {
                    out.add(java.util.HexFormat.of().formatHex(sha.digest(c.bytes()), 0, 20));
                }
                return out;
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24
                | (b[at + 1] & 0xFF) << 16
                | (b[at + 2] & 0xFF) << 8
                | (b[at + 3] & 0xFF);
    }

    private static Chunk seal(List<byte[]> entries, int firstId) {
        int count = entries.size();
        int payload = 0;
        for (byte[] e : entries) payload += e.length;
        byte[] out = new byte[4 + 4 * (count + 1) + payload];
        writeInt(out, 0, count);
        int off = 0;
        for (int i = 0; i < count; i++) {
            writeInt(out, 4 + 4 * i, off);
            off += entries.get(i).length;
        }
        writeInt(out, 4 + 4 * count, off);
        int p = 4 + 4 * (count + 1);
        for (byte[] e : entries) {
            System.arraycopy(e, 0, out, p, e.length);
            p += e.length;
        }
        return new Chunk(out, firstId, count);
    }

    private static void writeInt(byte[] out, int at, int v) {
        out[at] = (byte) (v >>> 24);
        out[at + 1] = (byte) (v >>> 16);
        out[at + 2] = (byte) (v >>> 8);
        out[at + 3] = (byte) v;
    }

    interface Closer {
        /** True when the chunk should close AFTER this entry. */
        boolean closes(byte[] entry, int entriesInChunk, int bytesInChunk);
    }

    static Store build(byte[][] terms, Closer closer, int fixedCount) {
        List<Chunk> chunks = new ArrayList<>();
        List<Integer> fences = new ArrayList<>();
        List<byte[]> current = new ArrayList<>();
        int bytes = 0;
        int firstId = 0;
        long total = 0;
        for (int id = 0; id < terms.length; id++) {
            current.add(terms[id]);
            bytes += terms[id].length;
            if (closer.closes(terms[id], current.size(), bytes)) {
                Chunk c = seal(current, firstId);
                chunks.add(c);
                fences.add(firstId);
                total += c.bytes().length;
                firstId = id + 1;
                current = new ArrayList<>();
                bytes = 0;
            }
        }
        if (!current.isEmpty()) {
            Chunk c = seal(current, firstId);
            chunks.add(c);
            fences.add(firstId);
            total += c.bytes().length;
        }
        int[] f = fences.stream().mapToInt(Integer::intValue).toArray();
        return new Store(chunks, f, fixedCount, total);
    }

    static Store fixedCount(byte[][] terms, int c) {
        return build(terms, (e, n, b) -> n >= c, c);
    }

    static Store byteBudget(byte[][] terms, int targetBytes) {
        return build(terms, (e, n, b) -> b >= targetBytes, 0);
    }

    static Store contentDefined(byte[][] terms) {
        RollingHashSplitter splitter = new RollingHashSplitter(0);
        return build(
                terms,
                (entry, n, b) -> {
                    splitter.append(MemorySegment.ofArray(entry), null);
                    if (splitter.crossedBoundary()) {
                        splitter.reset();
                        return true;
                    }
                    return false;
                },
                0);
    }

    private ReverseStores() {}
}
