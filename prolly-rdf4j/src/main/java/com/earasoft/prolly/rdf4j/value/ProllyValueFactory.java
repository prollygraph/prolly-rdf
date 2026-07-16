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
package com.earasoft.prolly.rdf4j.value;

import com.earasoft.prolly.rdf4j.term.HashFunction;
import com.earasoft.prolly.rdf4j.term.HashFunctions;
import com.earasoft.prolly.rdf4j.term.PrefixTable;
import com.earasoft.prolly.rdf4j.term.TermCodec;
import com.earasoft.prolly.rdf4j.term.TermEncoder;
import com.earasoft.prolly.rdf4j.term.TermId;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Triple;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.jspecify.annotations.Nullable;

/**
 * {@link org.eclipse.rdf4j.model.ValueFactory} producing {@link ProllyValue} instances backed by
 * heap-allocated MemorySegments.
 *
 * <p>This is the "free-standing" factory — values created here are not yet inserted into any
 * Dictionary. For Sail-backed creation (where each value gets a stable TermId in the persistent
 * dict), use the factory exposed by the Sail's open connection (Phase 2).
 *
 * <p>{@link #createTriple} stashes its components in a per-factory heap map so the resulting {@link
 * ProllyTriple}'s component accessors can resolve back to the original values without going through
 * a Dictionary.
 *
 * <p>This factory delegates {@link #createStatement} to RDF4J's {@link SimpleValueFactory} because
 * we don't yet have a {@code ProllyStatement} wrapper that adds value over {@code SimpleStatement}
 * (added in iter 16).
 *
 * <p>Not thread-safe — the internal Triple-component cache is unsynchronized.
 */
public class ProllyValueFactory implements org.eclipse.rdf4j.model.ValueFactory {

    private final PrefixTable prefixes;
    private final HashFunction hashFn;

    /**
     * Explicit, caller-managed allocator shared by every value, or {@code null} for the default
     * (production) path where each value gets its OWN {@link Arena#ofAuto()} (see {@link
     * #arenaForValue()}).
     *
     * <p><b>The leak this nullability fixes (root-caused 2026-06-15 via async-profiler alloc+live;
     * a 1-HR soak OOM'd in ~9 min).</b> The default ctor previously stored one process-lifetime
     * {@link Arena#ofAuto()} and encoded EVERY value into it. An automatic arena retains one {@code
     * jdk.internal.foreign.SegmentFactories$1} cleanup action per allocation for its whole
     * lifetime, so a Sail-shared factory accumulated one such object per value ever created (~48
     * bytes each; 21.8M live at OOM) even though the {@link ProllyValue} wrappers and their
     * segments were collected normally. Per-value arenas bound the live cleanup-action set to the
     * live-VALUE working set instead of total-ever-created. A non-null injected arena keeps the old
     * shared behavior on purpose — the use-after-free suite needs every value in one closeable
     * arena.
     */
    // @Nullable: null => each value gets its own Arena.ofAuto() (the non-leaking default);
    // arenaForValue() guards the deref. A non-null injected arena is the shared-arena test path.
    private final @Nullable Arena injectedArena;

    /** Component values stashed during createTriple, keyed by their TermId. */
    private final Map<TermId, ProllyValue> tripleComponents = new HashMap<>();

    private final TermResolver triplResolver =
            id -> {
                ProllyValue v = tripleComponents.get(id);
                if (v == null)
                    throw new IllegalStateException(
                            "unknown TermId "
                                    + id
                                    + " — only triples created via this factory can be resolved");
                return v;
            };

    public ProllyValueFactory(PrefixTable prefixes) {
        // null injected arena => per-value Arena.ofAuto() (the non-leaking default). Do NOT pass a
        // single shared Arena.ofAuto() here — that is the leak (see the injectedArena field doc).
        this(prefixes, HashFunctions.defaultHash(), null);
    }

    /**
     * @param arena explicit, caller-managed allocator shared by every value this factory creates,
     *     or {@code null} to give each value its own {@link Arena#ofAuto()} (the non-leaking
     *     default — see {@link #arenaForValue()}). A non-null shared arena is for tests that need a
     *     single closeable arena spanning all created values (the use-after-free suite).
     */
    public ProllyValueFactory(PrefixTable prefixes, HashFunction hashFn, @Nullable Arena arena) {
        this.prefixes = prefixes;
        this.hashFn = hashFn;
        this.injectedArena = arena;
    }

    /**
     * The arena a value's encoded bytes are allocated from. Default path ({@link #injectedArena} is
     * {@code null}): a FRESH {@link Arena#ofAuto()} per value, so the value's lone {@code
     * SegmentFactories$1} cleanup action is freed when the value is garbage-collected — no
     * process-lifetime accumulation (the leak fixed 2026-06-15). Injected path: the shared,
     * caller-managed arena, returned unchanged on every call.
     */
    private Arena arenaForValue() {
        return injectedArena != null ? injectedArena : Arena.ofAuto();
    }

    @Override
    public IRI createIRI(String iri) {
        return new ProllyIRI(TermCodec.encodeFullIri(iri, arenaForValue()), prefixes);
    }

    @Override
    public IRI createIRI(String namespace, String localName) {
        return createIRI(namespace + localName);
    }

    @Override
    public org.eclipse.rdf4j.model.BNode createBNode() {
        // Generate a random label rather than UUID-form so equals with SimpleBNode works.
        return createBNode("genid-" + java.util.UUID.randomUUID());
    }

    @Override
    public org.eclipse.rdf4j.model.BNode createBNode(String nodeID) {
        return new ProllyBNode(TermCodec.encodeBNodeLabel(nodeID, arenaForValue()));
    }

    @Override
    public Literal createLiteral(String label) {
        return new ProllyLiteral(TermCodec.encodeXsdString(label, arenaForValue()));
    }

    @Override
    public Literal createLiteral(String label, String language) {
        return new ProllyLiteral(TermCodec.encodeLangString(label, language, arenaForValue()));
    }

    @Override
    public Literal createLiteral(String label, IRI datatype) {
        Literal sentinel = SimpleValueFactory.getInstance().createLiteral(label, datatype);
        // Only a FAITHFULLY-dedicated datatype (1:1 tag) may be eagerly encoded here. A datatype
        // without
        // one must stay a SimpleLiteral so its EXACT datatype IRI survives to the Sail, where
        // DictionaryTermEncoder stores it via the custom path (this free-standing factory has no
        // Dictionary
        // to allocate a datatype-IRI TermId). This covers BOTH:
        //   - truly-custom datatypes (TermEncoder.encode would throw — DTYPE-2), and
        //   - the six derived integers (TermEncoder.encode would LOSSILY collapse them onto
        // xsd:integer —
        //     DTYPE-1, Step 6b): wrapping those collapsed bytes HERE is exactly what dropped the
        // subtype IRI
        //     before the literal ever reached the Sail. (Corrects the 6a assumption that this
        // factory needed
        //     no change: true for DTYPE-2, false for DTYPE-1.) (ADR-0043.)
        if (!TermEncoder.isDedicatedDatatype(datatype)) {
            return sentinel;
        }
        try {
            return new ProllyLiteral(TermEncoder.encode(sentinel, arenaForValue()));
        } catch (IllegalArgumentException illTypedLexical) {
            // A faithful datatype with an ill-typed lexical its encoder parses+rejects (e.g. bad
            // base64/hex/decimal) — keep the SimpleLiteral (a valid RDF term; the Sail decides
            // storage).
            return sentinel;
        }
    }

    @Override
    public Literal createLiteral(boolean v) {
        // The boolean overload mints the canonical xsd:boolean lexical form ("true"/"false");
        // encodeBoolean now stores that verbatim (term-faithful, ADR-0043).
        return new ProllyLiteral(TermCodec.encodeBoolean(v ? "true" : "false", arenaForValue()));
    }

    @Override
    public Literal createLiteral(byte v) {
        return new ProllyLiteral(TermCodec.encodeInt8(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(short v) {
        return new ProllyLiteral(TermCodec.encodeInt16(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(int v) {
        return new ProllyLiteral(TermCodec.encodeInt32(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(long v) {
        return new ProllyLiteral(TermCodec.encodeLong(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(float v) {
        return new ProllyLiteral(TermCodec.encodeFloat32(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(double v) {
        return new ProllyLiteral(TermCodec.encodeFloat64(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(java.math.BigInteger v) {
        // Term-faithful: store the canonical decimal lexical form under TAG_XSD_INTEGER, any
        // magnitude — TAG_XSD_INTEGER_BIG was removed (ADR-0043; lexical UTF-8 carries any size).
        return new ProllyLiteral(TermCodec.encodeInteger(v.toString(), arenaForValue()));
    }

    @Override
    public Literal createLiteral(java.math.BigDecimal v) {
        if (v.scale() < Byte.MIN_VALUE || v.scale() > Byte.MAX_VALUE) {
            // Decimal scale outside int8 — fall back to SimpleLiteral (label-based)
            return SimpleValueFactory.getInstance().createLiteral(v.toString(), XSD.DECIMAL);
        }
        return new ProllyLiteral(TermCodec.encodeDecimal(v, arenaForValue()));
    }

    @Override
    public Literal createLiteral(
            String label, IRI datatype, org.eclipse.rdf4j.model.base.CoreDatatype coreDatatype) {
        return createLiteral(label, datatype);
    }

    @Override
    public Literal createLiteral(
            String label, org.eclipse.rdf4j.model.base.CoreDatatype coreDatatype) {
        return createLiteral(label, coreDatatype.getIri());
    }

    @Override
    public Literal createLiteral(java.util.Date date) {
        return SimpleValueFactory.getInstance().createLiteral(date);
    }

    @Override
    public Literal createLiteral(javax.xml.datatype.XMLGregorianCalendar calendar) {
        return SimpleValueFactory.getInstance().createLiteral(calendar);
    }

    @Override
    public Literal createLiteral(java.time.temporal.TemporalAccessor value) {
        return SimpleValueFactory.getInstance().createLiteral(value);
    }

    @Override
    public Literal createLiteral(java.time.temporal.TemporalAmount value) {
        return SimpleValueFactory.getInstance().createLiteral(value);
    }

    @Override
    public Statement createStatement(Resource subject, IRI predicate, Value object) {
        // ProllyStatement lands in iter 16; SimpleStatement is the temporary stand-in
        return SimpleValueFactory.getInstance().createStatement(subject, predicate, object);
    }

    @Override
    public Statement createStatement(
            Resource subject, IRI predicate, Value object, Resource context) {
        return SimpleValueFactory.getInstance()
                .createStatement(subject, predicate, object, context);
    }

    @Override
    public Triple createTriple(Resource subject, IRI predicate, Value object) {
        TermId sId = encodeAndCacheComponent(subject);
        TermId pId = encodeAndCacheComponent(predicate);
        TermId oId = encodeAndCacheComponent(object);
        MemorySegment enc =
                TermCodec.encodeQuotedTriple(sId, pId, oId, /*asserted*/ true, arenaForValue());
        return new ProllyTriple(enc, triplResolver);
    }

    /** Encode a Value, register it in the local Triple-component cache, return its TermId. */
    private TermId encodeAndCacheComponent(Value v) {
        MemorySegment enc;
        ProllyValue wrapped;
        if (v instanceof ProllyValue pv) {
            // Already a Prolly value — but we still need its encoded bytes. Reach
            // through using our private re-encode (cheap; same value).
            enc = TermEncoder.encode((Value) pv, arenaForValue());
            wrapped = pv;
        } else {
            // Foreign Value (e.g., a SimpleIRI from another factory) — encode + wrap.
            enc = TermEncoder.encode(v, arenaForValue());
            if (v instanceof IRI) wrapped = new ProllyIRI(enc, prefixes);
            else if (v instanceof org.eclipse.rdf4j.model.BNode) wrapped = new ProllyBNode(enc);
            else if (v instanceof Literal) wrapped = new ProllyLiteral(enc);
            else
                throw new IllegalArgumentException(
                        "createTriple: unsupported component kind " + v.getClass().getName());
        }
        TermId id = TermId.ofNatural(hashFn.hash(enc));
        tripleComponents.put(id, wrapped);
        return id;
    }
}
