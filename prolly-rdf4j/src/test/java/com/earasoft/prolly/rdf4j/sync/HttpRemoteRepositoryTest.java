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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dolthub.prolly.HashUtils;
import com.earasoft.prolly.sync.SyncCommitEntry;
import com.earasoft.prolly.sync.SyncPack;
import com.earasoft.prolly.sync.SyncPackCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link HttpRemoteRepository} — the HTTP sync client, exercised against a JDK {@link
 * HttpServer} with canned handlers. This verifies request encoding and response decoding in
 * isolation; the full round trip against the real Spring {@code SyncController} is plan Step 14.
 */
class HttpRemoteRepositoryTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Register a handler that records the request body and replies with {@code response}. */
    private AtomicReference<byte[]> handle(String path, int status, byte[] response) {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        server.createContext(
                path,
                (HttpHandler)
                        (HttpExchange exchange) -> {
                            captured.set(exchange.getRequestBody().readAllBytes());
                            if (response == null) {
                                exchange.sendResponseHeaders(status, -1);
                            } else {
                                exchange.sendResponseHeaders(status, response.length);
                                try (OutputStream os = exchange.getResponseBody()) {
                                    os.write(response);
                                }
                            }
                            exchange.close();
                        });
        return captured;
    }

    private static byte[] hash(int seed) {
        byte[] h = new byte[20];
        h[0] = (byte) seed;
        return h;
    }

    @Test
    void advertiseRefs_parses_the_refs_json() throws IOException {
        String json =
                "{\"main\":\""
                        + HashUtils.toHex(hash(1))
                        + "\",\"feature/x\":\""
                        + HashUtils.toHex(hash(2))
                        + "\"}";
        handle("/sync/refs", 200, json.getBytes(StandardCharsets.UTF_8));

        Map<String, byte[]> refs = new HttpRemoteRepository(baseUrl).advertiseRefs();
        assertEquals(2, refs.size());
        assertArrayEquals(hash(1), refs.get("main"));
        assertArrayEquals(hash(2), refs.get("feature/x"));
    }

    @Test
    void fetchPack_posts_want_and_have_and_decodes_the_pack() throws IOException {
        SyncPack canned =
                new SyncPack(
                        List.of(new byte[] {4, 5, 6}),
                        List.of(
                                new SyncCommitEntry(
                                        Instant.ofEpochSecond(1),
                                        hash(9),
                                        hash(9),
                                        List.of(),
                                        "c",
                                        "")));
        AtomicReference<byte[]> body = handle("/sync/fetch", 200, SyncPackCodec.serialize(canned));

        SyncPack got = new HttpRemoteRepository(baseUrl).fetchPack(hash(7), List.of(hash(8)));

        String sent = new String(body.get(), StandardCharsets.UTF_8);
        assertTrue(sent.contains("\"want\":\"" + HashUtils.toHex(hash(7)) + "\""), sent);
        assertTrue(sent.contains(HashUtils.toHex(hash(8))), "the have hash was sent");
        assertArrayEquals(new byte[] {4, 5, 6}, got.chunks().get(0));
        assertEquals(1, got.commits().size());
    }

    @Test
    void receivePack_posts_the_serialized_pack() throws IOException {
        SyncPack pack = new SyncPack(List.of(new byte[] {1, 2}), List.of());
        AtomicReference<byte[]> body = handle("/sync/push", 200, null);

        new HttpRemoteRepository(baseUrl).receivePack(pack);

        SyncPack received = SyncPackCodec.parse(body.get());
        assertArrayEquals(new byte[] {1, 2}, received.chunks().get(0));
    }

    @Test
    void compareAndSetRef_sends_json_and_reads_the_updated_flag() throws IOException {
        AtomicReference<byte[]> body =
                handle("/sync/ref", 200, "{\"updated\":true}".getBytes(StandardCharsets.UTF_8));

        boolean updated = new HttpRemoteRepository(baseUrl).compareAndSetRef("main", null, hash(3));

        assertTrue(updated);
        String sent = new String(body.get(), StandardCharsets.UTF_8);
        assertTrue(sent.contains("\"branch\":\"main\""), sent);
        assertTrue(sent.contains("\"expected\":null"), sent);
        assertTrue(sent.contains("\"desired\":\"" + HashUtils.toHex(hash(3)) + "\""), sent);
    }

    @Test
    void a_non_200_response_is_surfaced_as_an_error() {
        handle("/sync/refs", 503, "backend down".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> new HttpRemoteRepository(baseUrl).advertiseRefs());
    }

    // ---- Step 1 of plans/admin-remotes-outbound-auth.md ----------

    /** Like {@link #handle} but also captures the Authorization header. */
    private AtomicReference<String> handleAndCaptureAuth(String path, int status, byte[] response) {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext(
                path,
                (HttpHandler)
                        (HttpExchange exchange) -> {
                            capturedAuth.set(
                                    exchange.getRequestHeaders().getFirst("Authorization"));
                            exchange.getRequestBody().readAllBytes();
                            if (response == null) {
                                exchange.sendResponseHeaders(status, -1);
                            } else {
                                exchange.sendResponseHeaders(status, response.length);
                                try (OutputStream os = exchange.getResponseBody()) {
                                    os.write(response);
                                }
                            }
                            exchange.close();
                        });
        return capturedAuth;
    }

    @Test
    void no_auth_constructor_sends_no_authorization_header() throws IOException {
        AtomicReference<String> auth = handleAndCaptureAuth("/sync/refs", 200, "{}".getBytes());
        new HttpRemoteRepository(baseUrl).advertiseRefs();
        assertNull(auth.get(), "1-arg constructor → no Authorization header");
    }

    @Test
    void noauth_explicit_sends_no_authorization_header() throws IOException {
        AtomicReference<String> auth = handleAndCaptureAuth("/sync/refs", 200, "{}".getBytes());
        new HttpRemoteRepository(baseUrl, OutboundAuth.NoAuth.INSTANCE).advertiseRefs();
        assertNull(auth.get());
    }

    @Test
    void basic_auth_sends_authorization_basic_with_base64_utf8() throws IOException {
        AtomicReference<String> auth = handleAndCaptureAuth("/sync/refs", 200, "{}".getBytes());
        new HttpRemoteRepository(baseUrl, new OutboundAuth.BasicAuth("alice", "hunter2"))
                .advertiseRefs();
        String expected =
                "Basic "
                        + java.util.Base64.getEncoder()
                                .encodeToString("alice:hunter2".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, auth.get());
    }

    @Test
    void bearer_auth_sends_authorization_bearer() throws IOException {
        AtomicReference<String> auth = handleAndCaptureAuth("/sync/refs", 200, "{}".getBytes());
        new HttpRemoteRepository(baseUrl, new OutboundAuth.BearerAuth("prdf4j_pat_abc123"))
                .advertiseRefs();
        assertEquals("Bearer prdf4j_pat_abc123", auth.get());
    }

    @Test
    void auth_header_attaches_on_all_four_endpoints() throws IOException {
        AtomicReference<String> refsAuth = handleAndCaptureAuth("/sync/refs", 200, "{}".getBytes());
        AtomicReference<String> fetchAuth =
                handleAndCaptureAuth(
                        "/sync/fetch",
                        200,
                        SyncPackCodec.serialize(new SyncPack(List.of(new byte[] {1}), List.of())));
        AtomicReference<String> pushAuth = handleAndCaptureAuth("/sync/push", 200, null);
        AtomicReference<String> refAuth =
                handleAndCaptureAuth(
                        "/sync/ref", 200, "{\"updated\":true}".getBytes(StandardCharsets.UTF_8));

        HttpRemoteRepository repo =
                new HttpRemoteRepository(baseUrl, new OutboundAuth.BearerAuth("t-token"));
        repo.advertiseRefs();
        repo.fetchPack(hash(1), List.of());
        repo.receivePack(new SyncPack(List.of(new byte[] {1}), List.of()));
        repo.compareAndSetRef("main", null, hash(2));

        // All four request paths carried the bearer.
        assertEquals("Bearer t-token", refsAuth.get());
        assertEquals("Bearer t-token", fetchAuth.get());
        assertEquals("Bearer t-token", pushAuth.get());
        assertEquals("Bearer t-token", refAuth.get());
    }

    @Test
    void utf8_multibyte_password_round_trips_via_base64() throws IOException {
        AtomicReference<String> auth = handleAndCaptureAuth("/sync/refs", 200, "{}".getBytes());
        // U+00E9 (é) requires multi-byte UTF-8 encoding.
        new HttpRemoteRepository(baseUrl, new OutboundAuth.BasicAuth("alíce", "héllo"))
                .advertiseRefs();
        String expected =
                "Basic "
                        + java.util.Base64.getEncoder()
                                .encodeToString("alíce:héllo".getBytes(StandardCharsets.UTF_8));
        assertEquals(
                expected,
                auth.get(),
                "D-15: HTTP Basic uses UTF-8 (modern convention), not ISO-8859-1");
    }

    @Test
    void null_auth_constructor_arg_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new HttpRemoteRepository(baseUrl, null));
    }

    @Test
    void sanitize_url_strips_embedded_userinfo_for_logs() {
        // D-10: URLs with embedded userinfo are never logged verbatim.
        assertEquals(
                "https://host/path",
                HttpRemoteRepository.sanitizeUrlForLog("https://user:pass@host/path"));
        assertEquals("https://host", HttpRemoteRepository.sanitizeUrlForLog("https://host"));
        assertNull(HttpRemoteRepository.sanitizeUrlForLog(null));
        // Malformed URL — return as-is (URL itself isn't a secret; secrets are
        // already stripped from any embedded userinfo by the time we get here).
        assertEquals("not a url", HttpRemoteRepository.sanitizeUrlForLog("not a url"));
    }

    /**
     * The hand-built JSON bodies are only safe if their inputs really are what the class javadoc
     * assumes. They were not checked: {@code compareAndSetRef} is a public interface method and
     * interpolated {@code branch} straight into {@code {"branch":"..."}}, so a name carrying a
     * double quote closed the string early and the peer received attacker-shaped JSON.
     *
     * <p>Rejection happens BEFORE any request is built, which is why these tests need no handler
     * registered on the server: reaching the network at all would already be the bug.
     */
    @Test
    void a_branch_name_with_a_quote_is_rejected_before_any_request() {
        HttpRemoteRepository remote = new HttpRemoteRepository(baseUrl);
        byte[] head = HashUtils.hash("head".getBytes(StandardCharsets.UTF_8));
        assertThrows(
                IllegalArgumentException.class,
                () -> remote.compareAndSetRef("main\", \"injected\":\"x", null, head),
                "a branch name containing a double quote must be refused, not interpolated into"
                        + " the request body");
    }

    @Test
    void a_branch_name_with_a_quote_is_rejected_on_the_substrate_paths_too() {
        HttpRemoteRepository remote = new HttpRemoteRepository(baseUrl);
        byte[] head = HashUtils.hash("head".getBytes(StandardCharsets.UTF_8));
        String hostile = "main\", \"injected\":\"x";
        assertThrows(
                IllegalArgumentException.class,
                () -> remote.fetchSubstratePack("sub", hostile, java.util.Set.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> remote.pushSubstratePack("sub", hostile, head, null, emptyPack()));
    }

    /**
     * {@code haveCommitHexes} is a {@code Set<String>} the caller supplies, appended verbatim into
     * {@code {"have":[...]}}. Unlike {@code fetchPack}, which derives its hex from {@code byte[]}
     * via {@code toHex} and so cannot emit anything but {@code [0-9a-f]}, nothing here constrained
     * the strings at all.
     */
    @Test
    void a_non_hex_have_entry_is_rejected() {
        HttpRemoteRepository remote = new HttpRemoteRepository(baseUrl);
        assertThrows(
                IllegalArgumentException.class,
                () -> remote.fetchSubstratePack("sub", "main", java.util.Set.of("\"],\"x\":[\"")),
                "a non-hex 'have' entry must be refused before it reaches the JSON body");
        assertThrows(
                IllegalArgumentException.class,
                () -> remote.fetchSubstratePack("sub", "main", java.util.Set.of("zz")),
                "'zz' is not hex either — the check is strictness, not just quote-hunting");
    }

    /** The happy path still works: ordinary names and real hex are not caught by the guards. */
    @Test
    void ordinary_branch_names_and_real_hex_still_pass_validation() throws Exception {
        HttpRemoteRepository remote = new HttpRemoteRepository(baseUrl);
        String hex = HashUtils.toHex(HashUtils.hash("c".getBytes(StandardCharsets.UTF_8)));
        // No handler is registered, so the server answers 404 and the client maps that to an empty
        // Optional. Reaching that outcome at all IS the assertion: the call got past validation and
        // completed a real round trip. An IllegalArgumentException here would mean the guards had
        // become too strict and started refusing legitimate input — a slash-bearing branch name and
        // genuine lowercase hex — which is why this sits alongside the rejection tests rather than
        // being left implied by them.
        assertEquals(
                java.util.Optional.empty(),
                remote.fetchSubstratePack("sub", "feature/my-branch.v2", java.util.Set.of(hex)));
    }

    private static SyncPack emptyPack() {
        return new SyncPack(List.of(), List.of());
    }
}
