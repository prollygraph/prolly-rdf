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
package com.earasoft.prolly.rdf4j.value;

import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.Optional;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.datatypes.XMLDatatypeUtil;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.jspecify.annotations.Nullable;

/**
 * RDF4J {@link Literal} backed by a {@link MemorySegment}.
 *
 * <p>Supports the Phase 1 numeric / temporal / string / langString / binary and custom-datatype
 * tags. Typed accessors ({@code intValue}, {@code doubleValue}, ...) fall through to {@link
 * #getLabel()} parsing via the default Literal interface methods — adequate for v2.0; perf-critical
 * paths should call the decoder directly via {@link TermCodec}.
 *
 * <p>Equality is RDF semantic: same label, same datatype, same language.
 */
public final class ProllyLiteral implements ProllyValue, Literal {

    private final MemorySegment encoded;
    // @Nullable lazy caches: materialized on first getLabel()/getDatatype()/getLanguage() call. The
    // datatype cache doubles as the resolved-custom-datatype slot (set by the 2-arg constructor),
    // so
    // it is null until either the constructor or the tag switch fills it.
    private @Nullable String cachedLabel;
    private @Nullable IRI cachedDatatype;
    private @Nullable Optional<String> cachedLanguage;

    public ProllyLiteral(MemorySegment encoded) {
        this(encoded, null);
    }

    /**
     * @param encoded the tag-prefixed term bytes
     * @param customDatatype the resolved datatype IRI for a {@code TAG_CUSTOM_LITERAL} term — whose
     *     datatype is stored as a Dictionary {@code TermId} this lightweight byte-wrapper cannot
     *     resolve on its own. Pass {@code null} for any built-in tag (the datatype derives from the
     *     tag). Set by {@link DictionaryTermResolver} on the read path; reusing the {@link
     *     #cachedDatatype} slot means {@link #getDatatype()} returns it directly without consulting
     *     the tag switch.
     */
    public ProllyLiteral(MemorySegment encoded, @Nullable IRI customDatatype) {
        this.encoded = encoded;
        this.cachedDatatype = customDatatype;
    }

    private byte tag() {
        return TermCodec.tagOf(encoded);
    }

    private MemorySegment payload() {
        return TermCodec.payloadOf(encoded);
    }

    @Override
    public String getLabel() {
        if (cachedLabel != null) return cachedLabel;
        cachedLabel =
                switch (tag()) {
                    case TermCodec.TAG_BOOLEAN ->
                            TermCodec.decodeLexical(
                                    payload()); // verbatim lexical (term-faithful, ADR-0043)
                    case TermCodec.TAG_XSD_BYTE,
                            TermCodec.TAG_XSD_SHORT,
                            TermCodec.TAG_XSD_INT,
                            TermCodec.TAG_XSD_INTEGER,
                            TermCodec.TAG_XSD_LONG,
                            TermCodec.TAG_XSD_UINT,
                            TermCodec.TAG_XSD_ULONG,
                            TermCodec.TAG_XSD_FLOAT,
                            TermCodec.TAG_XSD_DOUBLE,
                            TermCodec.TAG_XSD_DECIMAL ->
                            TermCodec.decodeLexical(
                                    payload()); // all numeric tags: verbatim lexical
                    // (term-faithful, ADR-0043)
                    case TermCodec.TAG_XSD_GYEAR,
                            TermCodec.TAG_XSD_GYEARMONTH,
                            TermCodec.TAG_XSD_DATE,
                            TermCodec.TAG_XSD_DATETIME,
                            TermCodec.TAG_XSD_TIME ->
                            TermCodec.decodeLexical(
                                    payload()); // all temporal tags: verbatim lexical
                    // (term-faithful, ADR-0043)
                    case TermCodec.TAG_XSD_DURATION -> {
                        var d = TermCodec.decodeDuration(payload());
                        yield "P" + d.months() + "M" + d.nanos() + "N";
                    }
                    case TermCodec.TAG_XSD_UUID -> TermCodec.decodeUuid(payload()).toString();
                    case TermCodec.TAG_XSD_STRING -> TermCodec.decodeXsdString(payload());
                    case TermCodec.TAG_XSD_ANYURI -> TermCodec.decodeAnyURI(payload());
                    case TermCodec.TAG_RDF_LANGSTRING ->
                            TermCodec.decodeLangString(payload()).lex();
                    case TermCodec.TAG_XSD_BASE64BINARY, TermCodec.TAG_XSD_HEXBINARY ->
                            TermCodec.decodeLexical(
                                    payload()); // binary tags: verbatim lexical (term-faithful,
                    // ADR-0043 Step 6e)
                    case TermCodec.TAG_CUSTOM_LITERAL ->
                            TermCodec.decodeCustomLiteral(payload()).lex();
                    default ->
                            throw new IllegalStateException(
                                    "ProllyLiteral wrapping non-literal tag 0x"
                                            + Integer.toHexString(tag() & 0xFF));
                };
        return cachedLabel;
    }

    @Override
    public IRI getDatatype() {
        if (cachedDatatype != null) return cachedDatatype;
        cachedDatatype =
                switch (tag()) {
                    case TermCodec.TAG_BOOLEAN -> XSD.BOOLEAN;
                    case TermCodec.TAG_XSD_BYTE -> XSD.BYTE;
                    case TermCodec.TAG_XSD_SHORT -> XSD.SHORT;
                    case TermCodec.TAG_XSD_INT -> XSD.INT;
                    case TermCodec.TAG_XSD_INTEGER -> XSD.INTEGER;
                    case TermCodec.TAG_XSD_LONG -> XSD.LONG;
                    case TermCodec.TAG_XSD_UINT -> XSD.UNSIGNED_INT;
                    case TermCodec.TAG_XSD_ULONG -> XSD.UNSIGNED_LONG;
                    case TermCodec.TAG_XSD_FLOAT -> XSD.FLOAT;
                    case TermCodec.TAG_XSD_DOUBLE -> XSD.DOUBLE;
                    case TermCodec.TAG_XSD_DECIMAL -> XSD.DECIMAL;
                    case TermCodec.TAG_XSD_GYEAR -> XSD.GYEAR;
                    case TermCodec.TAG_XSD_GYEARMONTH -> XSD.GYEARMONTH;
                    case TermCodec.TAG_XSD_DATE -> XSD.DATE;
                    case TermCodec.TAG_XSD_DATETIME -> XSD.DATETIME;
                    case TermCodec.TAG_XSD_TIME -> XSD.TIME;
                    case TermCodec.TAG_XSD_DURATION -> XSD.DURATION;
                    case TermCodec.TAG_XSD_UUID ->
                            Values.iri("http://www.w3.org/2001/XMLSchema#UUID");
                    case TermCodec.TAG_XSD_STRING -> XSD.STRING;
                    case TermCodec.TAG_XSD_ANYURI -> XSD.ANYURI;
                    case TermCodec.TAG_RDF_LANGSTRING -> RDF.LANGSTRING;
                    case TermCodec.TAG_XSD_BASE64BINARY -> XSD.BASE64BINARY;
                    case TermCodec.TAG_XSD_HEXBINARY -> XSD.HEXBINARY;
                    case TermCodec.TAG_CUSTOM_LITERAL ->
                            // Unreachable for a correctly-built custom literal:
                            // DictionaryTermResolver resolves the
                            // datatype TermId and passes it to the 2-arg constructor, so
                            // cachedDatatype is non-null
                            // and the early return above fires. Reaching here means a custom
                            // literal was wrapped via
                            // the 1-arg constructor without its resolved datatype (a construction
                            // bug). (ADR-0043 6c.)
                            throw new IllegalStateException(
                                    "custom-datatype ProllyLiteral built without its resolved datatype IRI — "
                                            + "wrap via DictionaryTermResolver (the 2-arg constructor), not new ProllyLiteral(bytes)");
                    default ->
                            throw new IllegalStateException(
                                    "ProllyLiteral non-literal tag 0x"
                                            + Integer.toHexString(tag() & 0xFF));
                };
        return cachedDatatype;
    }

    @Override
    public Optional<String> getLanguage() {
        if (cachedLanguage != null) return cachedLanguage;
        if (tag() == TermCodec.TAG_RDF_LANGSTRING) {
            String lang = TermCodec.decodeLangString(payload()).lang();
            cachedLanguage = lang.isEmpty() ? Optional.empty() : Optional.of(lang);
        } else {
            cachedLanguage = Optional.empty();
        }
        return cachedLanguage;
    }

    @Override
    public String stringValue() {
        return getLabel();
    }

    // -- Typed accessors — minimal pass-through to label parsing for v2.0.
    //    Production code on the hot path should use TermCodec directly to skip
    //    the String round-trip.

    @Override
    public byte byteValue() {
        return Byte.parseByte(getLabel());
    }

    @Override
    public short shortValue() {
        return Short.parseShort(getLabel());
    }

    @Override
    public int intValue() {
        return Integer.parseInt(getLabel());
    }

    @Override
    public long longValue() {
        return Long.parseLong(getLabel());
    }

    @Override
    public java.math.BigInteger integerValue() {
        return new java.math.BigInteger(getLabel());
    }

    @Override
    public java.math.BigDecimal decimalValue() {
        return new java.math.BigDecimal(getLabel());
    }

    // XSD-aware parse of the (now verbatim-lexical) label — XMLDatatypeUtil handles xsd's "INF"/
    // "-INF"/"NaN" (which Float/Double.parse reject) for any tag; the float analog of
    // booleanValue().
    @Override
    public float floatValue() {
        return XMLDatatypeUtil.parseFloat(getLabel());
    }

    @Override
    public double doubleValue() {
        return XMLDatatypeUtil.parseDouble(getLabel());
    }

    // Delegate to RDF4J's own xsd:boolean parser for exact SimpleLiteral parity: it collapses
    // whitespace, maps {1,true}->true and {0,false}->false, and throws on an ill-typed value (the
    // Literal contract — an ill-typed term has no boolean *value*, though it stays a faithful
    // *term*).
    // Boolean.parseBoolean would wrongly return false for the now-verbatim "1". ADR-0043.
    @Override
    public boolean booleanValue() {
        return XMLDatatypeUtil.parseBoolean(getLabel());
    }

    @Override
    public javax.xml.datatype.XMLGregorianCalendar calendarValue() {
        try {
            return javax.xml.datatype.DatatypeFactory.newInstance()
                    .newXMLGregorianCalendar(getLabel());
        } catch (javax.xml.datatype.DatatypeConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public java.time.temporal.TemporalAccessor temporalAccessorValue() {
        return java.time.OffsetDateTime.parse(getLabel());
    }

    @Override
    public java.time.temporal.TemporalAmount temporalAmountValue() {
        return java.time.Duration.parse(getLabel());
    }

    @Override
    public org.eclipse.rdf4j.model.base.CoreDatatype getCoreDatatype() {
        return org.eclipse.rdf4j.model.base.CoreDatatype.from(getDatatype());
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Literal other)) return false;
        return getLabel().equals(other.getLabel())
                && getDatatype().equals(other.getDatatype())
                && Objects.equals(getLanguage(), other.getLanguage());
    }

    @Override
    public int hashCode() {
        return getLabel().hashCode();
    }

    @Override
    public String toString() {
        // RDF/Turtle-ish representation
        StringBuilder sb = new StringBuilder("\"").append(getLabel()).append("\"");
        Optional<String> lang = getLanguage();
        if (lang.isPresent()) {
            sb.append("@").append(lang.get());
        } else if (tag() != TermCodec.TAG_XSD_STRING) {
            sb.append("^^<").append(getDatatype().stringValue()).append(">");
        }
        return sb.toString();
    }

    /**
     * Serialization proxy: RDF4J's {@code Value} contract extends {@link java.io.Serializable}, but
     * this instance is backed by a non-serializable {@link MemorySegment}. Replace it on write with
     * the plain, fully-serializable {@link SimpleValueFactory} equivalent — language-tagged or
     * datatyped to match (the segment is never written). See {@code
     * bugs/rdf4j-repository-connection-contract-triage.md}.
     */
    private Object writeReplace() {
        Optional<String> lang = getLanguage();
        return lang.isPresent()
                ? SimpleValueFactory.getInstance().createLiteral(getLabel(), lang.get())
                : SimpleValueFactory.getInstance().createLiteral(getLabel(), getDatatype());
    }

    // ---- helpers ----

    // HISTORICAL (bug fixed 2026-05-15, caught by the W3C SPARQL 1.1 suite; the fix is now
    // SUBSUMED):
    // getLabel() once rendered xsd:dateTime / xsd:time via Java's
    // OffsetDateTime/OffsetTime.toString(),
    // which OMITS the seconds field when seconds AND nanos are zero ("…T23:59Z") — not a valid XSD
    // lexical form, so RDF4J function evaluation (YEAR(), TZ(), …) threw and silently returned
    // unbound.
    // The fix was an explicit XSD formatter (XSD_DATETIME / XSD_TIME). Term-faithful storage
    // (ADR-0043 Step 6) retires BOTH formatters: the verbatim lexical form IS the label, so there
    // is
    // no rendering step left to get wrong. (pad2/yearOnly/pad4 retired with gYear/gYearMonth/date
    // too;
    // bytesToHex/HEX retired with xsd:hexBinary going verbatim lexical — Step 6e.)
}
