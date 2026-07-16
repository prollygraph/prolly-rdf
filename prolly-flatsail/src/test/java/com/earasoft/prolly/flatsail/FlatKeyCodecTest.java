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

import static org.junit.jupiter.api.Assertions.*;

import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Coverage for {@link FlatKeyCodec} — the 32-byte 4×TermId index-key codec. */
class FlatKeyCodecTest {

    private static final TermId S = TermId.of(0x0102030405060708L);
    private static final TermId P = TermId.of(0x1112131415161718L);
    private static final TermId O = TermId.of(0x2122232425262728L);
    private static final TermId C = TermId.of(0x3132333435363738L);

    @Test
    void key_size_is_thirty_two_bytes() {
        assertEquals(32, FlatKeyCodec.KEY_SIZE);
        assertEquals(8, FlatKeyCodec.TERM_BYTES);
        assertEquals(FlatKeyCodec.KEY_SIZE, FlatKeyCodec.encode(new SpocKey(S, P, O, C)).length);
    }

    @Test
    void spockey_roundtrips_through_encode_decode() {
        SpocKey key = new SpocKey(S, P, O, C);
        SpocKey back = FlatKeyCodec.decode(FlatKeyCodec.encode(key));
        assertEquals(S, back.col0());
        assertEquals(P, back.col1());
        assertEquals(O, back.col2());
        assertEquals(C, back.col3());
    }

    @Test
    void logical_quad_roundtrips_under_every_order() {
        // encode permutes (s,p,o,c) into the order's physical layout;
        // decodeQuad must permute it back to logical (s,p,o,c).
        for (QuadOrder order : QuadOrder.values()) {
            byte[] key = FlatKeyCodec.encode(order, S, P, O, C);
            assertEquals(FlatKeyCodec.KEY_SIZE, key.length);
            TermId[] quad = FlatKeyCodec.decodeQuad(order, key);
            assertArrayEquals(
                    new TermId[] {S, P, O, C},
                    quad,
                    order
                            + ": decodeQuad must recover the logical subject/predicate/object/context");
        }
    }

    @Test
    void encoding_is_big_endian_most_significant_byte_first() {
        byte[] key = FlatKeyCodec.encode(new SpocKey(S, P, O, C));
        // S = 0x0102030405060708 — MSB 0x01 lands at offset 0.
        assertEquals((byte) 0x01, key[0]);
        assertEquals((byte) 0x08, key[7]);
        // P starts at offset 8.
        assertEquals((byte) 0x11, key[8]);
    }

    @Test
    void byte_order_matches_unsigned_termid_order() {
        // A TermId with the top bit set is unsigned-greater than a small one;
        // its big-endian key bytes must also compare greater. (A naive signed
        // encoding would get this backwards.)
        TermId small = TermId.of(0x0000_0000_0000_0001L);
        TermId huge = TermId.of(0xFFFF_FFFF_FFFF_FFFFL);
        byte[] smallKey = FlatKeyCodec.encode(new SpocKey(small, P, O, C));
        byte[] hugeKey = FlatKeyCodec.encode(new SpocKey(huge, P, O, C));
        assertTrue(
                Arrays.compareUnsigned(smallKey, hugeKey) < 0,
                "big-endian key bytes must sort in unsigned-TermId order");
        assertTrue(small.compareTo(huge) < 0, "sanity: TermId orders unsigned");
    }

    @Test
    void prefix_is_a_byte_prefix_of_the_full_key() {
        byte[] full = FlatKeyCodec.encode(QuadOrder.SPOC, S, P, O, C);
        byte[] sp = FlatKeyCodec.prefix(S, P);
        assertEquals(16, sp.length, "two columns -> 16 bytes");
        assertArrayEquals(
                Arrays.copyOf(full, 16),
                sp,
                "the (s,p) prefix must equal the first 16 bytes of the SPOC key");
    }

    @Test
    void prefix_accepts_zero_to_four_columns() {
        assertEquals(0, FlatKeyCodec.prefix().length);
        assertEquals(8, FlatKeyCodec.prefix(S).length);
        assertEquals(32, FlatKeyCodec.prefix(S, P, O, C).length);
    }

    @Test
    void prefix_rejects_more_than_four_columns() {
        assertThrows(IllegalArgumentException.class, () -> FlatKeyCodec.prefix(S, P, O, C, S));
    }

    @Test
    void decode_rejects_a_wrong_length_key() {
        assertThrows(IllegalArgumentException.class, () -> FlatKeyCodec.decode(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> FlatKeyCodec.decode(new byte[33]));
    }

    @Test
    void zero_termids_roundtrip() {
        SpocKey allZero = new SpocKey(TermId.ZERO, TermId.ZERO, TermId.ZERO, TermId.ZERO);
        byte[] key = FlatKeyCodec.encode(allZero);
        assertArrayEquals(new byte[32], key, "all-zero TermIds encode to all-zero bytes");
        assertEquals(allZero, FlatKeyCodec.decode(key));
    }
}
