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
package com.earasoft.prolly.rdf4j.sail;

import static org.junit.jupiter.api.Assertions.*;

import org.eclipse.rdf4j.sail.SailException;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link SailConflictException}. Small surface, but pin the subtyping (must inherit
 * {@link SailException}) and message-carry contracts because Phase 4 retry-loop callers will catch
 * this specifically — drift to a non-SailException parent would silently change exception semantics
 * for downstream consumers.
 */
class SailConflictExceptionTest {

    @Test
    void is_sail_exception_subtype() {
        SailConflictException e = new SailConflictException("conflict");
        assertInstanceOf(
                SailException.class,
                e,
                "must inherit SailException so generic Sail callers still catch it");
    }

    @Test
    void carries_message_via_single_arg_constructor() {
        SailConflictException e = new SailConflictException("CAS retry exhausted");
        assertEquals("CAS retry exhausted", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void carries_message_and_cause_via_two_arg_constructor() {
        RuntimeException root = new RuntimeException("rebase blew up");
        SailConflictException e = new SailConflictException("CAS retry exhausted", root);
        assertEquals("CAS retry exhausted", e.getMessage());
        assertSame(root, e.getCause());
    }

    @Test
    void null_message_allowed() {
        SailConflictException e = new SailConflictException(null);
        assertNull(e.getMessage());
    }

    @Test
    void null_cause_allowed() {
        SailConflictException e = new SailConflictException("msg", null);
        assertEquals("msg", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void can_be_caught_as_sail_exception() {
        boolean caught = false;
        try {
            throw new SailConflictException("test");
        } catch (SailException expected) {
            caught = true;
        }
        assertTrue(
                caught, "callers that catch SailException must also catch SailConflictException");
    }

    @Test
    void stack_trace_captured_at_construction() {
        SailConflictException e = new SailConflictException("x");
        // Standard Throwable behavior — stack trace is non-null after construction.
        assertNotNull(e.getStackTrace());
        assertTrue(e.getStackTrace().length > 0);
    }
}
