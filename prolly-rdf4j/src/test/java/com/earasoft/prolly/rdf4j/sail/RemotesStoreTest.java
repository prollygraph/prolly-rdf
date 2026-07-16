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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage for {@link RemotesStore} — the sidecar registry of named sync remotes. */
class RemotesStoreTest {

    @Test
    void put_then_get_round_trips_a_remote_url(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        store.put("origin", "http://example.com:8080");

        assertEquals("http://example.com:8080", store.get("origin").orElseThrow());
        assertTrue(store.exists("origin"));
        assertEquals(Map.of("origin", "http://example.com:8080"), store.list());
    }

    @Test
    void list_returns_every_configured_remote(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        store.put("origin", "https://hub.example/repo");
        store.put("backup", "http://backup.local:9000");

        Map<String, String> all = store.list();
        assertEquals(2, all.size());
        assertEquals("https://hub.example/repo", all.get("origin"));
        assertEquals("http://backup.local:9000", all.get("backup"));
    }

    @Test
    void delete_removes_the_remote(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        store.put("origin", "http://example.com");

        assertTrue(store.delete("origin"));
        assertFalse(store.exists("origin"));
        assertTrue(store.get("origin").isEmpty());
        assertFalse(store.delete("origin"), "second delete on a missing remote returns false");
    }

    @Test
    void name_validation_rejects_invalid_characters(@TempDir Path dir) {
        RemotesStore store = RemotesStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> store.put("", "http://x.com"));
        assertThrows(IllegalArgumentException.class, () -> store.put("a b", "http://x.com"));
        assertThrows(IllegalArgumentException.class, () -> store.put("../escape", "http://x.com"));
        assertThrows(IllegalArgumentException.class, () -> store.put("with/slash", "http://x.com"));
    }

    @Test
    void url_validation_rejects_non_http_and_missing_host(@TempDir Path dir) {
        RemotesStore store = RemotesStore.beside(dir);
        assertThrows(IllegalArgumentException.class, () -> store.put("o", "ftp://x.com"));
        assertThrows(IllegalArgumentException.class, () -> store.put("o", "not a url"));
        assertThrows(IllegalArgumentException.class, () -> store.put("o", "http:///path"));
    }

    @Test
    void an_in_memory_store_keeps_everything_in_jvm_state() throws IOException {
        RemotesStore store = RemotesStore.inMemory();
        store.put("origin", "http://example.com");
        assertEquals("http://example.com", store.get("origin").orElseThrow());
        store.delete("origin");
        assertTrue(store.list().isEmpty());
    }

    // ---- Bindings — Step 8 of plans/admin-remotes-page.md ----

    @Test
    void binding_missing_returns_empty(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        assertTrue(store.getBinding("origin").isEmpty());
    }

    @Test
    void put_binding_then_get(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        store.putBinding("origin", "gr_abcdefghijklmnopqrstuvwxyz");
        assertEquals("gr_abcdefghijklmnopqrstuvwxyz", store.getBinding("origin").orElseThrow());
    }

    @Test
    void put_binding_rejects_invalid_id(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        assertThrows(
                IllegalArgumentException.class, () -> store.putBinding("origin", "not-a-valid-id"));
        assertThrows(IllegalArgumentException.class, () -> store.putBinding("origin", null));
    }

    @Test
    void put_binding_persists_across_instances(@TempDir Path dir) throws IOException {
        RemotesStore one = RemotesStore.beside(dir);
        one.putBinding("origin", "gr_abcdefghijklmnopqrstuvwxyz");

        RemotesStore two = RemotesStore.beside(dir);
        assertEquals("gr_abcdefghijklmnopqrstuvwxyz", two.getBinding("origin").orElseThrow());
    }

    @Test
    void delete_binding_returns_true_when_existed(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        store.putBinding("origin", "gr_abcdefghijklmnopqrstuvwxyz");
        assertTrue(store.deleteBinding("origin"));
        assertFalse(store.deleteBinding("origin"));
        assertTrue(store.getBinding("origin").isEmpty());
    }

    @Test
    void list_bindings_enumerates_all(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.beside(dir);
        store.putBinding("alpha", "gr_aaaaaaaaaaaaaaaaaaaaaaaaaa");
        store.putBinding("beta", "gr_bbbbbbbbbbbbbbbbbbbbbbbbbb");
        Map<String, String> bindings = store.listBindings();
        assertEquals(2, bindings.size());
        assertEquals("gr_aaaaaaaaaaaaaaaaaaaaaaaaaa", bindings.get("alpha"));
        assertEquals("gr_bbbbbbbbbbbbbbbbbbbbbbbbbb", bindings.get("beta"));
    }

    @Test
    void in_memory_bindings_work(@TempDir Path dir) throws IOException {
        RemotesStore store = RemotesStore.inMemory();
        store.putBinding("origin", "gr_abcdefghijklmnopqrstuvwxyz");
        assertEquals("gr_abcdefghijklmnopqrstuvwxyz", store.getBinding("origin").orElseThrow());
        store.deleteBinding("origin");
        assertTrue(store.listBindings().isEmpty());
    }

    @Test
    void parse_flat_json_handles_malformed_input() {
        // Corrupted bindings file is treated as "no bindings".
        assertTrue(RemotesStore.parseFlatJson("not json").isEmpty());
        assertTrue(RemotesStore.parseFlatJson("{").isEmpty());
        assertTrue(RemotesStore.parseFlatJson("{}").isEmpty());
        assertTrue(RemotesStore.parseFlatJson("").isEmpty());
    }

    @Test
    void encode_flat_json_round_trips() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("alpha", "gr_aaaaaaaaaaaaaaaaaaaaaaaaaa");
        m.put("beta", "gr_bbbbbbbbbbbbbbbbbbbbbbbbbb");
        String encoded = RemotesStore.encodeFlatJson(m);
        Map<String, String> decoded = RemotesStore.parseFlatJson(encoded);
        assertEquals(m, decoded);
    }
}
