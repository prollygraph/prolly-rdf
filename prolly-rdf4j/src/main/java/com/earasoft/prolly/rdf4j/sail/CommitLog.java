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
import com.dolthub.prolly.NodeStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only on-disk commit chain for a ProllySail-backed store.
 *
 * <p>Each successful Sail commit persists the commit as a content-addressed <b>chunk</b> in the
 * {@code NodeStore} (ADR-0073) and appends one <em>thin</em> row to {@code commits.log} next to
 * {@link RootMetaTreeStore}'s {@code meta-head} file — just the wall-clock time + the commit id:
 *
 * <pre>
 *   &lt;RFC 1123 datetime&gt; &lt;hex commit id&gt;
 * </pre>
 *
 * <p>Example:
 *
 * <pre>
 *   Tue, 12 May 2026 23:14:48 GMT a1b2c3…
 * </pre>
 *
 * <p>The content (RootMetaTree hash, parent ids, author, message) lives in the commit chunk under
 * that id; {@link #entries} reconstructs each full {@link Entry} from the chunk plus the row's
 * datetime. The timestamp is the one thing kept out of the id (ADR-0071 D-2), so it can only live
 * on the row, not in the chunk. A <b>file-backed</b> log therefore needs a {@link NodeStore}
 * {@linkplain #attachStore attached} (an in-memory log keeps full entries in memory instead).
 *
 * <h2>Commit id vs RootMetaTree hash (ADR-0071)</h2>
 *
 * <p>The commit <b>id</b> is a content hash over the commit's logically-meaningful content — {@code
 * hash(metaTreeHash ‖ parent-ids ‖ author ‖ message)} (see {@link CommitId}). It is <em>not</em>
 * the RootMetaTree hash: two distinct commits that share a tree but differ in parents must have
 * distinct ids, or the commit graph collapses them (the measured root cause of a sync-convergence
 * failure — see {@code prolly-rdf4j/plans/commit-identity-redesign.md}). The {@code metaTreeHash}
 * stays a separate field — it is the <em>tree address</em> used to open/read a commit's data
 * ({@link RootMetaTree#readFrom}).
 *
 * <p>The wall-clock timestamp is deliberately <b>excluded</b> from the id (ADR-0071 D-2), so the id
 * is deterministic — the same logical commit produced independently on two peers gets the same id.
 * The log keeps wall-clock time out of the immutable substrate while still recording it for Memento
 * / timemap.
 *
 * <h2>Atomicity and concurrency</h2>
 *
 * <p>Writes use {@code StandardOpenOption.APPEND} which is atomic for lines under {@code PIPE_BUF}
 * on POSIX filesystems — our lines (a datetime plus 64-hex hashes) are well under that. v2.0 is
 * single-writer so concurrent appends are not a design target; Phase 4 will integrate this with the
 * Manifest for cross-process safety.
 *
 * <h2>Memento intent</h2>
 *
 * <p>Backs the {@code Memento-Datetime} response header and the {@code /sparql/timemap} endpoint
 * per RFC 7089. The id side of the line backs the custom {@code X-Prolly-Commit-Id} header so
 * clients can address a specific snapshot by commit id via {@code ?commit=}.
 */
public final class CommitLog {

    private static final Logger LOG = LoggerFactory.getLogger(CommitLog.class);

    public static final String FILENAME = "commits.log";

    /** RFC 1123 datetime format used by HTTP headers (same as {@code Memento-Datetime}). */
    public static final DateTimeFormatter RFC_1123 =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /**
     * Backing file, or {@code null} for an in-memory log. In-memory mode keeps {@link
     * #memoryEntries} as the source of truth so JVM-local history queries (commit id → datetime
     * resolution, {@code /sparql/commits}, the event-log endpoint's parent → introducing-commit
     * walk) work uniformly whether or not durable persistence is configured.
     */
    private final @Nullable Path file;

    /** Only used when {@link #file} is {@code null} — JVM-local entries. */
    private final @Nullable List<Entry> memoryEntries;

    /**
     * The chunk store a <b>file-backed</b> log needs (ADR-0073): {@link #append} writes each
     * commit's content-addressed chunk here and persists only a thin {@code <datetime> <id>} row,
     * and {@link #entries} reconstructs the full {@link Entry} from that chunk. Attached once at
     * wiring time via {@link #attachStore} (by {@code ProllySail}). {@code null} for an in-memory
     * log (which keeps full entries in {@link #memoryEntries}) or before wiring.
     */
    private @Nullable NodeStore store;

    public CommitLog(Path file) {
        this.file = file;
        this.memoryEntries = null;
    }

    private CommitLog() {
        this.file = null;
        this.memoryEntries = new ArrayList<>();
    }

    /**
     * Attach the chunk store this file-backed log reconstructs commit content from (ADR-0073). A
     * <b>wiring-time</b> call, before any {@code append}; <b>last attach wins</b> (so a caller that
     * pre-attaches a store and then hands the log to {@code ProllySail} lets the Sail's own store —
     * the one it actually writes chunks to — take over). A no-op for an in-memory log, which keeps
     * full entries in memory and needs no reconstruction.
     */
    public void attachStore(NodeStore nodeStore) {
        Objects.requireNonNull(nodeStore, "nodeStore must not be null");
        if (memoryEntries != null) {
            return; // in-memory log keeps full entries; no chunk reconstruction needed
        }
        this.store = nodeStore;
    }

    /**
     * Static factory: build a {@link CommitLog} pointing at {@code commits.log} inside {@code
     * storeDir}. Caller is responsible for ensuring storeDir exists (typically true because the
     * NodeStore created it).
     */
    public static CommitLog beside(Path storeDir) {
        return new CommitLog(storeDir.resolve(FILENAME));
    }

    /**
     * As {@link #beside(Path)} but with the chunk {@code store} already {@linkplain #attachStore
     * attached} — the file-backed form a caller uses directly (a test, or a component that has its
     * {@code NodeStore} at construction). {@code ProllySail} uses {@link #beside(Path)} + attaches
     * later, since it receives the log before it can wire the store.
     */
    public static CommitLog beside(Path storeDir, NodeStore store) {
        CommitLog log = new CommitLog(storeDir.resolve(FILENAME));
        log.attachStore(store);
        return log;
    }

    /**
     * Factory for an in-memory log — appends accumulate in a list bounded by JVM lifetime. Backs
     * the in-memory-store mode so endpoints like {@code /sparql/provenance/log} (#127) can resolve
     * event parent hashes to introducing commits without a sidecar file.
     */
    public static CommitLog inMemory() {
        return new CommitLog();
    }

    /** File this log is written to, or {@code null} for an in-memory log. */
    public @Nullable Path file() {
        return file;
    }

    /**
     * The backing file, asserted present. Valid only on the file-backed path — {@link #append} and
     * {@link #entries} early-return when {@link #memoryEntries} is non-null, so reaching {@code
     * requireFile()} means file-backed mode. {@link #file} and {@link #memoryEntries} are mutually
     * exclusive (exactly one is null), an invariant NullAway cannot express across the
     * discriminator.
     */
    private Path requireFile() {
        return Objects.requireNonNull(file, "file-backed CommitLog operation on an in-memory log");
    }

    /**
     * Append one entry to the log with zero parents (genesis commit). The id is computed from the
     * tree + (empty) parents + message + author. Convenience over {@link #append(Instant, byte[],
     * byte[], List, String, String)}.
     */
    public void append(Instant when, byte[] metaTreeHash) throws IOException {
        append(when, metaTreeHash, Collections.emptyList(), "");
    }

    /**
     * Three-arg variant — preserves the pre-iter-X call sites that don't thread a commit message
     * through (genesis-only tests, merges, etc.). Parents are parent <b>commit ids</b>.
     */
    public void append(Instant when, byte[] metaTreeHash, List<byte[]> parents) throws IOException {
        append(when, metaTreeHash, parents, "");
    }

    /**
     * Four-arg variant — author defaults to empty. Preserves the call sites (merges, branch
     * helpers, tests) that don't thread an author through. Parents are parent <b>commit ids</b>.
     */
    public void append(Instant when, byte[] metaTreeHash, List<byte[]> parents, String message)
            throws IOException {
        append(when, metaTreeHash, parents, message, "");
    }

    /**
     * Five-arg variant — the id is <b>computed</b> from the other fields (the common local-commit
     * case). Delegates to {@link #append(Instant, byte[], byte[], List, String, String)} with a
     * null id. Parents are parent <b>commit ids</b>.
     */
    public void append(
            Instant when, byte[] metaTreeHash, List<byte[]> parents, String message, String author)
            throws IOException {
        append(when, null, metaTreeHash, parents, message, author);
    }

    /**
     * Append one entry to the log with an <b>explicit commit id</b>, the RootMetaTree (tree) hash,
     * the parent <b>commit ids</b>, an optional human-readable commit message, and an optional
     * author.
     *
     * <p>When {@code id} is null it is computed via {@link CommitId#of} from the tree + parents +
     * author + message (the local-commit path). When non-null it is <b>preserved verbatim</b> —
     * used by sync to adopt a received commit wholesale (the id is the cross-peer identity and must
     * not be re-derived; ADR-0071 D-4). Because {@link CommitId} is deterministic, a recompute
     * would yield the same bytes, but preserving keeps the door open to fail-closed verification
     * later.
     *
     * <p>Wire format (one line):
     *
     * <pre>
     *   &lt;RFC 1123 datetime&gt; &lt;hex id&gt; &lt;hex metaTreeHash&gt; [&lt;hex parent id&gt;…] [m=&lt;base64 message&gt;] [a=&lt;base64 author&gt;]
     * </pre>
     *
     * <p>The {@code m=} / {@code a=} tokens are omitted entirely when empty. Each base64-encodes
     * its value so it can carry spaces / unicode / newlines without breaking the
     * whitespace-tokenized line format. This is an append-only log reading its own durable history;
     * per the pre-1.0 no-backwards-compat rule (ADR-0071 D-5) there is no defensive reader for the
     * old (id-less) format — an existing store is migrated by the one-shot tool.
     */
    public void append(
            Instant when,
            byte @Nullable [] id,
            byte[] metaTreeHash,
            List<byte[]> parents,
            String message,
            String author)
            throws IOException {
        if (when == null) throw new IllegalArgumentException("when must not be null");
        if (metaTreeHash == null)
            throw new IllegalArgumentException("metaTreeHash must not be null");
        if (parents == null) throw new IllegalArgumentException("parents must not be null");
        for (byte[] p : parents) {
            if (p == null) throw new IllegalArgumentException("parent ids must not be null");
        }
        String msg = message == null ? "" : message;
        String auth = author == null ? "" : author;
        byte[] commitId = id != null ? id : CommitId.of(metaTreeHash, parents, auth, msg);
        if (memoryEntries != null) {
            memoryEntries.add(new Entry(when, commitId, metaTreeHash, parents, msg, auth));
            cached = null; // the memoized view no longer reflects the log
            return;
        }
        // File-backed (ADR-0073): persist the commit as a content-addressed chunk, then a thin
        // "<datetime> <id>" row. The chunk carries the content (metaTreeHash, parents, author,
        // message); the row carries only the id + the wall-clock time (excluded from the id, so it
        // cannot live in the chunk). entries() reconstructs the full Entry from the chunk. The
        // chunk
        // is written BEFORE the row, so a crash between the two leaves an orphan chunk (harmless),
        // never a row whose chunk is missing.
        NodeStore s =
                Objects.requireNonNull(
                        store,
                        "a file-backed CommitLog needs an attached NodeStore (attachStore) to persist"
                                + " commit chunks — ADR-0073");
        byte[] chunkId =
                new CommitStore(s).write(CommitObject.of(metaTreeHash, parents, auth, msg));
        if (!Arrays.equals(chunkId, commitId)) {
            throw new IllegalStateException(
                    "commit chunk address "
                            + HashUtils.toHex(chunkId)
                            + " != commit id "
                            + HashUtils.toHex(commitId)
                            + " (content does not hash to its id)");
        }
        String row =
                RFC_1123.format(ZonedDateTime.ofInstant(when, ZoneOffset.UTC))
                        + ' '
                        + HashUtils.toHex(commitId)
                        + '\n';
        Files.writeString(
                requireFile(),
                row,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        cached = null; // the memoized view no longer reflects the log
    }

    /** All entries in chronological order. Returns empty list if the log is absent / empty. */
    public List<Entry> entries() throws IOException {
        if (memoryEntries != null) {
            return Collections.unmodifiableList(new ArrayList<>(memoryEntries));
        }
        Path f = requireFile();
        if (!Files.exists(f)) return Collections.emptyList();
        NodeStore s =
                Objects.requireNonNull(
                        store,
                        "a file-backed CommitLog needs an attached NodeStore (attachStore) to"
                                + " reconstruct commit content from chunks — ADR-0073");
        CommitStore commits = new CommitStore(s);
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            ThinRow r;
            try {
                r = parseThinRow(line);
            } catch (RuntimeException malformed) {
                // A malformed *final* line on an otherwise-valid log is a torn
                // append — a crash interrupted the write of the last row.
                // Recover the durable prefix instead of bricking the whole log.
                // A malformed line earlier in the file (or a log with no valid
                // entries at all) is genuine corruption and still fails loudly.
                boolean isTrailing =
                        lines.subList(i + 1, lines.size()).stream().allMatch(String::isBlank);
                if (isTrailing && !out.isEmpty()) {
                    LOG.warn(
                            "CommitLog {}: dropping a torn trailing line "
                                    + "(append interrupted by a crash) — recovered {} entr{}",
                            f,
                            out.size(),
                            out.size() == 1 ? "y" : "ies");
                    break;
                }
                throw malformed;
            }
            // A well-formed row whose commit chunk is absent is a store/log inconsistency, never a
            // torn append (the chunk is written before the row) — fail loudly, do not "recover".
            Entry e =
                    commits.readEntry(r.id(), r.timestamp())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "commit chunk missing for "
                                                            + HashUtils.toHex(r.id())
                                                            + " — the commit log references a commit"
                                                            + " whose chunk is absent from the"
                                                            + " store"));
            out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Memoized view of {@link #entries()} — invalidated by {@link #append}. A plain {@code
     * volatile} publish suffices under the class's documented single-writer assumption (see the
     * class Javadoc; cross-process safety is the future Manifest integration's job).
     */
    private volatile @Nullable CachedEntries cached;

    /**
     * Memoized {@link #entries()} with O(1) commit-id lookups (D-8 of plans/commits-pagination.md).
     * Repeated calls between appends return the <b>same instance</b> — the file is not re-read and
     * the chunks are not re-fetched. {@link #append} invalidates.
     *
     * @apiNote read-heavy callers (the {@code /sparql/commits} controller, cursor resolution)
     *     should prefer this over {@link #entries()}; one-shot walkers (sync pack builders) can
     *     keep {@code entries()}.
     */
    public CachedEntries cache() throws IOException {
        CachedEntries c = cached;
        if (c == null) {
            c = new CachedEntries(entries());
            cached = c;
        }
        return c;
    }

    /** Immutable snapshot of the log with hash-indexed lookups. Built by {@link #cache()}. */
    public static final class CachedEntries {
        private final List<Entry> entries;
        private final Map<String, Entry> byHash;
        private final Map<String, Integer> seqByHash;

        CachedEntries(List<Entry> entries) {
            this.entries = entries;
            Map<String, Entry> by = new HashMap<>();
            Map<String, Integer> seq = new HashMap<>();
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                by.put(e.hashHex(), e);
                seq.put(e.hashHex(), i);
            }
            this.byHash = by;
            this.seqByHash = seq;
        }

        /** All entries, chronological — the same contract as {@link CommitLog#entries()}. */
        public List<Entry> entries() {
            return entries;
        }

        /** The entry whose hex commit id is {@code hex}, or {@code null} when unknown. */
        public @Nullable Entry byHash(String hex) {
            return byHash.get(hex);
        }

        /** Chronological index (0 = oldest) of a hex commit id, or {@code -1} when unknown. */
        public int seqOf(String hex) {
            Integer s = seqByHash.get(hex);
            return s == null ? -1 : s;
        }

        public int size() {
            return entries.size();
        }
    }

    /** The most recently-appended entry, or empty if the log has no entries. */
    public Optional<Entry> latest() throws IOException {
        List<Entry> all = entries();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    /**
     * Locate an entry by its commit id (ADR-0071). Used by {@code ?commit=&lt;id&gt;} lookups and
     * by the sync / graph layer to resolve a commit handle to its entry (and from there to its tree
     * hash for reads).
     */
    public Optional<Entry> findById(byte[] id) throws IOException {
        if (id == null) return Optional.empty();
        for (Entry e : entries()) {
            if (Arrays.equals(id, e.id())) return Optional.of(e);
        }
        return Optional.empty();
    }

    /**
     * One commit-log row.
     *
     * <ul>
     *   <li>{@code id} — the commit id: a content hash over tree + parents + author + message
     *       ({@link CommitId}). The stable handle used by refs, the commit graph, and sync.
     *   <li>{@code metaTreeHash} — the RootMetaTree chunk hash (the <em>tree address</em>; used to
     *       open/read the commit's data, not as its identity).
     *   <li>{@code timestamp} — wall-clock when the commit was persisted (not part of the id).
     *   <li>{@code parents} — zero (genesis), one (ordinary), or two (merge) parent commit
     *       <b>ids</b>. Order matters: parents[0] is the branch that received the merge.
     * </ul>
     */
    public record Entry(
            Instant timestamp,
            byte[] id,
            byte[] metaTreeHash,
            List<byte[]> parents,
            String message,
            String author) {
        /**
         * Explicit canonical constructor. The {@code id} parameter is {@code @Nullable}: a null
         * means "compute it" from {@code metaTreeHash + parents + author + message} (the
         * local-commit / convenience-constructor path); a non-null id is preserved verbatim (sync
         * wholesale-adopt, ADR-0071). The stored {@code id} component is therefore <em>always</em>
         * non-null — so the generated {@link #id()} accessor carries the honest non-null contract,
         * with the constructor-input {@code @Nullable} confined to this one parameter.
         */
        public Entry(
                Instant timestamp,
                byte @Nullable [] id,
                byte[] metaTreeHash,
                List<byte[]> parents,
                String message,
                String author) {
            if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
            if (metaTreeHash == null)
                throw new IllegalArgumentException("metaTreeHash must not be null");
            List<byte[]> normParents =
                    parents == null ? Collections.emptyList() : List.copyOf(parents);
            String normMessage = message == null ? "" : message;
            String normAuthor = author == null ? "" : author;
            this.timestamp = timestamp;
            this.id =
                    id == null
                            ? CommitId.of(metaTreeHash, normParents, normAuthor, normMessage)
                            : id;
            this.metaTreeHash = metaTreeHash;
            this.parents = normParents;
            this.message = normMessage;
            this.author = normAuthor;
        }

        /** Genesis / single-id convenience (mostly tests). id is computed from the fields. */
        public Entry(Instant timestamp, byte[] metaTreeHash) {
            this(timestamp, null, metaTreeHash, Collections.emptyList(), "", "");
        }

        /** Three-arg convenience — message + author default to empty; id computed. */
        public Entry(Instant timestamp, byte[] metaTreeHash, List<byte[]> parents) {
            this(timestamp, null, metaTreeHash, parents, "", "");
        }

        /** Four-arg convenience — author defaults to empty; id computed. */
        public Entry(Instant timestamp, byte[] metaTreeHash, List<byte[]> parents, String message) {
            this(timestamp, null, metaTreeHash, parents, message, "");
        }

        /** Five-arg convenience — id computed from the other fields. */
        public Entry(
                Instant timestamp,
                byte[] metaTreeHash,
                List<byte[]> parents,
                String message,
                String author) {
            this(timestamp, null, metaTreeHash, parents, message, author);
        }

        public String rfc1123() {
            return RFC_1123.format(ZonedDateTime.ofInstant(timestamp, ZoneOffset.UTC));
        }

        /** The commit id, hex-encoded — the stable commit handle (ADR-0071). */
        public String hashHex() {
            return HashUtils.toHex(id);
        }

        /** The RootMetaTree (tree) hash, hex-encoded — the tree address for reads. */
        public String treeHashHex() {
            return HashUtils.toHex(metaTreeHash);
        }

        /** Hex strings for each parent commit id — convenient for JSON serialization. */
        public List<String> parentsHex() {
            List<String> out = new ArrayList<>(parents.size());
            for (byte[] p : parents) out.add(HashUtils.toHex(p));
            return Collections.unmodifiableList(out);
        }
    }

    /**
     * RFC 1123 datetime token count: {@code "Tue, 12 May 2026 23:14:48 GMT"} → 6 whitespace tokens.
     */
    private static final int DT_TOKEN_COUNT = 6;

    /**
     * A parsed thin commit-log row (ADR-0073): the wall-clock time + the commit id. The content
     * (tree / parents / author / message) is <b>not</b> in the row — {@link #entries} reconstructs
     * it from the commit chunk stored under {@code id}.
     */
    record ThinRow(Instant timestamp, byte[] id) {}

    /**
     * Parse one thin {@code "<RFC 1123 datetime> <hex commit id>"} row. Package-visible for the
     * line-format fuzz test.
     *
     * @throws IllegalStateException if the line is not exactly a datetime block + one hex id
     */
    static ThinRow parseThinRow(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length != DT_TOKEN_COUNT + 1) {
            throw new IllegalStateException("malformed commit-log line: " + line);
        }
        String dt = String.join(" ", Arrays.copyOfRange(tokens, 0, DT_TOKEN_COUNT));
        try {
            ZonedDateTime z = ZonedDateTime.parse(dt, RFC_1123);
            byte[] id = HashUtils.fromHex(tokens[DT_TOKEN_COUNT]);
            return new ThinRow(z.toInstant(), id);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("malformed commit-log line: " + line, ex);
        }
    }
}
