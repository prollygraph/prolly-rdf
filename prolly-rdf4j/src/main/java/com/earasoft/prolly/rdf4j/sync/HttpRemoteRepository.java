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

import com.dolthub.prolly.HashUtils;
import com.earasoft.prolly.rdf4j.sail.RefsStore;
import com.earasoft.prolly.sync.DatabasePackSync;
import com.earasoft.prolly.sync.SyncPack;
import com.earasoft.prolly.sync.SyncPackCodec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A {@link RemoteRepository} that speaks the HTTP wire protocol of {@code
 * docs/distributed_sync_protocol.md} §3 to a remote {@code /sync} server, over the JDK's {@link
 * HttpClient} — no extra dependency.
 *
 * <p>The JSON payloads are tiny and fixed-shape — hex hashes and {@code RefsStore}-validated branch
 * names, neither of which contains a JSON-special character — so they are built and scanned
 * directly rather than pulling in a JSON library.
 *
 * <p><b>That premise is now ENFORCED here, which it previously was not.</b> The paragraph above
 * described what callers were assumed to pass, and nothing checked it: {@code compareAndSetRef} and
 * {@code fetchSubstratePack} are public interface methods that interpolated their {@code branch}
 * and {@code haveCommitHexes} arguments straight into the request body, so a branch name containing
 * a double quote produced malformed — and attacker-shaped — JSON. Every entry point that reaches a
 * hand-built payload now validates first, via {@link RefsStore#validateName} for branch names and
 * {@link HexFormat} for hex, and rejects rather than escaping. Rejecting is the right choice
 * precisely because it keeps the no-JSON-library design honest: escaping would mean hand-rolling an
 * encoder, which is the thing this class exists to avoid.
 *
 * <p>Note what did <i>not</i> need fixing, so a future reader does not add redundant checks: {@code
 * fetchPack} builds its hex from {@code byte[]} via {@link HashUtils#toHex}, which can only emit
 * {@code [0-9a-f]}, and the {@code /sync/fetch} and {@code /sync/push} query strings are {@code
 * URLEncoder}-encoded. Those are safe by construction.
 */
public final class HttpRemoteRepository implements RemoteRepository {

    private static final String JSON = "application/json";
    private static final String OCTET_STREAM = "application/octet-stream";

    /** Matches one {@code "name":"hexhash"} pair in the {@code /sync/refs} response. */
    private static final Pattern REF_PAIR =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([0-9a-fA-F]+)\"");

    /** Matches the {@code "updated":true|false} field of the {@code /sync/ref} response. */
    private static final Pattern UPDATED = Pattern.compile("\"updated\"\\s*:\\s*(true|false)");

    private final HttpClient http;
    private final URI base;
    private final OutboundAuth auth;

    /**
     * NoAuth API — Step 1 of plans/admin-remotes-outbound-auth.md D-1: not a back-compat shim, but
     * the semantically-distinct unauthenticated-target case.
     */
    public HttpRemoteRepository(String baseUrl) {
        this(baseUrl, OutboundAuth.NoAuth.INSTANCE);
    }

    /**
     * Auth-required API.
     *
     * @param baseUrl the remote server root, e.g. {@code http://host:8080}
     * @param auth what to send in the {@code Authorization} header on every outbound call; {@link
     *     OutboundAuth.NoAuth} = no header.
     */
    public HttpRemoteRepository(String baseUrl, OutboundAuth auth) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null/blank");
        }
        if (auth == null) {
            throw new IllegalArgumentException(
                    "auth must not be null — use OutboundAuth.NoAuth.INSTANCE");
        }
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(root);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.auth = auth;
    }

    @Override
    public Map<String, byte[]> advertiseRefs() throws IOException {
        HttpResponse<String> resp =
                sendForString(applyAuth(HttpRequest.newBuilder(uri("/sync/refs")).GET()).build());
        requireOk(resp.statusCode(), "GET /sync/refs", resp.body());
        Map<String, byte[]> refs = new LinkedHashMap<>();
        Matcher m = REF_PAIR.matcher(resp.body());
        while (m.find()) {
            refs.put(m.group(1), HashUtils.fromHex(m.group(2)));
        }
        return refs;
    }

    @Override
    public SyncPack fetchPack(byte[] want, Collection<byte[]> have) throws IOException {
        StringBuilder json =
                new StringBuilder("{\"want\":\"")
                        .append(HashUtils.toHex(want))
                        .append("\",\"have\":[");
        boolean first = true;
        if (have != null) {
            for (byte[] h : have) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(HashUtils.toHex(h)).append('"');
            }
        }
        json.append("]}");
        HttpResponse<byte[]> resp =
                sendForBytes(
                        applyAuth(
                                        HttpRequest.newBuilder(uri("/sync/fetch"))
                                                .header("Content-Type", JSON)
                                                .header("Accept", OCTET_STREAM)
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                json.toString())))
                                .build());
        requireOk(
                resp.statusCode(),
                "POST /sync/fetch",
                new String(resp.body(), StandardCharsets.UTF_8));
        return SyncPackCodec.parse(resp.body());
    }

    @Override
    public java.util.Optional<DatabasePackSync.PackAndHead> fetchSubstratePack(
            String substrate, String branch, java.util.Set<String> haveCommitHexes)
            throws IOException {
        RefsStore.validateName(branch);
        haveCommitHexes.forEach(HttpRemoteRepository::requireHex);
        String query =
                "/sync/fetch?substrate="
                        + java.net.URLEncoder.encode(substrate, StandardCharsets.UTF_8)
                        + "&branch="
                        + java.net.URLEncoder.encode(branch, StandardCharsets.UTF_8);
        StringBuilder json = new StringBuilder("{\"have\":[");
        boolean first = true;
        for (String h : haveCommitHexes) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(h).append('"');
        }
        json.append("]}");
        HttpResponse<byte[]> resp =
                sendForBytes(
                        applyAuth(
                                        HttpRequest.newBuilder(uri(query))
                                                .header("Content-Type", JSON)
                                                .header("Accept", OCTET_STREAM)
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                json.toString())))
                                .build());
        if (resp.statusCode() == 404) {
            return java.util.Optional.empty(); // no such branch on that substrate
        }
        requireOk(
                resp.statusCode(),
                "POST " + query,
                new String(resp.body(), StandardCharsets.UTF_8));
        String headHex =
                resp.headers()
                        .firstValue("X-Prolly-Substrate-Head")
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "POST "
                                                        + query
                                                        + ": missing X-Prolly-Substrate-Head"
                                                        + " header"));
        return java.util.Optional.of(
                new DatabasePackSync.PackAndHead(
                        SyncPackCodec.parse(resp.body()),
                        java.util.Optional.of(HashUtils.fromHex(headHex))));
    }

    @Override
    public void receivePack(SyncPack pack) throws IOException {
        HttpResponse<String> resp =
                sendForString(
                        applyAuth(
                                        HttpRequest.newBuilder(uri("/sync/push"))
                                                .header("Content-Type", OCTET_STREAM)
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofByteArray(
                                                                SyncPackCodec.serialize(pack))))
                                .build());
        requireOk(resp.statusCode(), "POST /sync/push", resp.body());
    }

    @Override
    public boolean pushSubstratePack(
            String substrate,
            String branch,
            byte[] newHead,
            byte @Nullable [] expectedOldHead,
            SyncPack pack)
            throws IOException {
        RefsStore.validateName(branch);
        String query =
                "/sync/push?substrate="
                        + java.net.URLEncoder.encode(substrate, StandardCharsets.UTF_8)
                        + "&branch="
                        + java.net.URLEncoder.encode(branch, StandardCharsets.UTF_8)
                        + "&head="
                        + HashUtils.toHex(newHead)
                        + (expectedOldHead == null
                                ? ""
                                : "&expected=" + HashUtils.toHex(expectedOldHead));
        HttpResponse<String> resp =
                sendForString(
                        applyAuth(
                                        HttpRequest.newBuilder(uri(query))
                                                .header("Content-Type", OCTET_STREAM)
                                                .header("Accept", JSON)
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofByteArray(
                                                                SyncPackCodec.serialize(pack))))
                                .build());
        requireOk(resp.statusCode(), "POST " + query, resp.body());
        Matcher m = UPDATED.matcher(resp.body());
        if (!m.find()) {
            throw new IOException(
                    "POST " + query + ": malformed response, no 'updated' field: " + resp.body());
        }
        return Boolean.parseBoolean(m.group(1));
    }

    @Override
    public boolean compareAndSetRef(String branch, byte @Nullable [] expected, byte[] desired)
            throws IOException {
        RefsStore.validateName(branch);
        String json =
                "{\"branch\":\""
                        + branch
                        + "\","
                        + "\"expected\":"
                        + (expected == null ? "null" : "\"" + HashUtils.toHex(expected) + "\"")
                        + ","
                        + "\"desired\":\""
                        + HashUtils.toHex(desired)
                        + "\"}";
        HttpResponse<String> resp =
                sendForString(
                        applyAuth(
                                        HttpRequest.newBuilder(uri("/sync/ref"))
                                                .header("Content-Type", JSON)
                                                .header("Accept", JSON)
                                                .POST(HttpRequest.BodyPublishers.ofString(json)))
                                .build());
        requireOk(resp.statusCode(), "POST /sync/ref", resp.body());
        Matcher m = UPDATED.matcher(resp.body());
        if (!m.find()) {
            throw new IOException(
                    "POST /sync/ref: malformed response, no 'updated' field: " + resp.body());
        }
        return Boolean.parseBoolean(m.group(1));
    }

    private URI uri(String path) {
        return URI.create(base + path);
    }

    /**
     * Attach the {@code Authorization} header per the configured {@link OutboundAuth}. Step 1 of
     * plans/admin-remotes-outbound-auth.md.
     *
     * <ul>
     *   <li>{@link OutboundAuth.NoAuth} → no header (pass-through)
     *   <li>{@link OutboundAuth.BasicAuth} → {@code Authorization: Basic <base64-utf8(user:pass)>}
     *       (D-15)
     *   <li>{@link OutboundAuth.BearerAuth} → {@code Authorization: Bearer <token>}
     * </ul>
     */
    private HttpRequest.Builder applyAuth(HttpRequest.Builder b) {
        return switch (auth) {
            case OutboundAuth.NoAuth ignored -> b;
            case OutboundAuth.BasicAuth ba -> {
                String raw = ba.username() + ":" + ba.password();
                String encoded =
                        java.util.Base64.getEncoder()
                                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                yield b.header("Authorization", "Basic " + encoded);
            }
            case OutboundAuth.BearerAuth br ->
                    b.header("Authorization", "Bearer " + br.tokenValue());
        };
    }

    /**
     * Return {@code url} with any embedded userinfo (e.g. {@code https://user:pass@host}) stripped
     * — for safe logging. D-10 of plans/admin-remotes-outbound-auth.md.
     */
    /**
     * Rejects anything that is not strict, ASCII, even-length hex before it can reach a hand-built
     * JSON body. {@link HexFormat#parseHex} is the validator rather than a regex on purpose: it is
     * the same strict parser the engine's {@code HashUtils.fromHex} uses, so "what counts as hex"
     * has exactly one definition on both sides of the wire. A regex here would be a second
     * definition, free to drift.
     */
    private static void requireHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("commit hex must not be null");
        }
        try {
            HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException notHex) {
            throw new IllegalArgumentException(
                    "commit hex must be strict hex, got: " + hex, notHex);
        }
    }

    public static @Nullable String sanitizeUrlForLog(@Nullable String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url);
            if (u.getUserInfo() == null) return url;
            return new URI(
                            u.getScheme(),
                            null,
                            u.getHost(),
                            u.getPort(),
                            u.getPath(),
                            u.getQuery(),
                            u.getFragment())
                    .toString();
        } catch (Exception e) {
            return url; // Malformed URL — best-effort; the URL itself isn't a secret.
        }
    }

    private HttpResponse<String> sendForString(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("sync request interrupted: " + request.uri(), e);
        }
    }

    private HttpResponse<byte[]> sendForBytes(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("sync request interrupted: " + request.uri(), e);
        }
    }

    private static void requireOk(int status, String what, String body) throws IOException {
        if (status != 200) {
            throw new IOException(
                    what
                            + " failed: HTTP "
                            + status
                            + (body == null || body.isBlank() ? "" : " — " + body));
        }
    }
}
