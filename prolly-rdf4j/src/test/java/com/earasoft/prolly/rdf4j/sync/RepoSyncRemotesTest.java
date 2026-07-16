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
package com.earasoft.prolly.rdf4j.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RemotesStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Coverage for the name-based convenience API on {@link RepoSync} (plan Step 16). */
class RepoSyncRemotesTest {

    private static ProllySail initedSail(Path dir) {
        ProllySail sail =
                new ProllySail(
                        new InMemoryNodeStore(),
                        new HeapBufferPool(),
                        RootMetaTreeStore.beside(dir),
                        CommitLog.beside(dir),
                        RefsStore.beside(dir),
                        false);
        new SailRepository(sail).init();
        return sail;
    }

    @Test
    void remoteAdd_then_list_then_remove_round_trip(@TempDir Path dir) throws IOException {
        RepoSync sync = new RepoSync(initedSail(dir), RemotesStore.inMemory());

        sync.remoteAdd("origin", "http://example.com:8080");
        sync.remoteAdd("backup", "https://backup.local");

        Map<String, String> all = sync.remoteList();
        assertEquals(2, all.size());
        assertEquals("http://example.com:8080", all.get("origin"));
        assertEquals("https://backup.local", all.get("backup"));

        assertTrue(sync.remoteRemove("backup"));
        assertEquals(1, sync.remoteList().size());
        assertFalse(sync.remoteRemove("backup"), "second remove on a missing remote returns false");
    }

    @Test
    void a_name_based_call_resolves_via_RemotesStore_and_hits_the_url(@TempDir Path dir)
            throws IOException {
        // A minimal /sync/refs endpoint that advertises NO branches.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/sync/refs",
                ex -> {
                    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                    ex.close();
                });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            RepoSync sync = new RepoSync(initedSail(dir), RemotesStore.inMemory());
            sync.remoteAdd("origin", url);

            // The URL resolved correctly and the request reached the endpoint —
            // which advertised no 'main', so RepoSync.fetch rejects.
            IllegalArgumentException ex =
                    assertThrows(
                            IllegalArgumentException.class, () -> sync.fetch("origin", "main"));
            assertTrue(ex.getMessage().contains("branch 'main'"), ex.getMessage());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void an_unknown_remote_name_is_rejected(@TempDir Path dir) throws IOException {
        RepoSync sync = new RepoSync(initedSail(dir), RemotesStore.inMemory());
        sync.remoteAdd("origin", "http://example.com");

        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> sync.fetch("nope", "main"));
        assertTrue(ex.getMessage().contains("'nope'"), ex.getMessage());
    }

    @Test
    void name_based_calls_require_a_RemotesStore(@TempDir Path dir) {
        RepoSync sync =
                new RepoSync(initedSail(dir)); // no remotes — only the (RemoteRepository) overloads
        assertThrows(IllegalStateException.class, () -> sync.fetch("origin", "main"));
        assertThrows(IllegalStateException.class, () -> sync.remoteAdd("origin", "http://x.com"));
        assertThrows(IllegalStateException.class, sync::remoteList);
    }
}
