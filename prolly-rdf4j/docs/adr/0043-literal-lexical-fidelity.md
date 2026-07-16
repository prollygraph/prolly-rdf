
# ADR-0043: Literal lexical fidelity

## Status

**Accepted, 2026-06-04 — Option A (term-faithful).** Decided by the governing invariant: the
content address must not over- or under-merge (`bytesEqual(encode a, encode b)` ⟺ `a` and `b`
are the same RDF term). That invariant is non-negotiable against efficiency, and it rules out
Option B (which over-merges by design). Findings catalogued in
[`spec-compliance/semantics/lexical-fidelity.md`](../../../spec-compliance/semantics/lexical-fidelity.md)
(`LEXFID-1`); current divergence pinned by `LexicalFidelityCharacterizationTest`; the
executable acceptance gate is `TermIdentityBijectionTest` (forward direction, `@Disabled` until
the fix lands). Implementation: `plans/literal-lexical-fidelity.md`.

## Context

RDF 1.1 literal **term** equality — the identity a content-addressed RDF store must preserve
— is **lexical**: lexical form and datatype IRI compared character-by-character, language tag
compared lower-cased. The spec states outright that *"two literals can have the same value
without being equal,"* citing `"1"^^xsd:integer` vs `"01"^^xsd:integer`. RDF4J's own Sails
(MemoryStore, NativeStore) preserve the lexical form accordingly.

The port's codec does not, for several datatypes. A Sail-level probe (2026-06-04) measured:

| Datatype | Inputs (same value, different lexical form) | Stored | |
|---|---|---|---|
| `xsd:integer` | `"1"`, `"01"` / `"1"`, `"+1"` | `"1"` | over-merged |
| `xsd:boolean` | `"true"`, `"1"` | `"true"` | over-merged |
| `xsd:double`/`float` | `"1.0E0"`, `"1.0e0"` | `"1.0"` | over-merged |
| `xsd:dateTime` (+ temporal) | `"…Z"`, `"…+00:00"`; fractional seconds | epoch | over-merged |
| `xsd:decimal` | `"1.0"`/`"1.00"` kept; `"1.0"`/`"01.0"`, `"0.5"`/`".5"` merged | partial | **only partly faithful** (BigDecimal: keeps trailing-zero scale, folds leading-zero/`+`/bare-dot) |
| simple vs `xsd:string` | `"foo"`, `"foo"^^xsd:string` | one | **correct** (RDF 1.1) |

So value-equal-but-lexically-distinct literals — which RDF says are **different terms** —
collapse to one `TermId`. Two harms: **wrong cardinality** (a graph with both `"1"` and
`"01"` stores one triple, not two) and **lossy round-trip** (commit `"01"`, read back `"1"`).
For a version-control-for-RDF product, lossy round-trip is the sharper harm — checkout does
not return what was committed.

Why decide now: the langString case-canonicalization fix (the *under*-merge sibling) surfaced
the whole equality-vs-bytes question; this is its *over*-merge counterpart. The W3C SPARQL
suites are blind to it (the result oracle erases the lexical dimension), so it will not
self-correct. And the codec is currently **inconsistent** (decimal partly faithful, the rest not),
which is the tell that the value-canonicalization was accreted, not designed. This touches
the on-disk term encoding → ADR-worthy.

### The one serious objection, measured

The intuitive cost of preserving lexical form: numeric byte-order would no longer equal
numeric value-order, so an index couldn't serve `ORDER BY ?n` / range filters directly. I
traced the evaluation path before writing this:

- `SailConnectionTripleSource` is a plain `TripleSource`; "Joins, FILTERs, OPTIONALs, etc.
  are all handled by RDF4J's evaluation" (above the Sail, value-aware).
- `BgpExtractor` leaves "FILTER, OPTIONAL, UNION, projection, paths, subqueries" to RDF4J.
- `TriejoinNode`: "surrounding algebra (FILTER, projection, OPTIONAL, **ORDER BY**) is
  untouched"; its `varOrder` is the worst-case-optimal-join *variable* order, unrelated to
  literal value-order. The triejoin needs a **consistent** total order to leapfrog-intersect
  — lexical byte-order is exactly as consistent as value-order.
- No `StatementOrder` service-provider interface implementation, no range-scan API, no order-capability advertised.

**Conclusion: value-ordered indexes are not load-bearing in the current architecture.** No
code path exploits numeric byte-order; `ORDER BY`/range run above the Sail and decode to
values regardless of how terms are stored.

## Options

| Option | RDF 1.1 term equality | Round-trip | Matches RDF4J Sail | Index value-order (measured non-load-bearing) | Dictionary size |
|---|---|---|---|---|---|
| **A — term-faithful** (store the verbatim lexical form for every literal, as `xsd:string` already does) | ✓ correct | ✓ lossless | ✓ | lost, but **unused** today | larger (no value-dedup — which is the *correct* behavior) |
| **B — value-canonical** (keep value storage; make it *consistent* by canonicalizing `decimal` too; document the divergence as intentional) | ✗ over-merges | ✗ lossy | ✗ | retained, but **unused** today | smaller |
| **C — hybrid** (term-faithful terms + a separate value index for future range/ORDER-BY pushdown) | ✓ | ✓ | ✓ | provided *when* pushdown is built | larger + a value index |

### Expanded pros / cons

**A — term-faithful**
- *Pros:* matches RDF 1.1 term identity + RDF4J's `Literal.equals` (Sail-conformant); **lossless round-trip** — for a version-control product this is a *foundational guarantee* (checkout returns exactly what was committed), not a nicety; removes the current decimal-vs-rest inconsistency; simplest mental model ("store the bytes you were given"); does not foreclose C.
- *Cons:* dictionary grows where value-equal/lexically-distinct literals coexist (rare in real data); loses the fixed-width *packed* encoding (an `xsd:integer` stored as its lexical string vs a varint/Int64 — note this is often *smaller* for short numbers/dates, and lives in the dictionary, not the fixed-layout `SpocKey`, so index keys are unaffected); value comparison requires decode+parse — but RDF4J already does exactly that above the Sail, so no *new* cost on the existing query path; numeric *terms* sort lexically in the indexes (measured non-load-bearing).

**B — value-canonical (made consistent)**
- *Pros:* compact fixed-width values; value-ordered indexes (range/`ORDER BY` *could* be pushed down without a separate structure); value-dedup across lexical forms; typed comparison without parsing.
- *Cons:* **violates RDF 1.1 term equality** (over-merge); **lossy round-trip** — disqualifying for a VCS; non-conformant as an RDF4J Sail (a SPARQL store that silently rewrites committed literals); cannot represent two distinct-lexical-same-value terms at all; you must *define and maintain a spec-correct canonical lexical form for every datatype* (canonical `xsd:decimal`/`dateTime`/`double` have real edge cases) — a new correctness surface; surprising to users.

**C — hybrid (faithful terms + value index)**
- *Pros:* correctness of A *and* value-ordered queries; lossless; the principled way to get value-pushdown.
- *Cons:* most implementation; write amplification (two structures maintained per write); **premature** — no current consumer needs value-pushdown (`ORDER BY`/range run above the Sail today), so building it now is speculative (YAGNI).

### A note on where canonicalization *belongs*

If a deployment genuinely wants canonical lexical forms (e.g. to dedup `"01"`→`"1"` at scale),
that is a **lossy normalization the user opts into** — best offered as an explicit import/transform
step (or a SPARQL-visible function), **not** baked silently into the storage layer. Putting it in
storage is what conflates "what was written" with "what we chose to keep." A keeps storage honest
and leaves canonicalization to an explicit, auditable transform.

## Decision

**Option A — term-faithful.** Accepted.

The decision is forced by the **governing invariant**: the content address must not over- or
under-merge — `bytesEqual(encode(a), encode(b))` iff `a` and `b` are the same RDF term. This is
the store's most important correctness property and is *not* tradeable against efficiency.
Option B breaks the forward direction (it over-merges value-equal-but-lexically-distinct terms
by design), so it is ruled out regardless of its performance merits. Only A and C satisfy the
invariant, and C = A + a value index.

Two facts then make A (not C) the right *now*: (1) Option B's only real advantage — value-ordered
indexes — was *measured* to be non-load-bearing (no current query path uses it; `ORDER BY`/range
run above the Sail), so there is nothing to preserve by deferring to C; and (2) for a system
whose purpose is to return exactly what was committed, lossy storage of a committed literal is
the wrong default anyway. A also makes the codec consistent with the behavior `xsd:decimal`
already has, and aligns term identity with RDF 1.1 §3.3.

Option C is the future escape hatch: if range/ORDER-BY pushdown is ever built, add a *value*
index then — value-canonical *terms* are the wrong tool for it (lossy), a value index is the
right one. So choosing A does not foreclose value-ordered query acceleration; it just refuses
to pay for it with lexical correctness.

If instead the maintainer values cross-version value-dedup highly enough to accept lossy
round-trip, Option B is coherent **only if made consistent and documented** — a silent,
partial value-canonicalization (today's state) is the one option ruled out.

## Consequences

If **A** is accepted:
- **On-disk format change** for `xsd:integer`, `xsd:boolean`, `xsd:float`, `xsd:double`, and
  the temporal types **and `xsd:decimal`**: store the verbatim lexical form (modeled on
  `encodeXsdString` — **not** `encodeDecimal`, which over-merges leading-zero/`+`/bare-dot),
  not the parsed value. Pre-1.0, no backwards-compat readers (per `CLAUDE.md`); existing
  stores re-ingest. A separate plan will sequence the encoder/decoder changes + test flips.
- `LexicalFidelityCharacterizationTest`'s `assertEquals(1, …)` lines flip to `2` — a
  conscious, reviewed change (that is why the test was written as characterization).
- Dictionaries grow where data contains value-equal/lexically-distinct literals (rare in
  practice; correct when present).
- Sort order of numeric *terms* in the indexes becomes lexical, not numeric. Measured to
  affect no current query path; if a future pushdown needs value-order, see Option C.
- Net: round-trip becomes lossless; cardinality matches RDF; the port becomes a faithful
  RDF4J Sail for literals.

If **B** is accepted: `xsd:decimal` is changed to *also* canonicalize, the divergence from
RDF term equality is documented as intentional in `lexical-fidelity.md`, and the
characterization test is re-framed as endorsed behavior.

Either way, the **inconsistency is removed** and the behavior is **documented** — the status
quo (silent, partial) is the only unacceptable outcome.

### Corollary (decided 2026-06-12) — ill-typed literals are stored faithfully, NOT validated

Made explicit so it is a *decision*, not a silent side-effect of A: because the encoders store the
verbatim lexical form, the store accepts **ill-typed** literals — a lexical form outside the
datatype's value space (`"maybe"^^xsd:boolean`, and once their encoders convert, `"notanum"^^xsd:int`
etc.). The value encoders *had* to reject these (they could not parse them to a value); the lexical
encoders store them. **Write-time lexical validation is deliberately NOT added to the term store**,
for five grounded reasons:

1. **RDF 1.1 conformance.** An ill-typed literal is a well-formed RDF *term* with identity (RDF 1.1
   §3.3); only its *value* is undefined. A conformant store must represent it.
   `SimpleValueFactory.createLiteral("maybe", XSD.BOOLEAN)` creates it without validation; RDF4J and
   Jena both store ill-typed literals — validation is opt-in (a parser strict mode, SHACL), never the
   store's default.
2. **SPARQL stays conformant.** RDF4J's effective-boolean-value (`QueryEvaluationUtil`, 5.1.4 source)
   reads the *label* and maps an ill-typed boolean to `false` (comment: "also false for illegal
   values"), not a type error, and never consults the codec — so faithful storage changes no query
   result. (The SPARQL path never calls `booleanValue()`; that `Literal`-API method follows the
   strict contract instead — it throws on an ill-typed value via `XMLDatatypeUtil.parseBoolean`,
   matching `SimpleLiteral` — a separate concern from EBV. An ill-typed literal is a faithful *term*
   with no *value*.)
3. **It is the fidelity thesis.** Option A *is* "store exactly what was committed, round-trip
   exactly." Rejecting some terms is a *different* infidelity (refusing valid RDF), not an improvement.
4. **Right layer.** The lexical encoders cannot validate without re-introducing the parse A removed;
   validation is deployment-specific *policy* and belongs above the store (validating import / parser
   strict mode / SHACL), operating on faithfully-stored data.
5. **The regulator case argues *for* faithful storage**: ingesting a flawed submission to *report on
   its errors* requires storing the invalid data, not refusing it at the door. Store faithfully;
   validate + report in a separate, queryable layer.

Pinned end-to-end by `LexicalFidelityCharacterizationTest.ill_typed_boolean_literal_round_trips_faithfully`
(sail) + `TermEncoderTest.ill_typed_boolean_lex_stored_verbatim` (codec). **Transient state:** until
the campaign converts every value-based encoder, the *non-boolean* datatypes still parse-and-reject
ill-typed forms — the end-state invariant lands with `plans/literal-lexical-fidelity.md`. Opt-in
write-time validation, if ever wanted, is a separate future plan (out of scope here).

## Follow-up / future work

- On acceptance of A: a `plans/literal-lexical-fidelity.md` sequencing the encoder/decoder
  changes per datatype, the characterization-test flips, and a re-ingest note.
- Value-ordered query acceleration (range/ORDER-BY pushdown via a value index or RDF4J's
  `StatementOrder` service-provider interface) is a separate, future ADR — independent of this decision.

## Open questions

- **Q1 (RESOLVED 2026-06-12 — no reliance; A can land).** Does any *downstream* consumer
  (SQL-over-Calcite surface, gRPC sync clients) rely on numeric terms sorting in value-order at
  the index level? **No.** Grounded by re-measurement of the non-SPARQL surfaces:
  (1) **no Calcite/SQL surface exists** — no `*calcite*`/`*sql*` module, no `org.apache.calcite`
  dependency anywhere in the reactor; (2) **no `StatementOrder` service-provider-interface
  implementation** — the Sail advertises no order capability and exposes no range-scan API;
  (3) the **gRPC sync path transfers by content-hash reachability, not value-order** —
  `prolly-rdf4j-grpc/.../sync.proto`'s `FetchPack` is a git-style want/have negotiation
  (`bytes want` + `repeated bytes have` for Merkle-skip pruning) returning a raw pack of chunks
  addressed by hash, so a term's lexical-vs-value byte encoding is invisible to sync (a grep of
  the grpc + storage-sync sources for `value-order`/`range-scan`/`ORDER BY`/`compareValue` is
  empty). The SPARQL path's independence was already traced above (the triejoin needs only a
  *consistent* total order — lexical byte-order is as consistent as value-order). With every
  surface confirmed, D-6's "index ordering of numeric terms becomes lexical" affects no consumer,
  and Option A lands via `plans/literal-lexical-fidelity.md`.
