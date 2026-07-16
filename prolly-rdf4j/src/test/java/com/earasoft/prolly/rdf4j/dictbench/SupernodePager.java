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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The disk shape that keeps the radix benefits: SUPERNODE paging. Each disk block holds a whole
 * radix <em>subtree fragment</em> (~4 KiB budget) — prefix compression and byte-compare navigation
 * stay intact <em>inside</em> the fetched page, while the <em>between-page</em> shape becomes the
 * engine's winning disk geometry (fanout in the hundreds, 2–3 fetches per lookup instead of one
 * fetch per trie hop, which is what flipped the naive layouts in {@code DiskBench}).
 *
 * <p>Merkle at page granularity: a page's address is the SHA-256/20 of its bytes, and parent pages
 * embed child PAGE addresses in a ref table — so canonicity (same dictionary → identical page set,
 * pinned), subtree addressing, and pruned diff survive, coarsened to pages.
 *
 * <p>In-page child pointers are DELTAS relative to the referencing record's start, so a child
 * fragment embeds into its parent's fragment without offset rebasing. External pointers index the
 * page-header ref table. Inline-vs-page decision: children are inlined in edge order while the
 * running fragment size stays within budget; the rest become child pages — a deterministic function
 * of the tree, hence canonical.
 */
final class SupernodePager {

    static final int PAGE_BUDGET = 4 * 1024;
    static final int ADDR = MerkleRadixDictionary.ADDR_LEN;

    final Map<MerkleRadixDictionary.Addr, byte[]> pages = new LinkedHashMap<>();
    MerkleRadixDictionary.Addr rootPage;

    private final MessageDigest sha;

    private SupernodePager() {
        try {
            this.sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // -------------------------------------------------------------- building

    private record Fragment(byte[] bytes, List<MerkleRadixDictionary.Addr> extRefs) {}

    static SupernodePager of(MerkleRadixDictionary dict, MerkleRadixDictionary.Addr root) {
        SupernodePager pager = new SupernodePager();
        Fragment rootFragment = pager.layout(dict, dict.node(root));
        pager.rootPage = pager.sealPage(rootFragment);
        return pager;
    }

    private MerkleRadixDictionary.Addr sealPage(Fragment f) {
        int n = f.extRefs().size();
        byte[] page = new byte[2 + n * ADDR + f.bytes().length];
        page[0] = (byte) (n >>> 8);
        page[1] = (byte) n;
        for (int i = 0; i < n; i++) {
            System.arraycopy(f.extRefs().get(i).bytes(), 0, page, 2 + i * ADDR, ADDR);
        }
        System.arraycopy(f.bytes(), 0, page, 2 + n * ADDR, f.bytes().length);
        MerkleRadixDictionary.Addr addr =
                new MerkleRadixDictionary.Addr(Arrays.copyOf(sha.digest(page), ADDR));
        pages.put(addr, page);
        return addr;
    }

    private Fragment layout(MerkleRadixDictionary dict, MerkleRadixDictionary.Node node) {
        return layout(dict, node, PAGE_BUDGET);
    }

    /**
     * Lay out {@code node} into one fragment of at most {@code budget} bytes (a single record
     * larger than the budget becomes an oversized page — unavoidable, e.g. a huge bucket).
     *
     * <p>Packing strategy, measured into shape on the NCIt corpus (10k keys, 4 KiB budget): the
     * first cut inlined a child only if its <em>entire subtree fragment</em> fit — one page per
     * trie level once subtrees outgrow the budget, <b>4.94</b> fetches/lookup, hypothesis refuted.
     * Granting the running leftover greedily in edge order got <b>4.28</b> — a deep inline spine
     * for early edges that random lookups rarely follow. What meets the 2–3 hypothesis (<b>2.64</b>
     * measured) is rationing: when every child's <em>record</em> fits, inline them all and split
     * the leftover equally — the fetch count is bounded by the SHALLOWEST inlined path, so breadth
     * beats depth. The cost, honestly: more and smaller pages (3,503 × 277 B mean vs 977 × 937 B
     * greedy) — fill efficiency is the study's open question, not this class's claim. Purely a
     * function of (tree, budget): deterministic, hence canonical.
     */
    private Fragment layout(
            MerkleRadixDictionary dict, MerkleRadixDictionary.Node node, int budget) {
        if (node instanceof MerkleRadixDictionary.Bucket b) {
            return new Fragment(bucketRecord(b), new ArrayList<>());
        }
        MerkleRadixDictionary.Internal in = (MerkleRadixDictionary.Internal) node;
        int edges = in.edgeBytes().length;
        MerkleRadixDictionary.Addr[] kids = in.children();
        int recordSize = internalRecordSize(in);
        boolean[] inline = new boolean[edges];
        Fragment[] childFragments = new Fragment[edges];
        int[] floors = new int[edges];
        long floorSum = 0;
        for (int i = 0; i < edges; i++) {
            floors[i] = minimalRecordSize(dict.node(kids[i]));
            floorSum += floors[i];
        }
        int running = recordSize;
        if (edges > 0 && recordSize + floorSum <= budget) {
            // Every child record fits: inline them ALL, rationing the leftover equally so
            // inlined depth grows breadth-first — fetch count is bounded by the SHALLOWEST
            // inlined path, so an equal split beats handing one child the whole leftover.
            int share = (int) ((budget - recordSize - floorSum) / edges);
            for (int i = 0; i < edges; i++) {
                Fragment cf = layout(dict, dict.node(kids[i]), floors[i] + share);
                childFragments[i] = cf;
                inline[i] = true;
                running += cf.bytes().length;
            }
        } else {
            // Not all records fit: inline floors greedily in edge order (deterministic).
            for (int i = 0; i < edges; i++) {
                if (running + floors[i] <= budget) {
                    Fragment cf = layout(dict, dict.node(kids[i]), floors[i]);
                    childFragments[i] = cf;
                    inline[i] = true;
                    running += cf.bytes().length;
                }
            }
        }
        List<MerkleRadixDictionary.Addr> extRefs = new ArrayList<>();
        byte[] out = new byte[running];
        int childAt = recordSize;
        int p = writeInternalHeader(out, in);
        for (int i = 0; i < edges; i++) {
            out[p++] = (byte) in.edgeBytes()[i];
            if (inline[i]) {
                Fragment cf = childFragments[i];
                out[p++] = 0; // in-page delta pointer (relative to record start = offset 0 here)
                writeInt(out, p, childAt);
                System.arraycopy(cf.bytes(), 0, out, childAt, cf.bytes().length);
                // Child's external refs bubble up into this fragment's ref list; the child's
                // ext indexes must be remapped — children reference refs by ABSOLUTE index in
                // the final page table, so we rewrite them below via a two-pass would be
                // complex. Instead: ext indexes are assigned in DISCOVERY ORDER globally per
                // fragment; a child fragment's internal ext indexes are offset by the refs
                // already collected. Rewrite the child's ext-pointer indexes in place:
                offsetExtIndexes(out, childAt, cf.bytes().length, extRefs.size());
                extRefs.addAll(cf.extRefs());
                childAt += cf.bytes().length;
            } else {
                MerkleRadixDictionary.Addr pageAddr =
                        sealPage(layout(dict, dict.node(kids[i]), PAGE_BUDGET));
                out[p++] = 1; // external page pointer
                writeInt(out, p, extRefs.size());
                extRefs.add(pageAddr);
            }
            p += 4;
        }
        return new Fragment(out, extRefs);
    }

    /** The floor a node needs in-page: its own record, with every child pointer external. */
    private static int minimalRecordSize(MerkleRadixDictionary.Node n) {
        if (n instanceof MerkleRadixDictionary.Bucket b) {
            int payload = 0;
            for (byte[] s : b.suffixes()) payload += s.length;
            return 1 + 2 + 4 * (b.suffixes().length + 1) + payload + 8 * b.suffixes().length;
        }
        return internalRecordSize((MerkleRadixDictionary.Internal) n);
    }

    /** Walk a fragment's records and add {@code delta} to every external ref index. */
    private static void offsetExtIndexes(byte[] buf, int start, int len, int delta) {
        if (delta == 0) return;
        int at = start;
        while (at < start + len) {
            if (buf[at] == 'B') {
                at += bucketRecordLength(buf, at);
            } else {
                int prefixLen = readShort(buf, at + 1);
                int edges = readShort(buf, at + 3 + prefixLen + 9);
                int q = at + 3 + prefixLen + 9 + 2;
                for (int e = 0; e < edges; e++) {
                    q++; // edge byte
                    if (buf[q] == 1) {
                        int idx = readInt(buf, q + 1) + delta;
                        writeInt(buf, q + 1, idx);
                    }
                    q += 5;
                }
                at = q;
            }
        }
        // NOTE: this linear sweep relies on records being contiguously packed, which the
        // assembler guarantees (parent record, then inline child fragments in order).
    }

    // Record formats (pager-local, not the dictionary's node format):
    // 'I' prefixLen:int16 prefix hasTerminal:byte terminal:long edges:int16
    //     then per edge: edgeByte, ptrTag(0 delta | 1 ext), int32
    // 'B' <bucket record as below>
    private static int internalRecordSize(MerkleRadixDictionary.Internal in) {
        return 1 + 2 + in.prefix().length + 1 + 8 + 2 + in.edgeBytes().length * 6;
    }

    private static int writeInternalHeader(byte[] out, MerkleRadixDictionary.Internal in) {
        int p = 0;
        out[p++] = 'I';
        out[p++] = (byte) (in.prefix().length >>> 8);
        out[p++] = (byte) in.prefix().length;
        System.arraycopy(in.prefix(), 0, out, p, in.prefix().length);
        p += in.prefix().length;
        out[p++] = (byte) (in.terminalId() >= 0 ? 1 : 0);
        long t = Math.max(in.terminalId(), 0);
        for (int i = 0; i < 8; i++) out[p++] = (byte) (t >>> (56 - 8 * i));
        out[p++] = (byte) (in.edgeBytes().length >>> 8);
        out[p++] = (byte) in.edgeBytes().length;
        return p;
    }

    private static byte[] bucketRecord(MerkleRadixDictionary.Bucket b) {
        byte[][] suffixes = b.suffixes();
        long[] ids = b.ids();
        int n = suffixes.length;
        int payload = 0;
        for (byte[] s : suffixes) payload += s.length;
        byte[] out = new byte[1 + 2 + 4 * (n + 1) + payload + 8 * n];
        int p = 0;
        out[p++] = 'B';
        out[p++] = (byte) (n >>> 8);
        out[p++] = (byte) n;
        int off = 0;
        for (int i = 0; i < n; i++) {
            writeInt(out, p + 4 * i, off);
            off += suffixes[i].length;
        }
        writeInt(out, p + 4 * n, off);
        p += 4 * (n + 1);
        for (byte[] s : suffixes) {
            System.arraycopy(s, 0, out, p, s.length);
            p += s.length;
        }
        for (long id : ids) {
            for (int i = 0; i < 8; i++) out[p++] = (byte) (id >>> (56 - 8 * i));
        }
        return out;
    }

    private static int bucketRecordLength(byte[] buf, int at) {
        int n = readShort(buf, at + 1);
        int payload = readInt(buf, at + 3 + 4 * n);
        return 1 + 2 + 4 * (n + 1) + payload + 8 * n;
    }

    private static void writeInt(byte[] out, int at, int v) {
        out[at] = (byte) (v >>> 24);
        out[at + 1] = (byte) (v >>> 16);
        out[at + 2] = (byte) (v >>> 8);
        out[at + 3] = (byte) v;
    }

    private static int readInt(byte[] b, int at) {
        return (b[at] & 0xFF) << 24
                | (b[at + 1] & 0xFF) << 16
                | (b[at + 2] & 0xFF) << 8
                | (b[at + 3] & 0xFF);
    }

    private static int readShort(byte[] b, int at) {
        return (b[at] & 0xFF) << 8 | (b[at + 1] & 0xFF);
    }

    private static long readLong(byte[] b, int at) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[at + i] & 0xFF);
        return v;
    }

    // --------------------------------------------------------------- lookup

    /** Page fetcher abstraction: in-memory map for tests, RocksDB in the disk bench. */
    interface PageSource {
        byte[] fetch(MerkleRadixDictionary.Addr page);
    }

    PageSource inMemory() {
        return pages::get;
    }

    /** key → id (−1 absent); {@code fetches[0]} accumulates page fetches when non-null. */
    static long get(
            PageSource source, MerkleRadixDictionary.Addr rootPage, byte[] key, int[] fetches) {
        byte[] page = source.fetch(rootPage);
        if (fetches != null) fetches[0]++;
        int refCount = readShort(page, 0);
        int record = 2 + refCount * ADDR;
        int at = 0;
        while (true) {
            if (page[record] == 'B') {
                int n = readShort(page, record + 1);
                int tableAt = record + 3;
                int payloadAt = tableAt + 4 * (n + 1);
                int idsAt = payloadAt + readInt(page, tableAt + 4 * n);
                int lo = 0;
                int hi = n - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int s = payloadAt + readInt(page, tableAt + 4 * mid);
                    int e = payloadAt + readInt(page, tableAt + 4 * (mid + 1));
                    int cmp = Arrays.compareUnsigned(page, s, e, key, at, key.length);
                    if (cmp == 0) return readLong(page, idsAt + 8 * mid);
                    if (cmp < 0) lo = mid + 1;
                    else hi = mid - 1;
                }
                return -1;
            }
            int prefixLen = readShort(page, record + 1);
            int prefixAt = record + 3;
            if (key.length - at < prefixLen
                    || !Arrays.equals(
                            page, prefixAt, prefixAt + prefixLen, key, at, at + prefixLen)) {
                return -1;
            }
            at += prefixLen;
            int p = prefixAt + prefixLen;
            boolean hasTerminal = page[p] == 1;
            long terminal = readLong(page, p + 1);
            p += 9;
            int edges = readShort(page, p);
            p += 2;
            if (at == key.length) return hasTerminal ? terminal : -1;
            int want = key[at] & 0xFF;
            int found = -1;
            for (int e = 0; e < edges; e++) {
                int b = page[p + e * 6] & 0xFF;
                if (b == want) {
                    found = e;
                    break;
                }
                if (b > want) break;
            }
            if (found < 0) return -1;
            int ptrAt = p + found * 6 + 1;
            at++;
            if (page[ptrAt] == 0) {
                record = record + readInt(page, ptrAt + 1);
            } else {
                int refIdx = readInt(page, ptrAt + 1);
                MerkleRadixDictionary.Addr next =
                        new MerkleRadixDictionary.Addr(
                                Arrays.copyOfRange(
                                        page, 2 + refIdx * ADDR, 2 + (refIdx + 1) * ADDR));
                page = source.fetch(next);
                if (fetches != null) fetches[0]++;
                refCount = readShort(page, 0);
                record = 2 + refCount * ADDR;
            }
        }
    }
}
