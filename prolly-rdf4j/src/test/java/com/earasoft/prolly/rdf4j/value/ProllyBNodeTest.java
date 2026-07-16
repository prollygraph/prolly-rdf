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

import static org.junit.jupiter.api.Assertions.*;

import com.earasoft.prolly.rdf4j.term.TermCodec;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.UUID;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link ProllyBNode}. Three encoded forms (UUID, label, canonical) must
 * each decode to the right ID; equality must follow RDF semantics (by ID, across implementations);
 * and the cached ID must remain stable across calls.
 */
class ProllyBNodeTest {

    @Test
    void uuid_bnode_decodes_to_canonical_uuid_string() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment encoded = TermCodec.encodeBNodeUuid(id, arena);
            ProllyBNode b = new ProllyBNode(encoded);
            assertEquals(id.toString(), b.getID());
        }
    }

    @Test
    void label_bnode_decodes_to_label() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment encoded = TermCodec.encodeBNodeLabel("genid-42", arena);
            ProllyBNode b = new ProllyBNode(encoded);
            assertEquals("genid-42", b.getID());
        }
    }

    @Test
    void canonical_bnode_decodes_with_c14n_prefix() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment encoded = TermCodec.encodeBNodeCanon(7, arena);
            ProllyBNode b = new ProllyBNode(encoded);
            assertEquals("c14n7", b.getID());
        }
    }

    @Test
    void stringValue_returns_id() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("x", arena));
            assertEquals(b.getID(), b.stringValue());
        }
    }

    @Test
    void toString_prefixes_id_with_underscore_colon() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("xyz", arena));
            assertEquals(
                    "_:xyz", b.toString(), "RDF Turtle-style blank-node format requires _: prefix");
        }
    }

    @Test
    void cached_id_is_stable_across_calls() {
        // Property: getID() should return the same string each call —
        // the cache must not invalidate or recompute.
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("cached", arena));
            String first = b.getID();
            String second = b.getID();
            assertSame(
                    first, second, "cached ID must be the same String instance on repeated calls");
        }
    }

    // ---- equality ----

    @Test
    void equal_to_self_via_reference() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("a", arena));
            assertEquals(b, b);
        }
    }

    @Test
    void equal_to_other_bnode_with_same_id() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b1 = new ProllyBNode(TermCodec.encodeBNodeLabel("same", arena));
            ProllyBNode b2 = new ProllyBNode(TermCodec.encodeBNodeLabel("same", arena));
            assertEquals(b1, b2);
            assertEquals(b1.hashCode(), b2.hashCode());
        }
    }

    @Test
    void not_equal_to_other_bnode_with_different_id() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b1 = new ProllyBNode(TermCodec.encodeBNodeLabel("a", arena));
            ProllyBNode b2 = new ProllyBNode(TermCodec.encodeBNodeLabel("b", arena));
            assertNotEquals(b1, b2);
        }
    }

    @Test
    void equal_to_rdf4j_SimpleBNode_with_same_id() {
        // RDF4J Statement equality contract: BNodes compared across
        // implementations must agree if their IDs match.
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("shared", arena));
            BNode simple = SimpleValueFactory.getInstance().createBNode("shared");
            assertEquals(b, simple);
            // hashCode must also agree.
            assertEquals(b.hashCode(), simple.hashCode());
        }
    }

    @Test
    void not_equal_to_non_bnode_object() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("x", arena));
            assertNotEquals(b, "x");
            assertNotEquals(b, null);
        }
    }

    // ---- error path ----

    @Test
    void wrong_tag_byte_throws_on_decode() {
        try (Arena arena = Arena.ofConfined()) {
            // Build a payload that has a tag NOT in the BNode family (0xA0..0xA2).
            // Use 0xFF as an obviously-invalid tag.
            MemorySegment evil = arena.allocate(2);
            evil.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0, (byte) 0xFF);
            evil.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 1, (byte) 0x00);
            ProllyBNode b = new ProllyBNode(evil);
            assertThrows(
                    IllegalStateException.class,
                    b::getID,
                    "non-BNode tag must surface as IllegalStateException, not silent garbage");
        }
    }

    @Test
    void is_sealed_subtype_of_ProllyValue() {
        try (Arena arena = Arena.ofConfined()) {
            ProllyBNode b = new ProllyBNode(TermCodec.encodeBNodeLabel("x", arena));
            assertInstanceOf(ProllyValue.class, b);
            assertInstanceOf(BNode.class, b);
        }
    }
}
