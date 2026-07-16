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
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;

/**
 * Dispatcher: encode an arbitrary RDF4J {@link Value} into a tag-prefixed byte segment using the
 * appropriate {@link TermCodec} method.
 *
 * <p>For typed literals, the datatype IRI is matched against built-in XSD types and dispatched to
 * the corresponding fixed-width encoder. For unrecognized datatypes the caller must use {@link
 * TermCodec#encodeCustomLiteral} directly (it needs a datatype-IRI {@link TermId} that this encoder
 * cannot produce).
 *
 * <p>For IRIs, this encoder always uses the {@code 0x82} full-IRI form. A {@code PrefixTable}-aware
 * encoder that prefers {@code 0x80} short-prefix form arrives later (Phase 2 Sail integration).
 */
public final class TermEncoder {
    private TermEncoder() {}

    /**
     * Encode an arbitrary {@link Value}. Returns a fresh MemorySegment allocated from {@code arena}
     * containing the {@code [tag][payload]} bytes.
     *
     * @throws IllegalArgumentException if the value is a Triple (use {@link
     *     TermCodec#encodeQuotedTriple} directly) or a custom-datatype Literal (use {@link
     *     TermCodec#encodeCustomLiteral})
     */
    public static MemorySegment encode(Value value, Arena arena) {
        if (value instanceof IRI iri) {
            return TermCodec.encodeFullIri(iri.stringValue(), arena);
        }
        if (value instanceof BNode bn) {
            return TermCodec.encodeBNodeLabel(bn.getID(), arena);
        }
        if (value instanceof Literal lit) {
            return encodeLiteral(lit, arena);
        }
        throw new IllegalArgumentException(
                "unsupported Value kind: "
                        + value.getClass().getName()
                        + " (Triple requires TermIds, use TermCodec.encodeQuotedTriple)");
    }

    private static MemorySegment encodeLiteral(Literal lit, Arena arena) {
        // Language-tagged literals are rdf:langString regardless of declared datatype.
        if (lit.getLanguage().isPresent()) {
            return TermCodec.encodeLangString(lit.getLabel(), lit.getLanguage().get(), arena);
        }
        IRI dt = lit.getDatatype();
        // Dispatch on datatype IRI — uses RDF4J's XSD/RDF constants (deterministic equality).
        if (dt.equals(XSD.STRING)) return TermCodec.encodeXsdString(lit.getLabel(), arena);
        if (dt.equals(XSD.ANYURI)) return TermCodec.encodeAnyURI(lit.getLabel(), arena);
        if (dt.equals(XSD.BOOLEAN)) return TermCodec.encodeBoolean(lit.getLabel(), arena);
        if (dt.equals(XSD.BYTE)) return TermCodec.encodeInt8(lit.getLabel(), arena);
        if (dt.equals(XSD.SHORT)) return TermCodec.encodeInt16(lit.getLabel(), arena);
        if (dt.equals(XSD.INT)) return TermCodec.encodeInt32(lit.getLabel(), arena);
        if (dt.equals(XSD.INTEGER)) return encodeXsdInteger(lit.getLabel(), arena);
        // xsd:integer subtypes — LOSSY Dictionary-less fallback (DTYPE-1, Step 6b). On the Sail
        // write
        // path these route through the custom path instead (isDedicatedDatatype excludes them),
        // which
        // preserves the exact subtype IRI; this arm only fires where no Dictionary is available
        // (e.g.
        // ProllyValueFactory's RDF-star component path), collapsing onto TAG_XSD_INTEGER. OWL
        // ontologies
        // use xsd:nonNegativeInteger for owl:cardinality; the rest round out the XSD integer
        // hierarchy.
        if (dt.equals(XSD.NON_NEGATIVE_INTEGER)
                || dt.equals(XSD.POSITIVE_INTEGER)
                || dt.equals(XSD.NON_POSITIVE_INTEGER)
                || dt.equals(XSD.NEGATIVE_INTEGER)
                || dt.equals(XSD.UNSIGNED_BYTE)
                || dt.equals(XSD.UNSIGNED_SHORT)) {
            return encodeXsdInteger(lit.getLabel(), arena);
        }
        if (dt.equals(XSD.LONG)) return TermCodec.encodeLong(lit.getLabel(), arena);
        if (dt.equals(XSD.UNSIGNED_INT)) return TermCodec.encodeUInt32(lit.getLabel(), arena);
        if (dt.equals(XSD.UNSIGNED_LONG)) return TermCodec.encodeUInt64(lit.getLabel(), arena);
        // Use RDF4J's own xsd-aware accessors, NOT Float/Double.parseDouble:
        // Term-faithful: store the verbatim lexical form. No parse — so the "INF"/"-INF"/"NaN" and
        // exponent-case ("1.0E0" vs "1.0e0") forms are preserved as distinct terms, and the old
        // problem (Java's parser rejecting xsd's "INF") simply does not arise.
        if (dt.equals(XSD.FLOAT)) return TermCodec.encodeFloat32(lit.getLabel(), arena);
        if (dt.equals(XSD.DOUBLE)) return TermCodec.encodeFloat64(lit.getLabel(), arena);
        if (dt.equals(XSD.DECIMAL))
            return TermCodec.encodeDecimal(
                    lit.getLabel(),
                    arena); // verbatim lexical (term-faithful, ADR-0043 Step 6d; no BigDecimal
        // parse → over-merge + ill-typed-reject both gone)
        if (dt.equals(RDF.LANGSTRING))
            return TermCodec.encodeLangString(lit.getLabel(), lit.getLanguage().orElse(""), arena);
        if (dt.equals(XSD.DATE)) return encodeXsdDate(lit.getLabel(), arena);
        if (dt.equals(XSD.GYEAR)) return encodeXsdGYear(lit.getLabel(), arena);
        if (dt.equals(XSD.GYEARMONTH)) return encodeXsdGYearMonth(lit.getLabel(), arena);
        if (dt.equals(XSD.DATETIME)) return encodeXsdDateTime(lit.getLabel(), arena);
        if (dt.equals(XSD.TIME)) return encodeXsdTime(lit.getLabel(), arena);
        if (dt.equals(XSD.BASE64BINARY))
            return TermCodec.encodeBase64Binary(
                    lit.getLabel(), arena); // verbatim lexical (term-faithful, ADR-0043 Step 6e)
        if (dt.equals(XSD.HEXBINARY))
            return TermCodec.encodeHexBinary(
                    lit.getLabel(),
                    arena); // verbatim lexical (no hex-decode → "0A" vs "0a" stay distinct terms)

        throw new IllegalArgumentException(
                "unsupported datatype "
                        + dt.stringValue()
                        + " — use TermCodec.encodeCustomLiteral with a Dictionary-allocated TermId");
    }

    /**
     * The datatype IRIs with a <b>faithful</b> dedicated tag — one that maps 1:1 to this exact
     * datatype IRI (ADR-0043 D-1). This is the decision boundary the Sail write path uses: a
     * datatype NOT in this set is routed through {@link TermCodec#encodeCustomLiteral} (a
     * Dictionary-allocated datatype-IRI {@code TermId} + verbatim lexical) so its exact IRI
     * survives — see {@code DictionaryTermEncoder}.
     *
     * <p><b>Why the six derived integers are NOT here</b> ({@code nonNegativeInteger}, {@code
     * positiveInteger}, {@code negativeInteger}, {@code nonPositiveInteger}, {@code unsignedShort},
     * {@code unsignedByte}): they have no faithful tag — the dispatch above <em>lossily</em>
     * collapses them onto {@code TAG_XSD_INTEGER}, losing the subtype IRI. That collapse is {@code
     * DTYPE-1}. Excluding them here routes them through the Sail's custom path, where the exact IRI
     * <em>is</em> preserved (Step 6b). The dispatch keeps the lossy collapse only as a
     * <b>Dictionary-less fallback</b> (e.g. the free-standing {@code ProllyValueFactory} RDF-star
     * component path, which has no Dictionary to allocate a datatype-IRI {@code TermId}) — there a
     * lossy collapse beats a hard throw. So, unlike a truly-custom datatype, {@code
     * isDedicatedDatatype} returning {@code false} for these does NOT imply {@link #encode} throws.
     * {@code TermEncoderTest} guards the set against the dispatch (every faithful tag encodes; a
     * truly-custom datatype throws).
     */
    private static final java.util.Set<IRI> DEDICATED_DATATYPES =
            java.util.Set.of(
                    XSD.STRING,
                    XSD.ANYURI,
                    XSD.BOOLEAN,
                    XSD.BYTE,
                    XSD.SHORT,
                    XSD.INT,
                    XSD.INTEGER,
                    XSD.LONG,
                    XSD.UNSIGNED_INT,
                    XSD.UNSIGNED_LONG,
                    XSD.FLOAT,
                    XSD.DOUBLE,
                    XSD.DECIMAL,
                    RDF.LANGSTRING,
                    XSD.DATE,
                    XSD.GYEAR,
                    XSD.GYEARMONTH,
                    XSD.DATETIME,
                    XSD.TIME,
                    XSD.BASE64BINARY,
                    XSD.HEXBINARY);

    /**
     * Whether {@code dt} has a <b>faithful</b> dedicated tag (1:1 with this exact datatype IRI). A
     * datatype not in this set is routed through {@link TermCodec#encodeCustomLiteral} at the Sail
     * write path ({@code DictionaryTermEncoder}) so its exact IRI survives — both truly-custom
     * datatypes (DTYPE-2) and the six derived integers (DTYPE-1, whose dedicated-looking encoding
     * is a lossy collapse onto {@code xsd:integer}).
     *
     * <p><b>{@code false} means "no faithful tag", not "unencodable":</b> {@link #encode} still
     * lossily encodes the six derived integers as a Dictionary-less fallback (see the set's docs).
     * Only a truly-custom datatype makes {@code encode} throw.
     *
     * @param dt the literal's datatype IRI (language-tagged literals are {@code rdf:langString},
     *     handled by the language path — not relevant here)
     * @return {@code true} iff {@code dt} has a faithful 1:1 dedicated tag
     */
    public static boolean isDedicatedDatatype(IRI dt) {
        return DEDICATED_DATATYPES.contains(dt);
    }

    private static MemorySegment encodeXsdInteger(String label, Arena arena) {
        // Term-faithful: store the verbatim lexical form (any magnitude) under TAG_XSD_INTEGER —
        // no long-vs-big bifurcation (that was a value-encoding artifact; ADR-0043). xsd:integer
        // itself
        // is faithful (1:1 tag). The six derived integers reach here ONLY on a Dictionary-less path
        // — a
        // lossy collapse onto this tag (their subtype IRI is lost); the Sail write path routes them
        // through the custom path, which preserves the exact IRI (DTYPE-1 fixed there, Step 6b).
        return TermCodec.encodeInteger(label, arena);
    }

    private static MemorySegment encodeXsdDate(String label, Arena arena) {
        // Term-faithful: store the verbatim lexical form — no parse. Any year (BCE "-…", 5+ digit)
        // and
        // even a timezoned date (which the old fixed-width encoding rejected) now round-trip
        // exactly.
        return TermCodec.encodeDate(label, arena);
    }

    private static MemorySegment encodeXsdGYear(String label, Arena arena) {
        return TermCodec.encodeGYear(label, arena); // verbatim lexical (term-faithful, ADR-0043)
    }

    private static MemorySegment encodeXsdDateTime(String label, Arena arena) {
        // Term-faithful: store the verbatim lexical form — no parse. This also fixes the old value
        // encoding's tz-absent wart: a tz-less "2026-05-15T12:00:00" now round-trips exactly (it
        // was
        // coerced to UTC "…Z"), and "…Z" vs "…+00:00" (and fractional-second variants) stay
        // distinct
        // terms. (RDF4J already validated the lexical at literal creation; an ill-typed value is a
        // faithful term per the ill-typed-literals corollary, not the codec's to reject.)
        return TermCodec.encodeDateTime(label, arena);
    }

    private static MemorySegment encodeXsdTime(String label, Arena arena) {
        // Term-faithful: store the verbatim lexical form — no parse. This retires the old value
        // encoding's warts exactly as xsd:dateTime did: a tz-less time is no longer coerced to UTC,
        // "…Z" vs "…+00:00" stay distinct terms, sub-nanosecond/extra-fractional and the XSD
        // end-of-day "24:00:00" survive, and there is no tz-whole-minutes/Int16 constraint. With
        // this,
        // xsd:time was the LAST value-encoded temporal — so `unencodableTemporal` lost its only
        // caller
        // and was removed; no temporal validates at encode time now (an ill-typed temporal is a
        // faithful RDF term per the ill-typed-literals corollary, ADR-0043).
        return TermCodec.encodeTime(label, arena);
    }

    private static MemorySegment encodeXsdGYearMonth(String label, Arena arena) {
        return TermCodec.encodeGYearMonth(
                label, arena); // verbatim lexical (term-faithful, ADR-0043)
    }
    // (requireNoTimezone + TZ_OFFSET_TAIL removed — they rejected timezoned date/gYear/gYearMonth
    // for
    //  the old fixed-width encoding; lexical storage accepts any well-formed lexical, so they're
    // moot.)
    // (hexDecode removed — xsd:hexBinary is verbatim lexical now (Step 6e); no decode on the write
    // path.)
}
