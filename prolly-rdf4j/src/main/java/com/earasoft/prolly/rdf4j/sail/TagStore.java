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

import com.dolthub.prolly.HashUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Sidecar storage for immutable named tags — git-style named pointers at a commit.
 *
 * <p>Filesystem layout (beside {@link RefsStore}'s {@code refs/}):
 *
 * <pre>
 *   &lt;storeDir&gt;/tags/v1.0.0      ← line 1 = hex commit hash; remaining bytes = the message
 *   &lt;storeDir&gt;/tags/release-7   ← (message may be empty or multi-line)
 * </pre>
 *
 * @implNote <b>Why a dedicated store rather than a {@code refs/tags/} namespace (the rejected
 *     alternative).</b> Putting tags in {@link RefsStore} would surface them as phantom branches in
 *     the already-shipped {@code ListBranches} / {@code GetBranchHeads} (which read {@code
 *     RefsStore.list()} unfiltered), and {@code RefsStore}'s hex-only values cannot carry the tag
 *     {@code message}. A separate store isolates tags by construction and stores the message
 *     natively. See <a
 *     href="../../../../../../../../docs/adr/0047-grpc-tag-storage.md">ADR-0047</a>.
 *     <p><b>Collaborators:</b> name validation is borrowed from {@link
 *     RefsStore#validateName(String)} (the same path-traversal + absolute-path guards —
 *     load-bearing security). Hash hex encoding is {@link HashUtils}.
 * @apiNote Tags are <b>immutable</b>: {@link #create} is create-if-absent and there is no update.
 *     The only mutation after creation is {@link #delete}. {@code create} is atomic
 *     create-if-absent at the OS level ({@code CREATE_NEW} / {@code O_EXCL}), so two racing
 *     creators cannot both win.
 */
public final class TagStore {

    public static final String DIRNAME = "tags";

    /** A tag's payload: the commit it points at and its (possibly empty) annotation message. */
    public record Entry(byte[] commit, String message) {}

    /** Backing directory, or {@code null} for an in-memory store (tests / JVM-only deployments). */
    private final @Nullable Path dir;

    /** Only used when {@link #dir} is {@code null}. */
    private final @Nullable Map<String, Entry> memoryTags;

    public TagStore(Path dir) {
        this.dir = dir;
        this.memoryTags = null;
    }

    private TagStore() {
        this.dir = null;
        this.memoryTags = new ConcurrentHashMap<>();
    }

    /** Build a {@link TagStore} rooted at {@code <storeDir>/tags}. */
    public static TagStore beside(Path storeDir) {
        return new TagStore(storeDir.resolve(DIRNAME));
    }

    /** Factory for an in-memory tag store — bounded by JVM lifetime. */
    public static TagStore inMemory() {
        return new TagStore();
    }

    public @Nullable Path dir() {
        return dir;
    }

    /**
     * The backing directory, asserted present. Valid only on the file-backed path — every public
     * method early-returns when {@link #memoryTags} is non-null, so reaching {@code requireDir()}
     * means file-backed mode. {@link #dir} and {@link #memoryTags} are mutually exclusive (exactly
     * one is null), an invariant NullAway cannot express across the discriminator.
     */
    private Path requireDir() {
        return Objects.requireNonNull(dir, "file-backed TagStore operation on an in-memory store");
    }

    /**
     * Create a tag if it does not already exist. Returns {@code true} when created, {@code false}
     * when a tag of that name is already present (the caller maps that to {@code ALREADY_EXISTS}).
     * Atomic create-if-absent — no racing creator can also succeed.
     *
     * @param message the annotation message; may be empty but not null
     */
    public boolean create(String name, byte[] commit, String message) throws IOException {
        RefsStore.validateName(name);
        if (commit == null) throw new IllegalArgumentException("commit must not be null");
        if (message == null)
            throw new IllegalArgumentException("message must not be null (use \"\")");
        if (memoryTags != null) {
            return memoryTags.putIfAbsent(name, new Entry(commit.clone(), message)) == null;
        }
        Path d = requireDir();
        Files.createDirectories(d);
        Path file = d.resolve(name);
        Files.createDirectories(file.getParent()); // for nested names like "release/2026"
        String body = HashUtils.toHex(commit) + "\n" + message;
        try {
            Files.write(file, body.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
            return true;
        } catch (java.nio.file.FileAlreadyExistsException exists) {
            return false;
        }
    }

    /** Read a tag's commit + message, or empty if no such tag. */
    public Optional<Entry> get(String name) throws IOException {
        RefsStore.validateName(name);
        if (memoryTags != null) {
            Entry e = memoryTags.get(name);
            return e == null
                    ? Optional.empty()
                    : Optional.of(new Entry(e.commit().clone(), e.message()));
        }
        Path file = requireDir().resolve(name);
        if (!Files.exists(file)) return Optional.empty();
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        return Optional.of(parse(file, name, raw));
    }

    /** List every tag, keyed by name (sorted for stable output). */
    public Map<String, Entry> list() throws IOException {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (memoryTags != null) {
            memoryTags.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            e ->
                                    out.put(
                                            e.getKey(),
                                            new Entry(
                                                    e.getValue().commit().clone(),
                                                    e.getValue().message())));
            return out;
        }
        Path d = requireDir();
        if (!Files.exists(d)) return out;
        try (java.util.stream.Stream<Path> walk = Files.walk(d)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(
                            p -> {
                                String name = d.relativize(p).toString().replace('\\', '/');
                                try {
                                    out.put(
                                            name,
                                            parse(
                                                    p,
                                                    name,
                                                    Files.readString(p, StandardCharsets.UTF_8)));
                                } catch (IOException io) {
                                    throw new java.io.UncheckedIOException(io);
                                }
                            });
        } catch (java.io.UncheckedIOException u) {
            throw u.getCause();
        }
        return out;
    }

    /**
     * Delete a tag. Returns {@code true} if it existed (deletes are not idempotent at the verb
     * edge).
     */
    public boolean delete(String name) throws IOException {
        RefsStore.validateName(name);
        if (memoryTags != null) {
            return memoryTags.remove(name) != null;
        }
        return Files.deleteIfExists(requireDir().resolve(name));
    }

    /** Parse a tag file: first line = hex commit, the rest (after the first newline) = message. */
    private static Entry parse(Path file, String name, String raw) throws IOException {
        int nl = raw.indexOf('\n');
        String hex = (nl < 0 ? raw : raw.substring(0, nl)).trim();
        String message = nl < 0 ? "" : raw.substring(nl + 1);
        try {
            return new Entry(HashUtils.fromHex(hex), message);
        } catch (RuntimeException corrupt) {
            throw new IOException(
                    "corrupt tag file "
                            + file
                            + " for tag '"
                            + name
                            + "' — first line is not a valid hex hash",
                    corrupt);
        }
    }
}
