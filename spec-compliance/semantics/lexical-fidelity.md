
# Lexical fidelity (the over-merge finding) — RESOLVED

> **RESOLVED 2026-06-12 (ADR-0043, term-faithful Option A).** Every typed literal now stores its
> **verbatim lexical form** + exact datatype IRI; `LEXFID-1` (and the datatype-axis siblings in
> [`datatype-identity.md`](datatype-identity.md)) no longer hold. The governing invariant
> `bytesEqual(encode a, encode b) ⟺ a.equals(b)` is proven across the entire datatype space by the
> now-**green** acceptance gate (`TermFaithfulnessGateTest` + `TermIdentityBijectionTest.no_over_merge`),
> and the fix **raised** W3C SPARQL conformance to 174/176 query + 90/90 update (it un-baselined
> `TZ()`/`TIMEZONE()`/`tsv03` — see [`conformance-frontier.md`](../../prolly-rdf4j-compliance/docs/conformance-frontier.md)).
> This document is kept as the **record of the finding and its resolution** — the over-merge it
> describes is history, flipped in place below.

This is the sibling of [`canonicalization.md`](canonicalization.md)'s `CANON-LANG-1`:
**same class, opposite direction.** langString was *under*-merge (one term, two byte
strings). The finding here was *over*-merge — the codec **used to** store some typed literals by
**value** and discard the **lexical form**, so RDF-distinct terms collapsed to one
`TermId` and the lexical form silently changed on round-trip. Term-faithful storage fixed it.

## The rule being violated

RDF 1.1 Concepts §3.3 defines literal *term* equality char-by-char:

> "Two literals are term-equal (the same RDF literal) if and only if the two lexical forms,
> the two datatype IRIs, and the two language tags (if any) compare equal, **character by
> character**."

and uses exactly the integer case as its worked example — `"1"^^xsd:integer` and
`"01"^^xsd:integer` denote the same *value* but **"are not term-equal because their lexical
form differs."** (The one component that is *not* a pure char-by-char compare is the language
tag, whose value space §3.3 fixes as lower-case — see [`canonicalization.md`](canonicalization.md).)
A store of RDF terms — which a content-addressed Sail is — must therefore keep
value-equal-but-lexically-distinct literals **distinct**. RDF4J's own Sails (MemoryStore,
NativeStore) do exactly that.

## `LEXFID-1` — value-canonicalization drops the lexical form

| Field | Value |
|---|---|
| **Spec** | RDF 1.1 Concepts §3.3 — term equality is by lexical form (char-by-char), not by value. |
| **Invariant (RDF-faithful reading)** | Two literals with the same datatype and the **same value but different lexical form** are different terms → must encode to **different** bytes, and each must round-trip to its **own** lexical form. |
| **Port behavior** | **RESOLVED (term-faithful, ADR-0043).** Every typed literal now stores its **verbatim lexical bytes** + exact datatype IRI: the built-in tags (`encodeBoolean`/`encodeInteger`/`encodeFloat*`/`encodeDateTime`/temporal/`encodeDecimal`/`encodeBase64Binary`/`encodeHexBinary`) became `encodeTaggedUtf8(tag, lexical)`, and datatypes without a faithful tag (custom, the derived integers, `xsd:duration`, `gMonth`, …) route through the Dictionary-backed custom path (`DictionaryTermEncoder` → `encodeCustomLiteral`). `xsd:decimal` is now **fully** faithful (leading-zero/`+`/bare-dot all distinct). Simple-literal ≡ `xsd:string` merging stays **correct** (RDF 1.1 §3.3). *(Was: violated for integer/boolean/double/float/temporal, partly decimal — value-decode-then-re-encode.)* |
| **Validated by** | The now-**green acceptance gate**: `TermFaithfulnessGateTest` (exhaustive round-trip over every datatype + a `tries=2000` jqwik bijection property) **and** `TermIdentityBijectionTest.no_over_merge` — both un-`@Disabled` 2026-06-12. `LexicalFidelityCharacterizationTest` flipped from "pins a divergence" to a faithful regression test (its `assertEquals(1,…)` over-merge counts became `2`). End-to-end via SPARQL by `TermFaithfulSparqlTest`. |
| **W3C-visible?** | **Was no** (the result oracle compares by an equality that erases the lexical dimension; fixtures use canonical forms — compliance-green proved nothing about lexical fidelity). But the fix turned out to be W3C-**positive** anyway: it un-baselined `TZ()`/`TIMEZONE()` (the tz-absent temporal distinction) and `tsv03` (a custom-datatype load), raising query conformance 171→174/176. |

### Grounded probe (Sail-level, 2026-06-04) — the bug, since fixed (2026-06-12)

Two value-equal, lexically-distinct literals inserted under one `<s> <p>`, then read back.
"Over-merged" = the store kept **one** (lexical form lost); "faithful" = kept **two**. The
**Then** column is the original 2026-06-04 measurement (the bug); **Now** is post-ADR-0043 — every
over-merge row is fixed (the store keeps both), pinned by `LexicalFidelityCharacterizationTest`
(flipped to faithful) and the exhaustive `TermFaithfulnessGateTest`.

| Datatype | Inputs | Stored (then) | Then → Now |
|---|---|---|---|
| `xsd:integer` | `"1"`, `"01"` | `["1"]` | over-merge → **faithful ✓** (`["1","01"]`) |
| `xsd:integer` | `"1"`, `"+1"` | `["1"]` | over-merge → **faithful ✓** |
| `xsd:boolean` | `"true"`, `"1"` | `["true"]` | over-merge → **faithful ✓** |
| `xsd:double` | `"1.0E0"`, `"1.0e0"` | `["1.0"]` | over-merge → **faithful ✓** |
| `xsd:dateTime` | `"…00:00Z"`, `"…00:00+00:00"` | `["…00:00Z"]` | over-merge → **faithful ✓** |
| `xsd:dateTime` | `"…00Z"`, `"…00.000Z"` | `["…00Z"]` | over-merge → **faithful ✓** |
| `xsd:decimal` | `"1.0"`, `"1.00"` | `["1.0","1.00"]` | trailing-zero scale kept ✓ (always was) |
| `xsd:decimal` | `"1.0"`, `"01.0"` / `"0.5"`, `".5"` | one | over-merge → **faithful ✓** (now fully faithful) |
| simple vs typed | `"foo"`, `"foo"^^xsd:string` | `["foo"]` | correct ✓ — one term, RDF 1.1 (unchanged) |

This is not confounded the way the langString count was: the two inputs have **different
labels**, so `Value.equals`/`Statement.equals` do *not* collapse them — a `Set<Statement>`
keeps them apart. The single stored value is therefore genuine over-merge, not a
measurement artifact.

### Two distinct harms

1. **Data loss / wrong cardinality.** A graph that legitimately contains both
   `<s> <p> "1"^^xsd:integer` and `<s> <p> "01"^^xsd:integer` (two triples) is stored as
   one. Diff, merge, and commit hashes all see one triple where RDF says two.
2. **Lossy round-trip.** Commit `"01"^^xsd:integer`, read it back, get `"1"`. For a
   *version-control-for-RDF* system this is the sharper harm: checkout does not return what
   was committed. (Company context: a regulatory / R&D-infrastructure tool that silently
   rewrites committed literals is a hard sell.)

## The decision — [ADR-0043](../../prolly-rdf4j/docs/adr/0043-literal-lexical-fidelity.md): term-faithful (Option A), DECIDED + IMPLEMENTED

This was **not** an obvious bug fix — value-canonicalization buys something real and the choice touches
the on-disk format — which is why it went through ADR-0043 rather than a quick patch. The deciding
measurement: the one serious objection, value-ordered indexes, is **not load-bearing** in this
architecture (`ORDER BY`/range run above the Sail; the triejoin needs only a *consistent* order, which
lexical byte-order provides — re-confirmed 2026-06-12: no Calcite/SQL surface, no `StatementOrder` SPI,
gRPC-sync copies by content-hash). **Option A (term-faithful) was chosen and implemented** across the
campaign; the index-ordering cost was accepted (a separate value index stays out of scope — ADR-0043
Option C). The comparison that drove it:

| | **A — term-faithful** (store the verbatim lexical form, like `xsd:string` does) | **B — value-canonical** (canonicalize all, consistently) |
|---|---|---|
| RDF 1.1 term equality | ✓ correct | ✗ violates (over-merges) |
| Round-trip fidelity | ✓ lossless | ✗ lossy |
| Matches RDF4J Sail contract | ✓ | ✗ |
| Dictionary size / cross-version dedup | larger (no value-dedup) | smaller |
| **Index ordering** (the real cost) | byte order ≠ value order for numerics → `ORDER BY`/range filters can't ride the SPOC byte order; need value-aware comparison or a value index | byte order = value order → range/order "for free" *(consequence to confirm against the query path, not yet traced)* |
| Consistency today | — | already inconsistent (decimal partly faithful; int/long/etc. datatype-faithful) |

**Outcome (the recommendation, now decided + shipped):** **term-faithful (A)** was adopted — the
correctness-preserving default for a version-control-for-RDF product (lossy round-trip is a poor property
for a system whose job is to return exactly what was committed). The index-ordering cost was weighed and
accepted; a separate value index (ADR-0043 Option C) remains the escape hatch if value-order range scans
are ever needed. This catalog records the tradeoff made deliberately — and the result: the over-merge is
gone, the bijection gate is green across every datatype, and W3C conformance *rose* (171→174/176 query).

## Where this lives

- `prolly-codec/.../term/TermCodec.java` — the now verbatim-lexical `encodeBoolean`/`encodeInteger`/`encodeFloat*`/`encodeDateTime`/temporal/`encodeDecimal`/`encodeBase64Binary`/`encodeHexBinary` (each `encodeTaggedUtf8(tag, lexical)`), the shared `decodeLexical`, and `encodeCustomLiteral` (the long-tail path).
- `prolly-codec/.../term/TermEncoder.java` — the `Value`→bytes dispatch + `isDedicatedDatatype` (the faithful-tag-vs-custom boundary).
- `prolly-rdf4j/.../value/DictionaryTermEncoder.java` + `DictionaryTermResolver.java` — the Dictionary-backed custom-datatype write/read path (datatype IRI interned as a `TermId`).
- `prolly-rdf4j/.../sail/TermFaithfulnessGateTest.java` — **the** acceptance gate (exhaustive round-trip + `tries=2000` bijection), green.
- `prolly-codec/.../term/TermIdentityBijectionTest.java` — `bytesEqual ⟺ a.equals(b)`, both halves green.
- `prolly-rdf4j/.../sail/LexicalFidelityCharacterizationTest.java` — flipped from divergence-pin to faithful regression test.
- `prolly-rdf4j-compliance/docs/conformance-frontier.md` — the W3C suites were blind to lexical fidelity, but the fix raised them anyway (`TZ()`/`TIMEZONE()`/`tsv03` un-baselined, query 171→174/176).
