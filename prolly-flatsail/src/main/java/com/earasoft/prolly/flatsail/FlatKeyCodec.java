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
package com.earasoft.prolly.flatsail;

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.QuadRole;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;

/**
 * Encodes and decodes the fixed <strong>32-byte</strong> index keys of the flat Sail: four 8-byte
 * {@link TermId} columns, each written <strong>big-endian</strong>.
 *
 * <p>Big-endian is deliberate. {@code TermId} orders by {@link Long#compareUnsigned} ({@link
 * TermId#compareTo}), and the big-endian bytes of an unsigned 64-bit value sort lexicographically
 * in exactly that order. So RocksDB's natural byte-key ordering matches TermId ordering, and a
 * <em>byte prefix</em> of a key is a <em>column prefix</em> in TermId space — which is what makes a
 * leading-column range scan ({@link #prefix}) correct.
 *
 * <p>Keys are physical-order: the four columns are whatever permutation the scanned {@link
 * QuadOrder} stores (SPOC/POSC/OSPC/CSPO). {@link #encode(QuadOrder, TermId, TermId, TermId,
 * TermId)} permutes a logical quad into that order; {@link #decodeQuad} permutes a stored key back
 * to logical (s, p, o, c).
 *
 * @apiNote Stateless — every method is static and the class is never instantiated. {@link #encode}
 *     builds a key, {@link #decode} recovers the physical {@link SpocKey}, {@link #decodeQuad}
 *     recovers the logical quad, and {@link #prefix} builds the leading-column prefix a range scan
 *     seeks on. The big-endian layout is the contract a scan depends on: changing it would silently
 *     break range-scan correctness (the byte order would no longer match TermId order).
 * @implNote <b>Collaborators:</b> {@link TermId} (the 8-byte unsigned columns), {@link SpocKey}
 *     (the physical four-column key), {@link QuadOrder} (the logical-to-physical permutation).
 *     <b>Dependents:</b> {@code RocksDbFlatSailConnection} (encodes on write, decodes on scan) and
 *     {@code RocksFlatStore}'s per-permutation column families.
 */
public final class FlatKeyCodec {

    /** Width of one {@link TermId} column in a key: 8 bytes, big-endian. */
    public static final int TERM_BYTES = 8;

    /** Width of a full index key: four {@link TermId} columns. */
    public static final int KEY_SIZE = 4 * TERM_BYTES;

    private FlatKeyCodec() {}

    /** Encode a physical-order key (a {@link SpocKey}) to its 32-byte form. */
    public static byte[] encode(SpocKey key) {
        byte[] out = new byte[KEY_SIZE];
        putTerm(out, 0, key.col0());
        putTerm(out, TERM_BYTES, key.col1());
        putTerm(out, 2 * TERM_BYTES, key.col2());
        putTerm(out, 3 * TERM_BYTES, key.col3());
        return out;
    }

    /**
     * Encode a logical {@code (s, p, o, c)} quad into the 32-byte key for {@code order} — the four
     * TermIds are permuted into {@code order}'s physical column layout.
     */
    public static byte[] encode(QuadOrder order, TermId s, TermId p, TermId o, TermId c) {
        return encode(order.keyOf(s, p, o, c));
    }

    /** Decode a 32-byte key back to its physical-order {@link SpocKey}. */
    public static SpocKey decode(byte[] key) {
        requireKeyLength(key);
        return new SpocKey(
                getTerm(key, 0),
                getTerm(key, TERM_BYTES),
                getTerm(key, 2 * TERM_BYTES),
                getTerm(key, 3 * TERM_BYTES));
    }

    /**
     * Decode a 32-byte key stored under {@code order} back to the logical quad, returned as {@code
     * [subject, predicate, object, context]}.
     */
    public static TermId[] decodeQuad(QuadOrder order, byte[] key) {
        SpocKey physical = decode(key);
        QuadRole role = order.role();
        return new TermId[] {
            role.col(physical, 0),
            role.col(physical, 1),
            role.col(physical, 2),
            role.col(physical, 3),
        };
    }

    /**
     * Encode the leading {@code columns} of an index key — the scan prefix for a range query whose
     * first N physical columns are bound. The result is {@code 8 * columns.length} bytes and is a
     * byte-prefix of every full key that begins with those columns.
     *
     * @throws IllegalArgumentException if more than four columns are given
     */
    public static byte[] prefix(TermId... columns) {
        if (columns.length > 4) {
            throw new IllegalArgumentException(
                    "an index key has at most 4 columns, got " + columns.length);
        }
        byte[] out = new byte[columns.length * TERM_BYTES];
        for (int i = 0; i < columns.length; i++) {
            putTerm(out, i * TERM_BYTES, columns[i]);
        }
        return out;
    }

    // ---- big-endian TermId <-> bytes ------------------------------------

    /** Write {@code term}'s 64-bit value big-endian into {@code dst} at {@code offset}. */
    private static void putTerm(byte[] dst, int offset, TermId term) {
        long v = term.value();
        for (int i = TERM_BYTES - 1; i >= 0; i--) {
            dst[offset + i] = (byte) v;
            v >>>= 8;
        }
    }

    /** Read a big-endian 64-bit TermId from {@code src} at {@code offset}. */
    private static TermId getTerm(byte[] src, int offset) {
        long v = 0L;
        for (int i = 0; i < TERM_BYTES; i++) {
            v = (v << 8) | (src[offset + i] & 0xFFL);
        }
        return TermId.of(v);
    }

    private static void requireKeyLength(byte[] key) {
        if (key.length != KEY_SIZE) {
            throw new IllegalArgumentException(
                    "index key must be " + KEY_SIZE + " bytes, got " + key.length);
        }
    }
}
