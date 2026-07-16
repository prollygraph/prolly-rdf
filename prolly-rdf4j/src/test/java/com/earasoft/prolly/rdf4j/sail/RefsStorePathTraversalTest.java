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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Path-traversal hardening for {@link RefsStore}. Branch names flow in from the REST API ({@code
 * /sparql/branches/{name}}, staging, merge), and {@code validateName} is the only gate before the
 * name is handed to {@code dir.resolve(name)}.
 *
 * <p>{@code dir.resolve(absolutePath)} ignores the base directory entirely — so an absolute branch
 * name escapes {@code refs/} and turns {@code put}/{@code delete} into an arbitrary-file
 * write/delete. The {@code ..}-segment check alone does not cover this.
 */
class RefsStorePathTraversalTest {

    @Test
    void absolute_branch_names_are_rejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RefsStore.validateName("/etc/passwd"),
                "an absolute branch name must be rejected — it escapes refs/");
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("/x"));
    }

    @Test
    void dot_dot_segments_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName(".."));
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("../x"));
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("a/../b"));
        assertThrows(IllegalArgumentException.class, () -> RefsStore.validateName("a/.."));
    }

    @Test
    void legitimate_nested_branch_names_are_accepted() {
        // Nested names (Git-style) must still work.
        assertDoesNotThrow(() -> RefsStore.validateName("main"));
        assertDoesNotThrow(() -> RefsStore.validateName("feature/x"));
        assertDoesNotThrow(() -> RefsStore.validateName("release/2026.05"));
        assertDoesNotThrow(() -> RefsStore.validateName("user_alice-draft"));
    }

    @Test
    void put_with_an_absolute_name_does_not_escape_the_refs_directory(@TempDir Path dir)
            throws Exception {
        RefsStore refs = RefsStore.beside(dir);
        byte[] hash = new byte[20];
        // A would-be escape target outside the refs/ subtree.
        Path escapeTarget = dir.resolve("ESCAPED");
        assertThrows(
                IllegalArgumentException.class,
                () -> refs.put("/" + dir.resolve("ESCAPED"), hash),
                "put with an absolute branch name must be rejected, not write outside refs/");
        assertFalse(
                Files.exists(escapeTarget), "no file may be created outside the refs/ directory");
    }
}
