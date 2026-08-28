
# prolly-rdf4j — Architecture (v2)

> Audience: senior engineer working on the Sail. (The phase plans this doc once fronted live in the private monorepo's work tracker.)
>
> Status: design locked at Phase 0 sign-off; **v2 has since SHIPPED**. Byte layouts in §4 are normative and match the code (spot-verified 2026-07-16). Where the implementation diverged from this design, the section carries a dated correction; the recurring deltas:
> - Class names shipped WITHOUT the `2` suffix — `ProllySail` / `ProllySailConnection` (v2 replaced v1 in place).
> - The term layer (`TermCodec`, `Dictionary`, `TermId`, the hash) was extracted to the **`prolly-codec` module**; `PrefixTable` / `TermStats` stayed in this module's `term/`.
> - There is no `BgpEvaluator` class — BGP routing lives in the Sail's `evaluate()` + `GraphPatternEngine` (prolly-rdf) + the worst-case-optimal triejoin (ADR-0065); `IndexPlanner` lives in `index/`, not `sail/`.
> - Metrics shipped as **Micrometer** (ADR-0041), superseding §8.4's `SailMetrics` SPI design.
> - The dictionary hash shipped as **FNV-1a-64** (`prolly-codec` `Fnv1a64`); xxh3 remains the planned upgrade, not the default §4.1 describes.

---

## 1. What is prolly-rdf4j?

A versioned RDF triple/quad store presented to applications as an RDF4J `Sail`. It speaks SPARQL 1.1 (and forthcoming 1.2) over a content-addressed Prolly Tree substrate that gives us:

- **Git-style branches, tags, merge, blame, bisect** on the graph itself.
- **Off-heap storage** via the Panama `MemorySegment` API (final since JDK 22; this repo targets JDK 25) — heap stays flat regardless of dataset size.
- **Mergeable dictionaries** — branches diverge and recombine without coordinating a global term-counter.

v1 shipped as a dual-write façade over an in-memory `MemoryStore`. v2 (this rewrite) is **substrate-native**: queries scan Prolly Trees directly, terms persist in a versioned typed encoding, and the in-memory layer is gone.

## 2. Why v2 — the gap

| Concern              | v1 today                                          | v2 target                                  |
|----------------------|---------------------------------------------------|--------------------------------------------|
| Literals             | Lossy quoted-string; datatype + lang discarded    | Type-tagged binary; full fidelity          |
| Heap                 | Working set bounded by JVM heap                   | Bounded by off-heap NodeCache              |
| Range queries        | Full scan, parse-per-row                          | Byte-range scan on lex-sortable encoding   |
| Indexes              | SPOC + ad-hoc POSC rebuild                        | SPOC / POSC / OSPC / CSPO always built     |
| Concurrent writers   | Single-writer at Sail boundary                    | CAS + rebase; multi-writer across instances|
| RDF-star / 1.2       | Not supported                                     | Tag-byte slots reserved; encoding ready    |
| String materialization | On every encode/decode                           | Only on `Value.stringValue()`              |
| Cross-language port  | Sail format opaque                                | Frozen byte spec; Go port can read         |

## 3. Layered architecture

```
┌─────────────────────────────────────────────────────────────┐
│  prolly-rdf4j-rest  (Spring Boot REST — private monorepo)   │
└─────────────────────────────────────────────────────────────┘
                            │  HTTP / SPARQL
┌───────────────────────────▼─────────────────────────────────┐
│  RDF4J Repository / SailRepository                          │
└───────────────────────────┬─────────────────────────────────┘
                            │  Sail SPI
┌───────────────────────────▼─────────────────────────────────┐
│  com.earasoft.prolly.rdf4j.sail                             │
│    ProllySail / ProllySailConnection                        │
│      • Statement → ProllyValue → TermID                     │
│      • CloseableIteration backed by Prolly Cursor           │
│      • evaluate() routes BGPs to GraphPatternEngine,        │
│        falls through to RDF4J for FILTER/OPTIONAL/...       │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  com.earasoft.prolly.rdf4j.term                             │
│    TermCodec    encode/decode Value ↔ tag-prefixed bytes    │
│    Dictionary   Prolly Map (TermID → encoded bytes)         │
│    PrefixTable  Manifest-pinned IRI prefix dictionary       │
│    TermStats    cardinality stats per TermID for planner    │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  com.earasoft.prolly.rdf4j.value                            │
│    ProllyIRI / ProllyBNode / ProllyLiteral / ProllyTriple   │
│      • Implements org.eclipse.rdf4j.model.Value              │
│      • Backed by MemorySegment slice (zero-copy)            │
│      • stringValue() materializes on demand                 │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  prolly-rdf  (existing)                                     │
│    VersionedQuadStore + indexes  (extended to 4)            │
│    GraphPatternEngine            (BGP execution)            │
│    CanonicalizingQuadStore       (URDNA2015 wrapper)        │
└───────────────────────────┬─────────────────────────────────┘
                            │
┌───────────────────────────▼─────────────────────────────────┐
│  engine core (dolthub-java-port — the prolly-core repo)     │
│    Tuple / TupleDescriptor / TypeCodec / Cursor             │
│    NodeStore / TreeMutator / MutableMap                     │
│    Manifest / Database (versioning, GC)                     │
└─────────────────────────────────────────────────────────────┘
```

Packages created by this rewrite, as shipped (2026-07-16 correction — the design's `2`
suffixes were dropped, `BgpEvaluator` never existed as a class, and the term layer was
later extracted to **prolly-codec**):

- `prolly-codec …/rdf4j/term/` — `TermCodec`, `Dictionary`, `TermId`, `Fnv1a64`
- `prolly-rdf4j …/term/` — `PrefixTable`, `TermStats`
- `prolly-rdf4j …/value/` — `ProllyIRI`, `ProllyBNode`, `ProllyLiteral`, `ProllyTriple`, `ProllyValueFactory`
- `prolly-rdf4j …/index/` — `IndexPlanner`, the four `QuadIndex` permutations
- `prolly-rdf4j …/sail/` — `ProllySail`, `ProllySailConnection`, `RootMetaTree(Store)`

v2 replaces v1 in place — there is no on-disk migration path and none is planned. v1's storage was a JVM-heap façade; nothing depends on its bytes.

## 4. Core concepts

### 4.1 TermID — 64-bit content hash

```
+---+---+---+---+---+---+---+---+
| 8 bytes, big-endian            |
+---+---+---+---+---+---+---+---+
  ↑                              ↑
  bit 63 = extension flag        bit 0
```

- `id = H64(tag-byte || canonical-payload)` for the natural case (salt 0).
- **Shipped reality (2026-07-16): `H64` is FNV-1a-64** (`Fnv1a64` in prolly-codec, a `HashFunction` impl — its javadoc says "replace with xxh3-64 when performance benchmarks demand it"). The paragraph below is the DESIGN intent, retained for when that day comes:
- ~~**`H64` is `xxh3_64` by default;**~~ BLAKE3-truncated-64 opt-in via Sail config (`setHashFunction(HashFunction.BLAKE3)`). Default is xxh3 because (a) it's ~50 GB/s, (b) collisions are unforced — our inputs are RDF terms from cooperative producers. Use BLAKE3 if your Sail accepts untrusted SPARQL Update from internet-facing clients (an adversary could otherwise force collisions to inflate the dictionary). xxh3 implementation: include vendored `Xxh3.java` (~100 LOC, MIT licensed) in `term/hash/`; do **not** add a third-party Maven dep for it — the algorithm is small and stable. BLAKE3 via `o.b.b.blake3:blake3jni` if enabled. v2.0 Phase 1 ships FNV-1a-64 as a placeholder until the xxh3 vendor lands. <!-- doc-drift-allow: Xxh3.java planned vendored impl (v2.0); not yet landed — current placeholder is Fnv1a64.java -->
- **Collisions are handled by salted rehash, not by a separate extension table.** On collision (same natural slot, byte-different term), the encoder retries with `H(BE-32-bit-salt || term)` for salt = 1, 2, … and places the result in *extension address space* (top bit of TermId set) at `TermId.ofExtensionSlot(h & MASK)`. Re-encoding the same bytes follows the same deterministic salt chain. The dictionary uses a single Prolly tree containing both natural and extension entries — distinguished only by the top bit of their TermId. There is no separate `refs/system/term-ext` map; that part of an earlier draft was simplified out.
- **Insert protocol:**
  1. Compute natural `id`.
  2. Look up `id` in the dictionary.
  3. If miss → insert (term goes in at its natural id).
  4. If hit and bytes-equal → return existing id (dedupe).
  5. If hit and bytes-differ → **collision**. Allocate next free extension slot; set top bit on its id; write the term to the extension entry. Future encodes of that term resolve via byte-match against the extension table first, then fall back to natural lookup.
- Birthday-bound math: random 64-bit hash collides at ~50% probability at 2³² (~4.3 B) distinct terms. Real corpora collide far less. At 1 B distinct terms expected collision count is ≪ 1. The extension table exists for correctness, not steady-state load.

### 4.2 Encoded-term byte layout

Every term in the dictionary is `[tag : u8] [payload : variable]`. The tag's high nibble classifies the kind; the low nibble selects the variant.

| Range       | Kind            |
|-------------|-----------------|
| `0x00–0x3F` | Typed literal (numeric/temporal/boolean) — fixed-width payload |
| `0x40–0x5F` | String-like — `xsd:string`, `rdf:langString`, `xsd:anyURI` |
| `0x60–0x6F` | Binary literal — `xsd:base64Binary`, `xsd:hexBinary` |
| `0x80–0x9F` | IRI — prefix-table variants |
| `0xA0–0xAF` | Blank node |
| `0xC0–0xCF` | Quoted triple (RDF-star, RDF 1.2 directional) |
| `0xE0–0xEF` | Extended — custom datatype IRI + lexical bytes |
| `0xF0–0xFF` | Reserved — format-version / future |

#### Full type table

```
TYPED LITERALS  (fixed-width, lex-sortable)
  0x10  xsd:boolean         1B   { 0x00 | 0x01 }
  0x11  xsd:byte             1B   sign-flipped Int8
  0x12  xsd:short            2B   sign-flipped Int16, BE
  0x13  xsd:int              4B   sign-flipped Int32, BE
  0x14  xsd:integer          8B   sign-flipped Int64, BE  (canonical)
  0x15  xsd:long             8B   alias of 0x14
  0x16  xsd:unsignedInt      4B   BE, no flip
  0x17  xsd:unsignedLong     8B   BE, no flip
  0x18  xsd:float            4B   IEEE-754 lex-flip
  0x19  xsd:double           8B   IEEE-754 lex-flip
  0x1A  xsd:decimal          1B scale + N B unscaled (BE, sign-extended)
  0x1F  xsd:integer (big)    1B sign + 2B length + bytes (BE)

  0x20  xsd:dateTime        12B   6B epoch-ms (BE signed) + 4B sub-ms-nanos + 2B tz-offset-min
  0x21  xsd:date             4B   year(I16,BE) + month(u8) + day(u8)
  0x22  xsd:time             8B   6B ns-since-midnight + 2B tz-offset-min
  0x23  xsd:gYear            2B   year(I16,BE)
  0x24  xsd:gYearMonth       3B   year(I16,BE) + month(u8)
  0x25  xsd:duration        12B   months(I32,BE) + nanos(I64,BE)

  0x30  xsd:UUID            16B

STRING-LIKE
  0x40  xsd:string                 UTF-8 bytes (length implicit from dict-entry size)
  0x41  rdf:langString             1B lang-tag-len + lang-tag (ASCII) + UTF-8 lex bytes
  0x42  xsd:anyURI                 UTF-8 bytes (length implicit)

BINARY
  0x60  xsd:base64Binary           raw bytes (length implicit; lexical b64 not stored)
  0x61  xsd:hexBinary              raw bytes (length implicit; lexical hex not stored)

IRI
  0x80  short-prefix IRI           4B prefix-id (BE) + varint-len + local-part UTF-8
  0x81  long-prefix IRI            4B prefix-id-1 + 4B prefix-id-2 + varint-len + local-part
  0x82  full IRI (no prefix hit)   varint-len + full UTF-8

BLANK NODE
  0xA0  random BNode              16B UUID
  0xA1  labelled BNode             varint-len + UTF-8 label
  0xA2  canonical BNode (URDNA)    4B c14n-index (BE)

QUOTED TRIPLE / RDF-STAR
  0xC0  quoted triple, asserted    8B sID + 8B pID + 8B oID                    (24 B)
  0xC1  quoted triple, unasserted  8B sID + 8B pID + 8B oID
  0xC2  quoted quad, asserted      8B sID + 8B pID + 8B oID + 8B cID           (32 B)
  0xC3  quoted quad, unasserted    8B sID + 8B pID + 8B oID + 8B cID
  0xC8  directional triple (1.2)   8B sID + 8B pID + 8B oID + 1B direction

EXTENDED
  0xE0  custom datatype literal    8B datatype-IRI-id + lexical UTF-8 (length implicit)

RESERVED
  0xF0–0xFF                       future use; readers must error on unknown tag
```

**Why this design wins:**

1. **Range scans on numerics are byte-range scans** — `FILTER(?n > 30)` over `xsd:integer` is a single `MemorySegment.mismatch()` against `encode(30, xsd:integer)`. No parse, no boxing.
2. **One-byte type sniff** — pattern dispatch on the head byte without a dictionary lookup.
3. **Prefix-encoded IRIs** compress the dictionary; a fully-qualified `http://schema.org/Person` becomes `0x80 + 4B + 6B` ≈ 11 bytes once the prefix is registered.
4. **Quoted triples are by-id**, so SPOC rows never bloat for RDF-star and recursion is free.
5. **Tag-byte version reservations** mean RDF 1.2 / 1.3 additions don't require a format break.

### 4.3 Zero-copy `ProllyValue` contract

```java
sealed interface ProllyValue extends org.eclipse.rdf4j.model.Value
    permits ProllyIRI, ProllyBNode, ProllyLiteral, ProllyTriple { }
```

Each `ProllyValue`:

- Holds a `MemorySegment` slice into a Prolly leaf chunk (no copy).
- Carries a back-reference to the issuing `SailConnection`'s `Arena`.
- Implements `equals`/`hashCode` using **RDF semantic equality** (not segment identity):
  - IRI: lex-equal stringValue.
  - BNode: equal id (which post-URDNA is canonical).
  - Literal: equal lex + equal datatype + equal lang.
- Implements `stringValue()` by lazy UTF-8 materialization; result cached via `SoftReference`.
- Survives connection close only if `detach()` is called — see §5.

This is the contract that lets `ProllyValue` flow into RDF4J's stock SPARQL evaluator for FILTER/OPTIONAL/UNION without ceremony, while our hot loop (BGP joins, range scans) never materializes a String.

### 4.4 Index family

Four mandatory indexes, all 4-column Prolly Maps of `Tuple<int64, int64, int64, int64>` (sign-flipped for lex order via `TypeCodec.binaryParity`):

| Index | Columns       | Purpose                                            |
|-------|---------------|----------------------------------------------------|
| SPOC  | s, p, o, c    | Primary; bulk insert path; S-bound or full scan    |
| POSC  | p, o, s, c    | P- or PO-bound BGPs (most common: `?s rdf:type :T`)|
| OSPC  | o, s, p, c    | O-bound BGPs (`?s :name "Alice"`)                  |
| CSPO  | c, s, p, o    | Named-graph queries                                |

One optional index:

| Index   | Columns           | Purpose                                              |
|---------|-------------------|------------------------------------------------------|
| PT-int  | p, tag, val, o, s | Typed range FILTER push-down (numeric/date literals) |

PT-int is opt-in per predicate, built on first range query and persisted in the manifest as `refs/system/typed-idx/<pid>`.

Each index has its own root in the commit's tuple: `{ spoc, posc, ospc, cspo, dict, prefix, typed-*, term-stats }`.

### 4.5 Lifetime model — GC reachability, not refcount

> **Round-3 correction.** Earlier drafts of this section described `NodeCache.pin/unpin` and a refcount model. That was a misread. The real `NodeCache` (`prolly-port-core/src/main/java/com/dolthub/prolly/NodeCache.java`) is a `LinkedHashMap`-based LRU with no refcount API. The eviction semantics are *map eviction*, not memory free. A `Node` evicted from the cache map remains alive as long as any reachable reference holds it — standard JVM GC.

The actual lifetime chain:

```
ProllyValue ─holds─▶ Node ─holds─▶ MemorySegment (chunk bytes)
       │                  │
       │                  └── Backed by Arena that the NodeStore allocated for the chunk
       │                      (heap-bytes today; off-heap when RocksNodeStore reads into a
       │                       MemorySegment.ofBuffer; check actual NodeStore impl)
       │
       └── Reachable from user code → Node stays GC-rooted → segment stays valid
```

**Implications:**

1. **A long-held `ProllyValue` pins its `Node` against JVM GC** by holding a strong reference. The Node may have been LRU-evicted from the cache *map*; that's just unmaps it — the bytes live as long as something references the Node. Memory pressure is from your ProllyValue retention, not the cache.
2. **There is no NodeCache.pin/unpin API and we do not propose adding one.** It would be redundant with JVM reachability.
3. **The cross-process data-GC race (`Database.gc()`) is still real.** That GC walks reachability from refs and reclaims chunks *from RocksDB on disk*. If a connection's snapshot commit is no longer reachable from any active ref, data GC will reclaim its chunks from disk — but the JVM's in-memory `Node` is unaffected. The danger is when a `Cursor` later tries to descend into a child chunk that's been reclaimed: `NodeStore.read(childHash)` returns `Optional.empty()`. This is fixed by treating open snapshots as GC roots — see §6.7.

**Arena kind: `Arena.ofShared()` by default** for any explicit allocations (e.g., the encoding arena `TermCodec` uses for newly-built terms during `addStatement`). Cross-thread `CloseableIteration` consumption is the supporting reason; `Arena.ofConfined()` would throw `WrongThreadException`. `setSingleThread(true)` opts in to confined for benchmarks.

**`close()` behavior:** the connection has an optional encoding arena (for terms it built but didn't commit). Its `close()` invalidates segments allocated *from that arena*. Segments inside committed `Node`s (the dictionary lookup results) are independent — they live on the chunks the `Node` holds, with JVM-GC lifetime.

So: long-lived `ProllyValue`s from the dictionary path are durable past connection close (the Node reference keeps the chunk alive). Long-lived `ProllyValue`s that wrap a freshly-encoded payload (created via `ValueFactory.createIRI(...)` outside a transaction) need `detach()` before the connection's encoding arena closes.

**This is the single most surprising behavior compared to in-memory RDF4J stores. Document loudly** in `ProllyValue`'s class javadoc and the README "Gotchas" section.

**Arena kind: `Arena.ofShared()` by default.** RDF4J connections regularly serve `CloseableIteration`s consumed from a different thread than the one that opened the connection (async executors, reactive streams). `Arena.ofConfined()` would throw `WrongThreadException` on these. `Arena.ofShared()` is fully thread-safe with a `close()` cost of ~µs (acceptable at connection-close granularity).

A `setSingleThread(true)` Sail config promotes to `Arena.ofConfined()` for callers who genuinely use one thread per connection — measurable but small allocation/close speedup. Default off; this is for benchmarks and embedded uses, not server deployments.

**`close()` behavior:**

- `close()` on the connection releases the arena. Any `ProllyValue` still in user code becomes invalid (Panama throws `IllegalStateException` on access).
- Long-lived `Value`s must call `ValueFactory.detach(Value)` before the connection closes — copies the slice to heap.
- `CloseableIteration.close()` automatically detaches `Value`s that were emitted but the iteration was abandoned mid-stream (rare).

This is the single most surprising behavior compared to in-memory RDF4J stores. **Documented loudly** in `ProllyValue`'s class javadoc and in the README's "Gotchas" section.

### 4.5.1 `ValueLayout` & byte-order rules

Every multi-byte read from an encoded-term payload **MUST** use `ValueLayout.JAVA_*_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN)` (or read byte-at-a-time and reassemble). Reasons:

- Default `ValueLayout.JAVA_LONG` is **native order** (LE on x86/ARM) — opposite of our BE-encoded payload.
- Default `ValueLayout.JAVA_LONG` requires **8-byte alignment** — our packed payloads sit at arbitrary offsets (e.g., `xsd:dateTime` 12-byte payload reads as 6B BE long at offset 0, then 4B int at offset 6 — offset 6 is not 4-aligned).

Canonical constants to define once and reuse:

```java
public static final ValueLayout.OfLong  LE64_U = ValueLayout.JAVA_LONG_UNALIGNED;
public static final ValueLayout.OfLong  BE64_U = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
public static final ValueLayout.OfInt   BE32_U = ValueLayout.JAVA_INT_UNALIGNED .withOrder(ByteOrder.BIG_ENDIAN);
public static final ValueLayout.OfShort BE16_U = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
```

`TupleKey` (the 32-byte `int64×4` index key) is the one place where we control alignment by construction — store it 8-byte aligned and use plain `JAVA_LONG.withOrder(BE)`. Everything inside encoded-term payloads goes through the `_UNALIGNED` variants.

### 4.5.2 Compare semantics

`MemorySegment.mismatch(a, b)` returns the offset of the first differing byte, or `-1` if equal. **It is not an ordering.** To get a comparator:

```java
static int compareUnsigned(MemorySegment a, MemorySegment b) {
    long m = a.mismatch(b);
    if (m < 0)                                   return 0;
    if (m >= a.byteSize())                       return -1;   // a is a prefix of b
    if (m >= b.byteSize())                       return  1;   // b is a prefix of a
    return Byte.compareUnsigned(
        a.get(ValueLayout.JAVA_BYTE, m),
        b.get(ValueLayout.JAVA_BYTE, m));
}
```

This is the *only* sanctioned compare for index keys and encoded terms. Wrong ordering = wrong tree-walk = corrupt commits.

### 4.5.3 SIMD / Vector API

`MemorySegment.mismatch()` is Panama (FFM). SIMD on its hot loop is *opportunistic JIT auto-vectorization*, not guaranteed. If a hot bench shows the compare loop is a bottleneck, lift it to the **Vector API** (`jdk.incubator.vector`, JEP 460+) — a separate incubator from Panama. The two are commonly conflated. Do not promise SIMD via Panama alone in design docs.

### 4.6 Canonicalization (URDNA2015)

The Sail wraps `VersionedQuadStore` with `CanonicalizingQuadStore` at commit time. Reads are not canonicalized — they return whatever was committed.

- **Stage 1 (v2.0):** uses the existing string-based canonicalizer. The Sail allocates `QuadPattern` objects with String forms for the duration of `commit()`. Write-only perf cost.
- **Stage 2 (v2.1):** byte-level canonicalizer operating directly on `MemorySegment` slices. Eliminates the per-commit allocation; brings canon down to ≪ 50 ms for 10k-quad diffs.

Both stages preserve the `CanonicalizingQuadStore.commit()` API; switching is a `Sail` config flag.

## 5. Key invariants

Document these prominently — violating any one of them is a correctness bug.

1. **Byte equality of an encoded term implies semantic equality of the RDF value.** Symmetrically, two terms with the same TermID are semantically equal (modulo the rare collision-extension case, which is internally invisible).
2. **`byteCompare(encode(a), encode(b)) == valueCompare(a, b)`** for any two same-typed values where the type's lex-sort property holds. Test this as a property for every type. **Known exceptions**: `xsd:decimal` across mixed scales (sorts by `(scale, unscaled)`); document at every site that relies on lex order.
3. **Indexes are derived; the dictionary + SPOC are the source of truth.** Any index can be regenerated from SPOC + dictionary. (This is what makes PT-int safe to be optional.)
4. **Canonicalization is commit-time only.** Reads never re-canonicalize. The dictionary stores whatever bytes were committed.
5. **Prefix table is append-only within a branch.** New entries are added; existing entries are never mutated. Merge unions the two prefix tables.
6. **`ProllyValue.Node` reachability keeps the underlying segment alive.** GC-managed, not refcount. A `ProllyValue` derived from the dictionary path holds a `Node` reference; that reference keeps the chunk segment alive past LRU map eviction. A `ProllyValue` wrapping a freshly-encoded payload depends on the encoding `Arena`; close that arena and `detach()` is required.
7. **TermID's top bit is reserved.** Application code may not synthesize TermIDs; only `TermCodec.encode()` produces them.
8. **No string materialization on the hot path.** Asserted by `ArenaAllocationCounter` in benchmarks — heap delta per row must be zero outside of the `Statement` envelope.
9. **Compare semantics for all encoded bytes** go through the unsigned-byte comparator in §4.5.2 — never raw `Arrays.compare` (signed) and never `mismatch` alone (no ordering).
10. **All multi-byte reads from packed payloads** use `_UNALIGNED.withOrder(BIG_ENDIAN)` ValueLayouts (§4.5.1). The 8-byte-aligned native-LE defaults are wrong for our encoding.
11. **`ExtensionTable.nextSlot` is derived from on-disk state at connection open**, never a JVM-local counter — restart safety.

## 5.x JDK floor & Panama maturity

- **Target JDK**: **Java 25 LTS** (bumped from Java 21 on 2026-06-05). Panama / Foreign Function & Memory finalized in JDK 22 (JEP 454), so the preview tax is gone — no `--enable-preview` flag, no `@SuppressWarnings("preview")` clutter, no preview-API churn. (Historical: on the old Java 21 floor it was preview and required `--enable-preview` at compile + runtime.)
- **Vector API status**: incubator across all currently-released JDKs (JEP 460 in 22, 469 in 23, ...). Use `jdk.incubator.vector` explicitly only when a perf bench justifies it; expect minor API breaks at each incubator round.
- **`Arena.ofShared()` cost on close**: the VM `Cleaner`-style machinery used to invalidate shared arenas has measurable wall-time on close (~µs to ms depending on slice count). At connection-close granularity it's invisible; if you ever consider `ofShared` *per query*, profile first.

## 6. Extension points

Where to plug new behavior without forking the architecture:

| Need                              | Extension point                                                |
|-----------------------------------|----------------------------------------------------------------|
| New XSD datatype                  | Allocate a tag in `0x00–0x3F` (fixed) or use `0xE0` (custom)   |
| New IRI prefix scheme             | Add tag in `0x80–0x9F`; update `TermCodec.encodeIRI`           |
| New index ordering                | Add a `QuadIndex` enum value + entry in `IndexPlanner`         |
| Alternative hash function         | Bump format-version byte in manifest; `TermID.hashFn` plugin   |
| Per-chunk dictionary              | Future optimization — wrap `Dictionary` with `ChunkLocalCache` |
| Custom BGP cost model             | Implement `CostModel` interface in `IndexPlanner`              |
| External canonicalizer            | Replace `CanonicalizingQuadStore` via Sail config              |
| Streaming SPARQL push-down        | Implement `EvaluationStrategy` hook in `BgpEvaluator`          |

## 6.5 RDF4J SPI contract details

These are the parts of the Sail SPI that aren't obvious from the layer diagram. Each is a place a reviewer would ask "what's the behavior?" and the answer must be definite.

| Surface                                | Behavior in `ProllySail2`                                                                               |
|----------------------------------------|---------------------------------------------------------------------------------------------------------|
| `getDefaultIsolationLevel()`           | `SNAPSHOT_READ`                                                                                         |
| `getSupportedIsolationLevels()`        | `[NONE, READ_UNCOMMITTED, READ_COMMITTED, SNAPSHOT_READ, SNAPSHOT, SERIALIZABLE]` — all map to SNAPSHOT_READ at runtime; SERIALIZABLE adds CAS-rebase strictness (no phantom rows) |
| `getStatements(..., includeInferred)`  | The flag is ignored — only asserted statements are returned. Inferencer wrappers (e.g. `ForwardChainingRDFSInferencer`) layer reasoning on top |
| `hasStatement(s,p,o,ctx)`              | Direct cursor seek + `hasNext` — does **not** materialize a `Statement`. Constant-time after index seek |
| `size(Resource... contexts)`           | O(1) via a manifest-pinned counter at `refs/system/size`. Counters updated at commit; per-context counters in `refs/system/size-by-context/<ctxID>` lazy-built |
| `setNamespace`/`getNamespace`/`clear`  | **Distinct from the internal IRI prefix table.** Backed by `refs/system/sparql-namespaces` (a Prolly Map `String → String`). Used by SPARQL parsers for query-text prefix expansion only; never touched by `TermCodec` |
| `Resource... contexts` on read, null array | Empty array means "any context" (i.e., no filter on c). `new Resource[]{null}` means "default graph only" (c == DEFAULT_GRAPH_ID). RDF4J convention — must match exactly to pass the conformance suite |
| `Resource... contexts` on add, null array | Default graph (`c = DEFAULT_GRAPH_ID`). Multiple contexts = the statement is added to each (N rows) |
| `initialize()` / `shutDown()`          | Both idempotent; second call is a no-op. `initialize()` after `shutDown()` is undefined (throw) |
| `prepareUpdate(...)`                   | Inherited from `AbstractSail`; SPARQL Update operations route through `addStatement`/`removeStatement` on our Connection — no separate path needed |

## 6.6 Transaction semantics

| Concern                              | Behavior                                                                                            |
|--------------------------------------|-----------------------------------------------------------------------------------------------------|
| Add/remove ordering in one tx        | Mutation buffer is **sequence-ordered**. Final state = fold over `[op_0, op_1, ...]` against the snapshot. `add(A); remove(A); add(A)` → final state contains A |
| Idempotent add                       | `add(A)` when A already in the snapshot or earlier in the buffer is a no-op at commit time |
| Idempotent remove                    | `remove(A)` when A not present is a no-op (no error) |
| Max transaction size                 | Soft default: 10 M operations. Above this, `commit()` throws `SailException("transaction too large; split commits or raise sail.maxTxSize")`. Spill-to-disk is **not** v2.0 — split your transaction |
| Commit atomicity                     | The manifest CAS is the **only** atomic point. Tree mutations write new roots but nothing references them until the manifest flip. Crashes mid-commit leave orphan trees that GC reclaims |
| Long-running canon vs CAS window     | Canon runs **before** the CAS. Other writers can commit while canon is running (the CAS will then fail and trigger rebase). This means canon never blocks the manifest |

## 6.7 GC + snapshot interaction

`Database.gc()` walks reachability from the active refs (`refs/heads/*`, `refs/tags/*`). A connection's open snapshot may point at a commit that has been **rebased past** by other writers — the snapshot's commit is no longer under any user-visible ref, but the connection still needs it.

**Solution:** open snapshots are **first-class GC roots**. The `Database` maintains an in-process `Set<CommitHash> activeSnapshots` keyed by `Snapshot` objects (one per open connection). GC walks union(refs, activeSnapshots). When a connection closes, its snapshot is removed from the set.

For cross-process correctness (multiple JVMs sharing a RocksDB store), additionally write a `refs/_snapshots/<jvm-id>/<conn-id>` ref on connection open (TTL-keyed; expired entries reaped on next GC). Without this, a second-JVM GC could reclaim a first-JVM snapshot's commit. This is **the** subtle multi-process bug in versioned content-addressed stores.

## 7. Non-goals (v2.0)

- **OWL reasoning** — delegate to RDF4J's `ForwardChainingRDFSInferencer` wrappers as today.
- **SPARQL federation** — deferred to v2.2; today's behavior (single Sail per query) is preserved.
- **Wire compatibility with other triple stores** (Jena, Stardog, Virtuoso) — we are our own format.
- **Property paths cost optimization** — they work via RDF4J's evaluator over our scans; specialized handling is future work.
- **Streaming / out-of-core query execution** — query results materialize through `CloseableIteration` as today; truly massive result sets are a future concern.
- **Wide-column / quad attribute storage** beyond context — every "extra" attribute is a separate triple.

## 8. Performance budgets

Design targets (JMH bars; **not wired as a CI job in this repo** — the gated build runs tests, not benches):

| Operation                | Cold target       | Warm target          | Notes                          |
|--------------------------|-------------------|----------------------|--------------------------------|
| `addStatement` buffered  | ≥ 30 k triples/s  | ≥ 250 k triples/s    | warm = repeat predicate IRIs   |
| `commit` of 100k triples | < 1500 ms         | < 800 ms             | canon Stage 1; S2 in Phase 7   |
| Point query (S-bound)    | < 80 µs           | < 12 µs              |                                |
| Point query (O-bound)    | < 120 µs          | < 18 µs              | OSPC                           |
| Range scan sequential    | ≥ 800 k triples/s | ≥ 2 M triples/s      | NodeCache warmed after first chunk |
| Typed-range FILTER       | n/a               | ≥ 1 M rows/s         | Phase 6; PT-int present        |
| URDNA2015 10k diff       | < 200 ms          | < 200 ms (S1)        | / < 40 ms (S2) — cache-independent |
| Disk per 1 B triples     | < 14 GB compressed | —                   | see Tuple-overhead caveat below |
| Heap footprint           | < 512 MB          | < 512 MB             | with `Arena.ofShared`          |

**Cold/warm**: cold = NodeCache misses on every chunk fetch (fresh-open worst case); warm = working set resident. Bench reports both. Cold targets are roughly 8× looser than warm — a realistic ratio given chunk-fetch + decompress dominates cold.

**Tuple-overhead caveat for disk budget**: 14 GB/1 B triples assumes the underlying `Tuple` representation in `prolly-port-core` elides per-slot offsets when the descriptor is all-fixed-width (`int64×4` for indexes; 32B raw, 32B-or-thereabouts on disk after leaf framing). If offsets are not elided today, the per-row tail metadata (~10B) pushes the figure to ~24 GB. Phase 0 verifies which holds and either: (a) updates the budget, or (b) opens a `prolly-port-core` task to add a narrow-tuple variant. **This single decision dominates the disk budget; do not skip it.**

~~Any miss fails the CI bench job~~ — no bench CI job exists here; treat the table as the budget to measure against, not an enforced gate (2026-07-16 correction).

## 8.4 Observability — ~~`SailMetrics`~~ Micrometer (superseded)

> **Superseded (ADR-0041).** The bespoke `SailMetrics` SPI this section designed was replaced by **native Micrometer instrumentation** — the Sail records meters against a `MeterRegistry` directly (12 classes instrument it today), so metrics land in whatever backend the embedder wires (Prometheus, OTLP, JMX) with no prolly-specific glue. See the module README's Observability section for the rationale. The counter/duration NAMES below survive approximately as meter names; the SPI, `noop()`, and `InMemorySailMetrics` do not exist.

### Counters (current set)

```
sail.add | sail.remove | sail.get | sail.commit | sail.rollback
index.{spoc|posc|ospc|cspo}.insert        <- dotted meter NAME
index.{...}.delete                        <- dotted meter NAME
index          tag name={spoc|...}.scan.examined  /  .scan.emitted
planner.choice tag choice={ORDER}.prefix{0|1|2|3}
```

**The last two are meter name + TAG, not dotted names, and the difference bites.**
`insertMetricKey`/`deleteMetricKey` really are the dotted strings above
(`QuadOrder.java:46-49`), but the scan counters are built as
`counter("index", "name", order + ".scan.examined")`
(`ProllySailConnection.java:1036-1041`) and the planner's as
`counter("planner.choice", "choice", order + ".prefix" + n)` (`IndexPlanner.java:92-93`).
A test that looks up `"index.spoc.scan.examined"` finds no meter, and the usual
`counter == null ? 0d` helper turns that into a passing assertion against nothing. Use
`registry.find("index").tag("name", "spoc.scan.examined")`.

**The scan counters publish only on exhaustion.** The increments sit after the `while` loop in
`filterByLogical`'s `hasNext()`, and closing the iteration does not drain it — so a `LIMIT` or an
early `break` records nothing. Drain before asserting.

### Durations (nanoseconds, with sample counts)

```
sail.commit.dict
sail.commit.indexes
sail.commit.prefixes
sail.commit.namespaces
sail.commit.stats
sail.commit.total
```

### Wiring

- `ProllySail(NodeStore, BufferPool)` defaults to `SailMetrics.noop()`.
- `ProllySail(NodeStore, BufferPool, SailMetrics)` injects a recorder.
- `IndexPlanner` records the chosen `QuadOrder` + prefix length per `choose()` call.
- `ProllySailConnection` records per-call counters and commit-stage durations.

### Use cases

- **Debug what the planner is doing**: enable `InMemorySailMetrics`, run a query, dump counters — see exactly which index served each pattern.
- **Microbenchmark commit phases**: run a write workload, read `sail.commit.dict` vs `sail.commit.indexes` to see which table dominates commit cost.
- **Scan-efficiency check**: `index{name=X.scan.examined}` ÷ `index{name=X.scan.emitted}` is the post-filter waste ratio for a query pattern. High ratios → planner picked the wrong index or post-filter is doing too much work. This is not hypothetical: it is how the dropped-context defect was found and how the fix is pinned — a graph-scoped read used to report `spoc.scan.examined` equal to the whole store for a handful emitted, and now reports `cspo.scan.examined` equal to the rows in that graph (`ProllySailContextPushdownTest`).

### Future extension points

Term-layer instrumentation lands in a later iter — Dictionary `encode.hit/insert/collision` counters, scan-result-counts at the leaf level, and per-table chunk-cache hit-rate need a Dictionary/SpocIndex change to thread metrics through. The SPI shape stays as-is; only the call sites grow.

## 8.5 Engine-core prerequisites — DELIVERED

> **All delivered (2026-07-16 check).** `Node.getKeySegment` / `Node.getValueSegment` exist in the engine core (`dolthub-java-port`, the prolly-core repo), and the atomic multi-root commit shipped via the recommended meta-tree path — `RootMetaTree` / `RootMetaTreeStore` in this module's `sail/`. The table below is the historical ask.

| Prereq                                  | What                                                                                          | Justification                                                |
|-----------------------------------------|-----------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| `Node.getKeySegment(int) → MemorySegment` | New zero-copy accessor that returns `chunk.asSlice(off, len)` instead of `byte[]`             | Today `Node.getKey()` allocates per row — the zero-copy story has a hole at the bottom of the stack |
| `Node.getValueSegment(int) → MemorySegment` | Same for value column                                                                       | Same reason; needed for SPOC value column (`Empty`) and dict value column (encoded term) |
| `Database.commit` overload for multi-root | `commit(branch, Map<String, StaticMap> rootsByName, byte[] expectedParent, ...)`               | Today commits one StaticMap; we need to advance 4 indexes + dict + stats + size + namespaces atomically. Either extend the API or wrap as a "meta-tree" (single StaticMap with `name → rootHash` rows). The meta-tree path needs no API change — recommended |
| `Off-heap MutableMap variant`             | `MutableMap` today is `TreeMap<MemorySegment, MemorySegment>` (heap). Add an off-heap alternative or accept the 100MB-per-million-ops heap cost | Heap budget is < 512 MB; a 1M-op transaction with 4 indexes blows 500MB just in TreeMap entries |

The recommended path is to **use the meta-tree wrapper** to avoid a `Database` API change, **add the zero-copy `Node` accessors** in a small `prolly-port-core` PR, and **accept the heap MutableMap for v2.0** with `sail.maxTxSize` defaulted lower (1M ops, not 10M) until an off-heap variant lands.

Track these as P0 in Phase 1; without them, Phase 2 cannot meet its DoD.

## 9. Risks & open questions

(The live risk register was `plans/RISKS.md`, now in the private monorepo's work tracker.) Headline risks as designed:

1. **`Value.equals` heterogeneity** — RDF4J consumers will mix `ProllyValue` with `SimpleValue`. Equals must be symmetric across impls; property-tested.
2. **Tuple 64 KB ceiling** — overflow path stores oversize terms as `BytesAddr` blobs out-of-line.
3. **URDNA2015 Stage 2 risk** — byte-level canonicalizer is a non-trivial rewrite of `prolly-urdna2015`; gated behind a feature flag.
4. **`findLCA` criss-cross limitation** — inherited from prolly-port-core; flag in release notes.
5. **Cross-language compatibility** — Go port (per top-level `TODO`) must read v2 format. Spec must be reviewed by the Dolt-port author before Phase 0 sign-off.

## 10. Glossary

- **TermID** — 64-bit content hash of an encoded RDF term; primary key in the dictionary; column type in every index.
- **Encoded term** — `[tag : u8] [payload : variable]`. The byte representation stored in the dictionary's value column.
- **Dictionary** — Prolly Map `TermID → encoded-term-bytes`, anchored at `refs/system/dictionary` in the manifest.
- **Prefix table** — Manifest-pinned `u32 → bytes` map of registered IRI prefixes.
- **Tag byte** — The leading byte of an encoded term; classifies kind and selects variant.
- **`ProllyValue`** — Zero-copy `MemorySegment`-backed implementation of RDF4J's `Value` SPI.
- **Arena** — Panama `Arena.ofConfined` bound to a `SailConnection`'s lifetime.
- **BGP** — Basic Graph Pattern; a join of triple/quad patterns. Routed to `GraphPatternEngine`.
- **SPOC / POSC / OSPC / CSPO** — Mandatory four-column index orderings.
- **PT-int** — Optional typed-range index for numeric/temporal literals.
- **Detach** — Promote a `ProllyValue` from arena-backed to heap-backed for survival past `close()`.
- **Stage 1 / Stage 2** — URDNA2015 string-level vs byte-level canonicalizer modes.

---

## 11. How to read this repo (as shipped, 2026-07-16)

```
prolly-rdf (the ring repo)
├── prolly-codec/…/rdf4j/term/    TermCodec, Dictionary, TermId, Fnv1a64 — the extracted term layer
├── prolly-rdf/…/semantic/        GraphPatternEngine, LeapfrogTriejoin, the versioned quad store
└── prolly-rdf4j/
    ├── ARCHITECTURE.md           ← you are here
    ├── docs/                     design docs (see docs/README.md) + 73 ADRs under docs/adr/
    └── src/main/java/com/earasoft/prolly/rdf4j/
        ├── term/    PrefixTable, TermStats (the rest moved to prolly-codec)
        ├── value/   ProllyIRI, ProllyBNode, ProllyLiteral, ProllyTriple, ProllyValueFactory
        ├── index/   IndexPlanner, the four QuadIndex permutations
        └── sail/    ProllySail, ProllySailConnection, RootMetaTree(Store), sync/…
```

The phase plans this section once pointed at live in the private monorepo's work tracker; the ADRs under [`docs/adr/`](docs/adr/) are the decision record that survived into this repo.
