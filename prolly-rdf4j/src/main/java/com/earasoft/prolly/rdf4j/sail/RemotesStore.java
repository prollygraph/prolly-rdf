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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Sidecar storage for named sync remotes — the {@code git remote add} registry.
 *
 * <p>Filesystem layout: one file per remote at {@code <storeDir>/remotes/<name>}, its content the
 * URL. Writes use temp-file-then-rename for atomicity. Mirrors {@link RefsStore} deliberately —
 * same one-file-per-name pattern, same crash-safe write path.
 *
 * <p>Lives in the {@code sail} package, next to {@link RefsStore} and {@link CommitLog}, so {@link
 * ProllySail}'s Java API (plan Step 16) can expose it without creating a {@code sail} → {@code
 * sync} dependency.
 */
public final class RemotesStore {

    public static final String DIRNAME = "remotes";

    /** Remote names: alphanumeric, dash, underscore, dot; 1-64 chars. Flat (no '/'). */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * Filename of the bindings sibling — name → globalRemoteId map written as a single JSON file
     * alongside the {@code remotes/} directory. Step 8 of {@code plans/admin-remotes-page.md}.
     * Missing file = no bindings (legacy data continues to read cleanly).
     */
    public static final String BINDINGS_FILENAME = "remotes-bindings.json";

    /** Backing directory, or {@code null} for an in-memory store. */
    private final @Nullable Path dir;

    /** Only used when {@link #dir} is {@code null}. */
    private final @Nullable Map<String, String> memoryRemotes;

    /** Only used when {@link #dir} is {@code null}. */
    private final @Nullable Map<String, String> memoryBindings;

    public RemotesStore(Path dir) {
        this.dir = dir;
        this.memoryRemotes = null;
        this.memoryBindings = null;
    }

    private RemotesStore() {
        this.dir = null;
        this.memoryRemotes = new ConcurrentHashMap<>();
        this.memoryBindings = new ConcurrentHashMap<>();
    }

    /** Path to the bindings file when {@link #dir} is non-null. */
    private Path bindingsFile() {
        return Objects.requireNonNull(requireDir().getParent()).resolve(BINDINGS_FILENAME);
    }

    /** A {@link RemotesStore} rooted at {@code <storeDir>/remotes}. */
    public static RemotesStore beside(Path storeDir) {
        return new RemotesStore(storeDir.resolve(DIRNAME));
    }

    /** An in-memory {@link RemotesStore} — bounded by JVM lifetime; for tests. */
    public static RemotesStore inMemory() {
        return new RemotesStore();
    }

    public @Nullable Path dir() {
        return dir;
    }

    /** The backing directory, asserted present (file-backed path only). */
    private Path requireDir() {
        return Objects.requireNonNull(dir, "file-backed RemotesStore op on an in-memory store");
    }

    /** Validate a remote name; throws {@link IllegalArgumentException} on bad input. */
    public static void validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("remote name must not be null");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid remote name '" + name + "': must match " + NAME_PATTERN.pattern());
        }
    }

    /** Validate a remote URL — must be a parseable http(s) URI with a host. */
    public static void validateUrl(String url) {
        if (url == null) {
            throw new IllegalArgumentException("remote URL must not be null");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("remote URL is not a valid URI: " + url, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("remote URL must use http or https: " + url);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("remote URL must include a host: " + url);
        }
    }

    /** The URL of remote {@code name}, or empty if it isn't configured. */
    public Optional<String> get(String name) throws IOException {
        validateName(name);
        if (memoryRemotes != null) {
            return Optional.ofNullable(memoryRemotes.get(name));
        }
        Path file = requireDir().resolve(name);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /**
     * Configure (or replace) remote {@code name} → {@code url}. Creates the {@code remotes/} dir on
     * first use; the write is atomic — a crash mid-write leaves either the old URL or the new one.
     */
    public void put(String name, String url) throws IOException {
        validateName(name);
        validateUrl(url);
        if (memoryRemotes != null) {
            memoryRemotes.put(name, url);
            return;
        }
        Files.createDirectories(requireDir());
        Path file = requireDir().resolve(name);
        // A temp path derived from the NAME is a race: two concurrent puts of the same remote both
        // write "<name>.tmp", the first mover renames it away, and the second fails with
        // NoSuchFileException having written nothing. Found by TagRemotesConcurrencyTest. The temp
        // must be unique per writer, and must stay in the same directory for ATOMIC_MOVE to hold.
        // It still ends in ".tmp" so the directory listing below keeps skipping it.
        Path tmp = Files.createTempFile(requireDir(), name + ".", ".tmp");
        try {
            Files.writeString(tmp, url + "\n", StandardCharsets.UTF_8);
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(tmp); // never leave a stray temp behind on the failure path
            throw failed;
        }
    }

    /** Remove a remote. Returns {@code true} if it existed. */
    public boolean delete(String name) throws IOException {
        validateName(name);
        if (memoryRemotes != null) {
            return memoryRemotes.remove(name) != null;
        }
        return Files.deleteIfExists(requireDir().resolve(name));
    }

    /** True iff a remote with this name is configured. */
    public boolean exists(String name) throws IOException {
        validateName(name);
        if (memoryRemotes != null) {
            return memoryRemotes.containsKey(name);
        }
        return Files.exists(requireDir().resolve(name));
    }

    // ---- Bindings (Step 8 of plans/admin-remotes-page.md) ----------------

    /**
     * Pattern for global-remote IDs ({@code gr_<26-char-base32>}). Validates parsed values from the
     * bindings file — defends against corruption.
     */
    public static final Pattern GLOBAL_REMOTE_ID_PATTERN = Pattern.compile("gr_[a-z0-9]{26}");

    /**
     * Get the bound {@code globalRemoteId} for {@code name}, or empty if this name has no binding
     * (ad-hoc URL only).
     */
    public Optional<String> getBinding(String name) throws IOException {
        validateName(name);
        if (memoryBindings != null) {
            return Optional.ofNullable(memoryBindings.get(name));
        }
        return Optional.ofNullable(readBindings().get(name));
    }

    /**
     * Set / replace the binding for {@code name}. The underlying per-name URL row is NOT
     * auto-created — caller is responsible for ensuring the URL is also set (typically copied from
     * the global remote's URL at bind time).
     */
    public void putBinding(String name, String globalRemoteId) throws IOException {
        validateName(name);
        if (globalRemoteId == null || !GLOBAL_REMOTE_ID_PATTERN.matcher(globalRemoteId).matches()) {
            throw new IllegalArgumentException(
                    "invalid globalRemoteId: "
                            + globalRemoteId
                            + " (must match "
                            + GLOBAL_REMOTE_ID_PATTERN.pattern()
                            + ")");
        }
        if (memoryBindings != null) {
            memoryBindings.put(name, globalRemoteId);
            return;
        }
        Map<String, String> bindings = readBindings();
        bindings.put(name, globalRemoteId);
        writeBindings(bindings);
    }

    /** Remove the binding for {@code name}. Returns {@code true} if the binding existed. */
    public boolean deleteBinding(String name) throws IOException {
        validateName(name);
        if (memoryBindings != null) {
            return memoryBindings.remove(name) != null;
        }
        Map<String, String> bindings = readBindings();
        if (bindings.remove(name) == null) return false;
        writeBindings(bindings);
        return true;
    }

    /** All configured bindings, name → globalRemoteId. */
    public Map<String, String> listBindings() throws IOException {
        if (memoryBindings != null) {
            return new LinkedHashMap<>(memoryBindings);
        }
        return readBindings();
    }

    private Map<String, String> readBindings() throws IOException {
        Path file = bindingsFile();
        if (!Files.exists(file)) return new LinkedHashMap<>();
        String body = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (body.isEmpty() || body.equals("{}")) return new LinkedHashMap<>();
        return parseFlatJson(body);
    }

    private void writeBindings(Map<String, String> bindings) throws IOException {
        Path file = bindingsFile();
        Files.createDirectories(file.getParent());
        // Same fixed-temp race as put() above, and worse here: the bindings file is global, so
        // EVERY concurrent binding write collides on one temp path rather than only writers of the
        // same remote. Unique temp per writer, same directory so ATOMIC_MOVE still applies.
        Path tmp = Files.createTempFile(file.getParent(), BINDINGS_FILENAME + ".", ".tmp");
        try {
            Files.writeString(tmp, encodeFlatJson(bindings), StandardCharsets.UTF_8);
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(tmp);
            throw failed;
        }
    }

    /**
     * Minimal flat-JSON encoder for the bindings map. Both keys (per {@link #NAME_PATTERN}) and
     * values (per {@link #GLOBAL_REMOTE_ID_PATTERN}) contain only characters that need no
     * JSON-escape, so this is safe without an escape pass. Two-space indent for human readability.
     */
    static String encodeFlatJson(Map<String, String> map) {
        if (map.isEmpty()) return "{}\n";
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            sb.append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            if (i++ < map.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Minimal flat-JSON parser. Returns an empty map on malformed input — defensive against partial
     * corruption (the bindings file is not a critical-correctness path; losing it just removes pool
     * references).
     */
    static Map<String, String> parseFlatJson(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        int i = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (i < 0 || end < 0 || end <= i) return out;
        int p = i + 1;
        while (p < end) {
            // Find opening quote of key.
            int k1 = body.indexOf('"', p);
            if (k1 < 0 || k1 >= end) break;
            int k2 = body.indexOf('"', k1 + 1);
            if (k2 < 0 || k2 >= end) break;
            String key = body.substring(k1 + 1, k2);
            // Find colon then opening quote of value.
            int colon = body.indexOf(':', k2);
            if (colon < 0 || colon >= end) break;
            int v1 = body.indexOf('"', colon);
            if (v1 < 0 || v1 >= end) break;
            int v2 = body.indexOf('"', v1 + 1);
            if (v2 < 0 || v2 >= end) break;
            String value = body.substring(v1 + 1, v2);
            // Sanity — only retain entries that match the expected patterns.
            if (NAME_PATTERN.matcher(key).matches()
                    && GLOBAL_REMOTE_ID_PATTERN.matcher(value).matches()) {
                out.put(key, value);
            }
            p = v2 + 1;
        }
        return out;
    }

    /** All configured remotes, name → URL. Iteration order is filesystem-dependent. */
    public Map<String, String> list() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (memoryRemotes != null) {
            out.putAll(memoryRemotes);
            return out;
        }
        if (!Files.exists(requireDir())) {
            return out;
        }
        try (Stream<Path> paths = Files.list(requireDir())) {
            paths.filter(Files::isRegularFile)
                    .forEach(
                            p -> {
                                String name = p.getFileName().toString();
                                if (name.endsWith(".tmp")) {
                                    return; // ignore in-flight writes
                                }
                                try {
                                    String value =
                                            Files.readString(p, StandardCharsets.UTF_8).trim();
                                    if (!value.isEmpty()) {
                                        out.put(name, value);
                                    }
                                } catch (IOException ignored) {
                                    // Skip unreadable files — one bad entry must not abort the
                                    // list.
                                }
                            });
        }
        return out;
    }
}
