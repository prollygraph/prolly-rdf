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

import com.earasoft.prolly.rdf4j.value.ProllyLiteral;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

/**
 * {@code xsd:double} / {@code xsd:float} special values — positive/negative infinity and NaN.
 * RDF4J's value factory renders these with the XSD canonical lexical forms ({@code "INF"}, {@code
 * "-INF"}, {@code "NaN"}), but {@code Double.parseDouble} only accepts Java's {@code "Infinity"}
 * spelling — so {@code TermEncoder} must not lose them.
 */
class TermEncoderSpecialFloatTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    @Test
    void rdf4j_renders_infinity_as_xsd_INF() {
        // Document the precondition that makes this a problem.
        assertEquals("INF", VF.createLiteral(Double.POSITIVE_INFINITY).getLabel());
        assertEquals("-INF", VF.createLiteral(Double.NEGATIVE_INFINITY).getLabel());
    }

    @Test
    void positive_infinity_double_round_trips() {
        try (Arena a = Arena.ofConfined()) {
            Literal lit = VF.createLiteral(Double.POSITIVE_INFINITY);
            MemorySegment enc =
                    assertDoesNotThrow(
                            () -> TermEncoder.encode(lit, a),
                            "an xsd:double of +INF must be encodable");
            ProllyLiteral back = new ProllyLiteral(enc);
            assertEquals(
                    Double.POSITIVE_INFINITY,
                    back.doubleValue(),
                    "the decoded value must still be +INF");
        }
    }

    @Test
    void negative_infinity_and_nan_double_round_trip() {
        try (Arena a = Arena.ofConfined()) {
            for (double v : new double[] {Double.NEGATIVE_INFINITY, Double.NaN}) {
                Literal lit = VF.createLiteral(v);
                MemorySegment enc =
                        assertDoesNotThrow(
                                () -> TermEncoder.encode(lit, a),
                                "xsd:double " + lit.getLabel() + " must be encodable");
                ProllyLiteral back = new ProllyLiteral(enc);
                if (Double.isNaN(v)) assertTrue(Double.isNaN(back.doubleValue()));
                else assertEquals(v, back.doubleValue());
            }
        }
    }

    @Test
    void infinity_float_round_trips() {
        try (Arena a = Arena.ofConfined()) {
            Literal lit = VF.createLiteral(Float.POSITIVE_INFINITY);
            MemorySegment enc =
                    assertDoesNotThrow(
                            () -> TermEncoder.encode(lit, a),
                            "an xsd:float of +INF must be encodable");
            ProllyLiteral back = new ProllyLiteral(enc);
            assertEquals(Float.POSITIVE_INFINITY, back.floatValue());
        }
    }
}
