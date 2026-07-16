
# Canonicalization invariants

RDF defines several literals as *equal under a normalization* rather than byte-for-byte:
`"x"@en-US` and `"x"@en-us` are the same term; `"1"^^xsd:integer` and `"01"^^xsd:integer`
are the same value. A **content-addressed** store must collapse each equality class to a
single canonical byte string, or one logical term acquires two `TermId`s — and then dedup
fails, a phantom duplicate triple appears, and two logically identical graphs produce
different root hashes.

The defining property: **content-addressing must match RDF term identity as RDF4J implements
it.** That identity (RDF 1.1 Concepts §3.3, *Literals*) is:

> "Two literals are term-equal (the same RDF literal) if and only if the two lexical forms,
> the two datatype IRIs, and the two language tags (if any) compare equal, **character by
> character**."

Two riders make the language-tag component *not* a pure char-by-char compare in practice:

> "Lexical representations of language tags MAY be converted to lower case. The value space
> of language tags is always in lower case."

So a `TermId` must be assigned per (lexical form, datatype IRI, **lower-cased** language tag).
That single normalization is what `CANON-*` below preserves; everything else is char-by-char
(see [`lexical-fidelity.md`](lexical-fidelity.md) for the over-merge bugs that *used to* fold
*more* than the spec allows — resolved 2026-06-12, term-faithful).

---

## `CANON-LANG-1` — language-tag case

| Field | Value |
|---|---|
| **Spec** | RDF 1.1 Concepts §3.3. Strict term equality is char-by-char on *all three* components — so by that clause alone `"x"@en-US` ≠ `"x"@en-us`. But the spec also fixes **"the value space of language tags is always in lower case"** and permits **"lexical representations … MAY be converted to lower case."** RDF4J's `Literal.equals` folds tag case accordingly (`createLiteral("h","en-US").equals(createLiteral("h","en-us"))` → `true`, measured). The Sail must match RDF4J's identity, so the tag is lower-cased. |
| **Invariant** | Two `rdf:langString` literals with the same lexical form and language tags differing **only in case** are the same value (lower-case value space) and are treated as the same term by RDF4J — so they MUST encode to identical bytes (one `TermId`). |
| **Port behavior** | `TermCodec.encodeLangString` lower-cases the tag (`Locale.ROOT`, to dodge the Turkish-i trap) before writing it — `prolly-codec/.../term/TermCodec.java:712`. This is the single point every langString flows through (the RDF→codec dispatcher `TermEncoder`, the dictionary, direct callers). |
| **Validated by** | `TermEncoderTest.language_tag_case_is_canonicalized_so_equal_literals_encode_identically` — `prolly-codec/.../term/TermEncoderTest.java:84`. It asserts `encode("hello"@en-US)` is **byte-identical** to `encode("hello"@en-us)`, via the real RDF4J `Value` path, and that the canonical bytes are the lower-cased tag. |
| **W3C-visible?** | **No.** See below — this is the catalog's motivating example. |

### Why the W3C suite is blind to this one

This was wrong for the entire life of the codec, and the 261-test W3C SPARQL 1.1 suite
(`prolly-rdf4j-compliance`) passed the whole time. Three layered reasons, in order of
decisiveness:

1. **The oracle erases the dimension the bug lives in.** The suite compares results with
   RDF term equality. RDF4J's own `Literal.equals` is case-insensitive on the tag —
   verified directly: `SimpleValueFactory.createLiteral("hello","en-US")
   .equals(createLiteral("hello","en-us"))` returns `true`. The result-set / graph
   isomorphism comparison is built on that equality, so it **cannot see a tag-case
   difference in either direction.** Proof: lower-casing every tag in the store changed
   **0 of 261** results.
2. **No fixture builds the collision.** The duplicate `TermId` only manifests when both
   case-variants live in one dataset; real fixtures use one consistent casing per tag.
3. **It's an identity invariant, not an answer.** "One term → one `TermId`" is a property
   of the canonical representation, which result-comparison never asserts.

### The trap: why the *obvious* fix-it test is also fooled

A natural validating test would be Sail-level: insert `"x"@en-US` and `"x"@en-us` under
the same subject/predicate, then assert the store holds **one** statement. **This test
passes even with the bug present** — because `Statement.equals` inherits the same
case-insensitive `Value.equals`, so a `HashSet<Statement>` (or any RDF4J statement
dedup) collapses the two to one *regardless of how many `TermId`s the store created
underneath*. The confound is the same one that blinds the W3C suite, one layer up.

The confound-free instrument is **byte identity at the codec** (same bytes ⇒ same
content-address ⇒ same `TermId`, by construction) — which is exactly what
`CANON-LANG-1`'s validating test asserts. The lesson generalizes to every `CANON-*`
row: **validate canonicalization by byte identity of the encoder, never by counting
RDF terms with an RDF-equality-based collection.**

### Decode side

Decoding returns the lower-cased tag (it returns what was stored). A round-trip of
`"x"@en-US` yields `"x"@en-us`. This is RDF-correct: the two are the same term, and
lower-case is RDF 1.1's comparison form. The two pre-existing round-trip tests in
`TermCodecStringsTest` that asserted the tag came back **as-given** (`en-US`,
`zh-Hant-CN-x-private-tag-extra`) were pinning the *buggy* case-preserving behavior;
they were corrected to expect the canonical lower-cased form when `CANON-LANG-1` landed.

---

## What RDF does *not* require canonicalized (retraction — corrected 2026-06-04)

> **Retraction in place.** An earlier version of this file listed `CANON-INT-1`,
> `CANON-DEC-1`, `CANON-DT-1`, `CANON-FLOAT-1` — value-equal lexical forms like
> `"1"`/`"01"` (`xsd:integer`) or `"1.0"`/`"1.00"` (`xsd:decimal`) — as *desired*
> canonicalization invariants "holding by construction." **That was wrong, in two
> ways**, and a Sail-level probe proved it. The rows are withdrawn and replaced by the
> finding below.

The error was conflating **value equality** with **term equality**. RDF 1.1 literal
*term* equality (the identity content-addressing must preserve) is **lexical**: lexical
form and datatype IRI compared character-by-character, language tag compared lower-cased
(`CANON-LANG-1`, above). The spec is explicit that *"two literals can have the same value
without being equal,"* citing exactly `"1"^^xsd:integer` vs `"01"^^xsd:integer`. So:

- `"1"` and `"01"` are **different RDF terms.** RDF requires NO numeric/temporal
  canonicalization. The port **used to** collapse them — an **over-merge** (data loss): two
  distinct terms became one and the lexical form changed on round-trip. **RESOLVED 2026-06-12**
  (term-faithful, ADR-0043): all typed literals now store the verbatim lexical form, so `"1"` and
  `"01"` stay distinct. (See [`lexical-fidelity.md`](lexical-fidelity.md).)
- The old codec was *inconsistent* — `xsd:decimal` was *partly* faithful (it kept trailing-zero
  scale, `"1.0"`≠`"1.00"`, but over-merged leading-zero/`+`/bare-dot), while `xsd:integer`/`boolean`/
  `double`/`dateTime` over-merged outright — which was itself the tell that the value-canonicalization
  was accidental, not designed. The fix made the whole codec consistently verbatim-lexical (decimal
  included).

The only canonicalizations RDF 1.1 actually requires for term identity are **`CANON-LANG-1`**
(language-tag case) and the **simple-literal ≡ `xsd:string`** equivalence (a bare `"foo"`
*is* `"foo"^^xsd:string` — the one case where merging is correct; the probe confirms the
port does this).

The over-merge finding — which datatypes lost the lexical form, the RDF rule each violated, and
the decision (term-faithful, ADR-0043, now **implemented**) — lives in
[`lexical-fidelity.md`](lexical-fidelity.md). It was the "same class as `CANON-LANG-1`, opposite
direction" bug; the W3C suites were blind to it for the same reason — though the fix turned out
W3C-positive anyway (it un-baselined `TZ()`/`TIMEZONE()`/`tsv03`, raising query conformance 171→174/176).

## Where this lives

- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermCodec.java` — `encodeLangString` (the canonicalization point) + the numeric/temporal encoders.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermEncoder.java` — the RDF4J `Value` → `TermCodec` dispatcher.
- `prolly-codec/src/test/java/com/earasoft/prolly/rdf4j/term/TermEncoderTest.java` — `CANON-LANG-1`'s validating test.
- `prolly-codec/src/test/java/com/earasoft/prolly/rdf4j/term/TermCodecBoundaryTest.java` — the NaN-boundary / Int48 range pins.
- `prolly-rdf4j-compliance/src/test/java/com/earasoft/prolly/rdf4j/compliance/` — the W3C SPARQL 1.1 suites that are blind to these invariants.
