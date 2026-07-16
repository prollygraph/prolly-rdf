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
package com.earasoft.prolly.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dolthub.prolly.Encoding;
import com.dolthub.prolly.MapIterator;
import com.dolthub.prolly.StaticMap;
import com.dolthub.prolly.Tuple;
import com.dolthub.prolly.TupleBuilder;
import com.dolthub.prolly.TupleDescriptor;
import com.dolthub.prolly.Type;
import com.earasoft.prolly.pool.DirectBufferPool;
import com.earasoft.prolly.storage.RocksNodeStore;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;

/**
 * Phase 2 Step 11 of prolly-rdf-test-strategy — multi-index atomicity (R-6). A random put/delete
 * sequence is applied to a {@link Table} with TWO secondary indexes; after flush, each secondary
 * index must equal the index recomputed from scratch off the final primary (oracle = project every
 * live row). This pins both directions of "lock-step": no <i>missing</i> key for a live row and no
 * <i>stale</i> key left behind by an overwritten/deleted row.
 *
 * <p>Rows carry their own pk as a field (`tag`), and both indexes project the tag, so index keys
 * are injective over live rows — the realistic RDF case (SPOC/POSC keys are the full permutation,
 * never colliding). Reference-counting of <i>colliding</i> projected keys is a separate concern,
 * noted in the plan wrap-up; it does not arise for permutation indexes.
 */
class MultiIndexAtomicityProperty {

    private static final TupleDescriptor PK_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final TupleDescriptor ROW_DESC =
            new TupleDescriptor(
                    List.of(
                            new Type(Encoding.String, false), // field 0: payload
                            new Type(Encoding.String, false))); // field 1: tag (== pk)
    private static final TupleDescriptor TAG_IDX_DESC =
            new TupleDescriptor(List.of(new Type(Encoding.String, false)));
    private static final TupleDescriptor PAYLOAD_TAG_IDX_DESC =
            new TupleDescriptor(
                    List.of(new Type(Encoding.String, false), new Type(Encoding.String, false)));
    private static final IndexSchema BY_TAG =
            new IndexSchema("by_tag", TAG_IDX_DESC, new int[] {1});
    private static final IndexSchema BY_PAYLOAD_TAG =
            new IndexSchema("by_payload_tag", PAYLOAD_TAG_IDX_DESC, new int[] {0, 1});

    record Op(boolean delete, String pk, String payload) {}

    private final List<Path> tempDirs = new ArrayList<>();

    @Provide
    Arbitrary<List<Op>> opSequences() {
        Arbitrary<String> pk = Arbitraries.of("p0", "p1", "p2", "p3", "p4", "p5");
        // Non-empty payloads: an empty field round-trips as a null Tuple field
        // (the tuple empty-vs-null-field ambiguity, a TupleCodec concern that is
        // orthogonal to index atomicity and out of scope for this step).
        Arbitrary<String> payload = Arbitraries.strings().ofMinLength(1).ofMaxLength(8);
        Arbitrary<Op> op =
                Combinators.combine(Arbitraries.of(true, false), pk, payload).as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(40);
    }

    @AfterTry
    void cleanup() {
        for (Path dir : tempDirs) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            } catch (IOException ignored) {
            }
        }
        tempDirs.clear();
    }

    @Property(tries = 50)
    void secondaryIndexesStayInLockStepWithPrimary(@ForAll @From("opSequences") List<Op> ops)
            throws Exception {
        Path dir = Files.createTempDirectory("rdf-tbl-");
        tempDirs.add(dir);
        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(dir.toString())) {
            Map<IndexSchema, StaticMap> emptySecondaries = new LinkedHashMap<>();
            emptySecondaries.put(BY_TAG, new StaticMap(store, null, TAG_IDX_DESC));
            emptySecondaries.put(BY_PAYLOAD_TAG, new StaticMap(store, null, PAYLOAD_TAG_IDX_DESC));
            Table table =
                    new Table(
                            store,
                            pool,
                            new StaticMap(store, null, PK_DESC),
                            PK_DESC,
                            ROW_DESC,
                            emptySecondaries);

            // Oracle: the live primary as pk -> payload (tag is always pk).
            Map<String, String> live = new LinkedHashMap<>();
            for (Op op : ops) {
                MemorySegment pk = key1(pool, op.pk());
                if (op.delete()) {
                    table.delete(pk);
                    live.remove(op.pk());
                } else {
                    table.put(pk, row(pool, op.payload(), op.pk()));
                    live.put(op.pk(), op.payload());
                }
            }
            Table.TableState state = table.flush();

            // Primary lock-step: scanned primary == live oracle.
            Map<String, String> primary = new LinkedHashMap<>();
            MapIterator it = state.primary().iter();
            while (it.next()) {
                Tuple row = new Tuple(it.value());
                primary.put(
                        new String(new Tuple(it.key()).getField(0)), new String(row.getField(0)));
            }
            assertEquals(live, primary, "primary must equal the live row set");

            // Secondary lock-step: each index's key set == keys recomputed from
            // the live rows (no missing, no stale).
            assertIndexMatches(state, BY_TAG, live, pool);
            assertIndexMatches(state, BY_PAYLOAD_TAG, live, pool);
        }
    }

    private void assertIndexMatches(
            Table.TableState state,
            IndexSchema schema,
            Map<String, String> live,
            DirectBufferPool pool) {
        Set<String> actual = new HashSet<>();
        MapIterator it = state.secondaries().get(schema).iter();
        while (it.next()) actual.add(hex(it.key().toArray(ValueLayout.JAVA_BYTE)));

        Set<String> oracle = new HashSet<>();
        live.forEach(
                (pk, payload) -> {
                    Tuple rowTuple = new Tuple(row(pool, payload, pk));
                    oracle.add(
                            hex(
                                    schema.buildIndexKey(rowTuple, pool)
                                            .toArray(ValueLayout.JAVA_BYTE)));
                });
        assertEquals(
                oracle,
                actual,
                "index '"
                        + schema.getName()
                        + "' must equal the index recomputed from the live primary");
    }

    private static MemorySegment key1(DirectBufferPool pool, String s) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes());
        return tb.build().segment();
    }

    private static MemorySegment row(DirectBufferPool pool, String payload, String tag) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, payload.getBytes());
        tb.putField(1, tag.getBytes());
        return tb.build().segment();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
