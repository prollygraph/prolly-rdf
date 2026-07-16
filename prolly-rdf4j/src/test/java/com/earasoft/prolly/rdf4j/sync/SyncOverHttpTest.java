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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.sail.CommitLog;
import com.earasoft.prolly.rdf4j.sail.ProllySail;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.rdf4j.sail.RootMetaTree;
import com.earasoft.prolly.rdf4j.sail.RootMetaTreeStore;
import com.earasoft.prolly.sync.SyncPackCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end coverage for the HTTP transport — {@link HttpRemoteRepository} driving {@link
 * RepoSync} over real HTTP. Closes plan Step 14.
 *
 * <p>Each peer fronts a {@link ProllySail} with a JDK {@link HttpServer} whose four {@code /sync/*}
 * handlers wrap an {@link InProcessRemoteRepository} — the same backend logic the Spring {@code
 * SyncController} (Step 12) wraps, exercised over real sockets and the full {@link SyncPackCodec}
 * wire format. The bidirectional test runs against two embedded servers concurrently.
 */
class SyncOverHttpTest {

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

    private static void commitTriple(ProllySail sail, String s, String p, String o) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            conn.begin();
            ValueFactory vf = conn.getValueFactory();
            conn.add(vf.createIRI("urn:" + s), vf.createIRI("urn:" + p), vf.createIRI("urn:" + o));
            conn.commit();
        }
    }

    private static long size(ProllySail sail) {
        try (RepositoryConnection conn = new SailRepository(sail).getConnection()) {
            return conn.size();
        }
    }

    @Test
    void push_over_http(@TempDir Path aDir, @TempDir Path bDir) throws IOException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        commitTriple(a, "y", "p", "2");
        // Refs hold commit *ids* (ADR-0071); the chunk store is opened by *tree* hash.
        byte[] headIdA = a.currentCommitId();
        byte[] headTreeA = a.currentCommitHash();
        ProllySail b = initedSail(bDir);
        try (Endpoint bEnd = new Endpoint(b)) {
            new RepoSync(a).push(new HttpRemoteRepository(bEnd.baseUrl()), "origin", "main");
        }
        // The push updates B's *durable* state — its chunk store, ref file, and
        // commit log. B's live in-memory Sail does not reflect external writes
        // until it is re-opened (the same way a server-side bare repo would
        // require a restart to query it); assert durable convergence instead.
        assertArrayEquals(
                headIdA,
                b.refsStore().orElseThrow().get("main").orElseThrow(),
                "B's main ref now points at A's head id");
        assertTrue(
                RootMetaTree.readFrom(b.store(), headTreeA).isPresent(),
                "A's head tree landed in B's chunk store");
        assertEquals(
                2,
                b.commitLog().orElseThrow().entries().size(),
                "both commits landed in B's commit log");
    }

    @Test
    void pull_over_http(@TempDir Path aDir, @TempDir Path bDir) throws IOException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        commitTriple(a, "y", "p", "2");
        ProllySail b = initedSail(bDir);
        try (Endpoint aEnd = new Endpoint(a)) {
            new RepoSync(b).pull(new HttpRemoteRepository(aEnd.baseUrl()), "origin", "main");
        }
        assertEquals(2, size(b), "B fetched and integrated A's data over real HTTP");
    }

    @Test
    void bidirectional_collaboration_with_two_embedded_servers(
            @TempDir Path aDir, @TempDir Path bDir) throws IOException {
        ProllySail a = initedSail(aDir);
        commitTriple(a, "x", "p", "1");
        ProllySail b = initedSail(bDir);
        commitTriple(b, "y", "p", "2");

        // Each peer runs its own server — two embedded servers, real HTTP both ways.
        try (Endpoint aEnd = new Endpoint(a);
                Endpoint bEnd = new Endpoint(b)) {
            // B pulls A's commit over HTTP → B merges → holds both.
            new RepoSync(b).pull(new HttpRemoteRepository(aEnd.baseUrl()), "origin", "main");
            assertEquals(2, size(b));
            // A pulls B's merged head over HTTP → A converges in turn.
            new RepoSync(a).pull(new HttpRemoteRepository(bEnd.baseUrl()), "origin", "main");
            assertEquals(2, size(a));
        }
    }

    // ---- the test endpoint --------------------------------------------------

    /**
     * A JDK {@link HttpServer} with four {@code /sync/*} handlers wrapping an {@link
     * InProcessRemoteRepository} — the wire form of what {@code SyncController} does in production.
     */
    private static final class Endpoint implements AutoCloseable {

        private static final Pattern WANT = Pattern.compile("\"want\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern HAVE = Pattern.compile("\"have\"\\s*:\\s*\\[([^\\]]*)\\]");
        private static final Pattern HEX_QUOTED = Pattern.compile("\"([0-9a-fA-F]+)\"");
        private static final Pattern BRANCH = Pattern.compile("\"branch\"\\s*:\\s*\"([^\"]+)\"");
        private static final Pattern EXPECTED =
                Pattern.compile("\"expected\"\\s*:\\s*(null|\"([^\"]+)\")");
        private static final Pattern DESIRED = Pattern.compile("\"desired\"\\s*:\\s*\"([^\"]+)\"");

        private final HttpServer server;
        private final String baseUrl;

        Endpoint(ProllySail sail) throws IOException {
            RemoteRepository remote = new InProcessRemoteRepository(sail);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sync/refs", ex -> safe(ex, () -> handleRefs(ex, remote)));
            server.createContext("/sync/fetch", ex -> safe(ex, () -> handleFetch(ex, remote)));
            server.createContext("/sync/push", ex -> safe(ex, () -> handlePush(ex, remote)));
            server.createContext("/sync/ref", ex -> safe(ex, () -> handleRef(ex, remote)));
            server.setExecutor(null);
            server.start();
            baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String baseUrl() {
            return baseUrl;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void handleRefs(HttpExchange ex, RemoteRepository remote)
                throws IOException {
            Map<String, byte[]> refs = remote.advertiseRefs();
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, byte[]> e : refs.entrySet()) {
                if (!first) json.append(',');
                first = false;
                json.append('"')
                        .append(e.getKey())
                        .append("\":\"")
                        .append(HashUtils.toHex(e.getValue()))
                        .append('"');
            }
            json.append('}');
            send(ex, 200, "application/json", json.toString().getBytes(StandardCharsets.UTF_8));
        }

        private static void handleFetch(HttpExchange ex, RemoteRepository remote)
                throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher w = WANT.matcher(body);
            if (!w.find()) {
                send(ex, 400, "text/plain", "missing 'want'".getBytes());
                return;
            }
            byte[] want = HashUtils.fromHex(w.group(1));
            List<byte[]> have = new ArrayList<>();
            Matcher h = HAVE.matcher(body);
            if (h.find()) {
                Matcher hex = HEX_QUOTED.matcher(h.group(1));
                while (hex.find()) have.add(HashUtils.fromHex(hex.group(1)));
            }
            send(
                    ex,
                    200,
                    "application/octet-stream",
                    SyncPackCodec.serialize(remote.fetchPack(want, have)));
        }

        private static void handlePush(HttpExchange ex, RemoteRepository remote)
                throws IOException {
            byte[] body = ex.getRequestBody().readAllBytes();
            remote.receivePack(SyncPackCodec.parse(body));
            send(ex, 200, "text/plain", new byte[0]);
        }

        private static void handleRef(HttpExchange ex, RemoteRepository remote) throws IOException {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher b = BRANCH.matcher(body);
            Matcher d = DESIRED.matcher(body);
            if (!b.find() || !d.find()) {
                send(ex, 400, "text/plain", "missing fields".getBytes());
                return;
            }
            byte[] expected = null;
            Matcher e = EXPECTED.matcher(body);
            if (e.find() && e.group(2) != null) expected = HashUtils.fromHex(e.group(2));
            boolean updated =
                    remote.compareAndSetRef(b.group(1), expected, HashUtils.fromHex(d.group(1)));
            send(
                    ex,
                    200,
                    "application/json",
                    ("{\"updated\":" + updated + "}").getBytes(StandardCharsets.UTF_8));
        }

        private static void send(HttpExchange ex, int status, String contentType, byte[] body)
                throws IOException {
            ex.getResponseHeaders().add("Content-Type", contentType);
            if (body.length == 0) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        }

        /** Wrap a handler so an uncaught error becomes a clean 500 rather than a hung socket. */
        @FunctionalInterface
        private interface Body {
            void run() throws IOException;
        }

        private static void safe(HttpExchange ex, Body body) throws IOException {
            try {
                body.run();
            } catch (Exception err) {
                String msg = err.getClass().getSimpleName() + ": " + err.getMessage();
                send(ex, 500, "text/plain", msg.getBytes(StandardCharsets.UTF_8));
            } finally {
                ex.close();
            }
        }
    }
}
