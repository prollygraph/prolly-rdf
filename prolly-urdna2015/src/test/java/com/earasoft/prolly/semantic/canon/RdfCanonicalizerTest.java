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
package com.earasoft.prolly.semantic.canon;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link RdfCanonicalizer#isBlankNode}. The classifier is the gate that decides
 * whether a value needs blank-node-renaming — a false negative lets a blank node bypass
 * canonicalization, a false positive renames a literal that happens to start with "_:".
 */
class RdfCanonicalizerTest {

    @Test
    void underscore_colon_prefix_is_blank_node() {
        assertTrue(RdfCanonicalizer.isBlankNode("_:b1"));
        assertTrue(RdfCanonicalizer.isBlankNode("_:c14n0"));
        assertTrue(RdfCanonicalizer.isBlankNode("_:genid-42"));
    }

    @Test
    void iri_is_not_blank_node() {
        assertFalse(RdfCanonicalizer.isBlankNode("http://example.org/x"));
        assertFalse(RdfCanonicalizer.isBlankNode("urn:isbn:0451450523"));
    }

    @Test
    void empty_string_is_not_blank_node() {
        assertFalse(RdfCanonicalizer.isBlankNode(""));
    }

    @Test
    void single_underscore_is_not_blank_node() {
        // Single char — too short to have the _: prefix.
        assertFalse(RdfCanonicalizer.isBlankNode("_"));
        assertFalse(RdfCanonicalizer.isBlankNode(":"));
    }

    @Test
    void two_chars_underscore_colon_is_blank_node() {
        // Boundary: exactly "_:" is the minimum-length valid blank-node id.
        assertTrue(RdfCanonicalizer.isBlankNode("_:"));
    }

    @Test
    void other_two_char_prefixes_are_not_blank_nodes() {
        assertFalse(RdfCanonicalizer.isBlankNode("__"));
        assertFalse(RdfCanonicalizer.isBlankNode("::"));
        assertFalse(RdfCanonicalizer.isBlankNode(":_"));
        assertFalse(RdfCanonicalizer.isBlankNode("-:"));
    }

    @Test
    void null_input_is_not_blank_node() {
        assertFalse(RdfCanonicalizer.isBlankNode(null), "null must not NPE — pin defensive guard");
    }

    @Test
    void literal_starting_with_underscore_colon_chars_inside_quotes() {
        // A literal value like "\"_:not-a-bnode\"" would NOT start with _:
        // because of the leading quote. Pin that the classifier is purely
        // prefix-based — callers strip RDF quotes before classifying.
        assertFalse(RdfCanonicalizer.isBlankNode("\"_:b\""));
    }

    @Test
    void blank_node_check_only_examines_first_two_chars() {
        // A long string starting with _: counts.
        assertTrue(RdfCanonicalizer.isBlankNode("_:very-long-blank-node-id-here"));
        // A long string with _: somewhere in the middle does NOT.
        assertFalse(RdfCanonicalizer.isBlankNode("http://example.org/_:x"));
    }
}
