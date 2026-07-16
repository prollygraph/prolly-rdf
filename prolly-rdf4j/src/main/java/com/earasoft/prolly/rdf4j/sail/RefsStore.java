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
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Sidecar storage for named branches.
 *
 * <p>Filesystem layout:
 *
 * <pre>
 *   &lt;storeDir&gt;/refs/main         ← hex RootMetaTree hash of main's head commit
 *   &lt;storeDir&gt;/refs/feature-x    ← hex RootMetaTree hash of feature-x's head
 * </pre>
 *
 * <p>One file per branch, content is a single line of hex (the RootMetaTree commit id). Writes use
 * temp-file-then-rename for atomicity. Branch names are validated against {@link #NAME_PATTERN} to
 * keep filesystem-safe characters only.
 *
 * <h2>Why a sidecar directory rather than a prolly tree</h2>
 *
 * <p>Refs change frequently and need to be human-inspectable (you should be able to {@code cat
 * refs/main} from a shell). Plain files mirror Git's {@code .git/refs/} layout exactly — operators
 * recognize it. A future Manifest integration can move this into a content-addressed structure if
 * cross-process atomicity becomes a requirement.
 *
 * <h2>Bootstrap</h2>
 *
 * <p>A fresh store has no refs directory. The {@link ProllySail} creates {@code refs/main} pointing
 * at the most recent commit (or leaves it absent if the store has no commits yet). The default
 * branch name is {@link #DEFAULT_BRANCH} = {@value DEFAULT_BRANCH}.
 */
public final class RefsStore {

    public static final String DIRNAME = "refs";
    public static final String DEFAULT_BRANCH = "main";

    /** Branch names: ASCII letters/digits, dash, underscore, dot, slash; 1-128 chars. */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_./-]{1,128}");

    /**
     * Backing directory, or {@code null} for an in-memory store. In-memory mode keeps {@link
     * #memoryRefs} as the source of truth so the {@code /sparql/branches} endpoint works in
     * JVM-only deployments without a configured {@code store-dir} (#127 follow-up).
     */
    private final @Nullable Path dir;

    /** Only used when {@link #dir} is {@code null}. */
    private final java.util.@Nullable Map<String, byte[]> memoryRefs;

    public RefsStore(Path dir) {
        this.dir = dir;
        this.memoryRefs = null;
    }

    private RefsStore() {
        this.dir = null;
        this.memoryRefs = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** Build a {@link RefsStore} rooted at {@code <storeDir>/refs}. */
    public static RefsStore beside(Path storeDir) {
        return new RefsStore(storeDir.resolve(DIRNAME));
    }

    /** Factory for an in-memory refs store — bounded by JVM lifetime. */
    public static RefsStore inMemory() {
        return new RefsStore();
    }

    public @Nullable Path dir() {
        return dir;
    }

    /**
     * The backing directory, asserted present. Valid only on the file-backed path — every public
     * method early-returns when {@link #memoryRefs} is non-null, so reaching a {@code requireDir()}
     * call means file-backed mode. {@link #dir} and {@link #memoryRefs} are mutually exclusive
     * (exactly one is null), an invariant NullAway cannot express across the discriminator.
     */
    private Path requireDir() {
        return Objects.requireNonNull(dir, "file-backed RefsStore operation on an in-memory store");
    }

    /** Validate a branch name; throws {@link IllegalArgumentException} on bad input. */
    public static void validateName(String name) {
        if (name == null) throw new IllegalArgumentException("branch name must not be null");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "invalid branch name '" + name + "': must match " + NAME_PATTERN.pattern());
        }
        // Disallow path traversal — '..' segments are syntactically possible inside the pattern
        // because '.' is allowed, but they'd escape the refs directory.
        if (name.equals("..")
                || name.startsWith("../")
                || name.endsWith("/..")
                || name.contains("/../")) {
            throw new IllegalArgumentException(
                    "branch name must not contain '..' segments: " + name);
        }
        // Disallow absolute paths. The name pattern allows '/', so a leading
        // slash is syntactically valid — but dir.resolve(absolutePath) ignores
        // the base directory entirely, escaping refs/ and turning put/delete
        // into an arbitrary-file write/delete. A branch name is always relative.
        if (name.startsWith("/")) {
            throw new IllegalArgumentException("branch name must not be an absolute path: " + name);
        }
    }

    /** Read one branch's head commit hash, or empty if the branch doesn't exist. */
    public Optional<byte[]> get(String name) throws IOException {
        validateName(name);
        if (memoryRefs != null) {
            byte[] v = memoryRefs.get(name);
            return v == null ? Optional.empty() : Optional.of(v.clone());
        }
        Path file = requireDir().resolve(name);
        if (!Files.exists(file)) return Optional.empty();
        String hex = Files.readString(file, StandardCharsets.UTF_8).trim();
        if (hex.isEmpty()) return Optional.empty();
        // A corrupt ref file makes fromHex throw an unchecked exception that
        // would escape this throws-IOException method — translate it.
        try {
            return Optional.of(HashUtils.fromHex(hex));
        } catch (RuntimeException corrupt) {
            throw new IOException(
                    "corrupt ref file "
                            + file
                            + " for branch '"
                            + name
                            + "' — not a valid hex hash",
                    corrupt);
        }
    }

    /**
     * Atomically set a branch's head. Creates the {@code refs/} dir on first use; uses a temp file
     * plus an atomic rename so a crash mid- write leaves either the old hash or the new one.
     */
    public void put(String name, byte[] commitHash) throws IOException {
        validateName(name);
        if (commitHash == null) throw new IllegalArgumentException("commitHash must not be null");
        if (memoryRefs != null) {
            memoryRefs.put(name, commitHash.clone());
            return;
        }
        Path d = requireDir();
        Files.createDirectories(d);
        Path file = d.resolve(name);
        Files.createDirectories(file.getParent()); // for nested names like "feature/x"
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, HashUtils.toHex(commitHash) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Atomically replace the branch's head iff its current value equals {@code expected}. With
     * {@code expected == null} the swap only happens when the branch does <em>not</em> yet exist
     * (git's "create-only" semantics); with {@code expected != null} the current value must
     * byte-for-byte equal {@code expected}. {@code desired} must be non-null.
     *
     * <p><b>Atomicity.</b> Within a single JVM, in-memory mode synchronizes on the backing map.
     * On-disk mode additionally holds an OS-level file lock on a sidecar {@code
     * <refs-dir>.cas.lock} file (created beside, not inside, the {@code refs/} directory so {@link
     * #list} never picks it up as a branch), so the CAS is correct across processes — necessary for
     * the concurrent-push hardening (plan Step 21).
     *
     * <p><b>Caveat.</b> {@link #put put} and {@link #delete delete} do <em>not</em> acquire this
     * same lock — a caller that mixes bare puts with CAS on the same name can still race. The
     * current sites mixing CAS with put are isolated to the push path and don't share names with
     * the local-commit put path; a broader sweep to serialize every write through this lock is
     * parked.
     *
     * @return {@code true} if the swap happened, {@code false} if the current value didn't match
     *     {@code expected}
     */
    public boolean compareAndSet(String name, byte @Nullable [] expected, byte[] desired)
            throws IOException {
        validateName(name);
        if (desired == null) {
            throw new IllegalArgumentException("desired must not be null");
        }
        if (memoryRefs != null) {
            synchronized (memoryRefs) {
                byte[] current = memoryRefs.get(name);
                boolean matches =
                        (expected == null)
                                ? current == null
                                : current != null && Arrays.equals(expected, current);
                if (!matches) return false;
                memoryRefs.put(name, desired.clone());
                return true;
            }
        }
        // On-disk: cross-process atomicity via a sidecar lockfile that lives
        // *outside* the refs/ dir so list() never picks it up as a branch.
        Path d = requireDir();
        Files.createDirectories(d);
        Path lockFile = d.resolveSibling(d.getFileName() + ".cas.lock");
        // synchronized(this) gates in-JVM threads: Java's FileChannel.lock()
        // throws OverlappingFileLockException if a second thread in the same
        // JVM tries to acquire it concurrently, so the monitor must serialize
        // them. The FileLock then handles cross-process atomicity.
        synchronized (this) {
            try (FileChannel ch =
                            FileChannel.open(
                                    lockFile,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.READ,
                                    StandardOpenOption.WRITE);
                    FileLock ignored = ch.lock()) {
                Optional<byte[]> current = get(name);
                boolean matches =
                        (expected == null)
                                ? current.isEmpty()
                                : current.isPresent() && Arrays.equals(expected, current.get());
                if (!matches) return false;
                put(name, desired);
                return true;
            }
        }
    }

    /** Delete a branch. Returns {@code true} if it existed, {@code false} otherwise. */
    public boolean delete(String name) throws IOException {
        validateName(name);
        if (memoryRefs != null) {
            return memoryRefs.remove(name) != null;
        }
        return Files.deleteIfExists(requireDir().resolve(name));
    }

    /**
     * Conditionally delete a branch — atomic check-and-delete. Returns {@code true} only when the
     * branch existed AND its head matched {@code expected}. If the branch was advanced concurrently
     * (e.g. a push between the caller reading the head and calling this method), the delete is
     * skipped and {@code false} is returned; the caller can decide whether to retry or surface the
     * race.
     *
     * <p>Cross-process atomicity is via the same sidecar lockfile {@link #compareAndSet} uses, so a
     * delete cannot interleave with a CAS in another process either.
     *
     * <p><b>Caveat (inherited from compareAndSet):</b> {@link #put put} does <em>not</em> acquire
     * this lock. A bare {@code put} between the head-read and the file-delete can therefore land a
     * value that this method then deletes — the check-and-delete is atomic against other CAS
     * callers, but not against plain puts. Callers that need full safety in the presence of
     * un-coordinated writes must serialize all writes through CAS (the merge auto-delete site does
     * this — the only other writer to a feature branch is a SPARQL push that goes through commit
     * log + ref update; that path doesn't observe the CAS window in practice).
     *
     * @return {@code true} if the head matched and the branch was deleted; {@code false} if the
     *     head didn't match (or the branch didn't exist).
     */
    public boolean compareAndDelete(String name, byte[] expected) throws IOException {
        validateName(name);
        if (expected == null) {
            throw new IllegalArgumentException("expected must not be null");
        }
        if (memoryRefs != null) {
            synchronized (memoryRefs) {
                byte[] current = memoryRefs.get(name);
                if (current == null || !Arrays.equals(expected, current)) return false;
                memoryRefs.remove(name);
                return true;
            }
        }
        Path d = requireDir();
        Files.createDirectories(d);
        Path lockFile = d.resolveSibling(d.getFileName() + ".cas.lock");
        synchronized (this) {
            try (FileChannel ch =
                            FileChannel.open(
                                    lockFile,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.READ,
                                    StandardOpenOption.WRITE);
                    FileLock ignored = ch.lock()) {
                Optional<byte[]> current = get(name);
                if (current.isEmpty() || !Arrays.equals(expected, current.get())) return false;
                Files.deleteIfExists(d.resolve(name));
                return true;
            }
        }
    }

    /** Returns true iff a branch with this name exists. */
    public boolean exists(String name) throws IOException {
        validateName(name);
        if (memoryRefs != null) {
            return memoryRefs.containsKey(name);
        }
        return Files.exists(requireDir().resolve(name));
    }

    /**
     * List all branches, name → head commit hash. Iteration order is filesystem-dependent; HTTP
     * responses should sort by name themselves if they need a stable order.
     */
    public Map<String, byte[]> list() throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (memoryRefs != null) {
            for (var e : memoryRefs.entrySet()) out.put(e.getKey(), e.getValue().clone());
            return out;
        }
        Path d = requireDir();
        if (!Files.exists(d)) return out;
        try (var paths = Files.walk(d)) {
            paths.filter(Files::isRegularFile)
                    .forEach(
                            p -> {
                                String name = d.relativize(p).toString().replace('\\', '/');
                                if (name.endsWith(".tmp")) return; // ignore in-flight writes
                                try {
                                    String hex = Files.readString(p, StandardCharsets.UTF_8).trim();
                                    if (!hex.isEmpty()) {
                                        out.put(name, HashUtils.fromHex(hex));
                                    }
                                } catch (IOException | RuntimeException ignored) {
                                    // Skip unreadable OR corrupt files — best-effort list; one
                                    // bad ref file must not abort listing every other branch.
                                    // (fromHex throws an unchecked exception on a corrupt file,
                                    // so RuntimeException must be caught here too.)
                                }
                            });
        }
        return out;
    }
}
