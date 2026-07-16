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
package com.earasoft.prolly.semantic;

import com.dolthub.prolly.*;
import com.earasoft.prolly.pool.*;
import com.earasoft.prolly.storage.*;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 *
 *
 * <h3>ProjectingIterator Test</h3>
 *
 * <p>Pins the iteration semantics of {@link com.earasoft.prolly.semantic.ProjectingIterator}: given
 * a multi-column tuple map keyed by {@code (s, p, o)}, the iterator emits the {@code o} field of
 * every tuple matching a fixed {@code (s, p)} prefix, in sorted order, and stops at the first
 * non-matching tuple.
 *
 * <p><b>The Gap:</b> {@code ProjectingIterator} had zero direct test references. It backs the
 * BGP/quad-store join engine; if the prefix-match stop condition or the field-projection ever
 * drifts, SPARQL-style joins would silently return wrong results.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>For prefix {@code (s="A", p="follows")} over the standard triple corpus, the iterator emits
 *       {@code o} values exactly equal to the ground-truth subset, in the same order as a {@link
 *       TreeMap} produces.
 *   <li>The iterator stops cleanly at the prefix boundary — calling {@code next()} after {@code
 *       done==true} returns {@code false} and does not throw.
 *   <li>{@code prev()} returns false (one-way iterator contract).
 *   <li>{@code value()} returns {@code MemorySegment.NULL} (projection is key-only).
 * </ol>
 */
public class ProjectingIteratorTest {
    public static void main(String[] args) throws Exception {
        System.out.println("--- ProjectingIterator Test ---");
        Path tempDir = Files.createTempDirectory("prolly-projecting-iter");

        try (DirectBufferPool pool = new DirectBufferPool();
                RocksNodeStore store = new RocksNodeStore(tempDir.toString())) {

            // 3-field tuple descriptor: (s, p, o) all strings.
            TupleDescriptor desc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false)));

            // Triples — including some that should match the prefix and some
            // that explicitly should NOT (different s, different p).
            String[][] triples =
                    new String[][] {
                        {"A", "follows", "Bob"},
                        {"A", "follows", "Carol"},
                        {"A", "follows", "Dan"},
                        {"A", "knows", "Bob"}, // different p — must not match
                        {"B", "follows", "Eve"}, // different s — must not match
                        {"A", "follows", "Eve"}, // matches; lex-after Dan
                    };

            TreeMap<String, String> sortedView = new TreeMap<>();
            for (String[] t : triples) {
                MemorySegment key = buildTriple(pool, t[0], t[1], t[2]);
                if (t[0].equals("A") && t[1].equals("follows")) {
                    sortedView.put(t[2], t[2]);
                }
                // Insert into the map by writing a tree.
            }

            // Build map.
            List<TreeMutator.Mutation> edits = new ArrayList<>();
            for (String[] t : triples) {
                MemorySegment k = buildTriple(pool, t[0], t[1], t[2]);
                edits.add(new TreeMutator.Mutation(k, MemorySegment.ofArray(new byte[0])));
            }
            // Sort by descriptor order before applyMutations.
            edits.sort((a, b) -> desc.compare(new Tuple(a.key()), new Tuple(b.key())));
            TreeMutator mutator = new TreeMutator(store, desc, pool);
            Node root = mutator.applyMutations(null, edits.iterator());
            StaticMap map = new StaticMap(store, root, desc);

            // Oracle 1: iterator emits the o-values for (A, follows, *) in sorted order.
            List<String> expectedOs = new ArrayList<>(sortedView.keySet());
            ProjectingIterator pi =
                    new ProjectingIterator(map, desc, pool, List.of("A", "follows"), 2);

            List<String> got = new ArrayList<>();
            while (pi.next()) {
                Tuple keyTup = new Tuple(pi.key());
                got.add(new String(keyTup.getField(0), StandardCharsets.UTF_8));
            }
            if (!got.equals(expectedOs)) {
                throw new RuntimeException(
                        "Projection mismatch: expected " + expectedOs + " got " + got);
            }
            System.out.println(
                    "Projection emits "
                            + got.size()
                            + " sorted o-values for (A, follows, *). (1/4)");

            // Oracle 2: post-exhaustion, next() returns false without throwing.
            if (pi.next()) throw new RuntimeException("Iterator should be exhausted");
            if (pi.next()) throw new RuntimeException("Repeated next() should remain exhausted");
            System.out.println("Iterator exhausts cleanly. (2/4)");

            // Oracle 3: prev() returns false (one-way iterator).
            ProjectingIterator pi2 =
                    new ProjectingIterator(map, desc, pool, List.of("A", "follows"), 2);
            if (pi2.prev()) throw new RuntimeException("prev() should return false");
            System.out.println("prev() returns false. (3/4)");

            // Oracle 4: value() returns NULL segment after a successful next().
            ProjectingIterator pi3 =
                    new ProjectingIterator(map, desc, pool, List.of("A", "follows"), 2);
            if (!pi3.next()) throw new RuntimeException("Iterator should have at least one match");
            MemorySegment v = pi3.value();
            if (v != MemorySegment.NULL) {
                throw new RuntimeException("value() should be MemorySegment.NULL — got " + v);
            }
            System.out.println("value() returns NULL (key-only projection). (4/4)");

            System.out.println("--- ProjectingIterator Test PASSED ---");
        }
    }

    private static MemorySegment buildTriple(DirectBufferPool pool, String s, String p, String o) {
        TupleBuilder tb = new TupleBuilder(pool);
        tb.putField(0, s.getBytes(StandardCharsets.UTF_8));
        tb.putField(1, p.getBytes(StandardCharsets.UTF_8));
        tb.putField(2, o.getBytes(StandardCharsets.UTF_8));
        return tb.build().segment();
    }
}
