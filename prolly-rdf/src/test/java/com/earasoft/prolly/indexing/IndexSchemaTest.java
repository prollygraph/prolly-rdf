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

import com.dolthub.prolly.*;
import com.earasoft.prolly.pool.*;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 *
 *
 * <h3>IndexSchema Test</h3>
 *
 * <p>Pins the projection contract of {@link
 * com.earasoft.prolly.indexing.IndexSchema#buildIndexKey}: given a primary row and a {@code
 * fieldMapping}, the produced index tuple has fields in the order specified by {@code
 * fieldMapping}, with each field's bytes copied from the matching primary-row field.
 *
 * <p><b>The Gap:</b> {@code IndexSchema} is the contract every secondary index uses. A regression
 * in field projection silently corrupts every index entry. Until now: zero direct test references.
 *
 * <p><b>Oracles:</b>
 *
 * <ol>
 *   <li>Identity mapping ({@code [0,1,2]}) yields the primary row's first three fields in order.
 *   <li>Reordering mapping ({@code [2,0]}) yields a 2-field index tuple containing primary[2] then
 *       primary[0].
 *   <li>{@code getName()} and {@code getDescriptor()} round-trip the constructor arguments.
 * </ol>
 */
public class IndexSchemaTest {
    public static void main(String[] args) {
        System.out.println("--- IndexSchema Test ---");

        try (DirectBufferPool pool = new DirectBufferPool()) {
            // Primary row schema: 4 string fields (a, b, c, d).
            // Index 1: identity-ish — map fields 0, 1, 2 verbatim.
            TupleDescriptor primaryDesc =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false)));
            TupleDescriptor idxDesc3 =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false)));

            IndexSchema idIdx = new IndexSchema("identity_3", idxDesc3, new int[] {0, 1, 2});

            // Build primary row (a, b, c, d).
            TupleBuilder primaryB = new TupleBuilder(pool);
            primaryB.putField(0, "alpha".getBytes(StandardCharsets.UTF_8));
            primaryB.putField(1, "bravo".getBytes(StandardCharsets.UTF_8));
            primaryB.putField(2, "charlie".getBytes(StandardCharsets.UTF_8));
            primaryB.putField(3, "delta".getBytes(StandardCharsets.UTF_8));
            Tuple primary = primaryB.build();

            // Oracle 1: identity mapping returns (alpha, bravo, charlie).
            MemorySegment idxKey = idIdx.buildIndexKey(primary, pool);
            Tuple idxTup = new Tuple(idxKey);
            if (!"alpha".equals(str(idxTup.getField(0)))) {
                throw new RuntimeException("idxTup.field0 = " + str(idxTup.getField(0)));
            }
            if (!"bravo".equals(str(idxTup.getField(1)))) {
                throw new RuntimeException("idxTup.field1 = " + str(idxTup.getField(1)));
            }
            if (!"charlie".equals(str(idxTup.getField(2)))) {
                throw new RuntimeException("idxTup.field2 = " + str(idxTup.getField(2)));
            }
            System.out.println("Identity mapping copies first three fields. (1/3)");

            // Oracle 2: reorder mapping [2, 0] yields (charlie, alpha).
            TupleDescriptor idxDesc2 =
                    new TupleDescriptor(
                            List.of(
                                    new Type(Encoding.String, false),
                                    new Type(Encoding.String, false)));
            IndexSchema reorderIdx = new IndexSchema("co_first", idxDesc2, new int[] {2, 0});
            MemorySegment reordered = reorderIdx.buildIndexKey(primary, pool);
            Tuple reorderedTup = new Tuple(reordered);
            if (!"charlie".equals(str(reorderedTup.getField(0)))) {
                throw new RuntimeException("reordered.field0 = " + str(reorderedTup.getField(0)));
            }
            if (!"alpha".equals(str(reorderedTup.getField(1)))) {
                throw new RuntimeException("reordered.field1 = " + str(reorderedTup.getField(1)));
            }
            System.out.println("Reorder mapping projects fields in declared order. (2/3)");

            // Oracle 3: name + descriptor accessors round-trip.
            if (!"identity_3".equals(idIdx.getName())) {
                throw new RuntimeException("getName = " + idIdx.getName());
            }
            if (idIdx.getDescriptor() != idxDesc3) {
                throw new RuntimeException("getDescriptor returned wrong instance");
            }
            System.out.println("getName and getDescriptor round-trip. (3/3)");

            System.out.println("--- IndexSchema Test PASSED ---");
        }
    }

    private static String str(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
