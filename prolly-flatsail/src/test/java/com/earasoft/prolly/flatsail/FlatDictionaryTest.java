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
package com.earasoft.prolly.flatsail;

import static org.junit.jupiter.api.Assertions.*;

import com.earasoft.prolly.rdf4j.term.TermId;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rocksdb.RocksDB;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;

/** Coverage for {@link FlatDictionary} — the RDF Value ↔ TermId dictionary. */
class FlatDictionaryTest {
    static {
        RocksDB.loadLibrary();
    }

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /** Intern one value in its own committed batch. */
    private static TermId internCommit(RocksFlatStore store, FlatDictionary dict, Value v)
            throws Exception {
        try (WriteBatch batch = new WriteBatch();
                WriteOptions opts = new WriteOptions()) {
            TermId id = dict.intern(v, batch, new HashMap<>());
            store.db().write(opts, batch);
            return id;
        }
    }

    @Test
    void intern_then_find_and_lookup_roundtrip(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            IRI alice = VF.createIRI("http://example.org/alice");
            TermId id = internCommit(store, dict, alice);

            assertTrue(id.value() > 0, "ids are assigned from 1 (0 is reserved)");
            assertEquals(Optional.of(id), dict.find(alice));
            assertEquals(Optional.of(alice), dict.lookup(id));
        }
    }

    @Test
    void ids_are_assigned_sequentially_from_one(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString());
                WriteBatch batch = new WriteBatch()) {
            FlatDictionary dict = new FlatDictionary(store);
            Map<ByteBuffer, TermId> pending = new HashMap<>();
            TermId first = dict.intern(VF.createIRI("urn:1"), batch, pending);
            TermId second = dict.intern(VF.createIRI("urn:2"), batch, pending);
            assertEquals(1L, first.value());
            assertEquals(2L, second.value());
        }
    }

    @Test
    void same_term_interned_twice_in_one_batch_gets_a_single_id(@TempDir Path dir)
            throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString());
                WriteBatch batch = new WriteBatch()) {
            FlatDictionary dict = new FlatDictionary(store);
            Map<ByteBuffer, TermId> pending = new HashMap<>();
            IRI predicate = VF.createIRI("urn:recurring-predicate");
            TermId a = dict.intern(predicate, batch, pending);
            TermId b = dict.intern(predicate, batch, pending);
            assertEquals(a, b, "a term recurring within one batch must keep one id");
        }
    }

    @Test
    void an_already_committed_term_resolves_in_a_fresh_batch(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            IRI iri = VF.createIRI("urn:x");
            TermId first = internCommit(store, dict, iri);
            // A new batch with a fresh pending map must still find the committed id.
            TermId again = internCommit(store, dict, iri);
            assertEquals(first, again);
        }
    }

    @Test
    void the_id_counter_persists_across_a_reopen(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            internCommit(store, dict, VF.createIRI("urn:a"));
            internCommit(store, dict, VF.createIRI("urn:b"));
        }
        // Reopen — a fresh FlatDictionary must continue the counter, not restart.
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            TermId third = internCommit(store, dict, VF.createIRI("urn:c"));
            assertEquals(3L, third.value(), "counter must resume after reopen");
        }
    }

    @Test
    void find_and_lookup_of_unknown_terms_return_empty(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            assertEquals(Optional.empty(), dict.find(VF.createIRI("urn:never-interned")));
            assertEquals(Optional.empty(), dict.lookup(TermId.of(9999L)));
        }
    }

    @Test
    void literals_roundtrip_through_the_dictionary(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            Literal typed = VF.createLiteral("42", XSD.INTEGER);
            Literal tagged = VF.createLiteral("hallo", "de");
            TermId typedId = internCommit(store, dict, typed);
            TermId taggedId = internCommit(store, dict, tagged);

            assertEquals(Optional.of((Value) typed), dict.lookup(typedId));
            assertEquals(Optional.of((Value) tagged), dict.lookup(taggedId));
            assertNotEquals(typedId, taggedId);
        }
    }

    @Test
    void repeated_lookups_are_cache_consistent(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            IRI iri = VF.createIRI("http://example.org/cached");
            TermId id = internCommit(store, dict, iri);
            // First lookup populates the term cache; the second is a cache hit.
            // Both must return the same value.
            assertEquals(Optional.of((Value) iri), dict.lookup(id));
            assertEquals(Optional.of((Value) iri), dict.lookup(id));
        }
    }

    @Test
    void lookup_all_resolves_a_batch_positionally(@TempDir Path dir) throws Exception {
        try (RocksFlatStore store = RocksFlatStore.open(dir.toString())) {
            FlatDictionary dict = new FlatDictionary(store);
            IRI a = VF.createIRI("urn:a");
            IRI b = VF.createIRI("urn:b");
            IRI c = VF.createIRI("urn:c");
            TermId idA = internCommit(store, dict, a);
            TermId idB = internCommit(store, dict, b);
            TermId idC = internCommit(store, dict, c);

            // Mixed order, with one id that was never assigned — the batched
            // multiGet must return values positionally, null for the unknown.
            Value[] got = dict.lookupAll(new TermId[] {idA, TermId.of(999_999L), idC, idB}, null);
            assertEquals(a, got[0]);
            assertNull(got[1], "an unknown id resolves to a null slot");
            assertEquals(c, got[2]);
            assertEquals(b, got[3]);
        }
    }
}
