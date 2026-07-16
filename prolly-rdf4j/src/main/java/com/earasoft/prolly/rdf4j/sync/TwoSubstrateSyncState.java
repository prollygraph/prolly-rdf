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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The per-repo, <b>two-substrate</b> sync state ({@code prolly-json-rest/plans/prolly-json-sync.md}
 * Step 5, D-5): ONE record per {@code (remote, branch)} holding BOTH substrates' last-synced heads
 * — the RDF {@code Database}'s and the JSON {@code Database}'s — persisted as a single file whose
 * update is one atomic temp-then-rename replace. The sync coordinator advances the pair only after
 * <i>both</i> substrates applied, so a mid-apply failure leaves <b>neither</b> side advanced:
 * substrate drift is impossible by construction, because no API exists that records one substrate's
 * head without the other's.
 *
 * @apiNote {@link #advance} is a compare-and-set: the caller states the pair it believes is current
 *     ({@code null} = the record must not exist yet) and the update is rejected on a mismatch — the
 *     same optimistic discipline as branch refs. A {@code null} head inside a {@link Heads} is
 *     legal and means "this substrate has never synced" (e.g. a repo whose remote takes RDF only).
 *     Chunk transfer is deliberately NOT covered by this atomicity: content-addressed chunks that
 *     landed before a failure are unreachable-and-harmless until a later successful sync claims
 *     them (garbage-collectable, never wrong).
 * @implNote Layout: {@code <dir>/<remote>/<branch>} containing two lines, {@code rdf <hex|->} and
 *     {@code json <hex|->}. Remote and branch names pass {@link RefsStore#validateName} — the same
 *     path-traversal guard the refs files earned ({@code bugs/refsstore-path-traversal}-class
 *     hardening, reused not re-derived). Writes are temp-then-{@code ATOMIC_MOVE} like {@code
 *     RefsStore}; a stray temp file from a crash is ignored by reads. Single-writer-per-repo is
 *     assumed (the warm-registry lease), with a belt-and-braces instance lock.
 *     <b>Collaborators:</b> the Phase-3 sync coordinator (the only intended writer); {@code
 *     RefsStore} (the name-validation + file conventions this mirrors).
 */
public final class TwoSubstrateSyncState {

    /** Both substrates' last-synced heads; a {@code null} head = that substrate never synced. */
    public record Heads(byte @Nullable [] rdfHead, byte @Nullable [] jsonHead) {

        public boolean sameAs(@Nullable Heads other) {
            return other != null
                    && Arrays.equals(rdfHead, other.rdfHead)
                    && Arrays.equals(jsonHead, other.jsonHead);
        }
    }

    private final Path dir;
    private final Object lock = new Object();

    private TwoSubstrateSyncState(Path dir) {
        this.dir = dir;
    }

    /** The repo's sync-state store, in {@code <repoDir>/sync-state/} beside its refs. */
    public static TwoSubstrateSyncState beside(Path repoDir) {
        return new TwoSubstrateSyncState(repoDir.resolve("sync-state"));
    }

    /** The recorded pair for {@code (remote, branch)}, or empty if never synced. */
    public Optional<Heads> read(String remote, String branch) {
        Path file = fileFor(remote, branch);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String[] lines = Files.readString(file, StandardCharsets.UTF_8).strip().split("\n");
            return Optional.of(new Heads(parseLine(lines, "rdf"), parseLine(lines, "json")));
        } catch (IOException e) {
            throw new UncheckedIOException("unreadable sync state: " + file, e);
        }
    }

    /**
     * Atomically advance the pair — compare-and-set from {@code expected} ({@code null}: the record
     * must not exist). Returns false on a mismatch, leaving the state untouched.
     */
    public boolean advance(String remote, String branch, @Nullable Heads expected, Heads next) {
        synchronized (lock) {
            Optional<Heads> current = read(remote, branch);
            if (expected == null ? current.isPresent() : !expected.sameAs(current.orElse(null))) {
                return false;
            }
            Path file = fileFor(remote, branch);
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(
                        tmp,
                        "rdf "
                                + render(next.rdfHead())
                                + "\njson "
                                + render(next.jsonHead())
                                + "\n",
                        StandardCharsets.UTF_8);
                Files.move(
                        tmp,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException e) {
                throw new UncheckedIOException("could not advance sync state: " + file, e);
            }
        }
    }

    private Path fileFor(String remote, String branch) {
        RefsStore.validateName(remote);
        RefsStore.validateName(branch);
        return dir.resolve(remote).resolve(branch);
    }

    private static String render(byte @Nullable [] head) {
        return head == null ? "-" : HashUtils.toHex(head);
    }

    private static byte @Nullable [] parseLine(String[] lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + " ")) {
                String v = line.substring(key.length() + 1).strip();
                return "-".equals(v) ? null : HashUtils.fromHex(v);
            }
        }
        throw new IllegalStateException("malformed sync-state record: missing '" + key + "' line");
    }
}
