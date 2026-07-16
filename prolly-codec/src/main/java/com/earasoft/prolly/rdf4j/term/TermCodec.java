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

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * Encoder / decoder for RDF terms in the prolly-rdf4j on-disk format.
 *
 * <p>Each encoded term is {@code [tag : u8] [payload : variable]}. The tag byte classifies the kind
 * (high nibble) and selects the variant (low nibble); see {@code SPEC.md} for the normative layout.
 *
 * <p>Tag-byte assignments are exposed as public constants for use by decoders, tests, and tooling.
 * <b>Do not change them once a Sail has committed data — the tag byte is part of the on-disk
 * format.</b>
 *
 * <p>Iteration scope (Phase 1, iter 3): numeric typed-literal tags {@code 0x10..0x19}. Temporal
 * types, decimal, big-integer, UUID, string-likes, IRIs, blank nodes, and quoted triples land in
 * subsequent iterations.
 *
 * @implNote <b>Collaborators:</b> the tag constants + payload layout are the contract both the
 *     encode and decode halves share. <b>Dependents:</b> {@link Dictionary} (stores the encoded
 *     bytes keyed by {@link TermId}) and the tuple-to-{@code ProllyValue} value-materialization
 *     path.
 */
public final class TermCodec {
    private TermCodec() {}

    // ------------------------------------------------------------------
    // Tag constants — typed literal range (0x10..0x3F)
    // ------------------------------------------------------------------

    /** xsd:boolean — 1B payload {0x00 | 0x01}. */
    public static final byte TAG_BOOLEAN = 0x10;

    /** xsd:byte — 1B sign-flipped Int8. */
    public static final byte TAG_XSD_BYTE = 0x11;

    /** xsd:short — 2B sign-flipped Int16, BE. */
    public static final byte TAG_XSD_SHORT = 0x12;

    /** xsd:int — 4B sign-flipped Int32, BE. */
    public static final byte TAG_XSD_INT = 0x13;

    /** xsd:integer — canonical for any xsd:integer that fits in Int64. 8B sign-flipped, BE. */
    public static final byte TAG_XSD_INTEGER = 0x14;

    /** xsd:long — alias of {@link #TAG_XSD_INTEGER}, distinguishes original type. */
    public static final byte TAG_XSD_LONG = 0x15;

    /** xsd:unsignedInt — 4B BE, no flip. */
    public static final byte TAG_XSD_UINT = 0x16;

    /** xsd:unsignedLong — 8B BE, no flip. */
    public static final byte TAG_XSD_ULONG = 0x17;

    /** xsd:float — 4B IEEE-754 lex-flip. */
    public static final byte TAG_XSD_FLOAT = 0x18;

    /** xsd:double — 8B IEEE-754 lex-flip. */
    public static final byte TAG_XSD_DOUBLE = 0x19;

    /** xsd:decimal — 1B sign-flipped scale + Nbyte 2's-complement BigInteger BE. */
    public static final byte TAG_XSD_DECIMAL = 0x1A;

    // 0x1F was TAG_XSD_INTEGER_BIG — removed when xsd:integer went term-faithful (ADR-0043):
    // lexical storage carries any magnitude under TAG_XSD_INTEGER, so the small/big split is gone.
    /** xsd:date — 2B sign-flipped Int16 year + 1B month + 1B day. */
    public static final byte TAG_XSD_DATE = 0x21;

    /** xsd:gYear — 2B sign-flipped Int16 year. */
    public static final byte TAG_XSD_GYEAR = 0x23;

    /** xsd:gYearMonth — 2B sign-flipped Int16 year + 1B month. */
    public static final byte TAG_XSD_GYEARMONTH = 0x24;

    /**
     * xsd:dateTime — 6B sign-flipped Int48 epoch-ms + 4B sub-ms-nanos + 2B sign-flipped Int16
     * tz-min.
     */
    public static final byte TAG_XSD_DATETIME = 0x20;

    /** xsd:time — 6B unsigned Int48 ns-since-midnight + 2B sign-flipped Int16 tz-min. */
    public static final byte TAG_XSD_TIME = 0x22;

    /** xsd:duration — 4B sign-flipped Int32 months + 8B sign-flipped Int64 nanos. */
    public static final byte TAG_XSD_DURATION = 0x25;

    /** xsd:UUID — 16B mostSigBits BE + leastSigBits BE. */
    public static final byte TAG_XSD_UUID = 0x30;

    /** xsd:string — UTF-8 bytes (length implicit from value size). */
    public static final byte TAG_XSD_STRING = 0x40;

    /** rdf:langString — 1B lang-tag-len + ASCII tag + UTF-8 lex bytes. */
    public static final byte TAG_RDF_LANGSTRING = 0x41;

    /** xsd:anyURI — UTF-8 bytes (literal URI string, distinct from IRI). */
    public static final byte TAG_XSD_ANYURI = 0x42;

    /** xsd:base64Binary — raw decoded bytes (not the lexical base64 string). */
    public static final byte TAG_XSD_BASE64BINARY = 0x60;

    /** xsd:hexBinary — raw decoded bytes. */
    public static final byte TAG_XSD_HEXBINARY = 0x61;

    /** IRI, short-prefix form — 4B prefix-id BE + UTF-8 local-part. */
    public static final byte TAG_IRI_SHORT_PREFIX = (byte) 0x80;

    /** IRI, long-prefix form — 4B prefix-id-1 BE + 4B prefix-id-2 BE + UTF-8 local. */
    public static final byte TAG_IRI_LONG_PREFIX = (byte) 0x81;

    /** IRI, full form (no prefix match) — UTF-8 bytes (length implicit). */
    public static final byte TAG_IRI_FULL = (byte) 0x82;

    /** Blank node, random — 16B UUID. */
    public static final byte TAG_BNODE_UUID = (byte) 0xA0;

    /** Blank node, labelled — UTF-8 label (length implicit). */
    public static final byte TAG_BNODE_LABEL = (byte) 0xA1;

    /** Blank node, canonical post-URDNA2015 — 4B c14n-index BE. */
    public static final byte TAG_BNODE_CANON = (byte) 0xA2;

    /** Quoted triple, asserted (RDF-star) — 8B s + 8B p + 8B o TermIds. */
    public static final byte TAG_QUOTED_TRIPLE_ASSERTED = (byte) 0xC0;

    /** Quoted triple, unasserted (RDF-star). */
    public static final byte TAG_QUOTED_TRIPLE_UNASSERTED = (byte) 0xC1;

    /** Quoted quad, asserted — 8B s + 8B p + 8B o + 8B c TermIds. */
    public static final byte TAG_QUOTED_QUAD_ASSERTED = (byte) 0xC2;

    /** Quoted quad, unasserted. */
    public static final byte TAG_QUOTED_QUAD_UNASSERTED = (byte) 0xC3;

    /** Custom-datatype literal — 8B datatype-IRI TermId + UTF-8 lexical (length implicit). */
    public static final byte TAG_CUSTOM_LITERAL = (byte) 0xE0;

    // ------------------------------------------------------------------
    // Tag inspection helpers
    // ------------------------------------------------------------------

    /**
     * @return the tag byte at offset 0 of an encoded term.
     */
    public static byte tagOf(MemorySegment encoded) {
        return encoded.get(Layouts.BYTE, 0);
    }

    /**
     * @return the payload slice (segment minus the leading tag byte).
     */
    public static MemorySegment payloadOf(MemorySegment encoded) {
        return encoded.asSlice(1, encoded.byteSize() - 1);
    }

    // ------------------------------------------------------------------
    // Boolean
    // ------------------------------------------------------------------

    /**
     * Encode {@code xsd:boolean} <b>term-faithfully</b>: the verbatim lexical form ({@code
     * "true"}/{@code "false"}/{@code "1"}/{@code "0"}) under {@link #TAG_BOOLEAN}, UTF-8.
     *
     * <p>Per ADR-0043 (term-faithful storage) the four lexical forms are <i>distinct RDF terms</i>
     * (RDF 1.1 §3.3 — literal equality is char-by-char on the lexical form) and must therefore get
     * distinct content addresses. So the payload is the lexical form, not a parsed {@code 0/1}
     * value — the old value encoding over-merged {@code "true"} with {@code "1"} (LEXFID-1).
     * Mirrors {@link #encodeXsdString} but under a boolean-specific tag, which preserves the {@code
     * xsd:boolean} datatype identity on decode (so {@code "true"^^xsd:boolean} is distinct from
     * {@code "true"^^xsd:string}).
     */
    public static MemorySegment encodeBoolean(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_BOOLEAN, lex, arena);
    }

    /**
     * Decode a verbatim-lexical payload back to its exact lexical {@link String} — the shared
     * decoder for every tag that stores its lexical form ({@code xsd:boolean} today; the
     * numeric/temporal tags as Phase 1 of term-faithful storage lands). The datatype identity is
     * carried by the {@link #tagOf tag}, not the payload, so a single lexical decoder serves them
     * all (cf. {@link #decodeXsdString} / {@link #decodeAnyURI}, the same body).
     */
    public static String decodeLexical(MemorySegment payload) {
        return decodeUtf8AllOf(payload);
    }

    // ------------------------------------------------------------------
    // Signed integers — bit-flipped for lex order under unsigned byte compare
    // ------------------------------------------------------------------

    // Fixed-width signed integer subtypes — term-faithful: verbatim lexical form under each 1:1
    // datatype tag (ADR-0043 Step 4b). The String overload is the identity encoder (preserves any
    // lexical form, e.g. "042"); the primitive overload is a canonical-lexical convenience for
    // callers
    // holding a value (e.g. ValueFactory.createLiteral(byte)). Decode for all is decodeLexical.

    /** Encode {@code xsd:byte} — verbatim lexical under {@link #TAG_XSD_BYTE}. */
    public static MemorySegment encodeInt8(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_BYTE, lex, arena);
    }

    /** Convenience: {@code xsd:byte} from a {@code byte} value (canonical decimal lexical). */
    public static MemorySegment encodeInt8(byte v, Arena arena) {
        return encodeInt8(Byte.toString(v), arena);
    }

    /** Encode {@code xsd:short} — verbatim lexical under {@link #TAG_XSD_SHORT}. */
    public static MemorySegment encodeInt16(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_SHORT, lex, arena);
    }

    /** Convenience: {@code xsd:short} from a {@code short} value (canonical decimal lexical). */
    public static MemorySegment encodeInt16(short v, Arena arena) {
        return encodeInt16(Short.toString(v), arena);
    }

    /** Encode {@code xsd:int} — verbatim lexical under {@link #TAG_XSD_INT}. */
    public static MemorySegment encodeInt32(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_INT, lex, arena);
    }

    /** Convenience: {@code xsd:int} from an {@code int} value (canonical decimal lexical). */
    public static MemorySegment encodeInt32(int v, Arena arena) {
        return encodeInt32(Integer.toString(v), arena);
    }

    /**
     * Encode {@code xsd:integer} <b>term-faithfully</b>: the verbatim lexical form under {@link
     * #TAG_XSD_INTEGER}, UTF-8 — for <i>any</i> magnitude. The old value encoding split long-range
     * ({@code TAG_XSD_INTEGER}) from arbitrary-precision ({@code TAG_XSD_INTEGER_BIG}, now
     * removed); lexical storage needs neither — the UTF-8 of the decimal string carries the
     * magnitude. {@code "1"} and {@code "01"} are distinct RDF terms and now get distinct content
     * addresses (LEXFID-1). Mirrors {@link #encodeBoolean} / {@link #encodeXsdString}.
     */
    public static MemorySegment encodeInteger(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_INTEGER, lex, arena);
    }

    /**
     * Encode {@code xsd:long} term-faithfully — verbatim lexical under {@link #TAG_XSD_LONG}. A
     * distinct datatype IRI from {@code xsd:integer} (so a distinct tag), with the same lexical
     * payload bytes for the same digits ({@code "42"^^xsd:long} ≠ {@code "42"^^xsd:integer} by
     * tag).
     */
    public static MemorySegment encodeLong(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_LONG, lex, arena);
    }

    /**
     * Convenience: encode {@code xsd:integer} from a {@code long} — its canonical decimal lexical
     * form ({@code Long.toString}) stored verbatim. The identity encoder is the {@code String}
     * overload (which preserves <i>any</i> lexical form, e.g. {@code "042"}); this is for callers
     * holding a numeric value. No value encoding — it just formats, then stores lexically.
     */
    public static MemorySegment encodeInteger(long v, Arena arena) {
        return encodeInteger(Long.toString(v), arena);
    }

    /**
     * Convenience: encode {@code xsd:long} from a {@code long} (canonical decimal lexical form).
     */
    public static MemorySegment encodeLong(long v, Arena arena) {
        return encodeLong(Long.toString(v), arena);
    }

    // ------------------------------------------------------------------
    // Unsigned integer subtypes — term-faithful: verbatim lexical (ADR-0043 Step 4b). The String
    // overload is the identity encoder; the primitive overload is a canonical-UNSIGNED-lexical
    // convenience (Integer/Long.toUnsignedString), so a raw bit pattern formats to its xsd value.
    // ------------------------------------------------------------------

    /** Encode {@code xsd:unsignedInt} — verbatim lexical under {@link #TAG_XSD_UINT}. */
    public static MemorySegment encodeUInt32(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_UINT, lex, arena);
    }

    /**
     * Convenience: {@code xsd:unsignedInt} from a raw {@code int} bit pattern (unsigned decimal).
     */
    public static MemorySegment encodeUInt32(int v, Arena arena) {
        return encodeUInt32(Integer.toUnsignedString(v), arena);
    }

    /** Encode {@code xsd:unsignedLong} — verbatim lexical under {@link #TAG_XSD_ULONG}. */
    public static MemorySegment encodeUInt64(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_ULONG, lex, arena);
    }

    /**
     * Convenience: {@code xsd:unsignedLong} from a raw {@code long} bit pattern (unsigned decimal).
     */
    public static MemorySegment encodeUInt64(long v, Arena arena) {
        return encodeUInt64(Long.toUnsignedString(v), arena);
    }

    // ------------------------------------------------------------------
    // IEEE-754 floats — term-faithful: verbatim lexical (ADR-0043 Step 5). The String overload is
    // the
    // identity encoder; the primitive overload is a canonical-lexical convenience matching RDF4J's
    // XSD
    // formatter ({@code XMLDatatypeUtil.toString}: {@code value.toString()} for finite, {@code
    // "INF"}/
    // {@code "-INF"}/{@code "NaN"} for the specials). Decode is {@code decodeLexical}. The signed
    // zeros,
    // exponent case ({@code "1.0E0"} vs {@code "1.0e0"}), and INF/NaN all round-trip exactly, as
    // distinct RDF terms. The old IEEE lex-flip value encoding is gone (index order is now lexical,
    // measured non-load-bearing — ADR-0043 D-6; numeric ORDER BY/FILTER run above the Sail by
    // value).
    // ------------------------------------------------------------------

    /** Encode {@code xsd:float} — verbatim lexical under {@link #TAG_XSD_FLOAT}. */
    public static MemorySegment encodeFloat32(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_FLOAT, lex, arena);
    }

    /**
     * Convenience: {@code xsd:float} from a {@code float} value (canonical xsd:float lexical form).
     */
    public static MemorySegment encodeFloat32(float v, Arena arena) {
        return encodeFloat32(fpLexical(v), arena);
    }

    /** Encode {@code xsd:double} — verbatim lexical under {@link #TAG_XSD_DOUBLE}. */
    public static MemorySegment encodeFloat64(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_DOUBLE, lex, arena);
    }

    /**
     * Convenience: {@code xsd:double} from a {@code double} value (canonical xsd:double lexical
     * form).
     */
    public static MemorySegment encodeFloat64(double v, Arena arena) {
        return encodeFloat64(fpLexical(v), arena);
    }

    /**
     * Canonical {@code xsd:float} lexical for a value (matches RDF4J's {@code
     * XMLDatatypeUtil.toString}).
     */
    private static String fpLexical(float v) {
        if (Float.isNaN(v)) return "NaN";
        if (v == Float.POSITIVE_INFINITY) return "INF";
        if (v == Float.NEGATIVE_INFINITY) return "-INF";
        return Float.toString(v);
    }

    /**
     * Canonical {@code xsd:double} lexical for a value (matches RDF4J's {@code
     * XMLDatatypeUtil.toString}).
     */
    private static String fpLexical(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == Double.POSITIVE_INFINITY) return "INF";
        if (v == Double.NEGATIVE_INFINITY) return "-INF";
        return Double.toString(v);
    }

    // ------------------------------------------------------------------
    // xsd:decimal — term-faithful: verbatim lexical (ADR-0043 Step 6d). The old fixed-width
    // sign-flipped-scale + BigInteger-unscaled value encoding is gone — it was only PARTIALLY
    // faithful
    // (it kept trailing-zero scale via BigDecimal, but over-merged leading-zero / explicit-`+` /
    // bare-dot,
    // e.g. "01.0"="1.0" and ".5"="0.5", since equal BigDecimals encode identically). Lexical
    // storage keeps
    // all of them distinct. Decode is decodeLexical. (Index order is lexical char-by-char,
    // non-load-bearing
    // — SPARQL ORDER BY/FILTER on xsd:decimal compute the value above the Sail; this also retires
    // the old
    // "lex order is by (scale, unscaled), not value" quirk that SPEC.md documented.)
    // ------------------------------------------------------------------

    /** Encode {@code xsd:decimal} — verbatim lexical under {@link #TAG_XSD_DECIMAL}. */
    public static MemorySegment encodeDecimal(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_DECIMAL, lex, arena);
    }

    /**
     * Value-convenience overload: encodes the {@link java.math.BigDecimal}'s canonical {@code
     * toString()} form. Spares callers that hold a {@code BigDecimal} value (e.g. the {@code
     * ProllyValueFactory.createLiteral(BigDecimal)} overload, test fixtures) — the
     * datatype-IRI-faithful lexical path takes the verbatim label via {@link #encodeDecimal(String,
     * Arena)}.
     */
    public static MemorySegment encodeDecimal(java.math.BigDecimal v, Arena arena) {
        return encodeDecimal(v.toString(), arena);
    }

    // ------------------------------------------------------------------
    // xsd:date / xsd:gYear / xsd:gYearMonth
    //
    // Year is stored sign-flipped Int16 BE so lex order matches signed year.
    // Year must fit in [-32768, +32767]; out-of-range throws.
    // ------------------------------------------------------------------

    // xsd:gYear / xsd:gYearMonth / xsd:date — term-faithful: verbatim lexical (ADR-0043 Step 6,
    // calendar types). The old sign-flipped Int16-year value encoding (capped at ±32767) is gone;
    // lexical storage carries any year (incl. BCE "-…" and 5+ digit years) and preserves leading
    // zeros + the exact form. Decode is decodeLexical.

    /** Encode {@code xsd:gYear} — verbatim lexical under {@link #TAG_XSD_GYEAR}. */
    public static MemorySegment encodeGYear(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_GYEAR, lex, arena);
    }

    /** Encode {@code xsd:gYearMonth} — verbatim lexical under {@link #TAG_XSD_GYEARMONTH}. */
    public static MemorySegment encodeGYearMonth(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_GYEARMONTH, lex, arena);
    }

    /** Encode {@code xsd:date} — verbatim lexical under {@link #TAG_XSD_DATE}. */
    public static MemorySegment encodeDate(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_DATE, lex, arena);
    }

    // ------------------------------------------------------------------
    // xsd:UUID — 16B raw, big-endian most-significant first
    // ------------------------------------------------------------------

    /** Encode {@code xsd:UUID}. 17 bytes total: tag + 16B UUID. */
    public static MemorySegment encodeUuid(java.util.UUID v, Arena arena) {
        MemorySegment out = arena.allocate(17);
        out.set(Layouts.BYTE, 0, TAG_XSD_UUID);
        out.set(Layouts.BE64_U, 1, v.getMostSignificantBits());
        out.set(Layouts.BE64_U, 9, v.getLeastSignificantBits());
        return out;
    }

    public static java.util.UUID decodeUuid(MemorySegment payload) {
        long mostSig = payload.get(Layouts.BE64_U, 0);
        long leastSig = payload.get(Layouts.BE64_U, 8);
        return new java.util.UUID(mostSig, leastSig);
    }

    // ------------------------------------------------------------------
    // xsd:dateTime — term-faithful: verbatim lexical (ADR-0043 Step 6). The old fixed-width Int48
    // epoch-ms encoding is gone; lexical storage also fixes its tz-absent wart (a tz-less
    // "2026-05-15T12:00:00" now round-trips verbatim instead of being coerced to "...Z"), and
    // "…Z" vs "…+00:00" (and fractional-second variants) are preserved as distinct terms. Decode is
    // decodeLexical. (Index order is lexical, non-load-bearing — ADR-0043 D-6; SPARQL ORDER
    // BY/FILTER
    // on dateTime compute the value above the Sail.)
    // ------------------------------------------------------------------

    /** Encode {@code xsd:dateTime} — verbatim lexical under {@link #TAG_XSD_DATETIME}. */
    public static MemorySegment encodeDateTime(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_DATETIME, lex, arena);
    }

    // ------------------------------------------------------------------
    // xsd:time — term-faithful: verbatim lexical (ADR-0043 Step 6). The old fixed-width
    // 6B-unsigned-ns-since-midnight + 2B-sign-flipped-tz-min value encoding is gone — it
    // canonicalized away the timezone spelling ("…Z" vs "…+00:00"), coerced a tz-less time to
    // UTC, capped fractional precision at nanoseconds, and could not represent the XSD end-of-day
    // "24:00:00" (java.time's LocalTime rejects it). Lexical storage round-trips all of them.
    // Decode is decodeLexical. (Index order is lexical, non-load-bearing — SPARQL ORDER BY/FILTER
    // on xsd:time compute the value above the Sail.)
    // ------------------------------------------------------------------

    /** Encode {@code xsd:time} — verbatim lexical under {@link #TAG_XSD_TIME}. */
    public static MemorySegment encodeTime(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_TIME, lex, arena);
    }

    // ------------------------------------------------------------------
    // xsd:duration — 4B sign-flipped Int32 months + 8B sign-flipped Int64 nanos
    //
    // RDF/XSD durations are split into a year-month component (variable-length months)
    // and a day-time component (precisely countable in nanoseconds). Two durations are
    // equal iff both components match — there is no canonical reduction across them.
    // ------------------------------------------------------------------

    /** A pair {@code (months, nanos)} representing an xsd:duration. */
    public record XsdDuration(int months, long nanos) {}

    /** Encode {@code xsd:duration}. 13 bytes total: tag + 12B payload. */
    public static MemorySegment encodeDuration(int months, long nanos, Arena arena) {
        MemorySegment out = arena.allocate(13);
        out.set(Layouts.BYTE, 0, TAG_XSD_DURATION);
        out.set(Layouts.BE32_U, 1, months ^ Integer.MIN_VALUE);
        out.set(Layouts.BE64_U, 5, nanos ^ Long.MIN_VALUE);
        return out;
    }

    /** Decode {@code xsd:duration}. */
    public static XsdDuration decodeDuration(MemorySegment payload) {
        int months = payload.get(Layouts.BE32_U, 0) ^ Integer.MIN_VALUE;
        long nanos = payload.get(Layouts.BE64_U, 4) ^ Long.MIN_VALUE;
        return new XsdDuration(months, nanos);
    }

    // ------------------------------------------------------------------
    // String-like values
    //
    // The dictionary entry's value length is already known to the reader,
    // so we omit any length prefix. Total payload size minus internal
    // sub-field offsets gives the UTF-8 byte count.
    // ------------------------------------------------------------------

    private static MemorySegment encodeTaggedUtf8(byte tag, String lex, Arena arena) {
        byte[] utf8 = lex.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment out = arena.allocate(1L + utf8.length);
        out.set(Layouts.BYTE, 0, tag);
        if (utf8.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(utf8), 0, out, 1, utf8.length);
        }
        return out;
    }

    private static String decodeUtf8AllOf(MemorySegment payload) {
        long size = payload.byteSize();
        if (size == 0) return "";
        byte[] bytes = payload.asSlice(0, size).toArray(Layouts.BYTE);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Encode {@code xsd:string}. */
    public static MemorySegment encodeXsdString(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_STRING, lex, arena);
    }

    public static String decodeXsdString(MemorySegment payload) {
        return decodeUtf8AllOf(payload);
    }

    /**
     * Encode {@code xsd:anyURI}. Stored as a *literal* URI string (distinct from IRI tag
     * 0x80..0x82).
     */
    public static MemorySegment encodeAnyURI(String uri, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_ANYURI, uri, arena);
    }

    public static String decodeAnyURI(MemorySegment payload) {
        return decodeUtf8AllOf(payload);
    }

    /** A {@code (lex, lang)} pair representing an {@code rdf:langString}. */
    public record LangString(String lex, String lang) {}

    /**
     * Encode {@code rdf:langString}. Layout: {@code [tag][lang-tag-len : u8][lang-tag : ASCII][lex
     * : UTF-8]}.
     *
     * <p>Lang tag is BCP47-style ASCII (length 0–255). Lex order over encoded langStrings is by
     * (lang-tag, lex) — i.e., grouped by language. Documented in SPEC.md.
     *
     * @throws IllegalArgumentException if lang tag exceeds 255 bytes
     */
    public static MemorySegment encodeLangString(String lex, String lang, Arena arena) {
        // Canonicalize the language tag to lowercase. RDF 1.1 Concepts §3.3 fixes "the value
        // space of language tags is always in lower case" and permits "lexical representations
        // of language tags MAY be converted to lower case"; RDF4J's Literal.equals folds tag
        // case accordingly (createLiteral("h","en-US").equals(...,"en-us") == true). So
        // "en-US" and "en-us" are the same value / same RDF4J term. In a content-addressed
        // store this is not cosmetic: without lowercasing, those equal literals would encode to
        // different bytes → different TermIds → broken dedup and a different root hash for
        // logically identical graphs. Locale.ROOT avoids locale-specific casing (Turkish-i).
        byte[] langBytes =
                lang.toLowerCase(java.util.Locale.ROOT)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (langBytes.length > 255) {
            throw new IllegalArgumentException("rdf:langString lang tag exceeds 255 bytes");
        }
        byte[] lexBytes = lex.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long totalSize = 1L + 1 + langBytes.length + lexBytes.length; // tag + 1B len + lang + lex
        MemorySegment out = arena.allocate(totalSize);
        out.set(Layouts.BYTE, 0, TAG_RDF_LANGSTRING);
        out.set(Layouts.BYTE, 1, (byte) langBytes.length);
        if (langBytes.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(langBytes), 0, out, 2, langBytes.length);
        }
        if (lexBytes.length > 0) {
            MemorySegment.copy(
                    MemorySegment.ofArray(lexBytes),
                    0,
                    out,
                    2L + langBytes.length,
                    lexBytes.length);
        }
        return out;
    }

    public static LangString decodeLangString(MemorySegment payload) {
        int langLen = payload.get(Layouts.BYTE, 0) & 0xFF;
        String lang =
                langLen == 0
                        ? ""
                        : new String(
                                payload.asSlice(1, langLen).toArray(Layouts.BYTE),
                                java.nio.charset.StandardCharsets.US_ASCII);
        long lexStart = 1L + langLen;
        long lexLen = payload.byteSize() - lexStart;
        String lex =
                lexLen == 0
                        ? ""
                        : new String(
                                payload.asSlice(lexStart, lexLen).toArray(Layouts.BYTE),
                                java.nio.charset.StandardCharsets.UTF_8);
        return new LangString(lex, lang);
    }

    // ------------------------------------------------------------------
    // Binary literals — term-faithful: verbatim lexical (ADR-0043 Step 6e). The old
    // raw-decoded-bytes
    // value encoding was only PARTIALLY faithful — it over-merged lexically-distinct forms that
    // decode to
    // the same bytes: xsd:hexBinary "0A" vs "0a" (case), and xsd:base64Binary padding/whitespace
    // variants.
    // RDF 1.1 §3.3 term equality is char-by-char, so those are DISTINCT terms; lexical storage
    // keeps them
    // so. Decode is decodeLexical. (No byte[] form is kept — RDF4J literals are label-based; a
    // consumer
    // that wants the bytes parses the lexical via XMLDatatypeUtil / java.util.Base64.)
    // ------------------------------------------------------------------

    /** Encode {@code xsd:base64Binary} — verbatim lexical under {@link #TAG_XSD_BASE64BINARY}. */
    public static MemorySegment encodeBase64Binary(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_BASE64BINARY, lex, arena);
    }

    /** Encode {@code xsd:hexBinary} — verbatim lexical under {@link #TAG_XSD_HEXBINARY}. */
    public static MemorySegment encodeHexBinary(String lex, Arena arena) {
        return encodeTaggedUtf8(TAG_XSD_HEXBINARY, lex, arena);
    }

    // ------------------------------------------------------------------
    // IRIs
    //
    // The actual prefix-id ↔ namespace mapping lives in PrefixTable (separate
    // class, future iter). Encoders here take a raw prefix-id; resolvers build
    // around them.
    // ------------------------------------------------------------------

    /** Encode a short-prefix IRI. Local part is UTF-8. */
    public static MemorySegment encodeShortPrefixIri(int prefixId, String localPart, Arena arena) {
        byte[] utf8 = localPart.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment out = arena.allocate(1L + 4 + utf8.length);
        out.set(Layouts.BYTE, 0, TAG_IRI_SHORT_PREFIX);
        out.set(Layouts.BE32_U, 1, prefixId);
        if (utf8.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(utf8), 0, out, 5, utf8.length);
        }
        return out;
    }

    /** A decoded short-prefix IRI: prefix-id and local part. */
    public record ShortPrefixIri(int prefixId, String localPart) {}

    public static ShortPrefixIri decodeShortPrefixIri(MemorySegment payload) {
        int prefixId = payload.get(Layouts.BE32_U, 0);
        long localLen = payload.byteSize() - 4;
        String local =
                localLen == 0
                        ? ""
                        : new String(
                                payload.asSlice(4, localLen).toArray(Layouts.BYTE),
                                java.nio.charset.StandardCharsets.UTF_8);
        return new ShortPrefixIri(prefixId, local);
    }

    /** Encode a long-prefix IRI (two-level: primary + secondary prefix-id). */
    public static MemorySegment encodeLongPrefixIri(
            int prefixId1, int prefixId2, String localPart, Arena arena) {
        byte[] utf8 = localPart.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment out = arena.allocate(1L + 8 + utf8.length);
        out.set(Layouts.BYTE, 0, TAG_IRI_LONG_PREFIX);
        out.set(Layouts.BE32_U, 1, prefixId1);
        out.set(Layouts.BE32_U, 5, prefixId2);
        if (utf8.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(utf8), 0, out, 9, utf8.length);
        }
        return out;
    }

    public record LongPrefixIri(int prefixId1, int prefixId2, String localPart) {}

    public static LongPrefixIri decodeLongPrefixIri(MemorySegment payload) {
        int prefixId1 = payload.get(Layouts.BE32_U, 0);
        int prefixId2 = payload.get(Layouts.BE32_U, 4);
        long localLen = payload.byteSize() - 8;
        String local =
                localLen == 0
                        ? ""
                        : new String(
                                payload.asSlice(8, localLen).toArray(Layouts.BYTE),
                                java.nio.charset.StandardCharsets.UTF_8);
        return new LongPrefixIri(prefixId1, prefixId2, local);
    }

    /** Encode a full IRI (no prefix-table hit). */
    public static MemorySegment encodeFullIri(String iri, Arena arena) {
        return encodeTaggedUtf8(TAG_IRI_FULL, iri, arena);
    }

    public static String decodeFullIri(MemorySegment payload) {
        return decodeUtf8AllOf(payload);
    }

    // ------------------------------------------------------------------
    // Blank Nodes
    // ------------------------------------------------------------------

    /** Encode a random (UUID-backed) blank node. */
    public static MemorySegment encodeBNodeUuid(java.util.UUID id, Arena arena) {
        MemorySegment out = arena.allocate(17);
        out.set(Layouts.BYTE, 0, TAG_BNODE_UUID);
        out.set(Layouts.BE64_U, 1, id.getMostSignificantBits());
        out.set(Layouts.BE64_U, 9, id.getLeastSignificantBits());
        return out;
    }

    public static java.util.UUID decodeBNodeUuid(MemorySegment payload) {
        return new java.util.UUID(payload.get(Layouts.BE64_U, 0), payload.get(Layouts.BE64_U, 8));
    }

    /** Encode a labelled blank node (caller-provided label). */
    public static MemorySegment encodeBNodeLabel(String label, Arena arena) {
        return encodeTaggedUtf8(TAG_BNODE_LABEL, label, arena);
    }

    public static String decodeBNodeLabel(MemorySegment payload) {
        return decodeUtf8AllOf(payload);
    }

    /**
     * Encode a canonical blank node — produced by URDNA2015 at commit time.
     *
     * <p>The c14n-index is non-negative; produced by an {@code IdentifierIssuer}.
     */
    public static MemorySegment encodeBNodeCanon(int c14nIndex, Arena arena) {
        if (c14nIndex < 0) {
            throw new IllegalArgumentException("canonical BNode index must be non-negative");
        }
        MemorySegment out = arena.allocate(5);
        out.set(Layouts.BYTE, 0, TAG_BNODE_CANON);
        out.set(Layouts.BE32_U, 1, c14nIndex);
        return out;
    }

    public static int decodeBNodeCanon(MemorySegment payload) {
        return payload.get(Layouts.BE32_U, 0);
    }

    // ------------------------------------------------------------------
    // Quoted triples / quads (RDF-star)
    //
    // Components are TermId references — recursion is by id, not by inline bytes.
    // Asserted vs unasserted are distinct tags → distinct TermIds at the dictionary
    // level → independently addressable rows in any index.
    // ------------------------------------------------------------------

    /** A decoded quoted triple. */
    public record QuotedTriple(TermId s, TermId p, TermId o, boolean asserted) {}

    /** A decoded quoted quad (RDF-star with named graph). */
    public record QuotedQuad(TermId s, TermId p, TermId o, TermId c, boolean asserted) {}

    /** Encode a quoted triple (asserted or unasserted). Total size: 25 bytes. */
    public static MemorySegment encodeQuotedTriple(
            TermId s, TermId p, TermId o, boolean asserted, Arena arena) {
        MemorySegment out = arena.allocate(25);
        out.set(
                Layouts.BYTE,
                0,
                asserted ? TAG_QUOTED_TRIPLE_ASSERTED : TAG_QUOTED_TRIPLE_UNASSERTED);
        out.set(Layouts.BE64_U, 1, s.value());
        out.set(Layouts.BE64_U, 9, p.value());
        out.set(Layouts.BE64_U, 17, o.value());
        return out;
    }

    /**
     * Decode a quoted triple. Reads the leading tag to determine the asserted flag.
     *
     * @throws IllegalArgumentException if the leading tag is not 0xC0 or 0xC1
     */
    public static QuotedTriple decodeQuotedTriple(MemorySegment encoded) {
        byte tag = encoded.get(Layouts.BYTE, 0);
        boolean asserted;
        if (tag == TAG_QUOTED_TRIPLE_ASSERTED) asserted = true;
        else if (tag == TAG_QUOTED_TRIPLE_UNASSERTED) asserted = false;
        else
            throw new IllegalArgumentException(
                    "not a quoted triple; tag=0x" + Integer.toHexString(tag & 0xFF));
        return new QuotedTriple(
                TermId.of(encoded.get(Layouts.BE64_U, 1)),
                TermId.of(encoded.get(Layouts.BE64_U, 9)),
                TermId.of(encoded.get(Layouts.BE64_U, 17)),
                asserted);
    }

    /** Encode a quoted quad. Total size: 33 bytes. */
    public static MemorySegment encodeQuotedQuad(
            TermId s, TermId p, TermId o, TermId c, boolean asserted, Arena arena) {
        MemorySegment out = arena.allocate(33);
        out.set(Layouts.BYTE, 0, asserted ? TAG_QUOTED_QUAD_ASSERTED : TAG_QUOTED_QUAD_UNASSERTED);
        out.set(Layouts.BE64_U, 1, s.value());
        out.set(Layouts.BE64_U, 9, p.value());
        out.set(Layouts.BE64_U, 17, o.value());
        out.set(Layouts.BE64_U, 25, c.value());
        return out;
    }

    /**
     * Decode a quoted quad.
     *
     * @throws IllegalArgumentException if the leading tag is not 0xC2 or 0xC3
     */
    public static QuotedQuad decodeQuotedQuad(MemorySegment encoded) {
        byte tag = encoded.get(Layouts.BYTE, 0);
        boolean asserted;
        if (tag == TAG_QUOTED_QUAD_ASSERTED) asserted = true;
        else if (tag == TAG_QUOTED_QUAD_UNASSERTED) asserted = false;
        else
            throw new IllegalArgumentException(
                    "not a quoted quad; tag=0x" + Integer.toHexString(tag & 0xFF));
        return new QuotedQuad(
                TermId.of(encoded.get(Layouts.BE64_U, 1)),
                TermId.of(encoded.get(Layouts.BE64_U, 9)),
                TermId.of(encoded.get(Layouts.BE64_U, 17)),
                TermId.of(encoded.get(Layouts.BE64_U, 25)),
                asserted);
    }

    // ------------------------------------------------------------------
    // Custom-datatype literal (0xE0)
    //
    // For xsd datatypes outside the built-in tag range. The datatype IRI is
    // itself a TermId resolved via the dictionary. Lexical form preserved
    // verbatim — RDF4J's value-factory decides equality semantics.
    // ------------------------------------------------------------------

    /** A custom-datatype literal: datatype IRI (as TermId) + lexical form. */
    public record CustomLiteral(TermId datatypeIri, String lex) {}

    /** Encode a custom-datatype literal. */
    public static MemorySegment encodeCustomLiteral(TermId datatypeIri, String lex, Arena arena) {
        byte[] lexBytes = lex.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment out = arena.allocate(1L + 8 + lexBytes.length);
        out.set(Layouts.BYTE, 0, TAG_CUSTOM_LITERAL);
        out.set(Layouts.BE64_U, 1, datatypeIri.value());
        if (lexBytes.length > 0) {
            MemorySegment.copy(MemorySegment.ofArray(lexBytes), 0, out, 9, lexBytes.length);
        }
        return out;
    }

    public static CustomLiteral decodeCustomLiteral(MemorySegment payload) {
        TermId datatypeIri = TermId.of(payload.get(Layouts.BE64_U, 0));
        long lexLen = payload.byteSize() - 8;
        String lex =
                lexLen == 0
                        ? ""
                        : new String(
                                payload.asSlice(8, lexLen).toArray(Layouts.BYTE),
                                java.nio.charset.StandardCharsets.UTF_8);
        return new CustomLiteral(datatypeIri, lex);
    }
}
