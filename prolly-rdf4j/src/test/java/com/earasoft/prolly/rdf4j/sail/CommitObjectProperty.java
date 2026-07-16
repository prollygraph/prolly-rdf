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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Property net for {@link CommitObject} (ADR-0073 Phase 0). Over generated commits it pins the
 * serialization round-trip, that the object's id is a consistent pure function of its fields, and —
 * the trust-boundary robustness property — that {@link CommitObject#deserialize} on an arbitrarily
 * mangled byte stream either parses to some object or throws {@link IllegalArgumentException}, but
 * <b>never</b> any other {@link Throwable} (no array-index over-read, no out-of-memory from a
 * hostile length). The byte-exact stability of the id itself is pinned by the (unchanged) {@code
 * CommitIdTest} — {@link CommitId#of} now routes through this type, so those tests transitively
 * guard the format here.
 */
class CommitObjectProperty {

    @Provide
    Arbitrary<byte[]> hash20() {
        return Arbitraries.bytes().array(byte[].class).ofSize(20);
    }

    @Provide
    Arbitrary<CommitObject> commits() {
        Arbitrary<byte[]> mth = hash20();
        Arbitrary<List<byte[]>> parents = hash20().list().ofMaxSize(3);
        Arbitrary<String> author = Arbitraries.strings().ofMaxLength(16);
        Arbitrary<String> message = Arbitraries.strings().ofMaxLength(40);
        return Combinators.combine(mth, parents, author, message).as(CommitObject::of);
    }

    @Property(tries = 200)
    void round_trips(@ForAll("commits") CommitObject c) {
        assertEquals(c, CommitObject.deserialize(c.serialize()), "deserialize(serialize(c)) == c");
        assertArrayEquals(c.id(), CommitObject.deserialize(c.serialize()).id(), "id survives");
    }

    @Property(tries = 200)
    void id_is_a_consistent_function_of_the_fields(@ForAll("commits") CommitObject c) {
        // Rebuilding from the fields yields the same id, and CommitId.of agrees (it routes here).
        CommitObject rebuilt =
                CommitObject.of(c.metaTreeHash(), c.parents(), c.author(), c.message());
        assertArrayEquals(c.id(), rebuilt.id());
        assertArrayEquals(
                CommitId.of(c.metaTreeHash(), c.parents(), c.author(), c.message()), c.id());
    }

    @Property(tries = 200)
    void serialize_is_deterministic(@ForAll("commits") CommitObject c) {
        assertArrayEquals(c.serialize(), c.serialize());
    }

    @Property(tries = 400)
    void deserialize_never_crashes_on_a_mangled_byte(
            @ForAll @From("commits") CommitObject c,
            @ForAll @IntRange(min = 0, max = 1_000_000) int posSeed,
            @ForAll byte mask) {
        byte[] b = c.serialize();
        b[Math.floorMod(posSeed, b.length)] ^= mask;
        try {
            CommitObject.deserialize(b); // may succeed (a different well-formed object) — fine
        } catch (IllegalArgumentException expected) {
            // the ONLY permitted failure mode: a clean, bounded rejection.
            // Any other Throwable (index over-read, out-of-memory, …) propagates and fails.
        }
    }
}
