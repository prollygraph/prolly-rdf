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

import com.earasoft.prolly.semantic.QuadPattern;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SQLite-grade coverage for {@link NoopCanonicalizer}. Fail-closed contract: a noop on
 * blank-node-bearing input would silently break substrate equality for structurally-equivalent
 * graphs. Pin that the exception fires on any path with a blank node in subject OR object.
 */
class NoopCanonicalizerTest {

    @Test
    void singleton_instance_returned() {
        assertSame(NoopCanonicalizer.INSTANCE, NoopCanonicalizer.INSTANCE);
    }

    @Test
    void empty_input_returns_empty_list() {
        List<QuadPattern> result = NoopCanonicalizer.INSTANCE.canonicalize(List.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void all_named_iri_input_passes_through_unchanged() {
        List<QuadPattern> input =
                List.of(QuadPattern.of("http://x/s", "http://x/p", "http://x/o", "g"));
        List<QuadPattern> result = NoopCanonicalizer.INSTANCE.canonicalize(input);
        assertSame(
                input,
                result,
                "noop must return the SAME list reference when no canonicalization is needed");
    }

    @Test
    void multi_quad_named_input_passes_through() {
        List<QuadPattern> input =
                List.of(
                        QuadPattern.of("http://x/a", "http://x/p", "http://x/o", "g"),
                        QuadPattern.of("http://x/b", "http://x/p", "http://x/o", "g"),
                        QuadPattern.of("http://x/c", "http://x/q", "http://x/o", "g"));
        List<QuadPattern> result = NoopCanonicalizer.INSTANCE.canonicalize(input);
        assertEquals(3, result.size());
    }

    // ---- fail-closed on blank nodes ----

    @Test
    void blank_node_subject_rejected() {
        List<QuadPattern> input = List.of(QuadPattern.of("_:b1", "http://x/p", "http://x/o", "g"));
        assertThrows(
                NonCanonicalizableException.class,
                () -> NoopCanonicalizer.INSTANCE.canonicalize(input),
                "noop must NEVER canonicalize a blank-node-bearing graph silently");
    }

    @Test
    void blank_node_object_rejected() {
        List<QuadPattern> input = List.of(QuadPattern.of("http://x/s", "http://x/p", "_:b1", "g"));
        assertThrows(
                NonCanonicalizableException.class,
                () -> NoopCanonicalizer.INSTANCE.canonicalize(input));
    }

    @Test
    void blank_node_in_middle_of_list_still_rejected() {
        // Should not pass-through partial; the entire batch must fail closed.
        List<QuadPattern> input =
                List.of(
                        QuadPattern.of("http://x/a", "http://x/p", "http://x/o", "g"),
                        QuadPattern.of("_:b1", "http://x/q", "http://x/o", "g"),
                        QuadPattern.of("http://x/c", "http://x/r", "http://x/o", "g"));
        assertThrows(
                NonCanonicalizableException.class,
                () -> NoopCanonicalizer.INSTANCE.canonicalize(input));
    }

    @Test
    void error_message_points_at_alternative_canonicalizers() {
        try {
            NoopCanonicalizer.INSTANCE.canonicalize(
                    List.of(QuadPattern.of("_:b", "http://x/p", "http://x/o", "g")));
            fail("should have thrown");
        } catch (NonCanonicalizableException e) {
            // The error must guide operators to the right replacement.
            assertTrue(
                    e.getMessage().contains("SimpleFirstDegree")
                            || e.getMessage().contains("Urdna"),
                    "error must name alternative canonicalizers: " + e.getMessage());
        }
    }

    @Test
    void implements_RdfCanonicalizer_spi() {
        assertInstanceOf(RdfCanonicalizer.class, NoopCanonicalizer.INSTANCE);
    }
}
