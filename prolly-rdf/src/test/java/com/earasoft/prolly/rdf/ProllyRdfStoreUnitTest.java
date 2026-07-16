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
package com.earasoft.prolly.rdf;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Coverage for the static v1-encoding helpers on {@link ProllyRdfStore}.
 *
 * <p>These functions are the shared contract between the Jena and RDF4J adapters for how literals
 * and blank nodes are flattened into the single stored {@code String} of an SPOC quad. A drift in
 * the encoding (e.g. the literal quote scheme or the {@code _:} blank-node prefix) silently
 * corrupts the round-trip on one adapter while the other still reads the old form — so the format
 * is pinned here.
 */
class ProllyRdfStoreUnitTest {

    // ---- encodeLiteral / decodeLiteralLexical --------------------------

    @Test
    void encodeLiteral_wraps_lexical_form_in_quotes() {
        assertEquals("\"hello\"", ProllyRdfStore.encodeLiteral("hello"));
        assertEquals(
                "\"\"",
                ProllyRdfStore.encodeLiteral(""),
                "an empty lexical form still gets the quote wrapper");
    }

    @Test
    void decodeLiteralLexical_strips_the_quotes() {
        assertEquals("hello", ProllyRdfStore.decodeLiteralLexical("\"hello\""));
        assertEquals("", ProllyRdfStore.decodeLiteralLexical("\"\""));
    }

    @Test
    void literal_encode_decode_round_trips() {
        for (String lexical : new String[] {"x", "a long lexical value", "", "123"}) {
            assertEquals(
                    lexical,
                    ProllyRdfStore.decodeLiteralLexical(ProllyRdfStore.encodeLiteral(lexical)),
                    "encode then decode must be the identity");
        }
    }

    // ---- isEncodedLiteral ----------------------------------------------

    @Test
    void isEncodedLiteral_recognises_quoted_strings() {
        assertTrue(ProllyRdfStore.isEncodedLiteral("\"hello\""));
        assertTrue(
                ProllyRdfStore.isEncodedLiteral("\"\""),
                "two bare quotes is the encoded empty literal");
    }

    @Test
    void isEncodedLiteral_rejects_non_literals() {
        assertFalse(ProllyRdfStore.isEncodedLiteral(null), "null is not an encoded literal");
        assertFalse(ProllyRdfStore.isEncodedLiteral(""), "empty string is too short");
        assertFalse(ProllyRdfStore.isEncodedLiteral("\""), "a lone quote is too short");
        assertFalse(ProllyRdfStore.isEncodedLiteral("hello"), "an unquoted string");
        assertFalse(ProllyRdfStore.isEncodedLiteral("\"hello"), "missing the closing quote");
        assertFalse(ProllyRdfStore.isEncodedLiteral("hello\""), "missing the opening quote");
    }

    // ---- encodeBlankNode / decodeBlankNodeLabel ------------------------

    @Test
    void encodeBlankNode_prefixes_with_underscore_colon() {
        assertEquals("_:b1", ProllyRdfStore.encodeBlankNode("b1"));
        assertEquals("_:", ProllyRdfStore.encodeBlankNode(""));
    }

    @Test
    void decodeBlankNodeLabel_strips_the_prefix() {
        assertEquals("b1", ProllyRdfStore.decodeBlankNodeLabel("_:b1"));
        assertEquals("", ProllyRdfStore.decodeBlankNodeLabel("_:"));
    }

    @Test
    void blank_node_encode_decode_round_trips() {
        for (String label : new String[] {"b1", "node-42", "genid12345"}) {
            assertEquals(
                    label,
                    ProllyRdfStore.decodeBlankNodeLabel(ProllyRdfStore.encodeBlankNode(label)),
                    "encode then decode must be the identity");
        }
    }

    // ---- isEncodedBlankNode --------------------------------------------

    @Test
    void isEncodedBlankNode_recognises_the_prefix() {
        assertTrue(ProllyRdfStore.isEncodedBlankNode("_:b1"));
        assertTrue(ProllyRdfStore.isEncodedBlankNode("_:"), "the bare prefix counts");
    }

    @Test
    void isEncodedBlankNode_rejects_non_blank_nodes() {
        assertFalse(ProllyRdfStore.isEncodedBlankNode(null), "null is not a blank node");
        assertFalse(ProllyRdfStore.isEncodedBlankNode("b1"), "a plain label has no prefix");
        assertFalse(ProllyRdfStore.isEncodedBlankNode("\"lit\""), "a literal is not a blank node");
        assertFalse(ProllyRdfStore.isEncodedBlankNode(":_b1"), "the prefix order matters");
    }

    // ---- the two encodings are disjoint --------------------------------

    @Test
    void encoded_literal_is_not_mistaken_for_a_blank_node_and_vice_versa() {
        String literal = ProllyRdfStore.encodeLiteral("value");
        String blank = ProllyRdfStore.encodeBlankNode("label");
        assertTrue(ProllyRdfStore.isEncodedLiteral(literal));
        assertFalse(
                ProllyRdfStore.isEncodedBlankNode(literal),
                "a quoted literal must not classify as a blank node");
        assertTrue(ProllyRdfStore.isEncodedBlankNode(blank));
        assertFalse(
                ProllyRdfStore.isEncodedLiteral(blank),
                "a _: blank node must not classify as a literal");
    }
}
