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

import static org.junit.jupiter.api.Assertions.*;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TermCodecUuidTest {

    @Test
    void tag_and_size() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = TermCodec.encodeUuid(UUID.randomUUID(), a);
            assertEquals(17, s.byteSize());
            assertEquals(TermCodec.TAG_XSD_UUID, TermCodec.tagOf(s));
        }
    }

    @Test
    void round_trip_random_uuids() {
        SplittableRandom r = new SplittableRandom(0xABCDL);
        try (Arena a = Arena.ofConfined()) {
            for (int i = 0; i < 100; i++) {
                UUID u = new UUID(r.nextLong(), r.nextLong());
                UUID rt = TermCodec.decodeUuid(TermCodec.payloadOf(TermCodec.encodeUuid(u, a)));
                assertEquals(u, rt);
            }
        }
    }

    @Test
    void nil_uuid_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            UUID nil = new UUID(0L, 0L);
            UUID rt = TermCodec.decodeUuid(TermCodec.payloadOf(TermCodec.encodeUuid(nil, a)));
            assertEquals(nil, rt);
            // Payload should be all zero
            MemorySegment payload = TermCodec.payloadOf(TermCodec.encodeUuid(nil, a));
            for (int i = 0; i < 16; i++) {
                assertEquals((byte) 0, payload.get(Layouts.BYTE, i));
            }
        }
    }

    @Test
    void max_uuid_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            UUID max = new UUID(-1L, -1L); // 0xFF...FF, 0xFF...FF
            UUID rt = TermCodec.decodeUuid(TermCodec.payloadOf(TermCodec.encodeUuid(max, a)));
            assertEquals(max, rt);
            MemorySegment payload = TermCodec.payloadOf(TermCodec.encodeUuid(max, a));
            for (int i = 0; i < 16; i++) {
                assertEquals((byte) 0xFF, payload.get(Layouts.BYTE, i));
            }
        }
    }

    @Test
    void sign_bit_boundaries_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            UUID[] samples = {
                new UUID(Long.MIN_VALUE, Long.MIN_VALUE),
                new UUID(Long.MAX_VALUE, Long.MAX_VALUE),
                new UUID(Long.MIN_VALUE, Long.MAX_VALUE),
                new UUID(Long.MAX_VALUE, Long.MIN_VALUE),
                new UUID(0L, 1L),
                new UUID(1L, 0L),
            };
            for (UUID u : samples) {
                UUID rt = TermCodec.decodeUuid(TermCodec.payloadOf(TermCodec.encodeUuid(u, a)));
                assertEquals(u, rt, "round-trip failed for " + u);
            }
        }
    }

    @Test
    void byte_order_is_big_endian_msb_first() {
        try (Arena a = Arena.ofConfined()) {
            UUID u = new UUID(0x0102030405060708L, 0x1112131415161718L);
            MemorySegment payload = TermCodec.payloadOf(TermCodec.encodeUuid(u, a));
            // First 8 bytes: mostSignificantBits, BE
            for (int i = 0; i < 8; i++) {
                assertEquals((byte) (i + 1), payload.get(Layouts.BYTE, i));
            }
            // Next 8 bytes: leastSignificantBits, BE
            for (int i = 0; i < 8; i++) {
                assertEquals((byte) (0x11 + i), payload.get(Layouts.BYTE, 8 + i));
            }
        }
    }

    @Test
    void lex_order_natural_byte_order() {
        try (Arena a = Arena.ofConfined()) {
            UUID lo = new UUID(0L, 0L);
            UUID mid = new UUID(0L, 1L);
            UUID hi = new UUID(1L, 0L);
            assertTrue(
                    Compare.compareUnsigned(
                                    TermCodec.encodeUuid(lo, a), TermCodec.encodeUuid(mid, a))
                            < 0);
            assertTrue(
                    Compare.compareUnsigned(
                                    TermCodec.encodeUuid(mid, a), TermCodec.encodeUuid(hi, a))
                            < 0);
        }
    }

    @Test
    void parsed_uuid_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            UUID parsed = UUID.fromString("12345678-1234-5678-1234-567812345678");
            UUID rt = TermCodec.decodeUuid(TermCodec.payloadOf(TermCodec.encodeUuid(parsed, a)));
            assertEquals(parsed, rt);
            assertEquals(parsed.toString(), rt.toString());
        }
    }
}
