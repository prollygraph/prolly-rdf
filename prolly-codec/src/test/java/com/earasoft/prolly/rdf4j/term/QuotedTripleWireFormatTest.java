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
package com.earasoft.prolly.rdf4j.term;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact wire-format pins for the RDF-star quoted-triple/quad encodings (roadmap T1 — these
 * tags landed in conformance round 3 with NO byte-stability coverage). TermIds are the content
 * hashes OF THESE BYTES: a silent layout drift re-addresses every stored triple term in every
 * store, so the layout is pinned to literal hex, decode is pinned back to the exact ids + flag, and
 * the reject path is pinned to a diagnosable message. Changing any expected byte here is an on-disk
 * format break — a deliberate format-version decision, never a refactor side effect.
 */
class QuotedTripleWireFormatTest {

    private static final TermId S = TermId.of(0x1122334455667788L);
    private static final TermId P = TermId.of(0x99AABBCCDDEEFF00L);
    private static final TermId O = TermId.of(0x0102030405060708L);
    private static final TermId C = TermId.of(0x0F0E0D0C0B0A0908L);

    private static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    private static byte[] bytes(MemorySegment seg) {
        return seg.toArray(ValueLayout.JAVA_BYTE);
    }

    @Test
    void quotedTripleGoldenBytes() {
        try (Arena arena = Arena.ofConfined()) {
            assertArrayEquals(
                    hex("C0" + "1122334455667788" + "99AABBCCDDEEFF00" + "0102030405060708"),
                    bytes(TermCodec.encodeQuotedTriple(S, P, O, true, arena)),
                    "asserted quoted triple: tag C0 + three BE64 TermIds, 25 bytes");
            assertArrayEquals(
                    hex("C1" + "1122334455667788" + "99AABBCCDDEEFF00" + "0102030405060708"),
                    bytes(TermCodec.encodeQuotedTriple(S, P, O, false, arena)),
                    "unasserted variant differs ONLY in the tag");
        }
    }

    @Test
    void quotedQuadGoldenBytes() {
        try (Arena arena = Arena.ofConfined()) {
            assertArrayEquals(
                    hex(
                            "C2"
                                    + "1122334455667788"
                                    + "99AABBCCDDEEFF00"
                                    + "0102030405060708"
                                    + "0F0E0D0C0B0A0908"),
                    bytes(TermCodec.encodeQuotedQuad(S, P, O, C, true, arena)),
                    "asserted quoted quad: tag C2 + four BE64 TermIds, 33 bytes");
            assertArrayEquals(
                    hex(
                            "C3"
                                    + "1122334455667788"
                                    + "99AABBCCDDEEFF00"
                                    + "0102030405060708"
                                    + "0F0E0D0C0B0A0908"),
                    bytes(TermCodec.encodeQuotedQuad(S, P, O, C, false, arena)));
        }
    }

    @Test
    void decodeReturnsTheExactIdsAndFlag() {
        TermCodec.QuotedTriple t =
                TermCodec.decodeQuotedTriple(
                        MemorySegment.ofArray(
                                hex(
                                        "C0"
                                                + "1122334455667788"
                                                + "99AABBCCDDEEFF00"
                                                + "0102030405060708")));
        assertEquals(S, t.s());
        assertEquals(P, t.p());
        assertEquals(O, t.o());
        assertTrue(t.asserted());
        TermCodec.QuotedQuad q =
                TermCodec.decodeQuotedQuad(
                        MemorySegment.ofArray(
                                hex(
                                        "C3"
                                                + "1122334455667788"
                                                + "99AABBCCDDEEFF00"
                                                + "0102030405060708"
                                                + "0F0E0D0C0B0A0908")));
        assertEquals(C, q.c());
        assertFalse(q.asserted());
    }

    @Test
    void wrongTagIsRefusedWithTheTagNamed() {
        byte[] notATriple =
                hex("14" + "1122334455667788" + "99AABBCCDDEEFF00" + "0102030405060708");
        IllegalArgumentException e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> TermCodec.decodeQuotedTriple(MemorySegment.ofArray(notATriple)));
        assertTrue(e.getMessage().contains("14"), "the offending tag is named: " + e.getMessage());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        TermCodec.decodeQuotedQuad(
                                MemorySegment.ofArray(
                                        hex(
                                                "C0" // a TRIPLE tag is not a QUAD tag
                                                        + "1122334455667788"
                                                        + "99AABBCCDDEEFF00"
                                                        + "0102030405060708"
                                                        + "0F0E0D0C0B0A0908"))));
    }
}
