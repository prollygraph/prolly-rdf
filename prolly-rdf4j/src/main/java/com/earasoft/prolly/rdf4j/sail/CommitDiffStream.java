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

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.DiffEngine;
import com.dolthub.prolly.Node;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.earasoft.prolly.rdf4j.index.QuadOrder;
import com.earasoft.prolly.rdf4j.index.SpocKey;
import com.earasoft.prolly.rdf4j.term.Dictionary;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.TermId;
import com.earasoft.prolly.rdf4j.value.DictionaryTermResolver;
import com.earasoft.prolly.rdf4j.value.TermResolver;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.Objects;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.jspecify.annotations.Nullable;

/**
 * Streams the INSERT/DELETE triple events between a commit and its parent by diffing the two SPOC
 * index trees directly — bounded heap, and only the changed triples decoded.
 *
 * <p>This is the engine primitive behind {@code GET /sparql/provenance?commit=} and {@code
 * /sparql/diff}. The pre-existing path buffered <em>both</em> full commit snapshots into heap maps
 * and decoded every triple of both to key them: O(2 × snapshot) heap and O(snapshot) decode even
 * for a one-triple commit. This primitive instead walks {@link DiffEngine#diffIterator} over the
 * two commits' SPOC roots, materializing and decoding only the keys that differ.
 *
 * <p><b>Cost, measured precisely (one-triple commit on a 20k-triple base — {@code
 * CommitDiffStreamBoundedWorkTest}).</b> <b>Heap</b> is O(tree height + changes): the two leaf
 * cursors plus the changed events — ~40,000× fewer materialized objects than buffering both
 * snapshots (1 vs ~40,001). This is the out-of-memory fix the plan split from {@code oom-hardening}
 * to deliver. <b>Decode</b> drops to O(changes): only the differing triples resolve through the
 * dictionary, vs the old path's per-triple decode of both snapshots — ~308× fewer node reads. The
 * <b>residual</b>: {@link DiffEngine} still <em>walks</em> both SPOC trees (it has a whole-tree and
 * a per-leaf-byte short-circuit but <b>no internal-subtree skip</b>), so the tree-walk itself is
 * O(snapshot SPOC nodes) = O(n / fanout) — small under prolly's high fanout, but not strictly
 * O(changes). An internal-node Merkle skip in {@code DiffEngine} would make the walk O(log n +
 * changes); that is a separate prolly-port-core enhancement.
 *
 * @apiNote Single call: {@link #stream(byte[], byte[], Handler)} pushes one {@link Event} per
 *     changed triple, in SPOC key order, to the handler. The caller supplies the first-parent hash
 *     (or {@code null} for a genesis commit, which diffs against the empty tree → all INSERTs) —
 *     the primitive does <b>not</b> walk the commit graph, so it composes with whatever
 *     parent-selection policy the caller already applies (matching {@code
 *     SparqlController.provenanceByCommit}'s first-parent + genesis semantics). The handler runs
 *     inline on the diff walk; it must not retain the {@link Event} across calls beyond its own
 *     bookkeeping. Throws {@code org.eclipse.rdf4j.sail.SailException} if either commit's root
 *     cannot be restored from the store. Not thread-safe; construct one per request.
 * @implNote <b>Why two dictionaries.</b> An INSERT's terms are resolved against the <em>here</em>
 *     commit's dictionary; a DELETE's against the <em>parent</em>'s. A deleted triple's terms may
 *     be absent from the newer dictionary (nothing else references them), so resolving a DELETE
 *     against {@code here} could fail — the buffer-and-diff path it replaces implicitly used each
 *     snapshot's own dictionary, and this mirrors that exactly. (The plan's D-1 phrasing "the
 *     dictionary at the newer commit" was imprecise for DELETEs; corrected here.)
 *     <p><b>Diff direction.</b> {@code diffIterator(parentRoot, hereRoot)} yields {@code ADD} for a
 *     key present in {@code here} but not {@code parent} (→ INSERT) and {@code DEL} for the reverse
 *     (→ DELETE), per {@link DiffEngine}'s {@code (rootA=before, rootB=after)} convention (the same
 *     one {@code MergeEngine.spocDelta} relies on). {@code MOD} cannot occur — a SPOC value is the
 *     empty segment, so equal keys are always equal entries — but is treated as a no-op (no
 *     membership change) for safety.
 *     <p><b>Collaborators:</b> {@link ProllySail#openSnapshotAt(byte[])} (restore each commit's
 *     roots — a pointer rehydrate, not a scan), {@link DiffEngine} (the streaming tree diff over
 *     {@link SpocKey#DESCRIPTOR}), {@link SpocKey#fromTuple} (decode a diff key to four {@link
 *     TermId}s), and {@link DictionaryTermResolver} (the hardened {@code TermId → Value} decode,
 *     reused rather than re-implemented). {@code col3() == }{@link TermId#ZERO} is the
 *     default-graph sentinel → a context-less {@link Statement}. <b>Dependents:</b> {@code
 *     SparqlController.provenanceByCommit} / {@code /sparql/diff} (Phase 1 of {@code
 *     plans/streaming-commit-diff.md}); pinned behaviour-identical to the buffer-and-diff path by
 *     {@code CommitDiffStreamDifferentialProperty}.
 */
public final class CommitDiffStream {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** Which side of the diff a changed triple fell on. */
    public enum Kind {
        INSERT,
        DELETE
    }

    /**
     * One changed triple: whether it was inserted or deleted in {@code here} relative to parent.
     */
    public record Event(Kind kind, Statement statement) {}

    /** Pull-free sink — invoked once per changed triple, in SPOC key order, on the diff walk. */
    @FunctionalInterface
    public interface Handler {
        void onEvent(Event event);
    }

    private final ProllySail live;

    public CommitDiffStream(ProllySail live) {
        this.live = live;
    }

    /**
     * Stream the triple-level diff of {@code hereCommit} against {@code parentCommit}.
     *
     * @param hereCommit the commit whose changes are reported (its root must restore)
     * @param parentCommit the first parent to diff against, or {@code null} for a genesis commit
     *     (diff against the empty tree → every triple is an INSERT)
     * @param handler receives one {@link Event} per changed triple
     */
    public void stream(byte[] hereCommit, byte @Nullable [] parentCommit, Handler handler) {
        NodeStore store = live.store();
        BufferPool pool = live.pool();

        ProllySail hereSnap =
                ProllySail.openSnapshotAt(
                        store, pool, new CompositeMeterRegistry(), live.treeHashOf(hereCommit));
        Node hereRoot = rootOf(hereSnap.indexRoot(QuadOrder.SPOC));
        TermResolver hereResolver = resolverFor(hereSnap, store, pool);

        Node parentRoot = null;
        TermResolver parentResolver = null;
        if (parentCommit != null) {
            ProllySail parentSnap =
                    ProllySail.openSnapshotAt(
                            store,
                            pool,
                            new CompositeMeterRegistry(),
                            live.treeHashOf(parentCommit));
            parentRoot = rootOf(parentSnap.indexRoot(QuadOrder.SPOC));
            parentResolver = resolverFor(parentSnap, store, pool);
        }

        Iterator<DiffEngine.DiffEntry> it =
                new DiffEngine(store, SpocKey.DESCRIPTOR).diffIterator(parentRoot, hereRoot);
        while (it.hasNext()) {
            DiffEngine.DiffEntry e = it.next();
            switch (e.type()) {
                // ADD ⇒ `here` is non-empty ⇒ its dict (hence resolver) is non-null;
                // DEL ⇒ `parent` is non-empty ⇒ its resolver is non-null (resolverFor returns
                // null only for an empty commit, which produces no diff entry on that side).
                case ADD ->
                        handler.onEvent(
                                new Event(
                                        Kind.INSERT,
                                        decode(e.key(), Objects.requireNonNull(hereResolver))));
                case DEL ->
                        handler.onEvent(
                                new Event(
                                        Kind.DELETE,
                                        decode(e.key(), Objects.requireNonNull(parentResolver))));
                case MOD -> {
                    // Unreachable: SPOC values are the empty segment, so an equal key is an equal
                    // entry. Treated as no membership change for safety.
                }
            }
        }
    }

    private static @Nullable Node rootOf(@Nullable StaticMap map) {
        return map == null ? null : map.root();
    }

    private static @Nullable TermResolver resolverFor(
            ProllySail snap, NodeStore store, BufferPool pool) {
        StaticMap dictRoot = snap.dictRoot();
        // A null dict root means an empty commit (no terms). That side then has an empty SPOC tree
        // too, so the diff produces no entry to decode against it (an ADD implies `here` is
        // non-empty; a DEL implies `parent` is) — a TermId in SPOC is content-addressed and always
        // present in its own commit's dict. Return null rather than NPE on `new Dictionary(…,
        // null)`; the null is provably never dereferenced.
        if (dictRoot == null) {
            return null;
        }
        Dictionary dict = new Dictionary(store, pool, HashFunctions.defaultHash(), dictRoot);
        return new DictionaryTermResolver(dict, snap.prefixes());
    }

    /** Decode a SPOC diff key to a {@link Statement}, resolving terms via {@code resolver}. */
    private static Statement decode(MemorySegment keySegment, TermResolver resolver) {
        SpocKey key = SpocKey.fromTuple(new Tuple(keySegment));
        Resource subject = (Resource) resolver.resolve(key.col0());
        IRI predicate = (IRI) resolver.resolve(key.col1());
        Value object = resolver.resolve(key.col2());
        TermId context = key.col3();
        if (context.equals(TermId.ZERO)) {
            return VF.createStatement(subject, predicate, object);
        }
        return VF.createStatement(subject, predicate, object, (Resource) resolver.resolve(context));
    }
}
