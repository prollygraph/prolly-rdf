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
package com.earasoft.prolly.rdf4j.term;

import com.dolthub.prolly.BufferPool;
import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Bidirectional map from {@code prefix-id : int} to {@code namespace : byte[]}.
 *
 * <p>Used by IRI encoding ({@link TermCodec#encodeShortPrefixIri}) so common namespace prefixes can
 * be stored as 4-byte ids instead of long UTF-8 strings.
 *
 * <p>Forward map (id → namespace) is persisted in a Prolly tree. Reverse map (namespace → id) is a
 * heap cache built at open time and updated on register.
 *
 * <h2>Address spaces</h2>
 *
 * <ul>
 *   <li>IDs {@code 1..15} are the <b>normative bootstrap entries</b> (well-known W3C prefixes — see
 *       SPEC §0.5). Registered automatically on construction against a fresh store.
 *   <li>IDs {@code 16..1023} are reserved for future bootstrap entries.
 *   <li>IDs {@code ≥ 1024} are for runtime promotion of caller-supplied namespaces.
 *   <li>ID {@code 0} is reserved as a sentinel for "no prefix".
 * </ul>
 *
 * <p>Bootstrap-id assignments are part of the on-disk format. Changing them requires a manifest
 * format-version bump.
 *
 * <p>Not thread-safe.
 *
 * @apiNote IRI encoding interns a namespace to a small integer id so a repeated namespace costs 4
 *     bytes on disk instead of its full UTF-8 length. Ids {@code 1..15} are the well-known W3C
 *     namespaces, fixed by the on-disk format; ids {@code ≥ 1024} are runtime-promoted. {@code
 *     register} is the allocation point; lookups go both directions — id to namespace from the
 *     persisted tree, namespace to id from the heap cache.
 * @implNote <b>Collaborators:</b> {@link NodeStore} + {@link BufferPool} (persist the forward
 *     id-to-namespace tree), {@link MutableMap} / {@link StaticMap} (buffer then commit), {@link
 *     TupleBuilder} (key/value encoding), and the heap reverse-map cache built at open.
 *     <b>Dependents:</b> {@link TermCodec#encodeShortPrefixIri} (the encoder that consumes prefix
 *     ids) and {@code ProllySail} (holds the Sail-level prefix table).
 */
public final class PrefixTable {

    public static final int ID_RDF = 1;
    public static final int ID_RDFS = 2;
    public static final int ID_OWL = 3;
    public static final int ID_XSD = 4;
    public static final int ID_FOAF = 5;
    public static final int ID_DC = 6;
    public static final int ID_DCTERMS = 7;
    public static final int ID_SCHEMA = 8;
    public static final int ID_SKOS = 9;
    public static final int ID_PROV = 10;
    public static final int ID_SH = 11;
    public static final int ID_DCAT = 12;
    public static final int ID_VOID = 13;
    public static final int ID_GEO = 14;
    public static final int ID_TIME = 15;

    /** First ID available for runtime-promoted prefixes. */
    public static final int RUNTIME_ID_START = 1024;

    /** The normative bootstrap entries, in (id, namespace) form. */
    public static final List<Map.Entry<Integer, String>> BOOTSTRAP =
            List.of(
                    Map.entry(ID_RDF, "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
                    Map.entry(ID_RDFS, "http://www.w3.org/2000/01/rdf-schema#"),
                    Map.entry(ID_OWL, "http://www.w3.org/2002/07/owl#"),
                    Map.entry(ID_XSD, "http://www.w3.org/2001/XMLSchema#"),
                    Map.entry(ID_FOAF, "http://xmlns.com/foaf/0.1/"),
                    Map.entry(ID_DC, "http://purl.org/dc/elements/1.1/"),
                    Map.entry(ID_DCTERMS, "http://purl.org/dc/terms/"),
                    Map.entry(ID_SCHEMA, "https://schema.org/"),
                    Map.entry(ID_SKOS, "http://www.w3.org/2004/02/skos/core#"),
                    Map.entry(ID_PROV, "http://www.w3.org/ns/prov#"),
                    Map.entry(ID_SH, "http://www.w3.org/ns/shacl#"),
                    Map.entry(ID_DCAT, "http://www.w3.org/ns/dcat#"),
                    Map.entry(ID_VOID, "http://rdfs.org/ns/void#"),
                    Map.entry(ID_GEO, "http://www.opengis.net/ont/geosparql#"),
                    Map.entry(ID_TIME, "http://www.w3.org/2006/time#"));

    private final NodeStore store;
    private final BufferPool pool;
    private final TupleDescriptor keySchema;
    private MutableMap buffer;

    /** Reverse cache: UTF-8 namespace → id. Strings used as keys (byte-by-byte equal via UTF-8). */
    private final Map<String, Integer> reverse = new HashMap<>();

    private int nextRuntimeId;

    public PrefixTable(NodeStore store, BufferPool pool) {
        this.store = store;
        this.pool = pool;
        this.keySchema = new TupleDescriptor(List.of(new Type(Encoding.Int32, false)));
        StaticMap emptyBase = new StaticMap(store, null, keySchema);
        this.buffer = new MutableMap(emptyBase, store, keySchema, pool);
        this.nextRuntimeId = RUNTIME_ID_START;
        // Auto-register the bootstrap entries on a fresh store.
        registerBootstrap();
    }

    /** Re-open against an existing committed root. Bootstraps are NOT re-registered. */
    public PrefixTable(NodeStore store, BufferPool pool, StaticMap committed) {
        this.store = store;
        this.pool = pool;
        this.keySchema = committed.descriptor();
        this.buffer = new MutableMap(committed, store, keySchema, pool);
        this.nextRuntimeId = computeNextRuntimeId();
        rebuildReverseCache();
    }

    private void registerBootstrap() {
        for (Map.Entry<Integer, String> e : BOOTSTRAP) {
            int id = e.getKey();
            String ns = e.getValue();
            byte[] nsBytes = ns.getBytes(StandardCharsets.UTF_8);
            buffer.put(buildKeyTuple(id), MemorySegment.ofArray(nsBytes));
            reverse.put(ns, id);
        }
    }

    /** Look up a namespace by id. */
    public Optional<byte[]> lookupNamespace(int prefixId) {
        Optional<MemorySegment> v = buffer.get(buildKeyTuple(prefixId));
        return v.map(seg -> seg.toArray(Layouts.BYTE));
    }

    /** Convenience: namespace as String. */
    public Optional<String> lookupNamespaceAsString(int prefixId) {
        return lookupNamespace(prefixId).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    /** Reverse lookup. */
    public OptionalInt lookupId(byte[] namespace) {
        Integer id = reverse.get(new String(namespace, StandardCharsets.UTF_8));
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    public OptionalInt lookupId(String namespace) {
        Integer id = reverse.get(namespace);
        return id == null ? OptionalInt.empty() : OptionalInt.of(id);
    }

    /**
     * Register a namespace, returning its id. Idempotent: re-registering an existing namespace
     * returns the existing id.
     */
    public int register(String namespace) {
        Integer existing = reverse.get(namespace);
        if (existing != null) return existing;
        int id = nextRuntimeId++;
        byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);
        buffer.put(buildKeyTuple(id), MemorySegment.ofArray(nsBytes));
        reverse.put(namespace, id);
        return id;
    }

    /** Flush buffered registrations to a new committed root and reset the buffer. */
    public StaticMap commit() {
        StaticMap next = buffer.flush();
        this.buffer = new MutableMap(next, store, keySchema, pool);
        return next;
    }

    /** Number of registered prefixes (bootstrap + runtime). */
    public int size() {
        return reverse.size();
    }

    /** Highest runtime id allocated so far, or {@link #RUNTIME_ID_START} - 1 if none. */
    public int highestRuntimeId() {
        return nextRuntimeId - 1;
    }

    private MemorySegment buildKeyTuple(int prefixId) {
        TupleBuilder tb = new TupleBuilder(pool, keySchema);
        tb.putField(0, intToBytesLE(prefixId)); // Int32, 4 raw little-endian bytes
        Tuple t = tb.build();
        return t.segment();
    }

    private static byte[] intToBytesLE(int v) {
        // Little-endian — matches Encoding.Int32 read semantics
        // (TypeCodec.readInt32 reads LE), so the Int32-keyed tree compares
        // prefix ids numerically. (TupleBuilder.putInt64 has special Int64
        // handling; for an Int32 key we just write the 4 raw bytes here.)
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    /** Scan tree for max id; set nextRuntimeId to max+1 (or RUNTIME_ID_START). */
    private int computeNextRuntimeId() {
        int max = RUNTIME_ID_START - 1;
        com.dolthub.prolly.MapIterator it = buffer.base().iter();
        while (it.next()) {
            byte[] keyBytes = new Tuple(it.key()).getField(0);
            if (keyBytes != null && keyBytes.length == 4) {
                int id = decodeIntLE(keyBytes);
                if (id > max) max = id;
            }
        }
        return max + 1;
    }

    private void rebuildReverseCache() {
        reverse.clear();
        com.dolthub.prolly.MapIterator it = buffer.base().iter();
        while (it.next()) {
            byte[] keyBytes = new Tuple(it.key()).getField(0);
            byte[] nsBytes = it.value().toArray(Layouts.BYTE);
            if (keyBytes != null && keyBytes.length == 4) {
                int id = decodeIntLE(keyBytes);
                reverse.put(new String(nsBytes, StandardCharsets.UTF_8), id);
            }
        }
    }

    private static int decodeIntLE(byte[] keyBytes) {
        return (keyBytes[0] & 0xFF)
                | ((keyBytes[1] & 0xFF) << 8)
                | ((keyBytes[2] & 0xFF) << 16)
                | ((keyBytes[3] & 0xFF) << 24);
    }
}
