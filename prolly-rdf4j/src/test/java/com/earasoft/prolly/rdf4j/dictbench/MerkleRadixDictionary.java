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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A Deterministic Merkle Radix dictionary — the Java twin of the Python reference ({@code
 * prolly-python-notebooks/merkle_radix_dict.py} in the engine repository), built to benchmark the
 * "Merkle Radix Prolly Tree" dictionary proposal against the engine's REAL dictionary ({@code
 * prolly-codec}'s hash-keyed {@code Dictionary}).
 *
 * <p>Structure: a path-compressed radix trie over key bytes mapping each key to a long id; every
 * node content-addressed (canonical serialization → SHA-256 truncated to 20 bytes) in an
 * append-only address → node pool. The compressed trie of a key set is unique, and edges serialize
 * sorted by byte, so the root is a pure function of the {key → id} mapping — history independence
 * needs no chunker. Pinned by {@link MerkleRadixDictionaryTest}: fold-of-inserts in shuffled orders
 * is byte-identical to the canonical batch build.
 *
 * <p><b>Study-scope</b> (test tree, like {@code chunkbench}) — this is a benchmark candidate, not a
 * production primitive; the production-primitive promotion gate applies if that ever changes.
 */
public final class MerkleRadixDictionary {

    static final int ADDR_LEN = 20;

    /** Content address — a 20-byte truncated SHA-256 with value equality. */
    public record Addr(byte[] bytes) {
        public Addr {
            if (bytes.length != ADDR_LEN) throw new IllegalArgumentException();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Addr(byte[] other) && Arrays.equals(bytes, other);
        }

        @Override
        public int hashCode() {
            // The address is itself uniform hash output; fold the first bytes.
            return (bytes[0] & 0xFF)
                    | (bytes[1] & 0xFF) << 8
                    | (bytes[2] & 0xFF) << 16
                    | (bytes[3] & 0xFF) << 24;
        }
    }

    sealed interface Node permits Bucket, Internal {
        byte[] serialize();
    }

    /**
     * A terminal holding up to {@code bucketSize} (suffix, id) entries, sorted by suffix — the
     * depth lever: the bottom of the trie (the last branching levels distinguishing few keys)
     * collapses into one binary-searched node. Layout: 'B', count(2), offset table (int32 per
     * entry, relative to payload start), then per entry int32 suffixLen + suffix + int64 id.
     */
    record Bucket(byte[][] suffixes, long[] ids) implements Node {
        @Override
        public byte[] serialize() {
            int n = suffixes.length;
            int payload = 0;
            for (byte[] s : suffixes) payload += 4 + s.length + 8;
            byte[] out = new byte[1 + 2 + 4 * n + payload];
            int p = 0;
            out[p++] = 'B';
            out[p++] = (byte) (n >>> 8);
            out[p++] = (byte) n;
            int off = 0;
            for (int i = 0; i < n; i++) {
                writeInt(out, p + 4 * i, off);
                off += 4 + suffixes[i].length + 8;
            }
            p += 4 * n;
            for (int i = 0; i < n; i++) {
                writeInt(out, p, suffixes[i].length);
                System.arraycopy(suffixes[i], 0, out, p + 4, suffixes[i].length);
                writeLong(out, p + 4 + suffixes[i].length, ids[i]);
                p += 4 + suffixes[i].length + 8;
            }
            return out;
        }
    }

    /** edges: byte value (0..255, ascending) → child address; terminalId −1 = absent. */
    record Internal(byte[] prefix, long terminalId, int[] edgeBytes, Addr[] children)
            implements Node {
        @Override
        public byte[] serialize() {
            int n = edgeBytes.length;
            byte[] out = new byte[1 + 4 + prefix.length + 9 + 2 + n * (1 + ADDR_LEN)];
            int p = 0;
            out[p++] = 'I';
            writeInt(out, p, prefix.length);
            p += 4;
            System.arraycopy(prefix, 0, out, p, prefix.length);
            p += prefix.length;
            out[p++] = (byte) (terminalId >= 0 ? 1 : 0);
            writeLong(out, p, terminalId >= 0 ? terminalId : 0L);
            p += 8;
            out[p++] = (byte) (n >>> 8);
            out[p++] = (byte) n;
            for (int i = 0; i < n; i++) {
                out[p++] = (byte) edgeBytes[i];
                System.arraycopy(children[i].bytes(), 0, out, p, ADDR_LEN);
                p += ADDR_LEN;
            }
            return out;
        }
    }

    private static void writeInt(byte[] out, int at, int v) {
        out[at] = (byte) (v >>> 24);
        out[at + 1] = (byte) (v >>> 16);
        out[at + 2] = (byte) (v >>> 8);
        out[at + 3] = (byte) v;
    }

    private static void writeLong(byte[] out, int at, long v) {
        for (int i = 0; i < 8; i++) out[at + i] = (byte) (v >>> (56 - 8 * i));
    }

    private final Map<Addr, Node> pool = new HashMap<>();
    private final MessageDigest sha;
    private final int bucketSize;

    public MerkleRadixDictionary() {
        this(1);
    }

    /** {@code bucketSize} = max entries per terminal node (1 = classic one-leaf-per-key). */
    public MerkleRadixDictionary(int bucketSize) {
        if (bucketSize < 1) throw new IllegalArgumentException();
        this.bucketSize = bucketSize;
        try {
            this.sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public int nodeCount() {
        return pool.size();
    }

    // ------------------------------------------------------- subtree addressing

    /**
     * Address of the minimal subtree containing every key that starts with {@code prefix} —
     * O(depth) resolution, null when no key matches. Equal subtree addresses across two dictionary
     * versions prove the whole namespace identical (the Merkle property at namespace granularity —
     * an operation a hashed-TermId dictionary cannot express, because a namespace's terms scatter
     * uniformly across its id space).
     *
     * <p>Granularity note: when the prefix ends inside a node's compressed prefix or inside a
     * bucket, the returned node may also cover keys OUTSIDE the queried prefix (the minimal
     * ENCLOSING subtree); {@link #entriesUnder} filters exactly.
     */
    public Addr subtreeAddress(Addr root, byte[] prefix) {
        Addr cur = root;
        int at = 0;
        while (true) {
            Node node = pool.get(cur);
            if (node instanceof Bucket(byte[][] suffixes, long[] ids)) {
                for (byte[] s : suffixes) {
                    if (startsWith(s, prefix, at)) return cur;
                }
                return null;
            }
            Internal in = (Internal) node;
            byte[] np = in.prefix();
            int remaining = prefix.length - at;
            if (remaining <= np.length) {
                // Prefix ends at or inside this node's compressed prefix.
                return Arrays.equals(np, 0, remaining, prefix, at, prefix.length) ? cur : null;
            }
            if (!Arrays.equals(np, 0, np.length, prefix, at, at + np.length)) return null;
            at += np.length;
            int idx = Arrays.binarySearch(in.edgeBytes(), prefix[at] & 0xFF);
            if (idx < 0) return null;
            cur = in.children()[idx];
            at++;
        }
    }

    private static boolean startsWith(byte[] suffix, byte[] prefix, int at) {
        int need = prefix.length - at;
        return suffix.length >= need && Arrays.equals(suffix, 0, need, prefix, at, prefix.length);
    }

    /** All (key, id) entries whose key starts with {@code prefix}, in sorted key order. */
    public TreeMap<byte[], Long> entriesUnder(Addr root, byte[] prefix) {
        TreeMap<byte[], Long> out = new TreeMap<>(Arrays::compareUnsigned);
        Addr cur = root;
        int at = 0;
        byte[] consumed = new byte[0];
        while (true) {
            Node node = pool.get(cur);
            if (node instanceof Bucket(byte[][] suffixes, long[] ids)) {
                for (int i = 0; i < suffixes.length; i++) {
                    byte[] full = concatKey(consumed, suffixes[i]);
                    if (startsWith(full, prefix, 0)) out.put(full, ids[i]);
                }
                return out;
            }
            Internal in = (Internal) node;
            byte[] np = in.prefix();
            int remaining = prefix.length - at;
            if (remaining <= np.length) {
                if (!Arrays.equals(np, 0, remaining, prefix, at, prefix.length)) return out;
                collect(cur, consumed, out);
                return out;
            }
            if (!Arrays.equals(np, 0, np.length, prefix, at, at + np.length)) return out;
            consumed = concatKey(consumed, np);
            at += np.length;
            int idx = Arrays.binarySearch(in.edgeBytes(), prefix[at] & 0xFF);
            if (idx < 0) return out;
            consumed = concatKey(consumed, new byte[] {prefix[at]});
            cur = in.children()[idx];
            at++;
        }
    }

    private void collect(Addr addr, byte[] consumed, TreeMap<byte[], Long> out) {
        Node node = pool.get(addr);
        if (node instanceof Bucket(byte[][] suffixes, long[] ids)) {
            for (int i = 0; i < suffixes.length; i++) {
                out.put(concatKey(consumed, suffixes[i]), ids[i]);
            }
            return;
        }
        Internal in = (Internal) node;
        byte[] base = concatKey(consumed, in.prefix());
        if (in.terminalId() >= 0) out.put(base, in.terminalId());
        for (int e = 0; e < in.edgeBytes().length; e++) {
            collect(in.children()[e], concatKey(base, new byte[] {(byte) in.edgeBytes()[e]}), out);
        }
    }

    private static byte[] concatKey(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // ------------------------------------------------------------------- diff

    /** Diff outcome: key → [oldId, newId] with −1 for absent; plus prune accounting. */
    public record DiffResult(TreeMap<byte[], long[]> changes, int visited, int pruned) {}

    /**
     * Version diff with Merkle pruning: equal child addresses are skipped without loading (the
     * git-diff motion). Both roots must live in this dictionary's pool (e.g. a base root and the
     * root returned by {@link #insertAll} on a {@link #fork}-shared pool).
     */
    public DiffResult diff(Addr rootA, Addr rootB) {
        TreeMap<byte[], long[]> changes = new TreeMap<>(Arrays::compareUnsigned);
        int[] counters = new int[2]; // visited, pruned
        diffWalk(rootA, rootB, new byte[0], changes, counters);
        return new DiffResult(changes, counters[0], counters[1]);
    }

    private void diffWalk(
            Addr a, Addr b, byte[] consumed, TreeMap<byte[], long[]> changes, int[] counters) {
        if (a != null && a.equals(b)) {
            counters[1]++;
            return; // identical subtree — pruned unread
        }
        diffNodes(
                a == null ? null : pool.get(a),
                a,
                b == null ? null : pool.get(b),
                b,
                consumed,
                changes,
                counters);
    }

    private void diffNodes(
            Node na,
            Addr aAddr,
            Node nb,
            Addr bAddr,
            byte[] consumed,
            TreeMap<byte[], long[]> changes,
            int[] counters) {
        if (aAddr != null && aAddr.equals(bAddr)) {
            counters[1]++;
            return;
        }
        counters[0]++;
        if (na == null || nb == null) {
            Node present = na == null ? nb : na;
            TreeMap<byte[], Long> entries = new TreeMap<>(Arrays::compareUnsigned);
            collectNode(present, consumed, entries);
            for (Map.Entry<byte[], Long> e : entries.entrySet()) {
                changes.put(
                        e.getKey(),
                        na == null ? new long[] {-1, e.getValue()} : new long[] {e.getValue(), -1});
            }
            return;
        }
        if (na instanceof Internal ia && nb instanceof Internal ib) {
            byte[] pa = ia.prefix();
            byte[] pb = ib.prefix();
            int l = 0;
            int max = Math.min(pa.length, pb.length);
            while (l < max && pa[l] == pb[l]) l++;
            if (l == pa.length && l == pb.length) {
                byte[] base = concatKey(consumed, pa);
                if (ia.terminalId() != ib.terminalId()) {
                    changes.put(base, new long[] {ia.terminalId(), ib.terminalId()});
                }
                int i = 0;
                int j = 0;
                while (i < ia.edgeBytes().length || j < ib.edgeBytes().length) {
                    int ba = i < ia.edgeBytes().length ? ia.edgeBytes()[i] : Integer.MAX_VALUE;
                    int bb = j < ib.edgeBytes().length ? ib.edgeBytes()[j] : Integer.MAX_VALUE;
                    int edge = Math.min(ba, bb);
                    byte[] childBase = concatKey(base, new byte[] {(byte) edge});
                    diffWalk(
                            ba == edge ? ia.children()[i] : null,
                            bb == edge ? ib.children()[j] : null,
                            childBase,
                            changes,
                            counters);
                    if (ba == edge) i++;
                    if (bb == edge) j++;
                }
                return;
            }
            if (l == pb.length && l < pa.length) {
                // B's node ends first: align A VIRTUALLY under B's edge pa[l] and walk B's
                // edges — this is the prefix-split case the coarse fallback made O(subtree).
                Internal aVirtual =
                        new Internal(
                                Arrays.copyOfRange(pa, l + 1, pa.length),
                                ia.terminalId(),
                                ia.edgeBytes(),
                                ia.children());
                byte[] base = concatKey(consumed, pb);
                if (ib.terminalId() >= 0) {
                    changes.put(base, new long[] {-1, ib.terminalId()});
                }
                int aEdge = pa[l] & 0xFF;
                boolean matched = false;
                for (int j = 0; j < ib.edgeBytes().length; j++) {
                    byte[] childBase = concatKey(base, new byte[] {(byte) ib.edgeBytes()[j]});
                    if (ib.edgeBytes()[j] == aEdge) {
                        matched = true;
                        diffNodes(
                                aVirtual,
                                null,
                                pool.get(ib.children()[j]),
                                ib.children()[j],
                                childBase,
                                changes,
                                counters);
                    } else {
                        diffWalk(null, ib.children()[j], childBase, changes, counters);
                    }
                }
                if (!matched) {
                    diffNodes(
                            aVirtual,
                            null,
                            null,
                            null,
                            concatKey(base, new byte[] {(byte) aEdge}),
                            changes,
                            counters);
                }
                return;
            }
            if (l == pa.length && l < pb.length) {
                // Symmetric: A's node ends first.
                Internal bVirtual =
                        new Internal(
                                Arrays.copyOfRange(pb, l + 1, pb.length),
                                ib.terminalId(),
                                ib.edgeBytes(),
                                ib.children());
                byte[] base = concatKey(consumed, pa);
                if (ia.terminalId() >= 0) {
                    changes.put(base, new long[] {ia.terminalId(), -1});
                }
                int bEdge = pb[l] & 0xFF;
                boolean matched = false;
                for (int i = 0; i < ia.edgeBytes().length; i++) {
                    byte[] childBase = concatKey(base, new byte[] {(byte) ia.edgeBytes()[i]});
                    if (ia.edgeBytes()[i] == bEdge) {
                        matched = true;
                        diffNodes(
                                pool.get(ia.children()[i]),
                                ia.children()[i],
                                bVirtual,
                                null,
                                childBase,
                                changes,
                                counters);
                    } else {
                        diffWalk(ia.children()[i], null, childBase, changes, counters);
                    }
                }
                if (!matched) {
                    diffNodes(
                            null,
                            null,
                            bVirtual,
                            null,
                            concatKey(base, new byte[] {(byte) bEdge}),
                            changes,
                            counters);
                }
                return;
            }
            // Prefixes diverge at l: the two key sets are DISJOINT — enumerating both IS
            // the answer, not a shortcut fallback.
        }
        TreeMap<byte[], Long> left = new TreeMap<>(Arrays::compareUnsigned);
        TreeMap<byte[], Long> right = new TreeMap<>(Arrays::compareUnsigned);
        collectNode(na, consumed, left);
        collectNode(nb, consumed, right);
        for (Map.Entry<byte[], Long> e : left.entrySet()) {
            Long other = right.get(e.getKey());
            if (other == null) changes.put(e.getKey(), new long[] {e.getValue(), -1});
            else if (!other.equals(e.getValue())) {
                changes.put(e.getKey(), new long[] {e.getValue(), other});
            }
        }
        for (Map.Entry<byte[], Long> e : right.entrySet()) {
            if (!left.containsKey(e.getKey())) {
                changes.put(e.getKey(), new long[] {-1, e.getValue()});
            }
        }
    }

    private void collectNode(Node node, byte[] consumed, TreeMap<byte[], Long> out) {
        if (node instanceof Bucket(byte[][] suffixes, long[] ids)) {
            for (int i = 0; i < suffixes.length; i++) {
                out.put(concatKey(consumed, suffixes[i]), ids[i]);
            }
            return;
        }
        Internal in = (Internal) node;
        byte[] base = concatKey(consumed, in.prefix());
        if (in.terminalId() >= 0) out.put(base, in.terminalId());
        for (int e = 0; e < in.edgeBytes().length; e++) {
            byte[] childBase = concatKey(base, new byte[] {(byte) in.edgeBytes()[e]});
            collectNode(pool.get(in.children()[e]), childBase, out);
        }
    }

    // ------------------------------------------------------------------ merge

    /** Merge outcome: the merged root (null when conflicted) + key → [oursId, theirsId]. */
    public record MergeOutcome(Addr root, TreeMap<byte[], long[]> conflicts) {}

    /**
     * Three-way merge via pruned diffs + one batched apply. A conflict is a key both sides changed
     * to different ids. Deletions are out of scope (dictionary terms are immortal in this study;
     * the diff detects removals but this merge does not apply them — documented limitation,
     * matching the append/update workloads measured elsewhere).
     */
    public MergeOutcome merge(Addr base, Addr ours, Addr theirs) {
        DiffResult ourDiff = diff(base, ours);
        DiffResult theirDiff = diff(base, theirs);
        TreeMap<byte[], Long> apply = new TreeMap<>(Arrays::compareUnsigned);
        TreeMap<byte[], long[]> conflicts = new TreeMap<>(Arrays::compareUnsigned);
        for (Map.Entry<byte[], long[]> e : theirDiff.changes().entrySet()) {
            long theirsNew = e.getValue()[1];
            if (theirsNew == -1) continue; // removal application out of scope
            long[] ourChange = ourDiff.changes().get(e.getKey());
            if (ourChange == null) {
                apply.put(e.getKey(), theirsNew);
            } else if (ourChange[1] != theirsNew) {
                conflicts.put(e.getKey(), new long[] {ourChange[1], theirsNew});
            } // equal change on both sides → already present in ours
        }
        if (!conflicts.isEmpty()) return new MergeOutcome(null, conflicts);
        return new MergeOutcome(insertAll(ours, apply), conflicts);
    }

    /** Package-private node access for the paging/report layers. */
    Node node(Addr addr) {
        return pool.get(addr);
    }

    public long storedBytes() {
        long total = 0;
        for (Node n : pool.values()) total += n.serialize().length;
        return total;
    }

    private Addr put(Node node) {
        byte[] digest = sha.digest(node.serialize());
        Addr a = new Addr(Arrays.copyOf(digest, ADDR_LEN));
        pool.put(a, node);
        return a;
    }

    // ------------------------------------------------------------ batch build

    /** Canonical batch build over the (sorted) key → id mapping; returns the root. */
    public Addr build(TreeMap<byte[], Long> sortedEntries) {
        List<byte[]> keys = new ArrayList<>(sortedEntries.keySet());
        long[] ids = new long[keys.size()];
        int i = 0;
        for (long v : sortedEntries.values()) ids[i++] = v;
        return build(keys, ids, 0, keys.size(), 0);
    }

    private Addr build(List<byte[]> keys, long[] ids, int from, int to, int depth) {
        if (to - from <= bucketSize) {
            byte[][] suffixes = new byte[to - from][];
            long[] bids = new long[to - from];
            for (int i = from; i < to; i++) {
                byte[] k = keys.get(i);
                suffixes[i - from] = Arrays.copyOfRange(k, depth, k.length);
                bids[i - from] = ids[i];
            }
            return put(new Bucket(suffixes, bids));
        }
        int lcp = lcpLen(keys.get(from), keys.get(to - 1), depth);
        byte[] prefix = Arrays.copyOfRange(keys.get(from), depth, depth + lcp);
        int at = depth + lcp;
        long terminal = -1;
        int i = from;
        if (keys.get(i).length == at) {
            terminal = ids[i];
            i++;
        }
        List<Integer> edgeBytes = new ArrayList<>();
        List<Addr> children = new ArrayList<>();
        while (i < to) {
            int b = keys.get(i)[at] & 0xFF;
            int j = i;
            while (j < to && (keys.get(j)[at] & 0xFF) == b) j++;
            edgeBytes.add(b);
            children.add(build(keys, ids, i, j, at + 1)); // the edge consumes its byte
            i = j;
        }
        int[] eb = edgeBytes.stream().mapToInt(Integer::intValue).toArray();
        return put(new Internal(prefix, terminal, eb, children.toArray(new Addr[0])));
    }

    private static int lcpLen(byte[] a, byte[] b, int from) {
        int n = 0;
        while (from + n < a.length && from + n < b.length && a[from + n] == b[from + n]) n++;
        return n;
    }

    // ---------------------------------------------------------------- insert

    /** Path-copying insert; hypothesis-pinned equal to batch build under any order. */
    public Addr insert(Addr root, byte[] key, long id) {
        return insertAt(root, key, 0, id);
    }

    private Addr insertAt(Addr addr, byte[] key, int at, long id) {
        Node node = pool.get(addr);
        if (node instanceof Bucket(byte[][] suffixes, long[] ids0)) {
            byte[] rest = Arrays.copyOfRange(key, at, key.length);
            TreeMap<byte[], Long> merged = new TreeMap<>(Arrays::compareUnsigned);
            for (int i = 0; i < suffixes.length; i++) merged.put(suffixes[i], ids0[i]);
            merged.put(rest, id);
            List<byte[]> keys = new ArrayList<>(merged.keySet());
            long[] ids = new long[keys.size()];
            int i = 0;
            for (long v : merged.values()) ids[i++] = v;
            // <= bucketSize stays a bucket; overflow rebuilds the subtree canonically —
            // the same rule the batch build applies, so fold == batch is preserved.
            return build(keys, ids, 0, keys.size(), 0);
        }
        Internal in = (Internal) node;
        byte[] prefix = in.prefix();
        int n = 0;
        while (n < prefix.length && at + n < key.length && prefix[n] == key[at + n]) n++;
        if (n < prefix.length) {
            // Diverges inside the prefix: split it; the new edge consumes prefix[n].
            Addr lower =
                    put(
                            new Internal(
                                    Arrays.copyOfRange(prefix, n + 1, prefix.length),
                                    in.terminalId(),
                                    in.edgeBytes(),
                                    in.children()));
            byte[] upperPrefix = Arrays.copyOfRange(prefix, 0, n);
            if (at + n == key.length) {
                return put(
                        new Internal(
                                upperPrefix, id, new int[] {prefix[n] & 0xFF}, new Addr[] {lower}));
            }
            Addr leaf =
                    put(
                            new Bucket(
                                    new byte[][] {Arrays.copyOfRange(key, at + n + 1, key.length)},
                                    new long[] {id}));
            int pb = prefix[n] & 0xFF;
            int kb = key[at + n] & 0xFF;
            int[] eb = pb < kb ? new int[] {pb, kb} : new int[] {kb, pb};
            Addr[] ch = pb < kb ? new Addr[] {lower, leaf} : new Addr[] {leaf, lower};
            return put(new Internal(upperPrefix, -1, eb, ch));
        }
        int rest = at + n;
        if (rest == key.length) return put(new Internal(prefix, id, in.edgeBytes(), in.children()));
        int b = key[rest] & 0xFF;
        int idx = Arrays.binarySearch(in.edgeBytes(), b);
        int[] eb;
        Addr[] ch;
        if (idx >= 0) {
            Addr child = insertAt(in.children()[idx], key, rest + 1, id);
            eb = in.edgeBytes().clone();
            ch = in.children().clone();
            ch[idx] = child;
        } else {
            int ins = -idx - 1;
            Addr leaf =
                    put(
                            new Bucket(
                                    new byte[][] {Arrays.copyOfRange(key, rest + 1, key.length)},
                                    new long[] {id}));
            eb = new int[in.edgeBytes().length + 1];
            ch = new Addr[in.children().length + 1];
            System.arraycopy(in.edgeBytes(), 0, eb, 0, ins);
            System.arraycopy(in.children(), 0, ch, 0, ins);
            eb[ins] = b;
            ch[ins] = leaf;
            System.arraycopy(in.edgeBytes(), ins, eb, ins + 1, in.edgeBytes().length - ins);
            System.arraycopy(in.children(), ins, ch, ins + 1, in.children().length - ins);
        }
        return put(new Internal(prefix, in.terminalId(), eb, ch));
    }

    /** Single-entry bootstrap root (for the insert-fold path). */
    public Addr singleton(byte[] key, long id) {
        return put(new Bucket(new byte[][] {key}, new long[] {id}));
    }

    // ---------------------------------------------------------------- lookup

    /** key → id, or −1 when absent. */
    public long get(Addr root, byte[] key) {
        Addr cur = root;
        int at = 0;
        while (true) {
            Node node = pool.get(cur);
            if (node instanceof Bucket(byte[][] suffixes, long[] ids)) {
                int lo = 0;
                int hi = suffixes.length - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int cmp =
                            Arrays.compareUnsigned(
                                    suffixes[mid], 0, suffixes[mid].length, key, at, key.length);
                    if (cmp == 0) return ids[mid];
                    if (cmp < 0) lo = mid + 1;
                    else hi = mid - 1;
                }
                return -1;
            }
            Internal in = (Internal) node;
            byte[] prefix = in.prefix();
            if (key.length - at < prefix.length
                    || !Arrays.equals(prefix, 0, prefix.length, key, at, at + prefix.length)) {
                return -1;
            }
            at += prefix.length;
            if (at == key.length) return in.terminalId();
            int idx = Arrays.binarySearch(in.edgeBytes(), key[at] & 0xFF);
            if (idx < 0) return -1;
            cur = in.children()[idx];
            at++;
        }
    }

    // ------------------------------------------------------------ batched insert

    /**
     * Insert a whole batch with ONE hash per touched node — the amortization the per-insert fold
     * lacks (which re-hashes O(depth) nodes per key). Untouched subtrees keep their addresses and
     * are never re-serialized. Canonicity contract: identical root to folding the same entries one
     * at a time (pinned in the test in random chunkings).
     */
    public Addr insertAll(Addr root, TreeMap<byte[], Long> batch) {
        if (batch.isEmpty()) return root;
        List<byte[]> keys = new ArrayList<>(batch.keySet());
        long[] ids = new long[keys.size()];
        int i = 0;
        for (long v : batch.values()) ids[i++] = v;
        return insertAllAt(root, keys, ids, 0, keys.size(), 0);
    }

    private Addr insertAllAt(Addr addr, List<byte[]> keys, long[] ids, int from, int to, int at) {
        if (from >= to) return addr;
        Node node = pool.get(addr);
        if (node instanceof Bucket(byte[][] suffixes, long[] bids)) {
            TreeMap<byte[], Long> merged = new TreeMap<>(Arrays::compareUnsigned);
            for (int i = 0; i < suffixes.length; i++) merged.put(suffixes[i], bids[i]);
            for (int i = from; i < to; i++) {
                byte[] k = keys.get(i);
                merged.put(Arrays.copyOfRange(k, at, k.length), ids[i]);
            }
            List<byte[]> mk = new ArrayList<>(merged.keySet());
            long[] mi = new long[mk.size()];
            int i = 0;
            for (long v : merged.values()) mi[i++] = v;
            return build(mk, mi, 0, mk.size(), 0);
        }
        Internal in = (Internal) node;
        byte[] prefix = in.prefix();
        // Minimal divergence of any batch key inside this node's prefix.
        int dmin = prefix.length;
        for (int i = from; i < to; i++) {
            byte[] k = keys.get(i);
            int l = 0;
            int max = Math.min(prefix.length, k.length - at);
            while (l < max && prefix[l] == k[at + l]) l++;
            if (l < prefix.length) dmin = Math.min(dmin, l);
        }
        if (dmin == prefix.length) {
            // Every key passes through the whole prefix.
            int newAt = at + prefix.length;
            long terminal = in.terminalId();
            int i = from;
            if (keys.get(i).length == newAt) {
                terminal = ids[i];
                i++;
            }
            TreeMap<Integer, Addr> edges = new TreeMap<>();
            for (int e = 0; e < in.edgeBytes().length; e++) {
                edges.put(in.edgeBytes()[e], in.children()[e]);
            }
            while (i < to) {
                int b = keys.get(i)[newAt] & 0xFF;
                int j = i;
                while (j < to && (keys.get(j)[newAt] & 0xFF) == b) j++;
                Addr existing = edges.get(b);
                edges.put(
                        b,
                        existing != null
                                ? insertAllAt(existing, keys, ids, i, j, newAt + 1)
                                : build(keys, ids, i, j, newAt + 1));
                i = j;
            }
            return putInternal(prefix, terminal, edges);
        }
        // Split the prefix at dmin. The lower node keeps the untouched children.
        Addr lower =
                put(
                        new Internal(
                                Arrays.copyOfRange(prefix, dmin + 1, prefix.length),
                                in.terminalId(),
                                in.edgeBytes(),
                                in.children()));
        int splitAt = at + dmin;
        long upperTerminal = -1;
        int i = from;
        if (keys.get(i).length == splitAt) {
            upperTerminal = ids[i];
            i++;
        }
        TreeMap<Integer, Addr> edges = new TreeMap<>();
        int lowerByte = prefix[dmin] & 0xFF;
        edges.put(lowerByte, lower);
        while (i < to) {
            int b = keys.get(i)[splitAt] & 0xFF;
            int j = i;
            while (j < to && (keys.get(j)[splitAt] & 0xFF) == b) j++;
            Addr existing = edges.get(b);
            edges.put(
                    b,
                    existing != null
                            ? insertAllAt(existing, keys, ids, i, j, splitAt + 1)
                            : build(keys, ids, i, j, splitAt + 1));
            i = j;
        }
        return putInternal(Arrays.copyOfRange(prefix, 0, dmin), upperTerminal, edges);
    }

    private Addr putInternal(byte[] prefix, long terminal, TreeMap<Integer, Addr> edges) {
        int[] eb = new int[edges.size()];
        Addr[] ch = new Addr[edges.size()];
        int i = 0;
        for (Map.Entry<Integer, Addr> e : edges.entrySet()) {
            eb[i] = e.getKey();
            ch[i] = e.getValue();
            i++;
        }
        return put(new Internal(prefix, terminal, eb, ch));
    }

    /** Shallow fork sharing this pool's nodes — the bench's per-invocation snapshot. */
    public MerkleRadixDictionary fork() {
        MerkleRadixDictionary f = new MerkleRadixDictionary(bucketSize);
        f.pool.putAll(pool);
        return f;
    }

    /** Nodes visited resolving {@code key} — the depth the design question is about. */
    public int depthOf(Addr root, byte[] key) {
        Addr cur = root;
        int at = 0;
        int depth = 0;
        while (true) {
            depth++;
            Node node = pool.get(cur);
            if (node instanceof Bucket) return depth;
            Internal in = (Internal) node;
            at += in.prefix().length;
            if (at >= key.length) return depth;
            int idx = Arrays.binarySearch(in.edgeBytes(), key[at] & 0xFF);
            if (idx < 0) return depth;
            cur = in.children()[idx];
            at++;
        }
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------- serialized probes

    /**
     * The pool in its PRODUCTION-SHAPED form: address → serialized node bytes, with a lookup that
     * parses each node from its bytes at every hop (header, prefix compare, edge scan) — the fair
     * counterpart to the engine probing serialized chunks. Per-hop it also pays the child address
     * copy an un-sliced byte[] map forces; a production layout could slice zero-copy, so this is
     * the pessimistic end of the serialized regime.
     */
    public static final class SerializedPool {
        private final Map<Addr, byte[]> blocks = new HashMap<>();

        /** Snapshot every node reachable in {@code dict} as serialized bytes. */
        public static SerializedPool of(MerkleRadixDictionary dict) {
            SerializedPool pool = new SerializedPool();
            for (Map.Entry<Addr, Node> e : dict.pool.entrySet()) {
                pool.blocks.put(e.getKey(), e.getValue().serialize());
            }
            return pool;
        }

        /**
         * One hop over a serialized node. Returns {@code {0, id}} when resolved (id −1 = absent) or
         * {@code {1, newAt, addrOffset}} to descend into the child address at {@code addrOffset} in
         * this node's bytes. Shared by the in-memory serialized walk and the disk-backed walkers.
         */
        public static long[] step(byte[] node, byte[] key, int at) {
            if (node[0] == 'B') {
                int count = ((node[1] & 0xFF) << 8) | (node[2] & 0xFF);
                int tableAt = 3;
                int payloadAt = 3 + 4 * count;
                int lo = 0;
                int hi = count - 1;
                while (lo <= hi) {
                    int mid = (lo + hi) >>> 1;
                    int e = payloadAt + readInt(node, tableAt + 4 * mid);
                    int len = readInt(node, e);
                    int cmp = Arrays.compareUnsigned(node, e + 4, e + 4 + len, key, at, key.length);
                    if (cmp == 0) return new long[] {0, readLong(node, e + 4 + len)};
                    if (cmp < 0) lo = mid + 1;
                    else hi = mid - 1;
                }
                return new long[] {0, -1};
            }
            int prefixLen = readInt(node, 1);
            if (key.length - at < prefixLen
                    || !Arrays.equals(node, 5, 5 + prefixLen, key, at, at + prefixLen)) {
                return new long[] {0, -1};
            }
            at += prefixLen;
            int p = 5 + prefixLen;
            boolean hasTerminal = node[p] == 1;
            long terminal = readLong(node, p + 1);
            p += 9;
            if (at == key.length) return new long[] {0, hasTerminal ? terminal : -1};
            int count = ((node[p] & 0xFF) << 8) | (node[p + 1] & 0xFF);
            p += 2;
            int want = key[at] & 0xFF;
            int lo = 0;
            int hi = count - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int b = node[p + mid * (1 + ADDR_LEN)] & 0xFF;
                if (b == want) {
                    return new long[] {1, at + 1, p + mid * (1 + ADDR_LEN) + 1};
                }
                if (b < want) lo = mid + 1;
                else hi = mid - 1;
            }
            return new long[] {0, -1};
        }

        public long get(Addr root, byte[] key) {
            byte[] node = blocks.get(root);
            int at = 0;
            while (true) {
                long[] r = step(node, key, at);
                if (r[0] == 0) return r[1];
                at = (int) r[1];
                node =
                        blocks.get(
                                new Addr(
                                        Arrays.copyOfRange(
                                                node, (int) r[2], (int) r[2] + ADDR_LEN)));
            }
        }

        private static int readInt(byte[] b, int at) {
            return (b[at] & 0xFF) << 24
                    | (b[at + 1] & 0xFF) << 16
                    | (b[at + 2] & 0xFF) << 8
                    | (b[at + 3] & 0xFF);
        }

        private static long readLong(byte[] b, int at) {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (b[at + i] & 0xFF);
            return v;
        }
    }
}
