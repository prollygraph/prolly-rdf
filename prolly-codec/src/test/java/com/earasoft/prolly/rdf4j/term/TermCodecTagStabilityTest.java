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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.LinkedHashMap;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.junit.jupiter.api.Test;

/**
 * I-5 codec fidelity for {@link TermCodec} — tag-byte stability + numeric round-trip
 * (plans/core-engine-test-strategy.md Step 11).
 *
 * <p>The leading <b>tag byte</b> of an encoded term is on-disk format: every persisted literal
 * carries it, and a dictionary keyed by the encoded bytes cannot find a term whose tag silently
 * changed. A drift here orphans every persisted index that referenced the old encoding. This golden
 * table is the tripwire — if someone renumbers a tag, this test fails before the change ships,
 * forcing a deliberate format-version decision (pre-1.0: a migration, not a defensive reader).
 */
class TermCodecTagStabilityTest {

    /** The pinned tag bytes. Changing any value here is an on-disk format break. */
    private static final Map<String, Byte> GOLDEN =
            new LinkedHashMap<>() {
                {
                    put("BOOLEAN", (byte) 0x10);
                    put("XSD_BYTE", (byte) 0x11);
                    put("XSD_SHORT", (byte) 0x12);
                    put("XSD_INT", (byte) 0x13);
                    put("XSD_INTEGER", (byte) 0x14);
                    put("XSD_LONG", (byte) 0x15);
                    put("XSD_UINT", (byte) 0x16);
                    put("XSD_ULONG", (byte) 0x17);
                    put("XSD_FLOAT", (byte) 0x18);
                    put("XSD_DOUBLE", (byte) 0x19);
                    put("XSD_DECIMAL", (byte) 0x1A);
                    put("XSD_DATETIME", (byte) 0x20);
                    put("XSD_DATE", (byte) 0x21);
                    put("XSD_TIME", (byte) 0x22);
                    put("XSD_GYEAR", (byte) 0x23);
                    put("XSD_GYEARMONTH", (byte) 0x24);
                    put("XSD_DURATION", (byte) 0x25);
                    put("XSD_UUID", (byte) 0x30);
                    put("XSD_STRING", (byte) 0x40);
                    put("RDF_LANGSTRING", (byte) 0x41);
                    put("XSD_ANYURI", (byte) 0x42);
                    put("XSD_BASE64BINARY", (byte) 0x60);
                    put("XSD_HEXBINARY", (byte) 0x61);
                    put("IRI_SHORT_PREFIX", (byte) 0x80);
                }
            };

    @Test
    void tagConstantsMatchTheGolden() {
        assertEquals(GOLDEN.get("BOOLEAN"), TermCodec.TAG_BOOLEAN);
        assertEquals(GOLDEN.get("XSD_BYTE"), TermCodec.TAG_XSD_BYTE);
        assertEquals(GOLDEN.get("XSD_SHORT"), TermCodec.TAG_XSD_SHORT);
        assertEquals(GOLDEN.get("XSD_INT"), TermCodec.TAG_XSD_INT);
        assertEquals(GOLDEN.get("XSD_INTEGER"), TermCodec.TAG_XSD_INTEGER);
        assertEquals(GOLDEN.get("XSD_LONG"), TermCodec.TAG_XSD_LONG);
        assertEquals(GOLDEN.get("XSD_UINT"), TermCodec.TAG_XSD_UINT);
        assertEquals(GOLDEN.get("XSD_ULONG"), TermCodec.TAG_XSD_ULONG);
        assertEquals(GOLDEN.get("XSD_FLOAT"), TermCodec.TAG_XSD_FLOAT);
        assertEquals(GOLDEN.get("XSD_DOUBLE"), TermCodec.TAG_XSD_DOUBLE);
        assertEquals(GOLDEN.get("XSD_DECIMAL"), TermCodec.TAG_XSD_DECIMAL);
        assertEquals(GOLDEN.get("XSD_DATETIME"), TermCodec.TAG_XSD_DATETIME);
        assertEquals(GOLDEN.get("XSD_DATE"), TermCodec.TAG_XSD_DATE);
        assertEquals(GOLDEN.get("XSD_TIME"), TermCodec.TAG_XSD_TIME);
        assertEquals(GOLDEN.get("XSD_GYEAR"), TermCodec.TAG_XSD_GYEAR);
        assertEquals(GOLDEN.get("XSD_GYEARMONTH"), TermCodec.TAG_XSD_GYEARMONTH);
        assertEquals(GOLDEN.get("XSD_DURATION"), TermCodec.TAG_XSD_DURATION);
        assertEquals(GOLDEN.get("XSD_UUID"), TermCodec.TAG_XSD_UUID);
        assertEquals(GOLDEN.get("XSD_STRING"), TermCodec.TAG_XSD_STRING);
        assertEquals(GOLDEN.get("RDF_LANGSTRING"), TermCodec.TAG_RDF_LANGSTRING);
        assertEquals(GOLDEN.get("XSD_ANYURI"), TermCodec.TAG_XSD_ANYURI);
        assertEquals(GOLDEN.get("XSD_BASE64BINARY"), TermCodec.TAG_XSD_BASE64BINARY);
        assertEquals(GOLDEN.get("XSD_HEXBINARY"), TermCodec.TAG_XSD_HEXBINARY);
        assertEquals(GOLDEN.get("IRI_SHORT_PREFIX"), TermCodec.TAG_IRI_SHORT_PREFIX);
    }

    @Test
    void encodersStampTheExpectedTag() {
        try (Arena arena = Arena.ofConfined()) {
            assertEquals(
                    TermCodec.TAG_BOOLEAN, TermCodec.tagOf(TermCodec.encodeBoolean("true", arena)));
            assertEquals(
                    TermCodec.TAG_XSD_BYTE, TermCodec.tagOf(TermCodec.encodeInt8((byte) 7, arena)));
            assertEquals(
                    TermCodec.TAG_XSD_SHORT,
                    TermCodec.tagOf(TermCodec.encodeInt16((short) 7, arena)));
            assertEquals(TermCodec.TAG_XSD_INT, TermCodec.tagOf(TermCodec.encodeInt32(7, arena)));
            assertEquals(
                    TermCodec.TAG_XSD_INTEGER,
                    TermCodec.tagOf(TermCodec.encodeInteger("7", arena)));
            assertEquals(
                    TermCodec.TAG_XSD_FLOAT, TermCodec.tagOf(TermCodec.encodeFloat32(1.5f, arena)));
            assertEquals(
                    TermCodec.TAG_XSD_DOUBLE,
                    TermCodec.tagOf(TermCodec.encodeFloat64(1.5d, arena)));
        }
    }

    // ---- numeric round-trips (tag-stripped via payloadOf) ----

    @Property(tries = 2000)
    void int8RoundTrips(@ForAll byte v) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeInt8(v, a); // value overload → canonical lexical
            assertEquals(Byte.toString(v), TermCodec.decodeLexical(TermCodec.payloadOf(enc)));
        }
    }

    @Property(tries = 2000)
    void int16RoundTrips(@ForAll short v) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeInt16(v, a);
            assertEquals(Short.toString(v), TermCodec.decodeLexical(TermCodec.payloadOf(enc)));
        }
    }

    @Property(tries = 2000)
    void int32RoundTrips(@ForAll int v) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc = TermCodec.encodeInt32(v, a);
            assertEquals(Integer.toString(v), TermCodec.decodeLexical(TermCodec.payloadOf(enc)));
        }
    }

    @Property(tries = 2000)
    void float64RoundTrips(@ForAll double v) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment enc =
                    TermCodec.encodeFloat64(v, a); // value overload → canonical xsd lexical
            double back =
                    XMLDatatypeUtil.parseDouble(TermCodec.decodeLexical(TermCodec.payloadOf(enc)));
            // Term-faithful (ADR-0043 Step 5): the VALUE round-trips through the canonical lexical.
            // doubleToLongBits canonicalizes NaN, so distinct raw NaN payloads (not RDF terms)
            // compare
            // equal here; ±0 and finite values are exact (Double.toString is round-trip-safe).
            assertEquals(
                    Double.doubleToLongBits(v),
                    Double.doubleToLongBits(back),
                    "Float64 value round-trips through the canonical xsd:double lexical");
        }
    }
}
