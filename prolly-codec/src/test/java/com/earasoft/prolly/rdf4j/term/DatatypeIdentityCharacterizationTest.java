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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;

/**
 * CHARACTERIZATION of the datatype-IRI-axis gaps in the term encoding, documented in {@code
 * spec-compliance/semantics/datatype-identity.md}. These are bijection violations on the
 * <em>datatype IRI</em> component of RDF term identity (the lexical-form axis is covered by {@link
 * com.earasoft.prolly.rdf4j.term.TermIdentityBijectionTest} / lexical-fidelity.md).
 *
 * <p>RDF 1.1 §3.3: term equality compares the datatype IRI character-by-character, so literals with
 * the same lexical form but different datatype IRIs are <b>different terms</b>.
 *
 * <p><b>This is a CODEC-level characterization, and the datatype-IRI fix does NOT live at the codec
 * (corrected ADR-0043 Step 6a/6b).</b> {@link TermEncoder} has no Dictionary, so it cannot allocate
 * a datatype-IRI {@code TermId}; the faithful {@code (datatype IRI, lexical)} storage is therefore
 * a <em>Sail</em>-level behavior ({@code DictionaryTermEncoder} + {@code TermFaithfulSparqlTest}),
 * not a codec one. So these codec assertions <b>do not flip</b> — they pin the codec's two
 * permanent non-faithful behaviors that the Sail layer compensates for: (A) the six derived
 * integers collapse onto {@code TAG_XSD_INTEGER} as a lossy Dictionary-less fallback (the Sail
 * routes them custom to keep the IRI), and (B) any datatype without a faithful tag throws (the Sail
 * stores it via the dictionary). The earlier framing — "the fixes flip these assertions" — was
 * wrong about the layer; corrected here.
 *
 * <p>Confound-free: different datatypes ⇒ {@code !Value.equals}, so collapse to one encoding is
 * genuine over-merge, not a Set artifact.
 */
class DatatypeIdentityCharacterizationTest {

    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private static byte[] enc(String lex, IRI dt) {
        try (Arena a = Arena.ofConfined()) {
            return TermEncoder.encode(VF.createLiteral(lex, dt), a).toArray(ValueLayout.JAVA_BYTE);
        }
    }

    // ---- GAP A: derived integers collapse onto xsd:integer at the CODEC (lossy Dictionary-less
    // fallback) ----
    // These six are distinct RDF terms from xsd:integer (different datatype IRI) yet encode
    // identically
    // at the codec. This assertion does NOT flip (corrected Step 6b): the codec collapse is the
    // permanent
    // Dictionary-less fallback; the datatype-faithful fix is at the Sail, where
    // DictionaryTermEncoder routes
    // them custom and preserves the exact IRI (proven by TermFaithfulSparqlTest's derived-integer
    // round-trips).

    @Test
    void derived_integer_types_collapse_onto_integer_at_the_codec() {
        byte[] integer5 = enc("5", XSD.INTEGER);
        IRI[] collapsing = {
            XSD.NON_NEGATIVE_INTEGER, XSD.POSITIVE_INTEGER, XSD.NEGATIVE_INTEGER,
            XSD.NON_POSITIVE_INTEGER, XSD.UNSIGNED_SHORT, XSD.UNSIGNED_BYTE,
        };
        for (IRI dt : collapsing) {
            // NEGATIVE_INTEGER/NON_POSITIVE need a negative sample; value is irrelevant to the
            // datatype-collapse, so use a sample each accepts.
            String lex =
                    (dt == XSD.NEGATIVE_INTEGER || dt == XSD.NON_POSITIVE_INTEGER) ? "-5" : "5";
            byte[] base =
                    (dt == XSD.NEGATIVE_INTEGER || dt == XSD.NON_POSITIVE_INTEGER)
                            ? enc("-5", XSD.INTEGER)
                            : integer5;
            assertArrayEquals(
                    base,
                    enc(lex, dt),
                    "CODEC FALLBACK: "
                            + dt.getLocalName()
                            + " collapses onto xsd:integer here (no Dictionary); "
                            + "the Sail's custom path keeps the IRI — see TermFaithfulSparqlTest");
        }
    }

    @Test
    void fixed_width_integer_types_keep_their_datatype() {
        // These six are already datatype-faithful (the inconsistency that shows the collapse is
        // accidental). Distinct datatype IRI ⇒ distinct bytes — the correct behavior.
        byte[] integer5 = enc("5", XSD.INTEGER);
        for (IRI dt :
                new IRI[] {
                    XSD.INT, XSD.LONG, XSD.SHORT, XSD.BYTE, XSD.UNSIGNED_INT, XSD.UNSIGNED_LONG
                }) {
            assertFalse(
                    Arrays.equals(integer5, enc("5", dt)),
                    dt.getLocalName() + " must keep its datatype IRI (distinct from xsd:integer)");
        }
    }

    // ---- GAP B: datatypes without a faithful tag throw at the CODEC — BY DESIGN (corrected Step
    // 6a) ----
    // Standard XSD long-tail types + all custom datatypes throw on TermEncoder.encode, because the
    // codec
    // has no Dictionary to allocate a datatype-IRI TermId. This is NOT a gap to flip: it is the
    // codec's
    // designed boundary. The Sail DOES store these faithfully now — DictionaryTermEncoder interns
    // the
    // datatype IRI and routes them through encodeCustomLiteral (proven end-to-end by
    // TermFaithfulSparqlTest's
    // custom-datatype round-trips). So this assertion pins the codec contract, not a divergence.

    @Test
    void unsupported_datatypes_throw_at_the_codec_by_design() {
        // Valid lexical per type, so the throw is "unsupported datatype", not a parse error.
        Object[][] cases = {
            {XSD.TOKEN, "foo"}, {XSD.NORMALIZEDSTRING, "foo"}, {XSD.LANGUAGE, "en"},
            {XSD.NAME, "foo"}, {XSD.NCNAME, "foo"}, {XSD.NMTOKEN, "foo"},
            {XSD.DATETIMESTAMP, "2020-01-01T00:00:00Z"}, {XSD.GMONTH, "--01"}, {XSD.GDAY, "---01"},
            {XSD.GMONTHDAY, "--01-01"}, {XSD.DURATION, "P1Y"}, {XSD.DAYTIMEDURATION, "P1D"},
            {XSD.YEARMONTHDURATION, "P1Y"}, {VF.createIRI("http://example.org/myType"), "anything"},
        };
        for (Object[] c : cases) {
            IRI dt = (IRI) c[0];
            String lex = (String) c[1];
            assertThrows(
                    IllegalArgumentException.class,
                    () -> enc(lex, dt),
                    "BY DESIGN: "
                            + dt
                            + " has no faithful tag so the CODEC throws (no Dictionary); "
                            + "the Sail stores it faithfully via DictionaryTermEncoder");
        }
    }
}
