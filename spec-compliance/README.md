
# Spec-compliance catalog

This folder is a catalog of the **conformance invariants** the port must satisfy that
the result-comparison test suites (the W3C SPARQL 1.1 query/update suites in
`prolly-rdf4j-compliance`) **cannot see**. It exists to be *used to validate the spec*:
each entry names a rule from a published spec, states the invariant precisely, records
how the port satisfies it, and points at the test that *actually exercises the regime
where the rule can fail*.

## The governing invariant — the content address must not over- or under-merge

Everything in this catalog is in service of one rule, the most important correctness
property the store has. Stated as a **bijection** between byte identity and RDF term
identity:

> **`bytesEqual(encode(a), encode(b))` if and only if `a` and `b` are the same RDF term**
> (RDF4J `Value.equals`).

- **Forward** (byteEqual ⇒ term-equal) forbids **over-merge** — distinct RDF terms must
  never collapse to one content address (data loss; [`lexical-fidelity.md`](semantics/lexical-fidelity.md)).
- **Reverse** (term-equal ⇒ byteEqual) forbids **under-merge** — equal RDF terms must never
  get two content addresses (the langString case bug; [`canonicalization.md`](semantics/canonicalization.md)).

RDF term identity (RDF 1.1 §3.3, as RDF4J implements it): lexical form + datatype IRI
char-by-char; language tag lower-cased; a bare `"foo"` *is* `"foo"^^xsd:string`. The bijection
spans **all three** identity components — the over-merge bugs live on the lexical-form axis
([`lexical-fidelity.md`](semantics/lexical-fidelity.md)) *and* the datatype-IRI axis
([`datatype-identity.md`](semantics/datatype-identity.md)). A **precondition** of the bijection
is representability: a literal the store cannot encode at all (`DTYPE-2` — the unwired
`encodeCustomLiteral`) has no content address to be correct about. This invariant is **not
negotiable against efficiency** — it is why option B (value-canonical storage) is ruled out in
[ADR-0043](../prolly-rdf4j/docs/adr/0043-literal-lexical-fidelity.md): B over-merges by design.
The executable gate is `prolly-codec/.../term/TermIdentityBijectionTest` (reverse direction
green; forward direction the acceptance gate for the term-faithful fix).

## Why this is a separate thing from the W3C suites

The W3C SPARQL 1.1 suites are the project's headline conformance signal — 261 tests
(171 query + 90 update) in `prolly-rdf4j-compliance`. They are necessary and they pass.
But they share one shape: **"query Q over dataset D produces result R."** That shape is
*structurally blind* to a whole class of correctness rule:

1. **Internal-identity / content-addressing invariants.** "Two equal RDF terms must
   map to one `TermId`, one storage row, one root hash" is not a query *answer* — it
   is a property of the store's canonical representation. No result-comparison test
   asserts it.
2. **Invariants whose dimension the oracle itself erases.** The result oracle compares
   with RDF term equality (`Value.equals`). When the bug *is* a distinction that term
   equality defines away — e.g. language-tag case (`"x"@en-US` vs `"x"@en-us`, which
   RDF 1.1 declares the same term) — the oracle cannot detect it *in either direction*.
   A clean green is then guaranteed regardless of whether the store is right.
3. **Invariants no fixture enters the regime for.** A bug that needs *two case-variants
   in one dataset* to manifest never fires against fixtures that use one consistent
   casing per tag — which is every real fixture.

The motivating case is [`semantics/canonicalization.md`](semantics/canonicalization.md):
language-tag canonicalization was wrong for the life of the codec, the 261-test W3C
suite passed the entire time, and lowercasing *every* tag in the store changed **zero**
of those 261 results. The suite was blind to the regime; the validating instrument is a
byte-identity test, not a query.

This is the measurement discipline from `CLAUDE.md` applied to conformance: **a clean
result from the wrong instrument is self-concealing.** This catalog is where we name the
right instrument per invariant.

## How to read an entry

Every invariant is one row in its file's table:

| Field | Meaning |
|---|---|
| **ID** | Stable handle, e.g. `CANON-LANG-1`. Cite it from tests and commit messages. |
| **Spec** | The published rule + section, e.g. *RDF 1.1 Concepts §3.3*. |
| **Invariant** | The rule stated as a checkable property of the port. |
| **Port behavior** | How the port satisfies it, with a `file:line` citation. |
| **Validated by** | The test that exercises the *regime where it can fail* — and, when relevant, a note on why the obvious test would be **fooled**. |
| **W3C-visible?** | Whether the result-comparison suites could catch a regression here (almost always **no** — that's why the row exists). |

The "Validated by" column is the point of the whole catalog: it must name an instrument
that enters the failing regime, not one that merely runs the code.

## Layout

```
spec-compliance/
  README.md                  this file
  semantics/                 RDF 1.1 / SPARQL 1.1 data-model invariants (built first)
    canonicalization.md      canonicalization RDF *requires*: language-tag case (CANON-LANG-1) + simple-literal==xsd:string
    lexical-fidelity.md      over-merge on the lexical-form axis: integer/boolean/double/dateTime drop the lexical form (LEXFID-1)
    datatype-identity.md     over-merge on the datatype-IRI axis (DTYPE-1: 6 derived ints collapse) + can't-store gap (DTYPE-2)
    term-identity.md         equal terms -> one TermId -> one row -> one root hash; the Statement.equals confound
  format/                    the port's OWN on-disk/wire format contract (scaffold; filled later)
    README.md                scope + pointers to existing format tests/docs
```

## Status

- `semantics/canonicalization.md` — **started**; flagship invariant `CANON-LANG-1`
  (language-tag case) is grounded and validated. Now scoped to the canonicalizations RDF
  *requires* — an earlier over-broad list of numeric/temporal "canonical forms" is
  **retracted in place** (those are over-merges, not required invariants; see below).
- `semantics/lexical-fidelity.md` — **started**; `LEXFID-1`, the over-merge finding —
  `xsd:integer`/`boolean`/`double`/`dateTime` drop the lexical form (RDF-distinct terms
  collapse, lossy round-trip). Grounded by a Sail probe, pinned by a characterization test.
  Opens an ADR-worthy decision (term-faithful vs value-canonical).
- `semantics/datatype-identity.md` — **started**; the gaps the lexical audit missed, on the
  datatype-IRI axis. `DTYPE-1`: six derived integer types (`nonNegativeInteger`, … `unsignedByte`)
  over-merge with `xsd:integer`. `DTYPE-2` (representability): string-derived XSD, several temporal,
  and **all custom datatypes** can't be stored — `encodeCustomLiteral` is built but unwired.
  Grounded by probes, pinned by `DatatypeIdentityCharacterizationTest`. Same fix as `LEXFID-1`
  (the general `(datatype-TermId, lexical)` path).
- `semantics/term-identity.md` — **started**; states the identity chain, documents the
  `Statement.equals` confound that fools the naïve test, and `IDENT-1` is validated by an
  executable content-address test (`LangTagContentAddressInvariantTest`) with a control arm.
- `format/` — **scaffold only.** Pointers to the existing format tests; prose to follow.
