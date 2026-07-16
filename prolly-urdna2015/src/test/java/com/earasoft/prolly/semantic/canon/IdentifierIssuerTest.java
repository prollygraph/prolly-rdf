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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the three properties required by URDNA2015's HashNDegreeQuads:
 *
 * <ol>
 *   <li>Sequential issuance with the configured prefix.
 *   <li>Idempotent {@code issue()} — same id returns same name on every call.
 *   <li>Issuance-ordered (NOT lexicographic) {@code issuedOrder()}.
 *   <li>Deep-cloneable via {@code copy()} — mutations to the clone do not affect the original.
 *   <li>Counter state preserved across {@code copy()} — the clone resumes the sequence.
 * </ol>
 */
class IdentifierIssuerTest {

    @Test
    void issue_producesSequentialNamesWithPrefix() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        assertEquals("_:c14n0", issuer.issue("_:a"));
        assertEquals("_:c14n1", issuer.issue("_:b"));
        assertEquals("_:c14n2", issuer.issue("_:c"));
        assertEquals(3, issuer.size());
    }

    @Test
    void issue_isIdempotent() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        String first = issuer.issue("_:x");
        String second = issuer.issue("_:x");
        String third = issuer.issue("_:x");
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(1, issuer.size(), "re-issue must not bump the counter");
    }

    @Test
    void hasIssued_reflectsState() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        assertFalse(issuer.hasIssued("_:x"));
        issuer.issue("_:x");
        assertTrue(issuer.hasIssued("_:x"));
        assertFalse(issuer.hasIssued("_:y"));
        assertFalse(issuer.hasIssued(null));
    }

    @Test
    void nameOf_returnsNullForUnknown() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        assertNull(issuer.nameOf("_:x"));
        issuer.issue("_:x");
        assertEquals("_:c14n0", issuer.nameOf("_:x"));
    }

    /**
     * The single most load-bearing property: {@code issuedOrder()} MUST preserve issuance order,
     * not lexicographic order. Bug #1 in the implementation guide ("forgetting LinkedHashMap") is
     * caught by this test.
     */
    @Test
    void issuedOrder_preservesIssuanceOrder_notLexicographic() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        issuer.issue("_:zebra");
        issuer.issue("_:alpha");
        issuer.issue("_:mango");
        // Issuance order, NOT alphabetical.
        assertEquals(List.of("_:zebra", "_:alpha", "_:mango"), issuer.issuedOrder());
    }

    @Test
    void idMap_isUnmodifiable() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        issuer.issue("_:x");
        assertThrows(
                UnsupportedOperationException.class, () -> issuer.idMap().put("_:y", "_:fake"));
    }

    @Test
    void copy_isDeepClone() {
        IdentifierIssuer original = new IdentifierIssuer("c14n");
        original.issue("_:a");
        original.issue("_:b");

        IdentifierIssuer clone = original.copy();
        assertNotSame(original, clone);
        assertEquals(original.idMap(), clone.idMap());

        // Mutate the clone — original must NOT see the new id.
        clone.issue("_:c");
        assertEquals(3, clone.size());
        assertEquals(2, original.size());
        assertFalse(original.hasIssued("_:c"));
        assertTrue(clone.hasIssued("_:c"));
    }

    @Test
    void copy_preservesCounterState() {
        IdentifierIssuer original = new IdentifierIssuer("c14n");
        original.issue("_:a"); // _:c14n0
        original.issue("_:b"); // _:c14n1
        original.issue("_:c"); // _:c14n2

        IdentifierIssuer clone = original.copy();
        // The clone's next issuance must continue the sequence at 3, not restart at 0.
        assertEquals("_:c14n3", clone.issue("_:d"));
    }

    @Test
    void prefix_isReported() {
        assertEquals("c14n", new IdentifierIssuer("c14n").prefix());
        assertEquals("b", new IdentifierIssuer("b").prefix());
    }

    @Test
    void constructor_validatesPrefix() {
        assertThrows(IllegalArgumentException.class, () -> new IdentifierIssuer(null));
        assertThrows(IllegalArgumentException.class, () -> new IdentifierIssuer(""));
        assertThrows(IllegalArgumentException.class, () -> new IdentifierIssuer("   "));
    }

    @Test
    void issue_rejectsNullId() {
        IdentifierIssuer issuer = new IdentifierIssuer("c14n");
        assertThrows(NullPointerException.class, () -> issuer.issue(null));
    }

    /**
     * Regression marker for guide §6.3 (IdentifierIssuer scoping): the global canonical issuer and
     * a per-call temp issuer must coexist without sharing state. This test asserts that two issuers
     * with different prefixes are independent.
     */
    @Test
    void multipleIssuers_areIndependent() {
        IdentifierIssuer canonical = new IdentifierIssuer("c14n");
        IdentifierIssuer temp = new IdentifierIssuer("b");

        canonical.issue("_:x"); // _:c14n0
        temp.issue("_:x"); // _:b0 (independent counter)

        assertEquals("_:c14n0", canonical.nameOf("_:x"));
        assertEquals("_:b0", temp.nameOf("_:x"));
    }
}
