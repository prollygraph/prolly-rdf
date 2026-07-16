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
import java.lang.foreign.ValueLayout;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * Golden-vector fixture for {@link TermEncoder} (plan Step 11, S-3) — a checked-in per-datatype
 * table of (term &rarr; encoded hex). <b>Java-self-consistent</b>: it pins the CURRENT encoding so
 * a future change to the on-disk term format is <i>caught</i>. This complements the round-trip
 * property (Step 8): {@code decode(encode(v))==v} would stay green under a <i>symmetric</i>
 * encode+decode change, while the persisted bytes silently shifted — a format break only a
 * byte-level golden sees. Go byte-parity is a separate project (BITCOMPAT_FINDINGS); this
 * characterizes the Java frontier the way {@code CrossLanguageFixtureTest} does for the tree.
 *
 * <p><b>What the vectors reveal about the on-disk term format.</b> Each encoded term begins with a
 * 1-byte <b>type tag</b>, then a type-specific payload — readable straight off the golden hex:
 *
 * <ul>
 *   <li>{@code 0x82} <b>IRI</b> — the full-IRI form, then the IRI's UTF-8 bytes ({@link
 *       TermEncoder} always emits {@code 0x82}); {@code 0x40} <b>plain literal</b> (UTF-8 label);
 *       {@code 0x41} <b>language literal</b> ({@code len}+lang-tag, then the UTF-8 label).
 *   <li>{@code 0x10} <b>xsd:boolean</b> ({@code 00}/{@code 01}); {@code 0x13} <b>xsd:int</b>,
 *       {@code 0x15} <b>xsd:long</b>, {@code 0x1f} <b>xsd:integer</b>, {@code 0x1a}
 *       <b>xsd:decimal</b>, {@code 0x19} <b>xsd:double</b>; {@code 0x20} <b>xsd:dateTime</b>,
 *       {@code 0x21} <b>xsd:date</b>.
 * </ul>
 *
 * <p>The numeric payloads are <b>order-preserving</b>: each is transformed so that an
 * <i>unsigned</i> byte comparison of two encodings equals the values' semantic order — the term
 * bytes are index keys, so byte-order must <i>be</i> value-order. It shows in the golden: {@code
 * xsd:int 42} &rarr; {@code 8000002a} (the sign bit is flipped, so every negative int sorts below
 * every positive one); {@code xsd:long Long.MIN_VALUE} &rarr; {@code 0000000000000000} (the
 * smallest key); doubles use the IEEE-754 total-order transform — {@code 1.5}&rarr;{@code bff8…},
 * {@code +∞}&rarr;{@code fff0…}, {@code NaN}&rarr;{@code fff8…} (positive: set the sign bit;
 * negative: flip all bits). This is the <i>positive</i> side of the order-preservation property —
 * contrast the dictionary {@code TermId}, whose order is hash/id order, not value order (see {@code
 * TermIdOrderingTrapTest} and {@code newcomer-docs/foundations/the-on-disk-format.md}).
 *
 * <p>To re-pin after an <i>intentional</i> format change: clear {@link #GOLDEN}, run this test (it
 * prints a paste-ready block), and paste it back — the diff is the format change, made deliberate.
 */
class TermCodecGoldenVectorTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private static String hex(Value v) {
        try (Arena arena = Arena.ofConfined()) {
            byte[] b = TermEncoder.encode(v, arena).toArray(ValueLayout.JAVA_BYTE);
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            return sb.toString();
        }
    }

    /** (label &rarr; term): representative + boundary terms across the encodable datatypes. */
    private static Map<String, Value> vectors() {
        Map<String, Value> m = new LinkedHashMap<>();
        m.put("iri", VF.createIRI("http://example.org/Thing"));
        m.put("plain-literal", VF.createLiteral("hello"));
        m.put("lang-en", VF.createLiteral("hello", "en"));
        m.put("boolean-true", VF.createLiteral(true));
        m.put("int-42", VF.createLiteral(42));
        m.put("long-min", VF.createLiteral(Long.MIN_VALUE));
        m.put("integer-big", VF.createLiteral(new BigInteger("123456789012345678901234567890")));
        m.put("decimal-pi", VF.createLiteral(new BigDecimal("3.14159")));
        m.put("double-1.5", VF.createLiteral(1.5d));
        m.put("double-pos-inf", VF.createLiteral(Double.POSITIVE_INFINITY));
        m.put("double-nan", VF.createLiteral(Double.NaN));
        m.put("date", VF.createLiteral("2026-06-10", XSD.DATE));
        m.put("datetime-z", VF.createLiteral("2026-06-10T12:00:00Z", XSD.DATETIME));
        m.put("time-z", VF.createLiteral("12:30:00Z", XSD.TIME));
        return m;
    }

    /** The pinned golden. A change to any value here flags a term-format shift. */
    private static final Map<String, String> GOLDEN =
            Map.ofEntries(
                    Map.entry("iri", "82687474703a2f2f6578616d706c652e6f72672f5468696e67"),
                    Map.entry("plain-literal", "4068656c6c6f"),
                    Map.entry("lang-en", "4102656e68656c6c6f"),
                    Map.entry(
                            "boolean-true",
                            "1074727565"), // [0x10]"true" — term-faithful lexical (ADR-0043; was
                    // value "1001")
                    Map.entry(
                            "int-42",
                            "133432"), // [0x13]"42" — term-faithful lexical (ADR-0043 Step 4b; was
                    // value "138000002a")
                    Map.entry(
                            "long-min",
                            "152d39323233333732303336383534373735383038"), // [0x15]"-9223372036854775808" — lexical (ADR-0043; was value "150000000000000000")
                    Map.entry(
                            "integer-big",
                            "14313233343536373839303132333435363738393031323334353637383930"), // [0x14]"123456789012345678901234567890" — lexical (was TAG_XSD_INTEGER_BIG 0x1f…)
                    Map.entry(
                            "decimal-pi",
                            "1a332e3134313539"), // [0x1a]"3.14159" — lexical (ADR-0043 Step 6d; was
                    // value "1a8504cb2f")
                    Map.entry(
                            "double-1.5",
                            "19312e35"), // [0x19]"1.5" — lexical (ADR-0043 Step 5; was value
                    // "19bff8…")
                    Map.entry("double-pos-inf", "19494e46"), // [0x19]"INF"
                    Map.entry("double-nan", "194e614e"), // [0x19]"NaN"
                    Map.entry(
                            "date",
                            "21323032362d30362d3130"), // [0x21]"2026-06-10" — lexical (ADR-0043
                    // Step 6; was value "2187ea060a")
                    Map.entry(
                            "datetime-z",
                            "20323032362d30362d31305431323a30303a30305a"), // [0x20]"2026-06-10T12:00:00Z" — lexical (ADR-0043 Step 6; was value "20819eb1…")
                    Map.entry(
                            "time-z",
                            "2231323a33303a30305a") // [0x22]"12:30:00Z" — lexical (ADR-0043 Step 6,
                    // time; was value 9-byte UInt48+tz)
                    );

    @Test
    void golden_vectors_are_byte_stable() {
        StringBuilder regen = new StringBuilder("\n=== TermCodec golden (paste into GOLDEN) ===\n");
        for (Map.Entry<String, Value> e : vectors().entrySet()) {
            String actual = hex(e.getValue());
            regen.append("        Map.entry(\"")
                    .append(e.getKey())
                    .append("\", \"")
                    .append(actual)
                    .append("\"),\n");
            if (GOLDEN.containsKey(e.getKey())) {
                assertEquals(
                        GOLDEN.get(e.getKey()),
                        actual,
                        "term-codec encoding for '"
                                + e.getKey()
                                + "' changed — a format shift. If "
                                + "intentional, re-pin GOLDEN (and version the format); else a real regression.");
            }
        }
        if (GOLDEN.size() < vectors().size()) {
            System.out.println(regen); // capture mode: nothing pinned yet, dump for pasting
        }
    }
}
