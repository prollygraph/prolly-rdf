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
package com.earasoft.prolly.rdf4j.uaf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.dolthub.prolly.HeapBufferPool;
import com.dolthub.prolly.InMemoryNodeStore;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.value.ProllyValueFactory;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Field;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Triple;
import org.junit.jupiter.api.Test;

/**
 * Use-after-free coverage for the RDF4J value wrappers (plans/off-heap-use-after-free-tests.md
 * Phase 4 Step 15): {@link ProllyValueFactory} and the {@code ProllyIRI} / {@code ProllyBNode} /
 * {@code ProllyLiteral} / {@code ProllyTriple} it produces.
 *
 * <p><b>The hazard is H4 (retention), and the net is arena lifetime — not a poison
 * differential.</b> Each value wrapper holds a {@code private final MemorySegment encoded}
 * <em>directly</em> (no defensive copy) and decodes it <em>lazily</em> (caching the String on first
 * read). RDF4J keeps query-result {@code Value}s alive long after the transaction that produced
 * them — so the load-bearing safety property is that the factory encodes each value into its OWN
 * {@link Arena#ofAuto()} (the default): a garbage-collected arena whose segment is freed only when
 * that value itself becomes unreachable. A value can therefore be read at any later point.
 *
 * <p><b>Per-VALUE, not one shared arena (the 2026-06-15 leak fix, ADR-0063).</b> The default
 * originally used a single process-lifetime {@code Arena.ofAuto()} for ALL values; an automatic
 * arena retains one {@code SegmentFactories$1} cleanup action per allocation for its whole
 * lifetime, so a Sail-shared factory leaked ~48 bytes per value ever created (21.8M live at OOM in
 * a 1-HR soak) even though the value wrappers were collected. The fix gives each value its own
 * arena; {@link #defaultFactoryGivesEachValueItsOwnSession} pins it, and the surviving-allocation
 * profile is reproduced by {@code test-support/soak-alloc-profile.sh}.
 *
 * <p>This step pins that property two ways: (1) a value built from an explicit {@link
 * Arena#ofConfined()} dies when that arena closes — proving the segment is held directly, so safety
 * <em>depends</em> on the arena choice (the {@code ofAuto} default is doing real work, D-2); (2) a
 * value from the default factory survives the factory becoming unreachable + a garbage collection —
 * the {@code ofAuto} retention guarantee. A regression switching the default to a
 * transaction-scoped arena would make every escaped query result a use-after-free, and (1)+(2) fail
 * it.
 *
 * <p><b>Lazy-cache caveat (see {@link #readBeforeCloseCachesSoTheNetMustReadAfterClose}):</b>
 * because the decode caches, the H1 reads below are deferred until <em>after</em> the arena closes
 * — a read taken while the arena is open caches the String and would mask the freed segment.
 */
class ProllyValueUseAfterFreeTest {

    private static PrefixTable prefixes() {
        // Full IRIs (the only kind created here) never consult the prefix table on encode/decode,
        // so an
        // empty one suffices; its heap pool is GC-managed and unrelated to the value-encoding
        // arena.
        return new PrefixTable(new InMemoryNodeStore(), new HeapBufferPool());
    }

    @Test
    void iriAndLiteralFromConfinedArenaThrowAfterClose() {
        IRI iri;
        Literal lit;
        try (Arena confined = Arena.ofConfined()) {
            ProllyValueFactory factory =
                    new ProllyValueFactory(prefixes(), HashFunctions.defaultHash(), confined);
            iri = factory.createIRI("http://example.org/subject");
            lit = factory.createLiteral("a literal value");
            // Deliberately NOT read here — a read would cache the String and hide the freed
            // segment.
        } // confined arena closed → its segments are freed

        assertThrows(
                IllegalStateException.class,
                iri::stringValue,
                "reading a confined-arena-backed IRI after close must throw — the value holds the segment"
                        + " directly, not a longer-lived copy");
        assertThrows(
                IllegalStateException.class,
                lit::getLabel,
                "same for a literal — safety must come from the arena choice, not a defensive copy");
    }

    @Test
    void quotedTripleFromConfinedArenaThrowsAfterClose() {
        Triple triple;
        try (Arena confined = Arena.ofConfined()) {
            ProllyValueFactory factory =
                    new ProllyValueFactory(prefixes(), HashFunctions.defaultHash(), confined);
            IRI s = factory.createIRI("http://example.org/s");
            IRI p = factory.createIRI("http://example.org/p");
            IRI o = factory.createIRI("http://example.org/o");
            triple = factory.createTriple(s, p, o); // encodeQuotedTriple(..., confined)
            // Not read here — getSubject() touches the triple's own segment via decodeQuotedTriple.
        }

        assertThrows(
                IllegalStateException.class,
                triple::getSubject,
                "reading an RDF-star ProllyTriple after its arena closes must throw — it decodes the"
                        + " quoted-triple segment lazily, holding it directly");
    }

    @Test
    void valueFromDefaultFactorySurvivesFactoryDropAndGc() {
        // The factory is created + dropped inside the helper, so only the returned IRI keeps the
        // Arena.ofAuto() segment reachable. The value is NOT read inside the helper, so this proves
        // the
        // *segment* survives (not merely a cached String).
        IRI iri = makeUnreadIriFromDroppedDefaultFactory("http://example.org/survives-gc");

        System.gc();
        Runtime.getRuntime().gc(); // ofAuto frees only when unreachable; the IRI keeps it alive

        assertEquals(
                "http://example.org/survives-gc",
                iri.stringValue(),
                "a value from the default (Arena.ofAuto) factory reads correctly after the factory is"
                        + " GC-eligible — retention-safe across any transaction scope");
    }

    @Test
    void readBeforeCloseCachesSoTheNetMustReadAfterClose() {
        // Documents WHY the H1 tests above defer their first read: a value read while its arena is
        // open
        // caches the decoded String, so the post-close read returns the cache without re-touching
        // the
        // freed segment — a read-before-close would silently mask a real use-after-free.
        IRI iri;
        try (Arena confined = Arena.ofConfined()) {
            ProllyValueFactory factory =
                    new ProllyValueFactory(prefixes(), HashFunctions.defaultHash(), confined);
            iri = factory.createIRI("http://example.org/cached-while-open");
            assertEquals(
                    "http://example.org/cached-while-open",
                    iri.stringValue(),
                    "read + cache while the arena is open");
        }

        assertEquals(
                "http://example.org/cached-while-open",
                iri.stringValue(),
                "a value read before close returns its cached String after close — the segment is never"
                        + " re-touched, which is exactly why the H1 net must read only after close");
    }

    private static IRI makeUnreadIriFromDroppedDefaultFactory(String iri) {
        ProllyValueFactory factory =
                new ProllyValueFactory(prefixes()); // default → per-value Arena.ofAuto()
        return factory.createIRI(iri);
        // factory is unreachable past this point — only the returned IRI retains the auto-arena
        // segment
    }

    /**
     * The 2026-06-15 leak regression pin (ADR-0063): the DEFAULT factory must allocate each value
     * in its OWN arena, so a value's native bookkeeping is freed when THAT value is collected — not
     * retained for the factory's lifetime. The leak was a single process-lifetime {@code
     * Arena.ofAuto()} shared across ALL values, which retains one {@code SegmentFactories$1}
     * cleanup action per allocation for its whole life. Per-value arenas make two values' memory
     * sessions distinct; a shared arena makes them identical — so this {@code assertNotSame} fails
     * the old code and passes the fix. (Mechanism-specific by design — a future move to heap-backed
     * value bytes would retire this in favour of the soak bench, the implementation-agnostic
     * guard.)
     */
    @Test
    void defaultFactoryGivesEachValueItsOwnSession() throws Exception {
        ProllyValueFactory factory =
                new ProllyValueFactory(prefixes()); // default → per-value ofAuto
        MemorySegment a = encodedSegment(factory.createIRI("urn:a"));
        MemorySegment b = encodedSegment(factory.createIRI("urn:b"));
        assertNotSame(
                a.scope(),
                b.scope(),
                "default factory must give each value its OWN arena/session — one shared arena leaks a"
                        + " SegmentFactories$1 cleanup action per value created, for the arena's whole life");
    }

    /**
     * The injected-arena seam the {@code confined}-arena tests above rely on: when an arena is
     * supplied, every value shares it, so closing it invalidates them all together.
     */
    @Test
    void injectedArenaIsSharedAcrossValues() throws Exception {
        try (Arena shared = Arena.ofShared()) {
            ProllyValueFactory factory =
                    new ProllyValueFactory(prefixes(), HashFunctions.defaultHash(), shared);
            MemorySegment a = encodedSegment(factory.createIRI("urn:a"));
            MemorySegment b = encodedSegment(factory.createIRI("urn:b"));
            assertSame(
                    a.scope(),
                    b.scope(),
                    "an injected arena is the shared allocator across all values (the UAF-test seam)");
        }
    }

    /**
     * Read a value wrapper's directly-held {@code encoded} {@link MemorySegment} — the field the H1
     * / H4 net turns on. Reflection (rather than a production accessor) keeps the segment
     * encapsulated; the field name is the stable contract this one test couples to.
     */
    private static MemorySegment encodedSegment(Object prollyValue) throws Exception {
        Field f = prollyValue.getClass().getDeclaredField("encoded");
        f.setAccessible(true);
        return (MemorySegment) f.get(prollyValue);
    }
}
