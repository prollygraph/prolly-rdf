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
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.MutableMap;
import com.dolthub.prolly.NodeStore;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistent {@code prefix → namespace} map for SPARQL query-text prefix declarations.
 * <b>Distinct</b> from {@link PrefixTable}, which compresses IRI bytes internally with a numeric
 * id. This map stores user-set prefixes exposed via {@code SailConnection.setNamespace}.
 *
 * <p>Schema: 1-column {@link Encoding#String} key (the prefix label, UTF-8), raw-bytes value (the
 * namespace URI, UTF-8).
 *
 * <p>Not thread-safe.
 */
public final class SparqlNamespaces {

    private final NodeStore store;
    private final BufferPool pool;
    private final TupleDescriptor keySchema;
    private MutableMap buffer;

    /** In-memory mirror of committed + buffered state. Authoritative for reads. */
    private final Map<String, String> cache = new LinkedHashMap<>();

    public SparqlNamespaces(NodeStore store, BufferPool pool) {
        this.store = store;
        this.pool = pool;
        this.keySchema = new TupleDescriptor(List.of(new Type(Encoding.String, false)));
        StaticMap empty = new StaticMap(store, null, keySchema);
        this.buffer = new MutableMap(empty, store, keySchema, pool);
    }

    public SparqlNamespaces(NodeStore store, BufferPool pool, StaticMap committed) {
        this.store = store;
        this.pool = pool;
        this.keySchema = committed.descriptor();
        this.buffer = new MutableMap(committed, store, keySchema, pool);
        rebuildCache();
    }

    public void set(String prefix, String namespace) {
        byte[] nsBytes = namespace.getBytes(StandardCharsets.UTF_8);
        buffer.put(buildKeyTuple(prefix), MemorySegment.ofArray(nsBytes));
        cache.put(prefix, namespace);
    }

    public void remove(String prefix) {
        buffer.delete(buildKeyTuple(prefix));
        cache.remove(prefix);
    }

    public Optional<String> get(String prefix) {
        return Optional.ofNullable(cache.get(prefix));
    }

    public void clear() {
        // Snapshot the heap cache first (mutating during iteration would fail).
        for (String prefix : cache.keySet().toArray(new String[0])) {
            buffer.delete(buildKeyTuple(prefix));
        }
        cache.clear();
    }

    /** A snapshot {@code Map<prefix, namespace>} of the current committed + buffered state. */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(cache);
    }

    private void rebuildCache() {
        cache.clear();
        MapIterator it = buffer.base().iter();
        while (it.next()) {
            String prefix = decodePrefixKey(it.key());
            String ns = new String(it.value().toArray(Layouts.BYTE), StandardCharsets.UTF_8);
            cache.put(prefix, ns);
        }
    }

    public StaticMap commit() {
        StaticMap next = buffer.flush();
        this.buffer = new MutableMap(next, store, keySchema, pool);
        return next;
    }

    private MemorySegment buildKeyTuple(String prefix) {
        TupleBuilder tb = new TupleBuilder(pool, keySchema);
        byte[] bytes = prefix.getBytes(StandardCharsets.UTF_8);
        tb.putField(0, bytes);
        Tuple t = tb.build();
        return t.segment();
    }

    private static String decodePrefixKey(MemorySegment keySeg) {
        Tuple t = new Tuple(keySeg);
        byte[] bytes = t.getField(0);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
