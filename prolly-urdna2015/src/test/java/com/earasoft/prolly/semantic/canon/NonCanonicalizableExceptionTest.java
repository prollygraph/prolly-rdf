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
 * Coverage for {@link NonCanonicalizableException}. The fail-closed signal — callers must catch
 * this specifically rather than falling back to a best-effort labelling.
 */
class NonCanonicalizableExceptionTest {

    @Test
    void inherits_runtime_exception() {
        NonCanonicalizableException e = new NonCanonicalizableException("x");
        assertInstanceOf(
                RuntimeException.class,
                e,
                "must inherit RuntimeException so generic catch (RE) still catches it");
    }

    @Test
    void carries_message() {
        NonCanonicalizableException e = new NonCanonicalizableException("test message");
        assertEquals("test message", e.getMessage());
        assertNull(e.getCause());
    }

    @Test
    void carries_message_and_cause() {
        Throwable root = new IllegalStateException("inner");
        NonCanonicalizableException e = new NonCanonicalizableException("outer", root);
        assertEquals("outer", e.getMessage());
        assertSame(root, e.getCause());
    }

    @Test
    void null_message_allowed() {
        NonCanonicalizableException e = new NonCanonicalizableException(null);
        assertNull(e.getMessage());
    }

    @Test
    void can_be_thrown_and_caught() {
        boolean caught = false;
        try {
            throw new NonCanonicalizableException("fail-closed");
        } catch (NonCanonicalizableException expected) {
            caught = true;
        }
        assertTrue(caught);
    }

    @Test
    void stack_trace_captured() {
        NonCanonicalizableException e = new NonCanonicalizableException("x");
        assertNotNull(e.getStackTrace());
        assertTrue(e.getStackTrace().length > 0);
    }
}
