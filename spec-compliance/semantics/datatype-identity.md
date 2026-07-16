
# Datatype-IRI identity (the gaps the lexical-form audit missed) — RESOLVED

> **RESOLVED 2026-06-12 (ADR-0043, the unifying fix below — `encodeCustomLiteral` as the general path).**
> `DTYPE-1` (derived-integer over-merge) and `DTYPE-2` (un-storable datatypes) are both fixed: derived
> integers and the whole long tail (custom, `xsd:duration`, `gMonth`, …) now route through the
> Dictionary-backed custom path and round-trip to their exact `(lexical form, datatype IRI)`. Proven by
> the now-**green** `TermFaithfulnessGateTest`. Kept as the record of the findings + their resolution.

[`lexical-fidelity.md`](lexical-fidelity.md) audited one component of RDF term identity — the
**lexical form**. RDF 1.1 §3.3 term equality has **three** components compared char-by-char:
lexical form, **datatype IRI**, and language tag. This doc covers the datatype-IRI axis, where
a comprehensive bijection probe (2026-06-04) found two more gaps. Both were missed by the
first pass precisely because it fixated on the lexical axis — a reminder that **"complete" is a
claim to distrust until the whole space is swept.** The campaign *re-proved* this twice over: the
Phase-1 step enumeration **missed `xsd:base64Binary`/`xsd:hexBinary`** (still byte-decoding,
over-merging `"0A"`/`"0a"`), caught only when re-anchoring on the acceptance gate's exhaustive case
list before enabling it — and a wrap-up that declared the campaign "complete" before that gap was
closed had to be retracted. The gate, not a confident enumeration, is what certified completeness.

## `DTYPE-1` — derived integer types over-merge with `xsd:integer`

| Field | Value |
|---|---|
| **Spec** | RDF 1.1 §3.3 — datatype IRIs compared char-by-char. `xsd:nonNegativeInteger` ≠ `xsd:integer` ⇒ `"5"^^xsd:nonNegativeInteger` and `"5"^^xsd:integer` are different terms. |
| **Invariant** | Same lexical form + **different datatype IRI** ⇒ different content address. |
| **Port behavior** | **RESOLVED.** The six derived types (`nonNegativeInteger`, `positiveInteger`, `negativeInteger`, `nonPositiveInteger`, `unsignedShort`, `unsignedByte`) now route through the Dictionary-backed custom path (`isDedicatedDatatype` excludes them) and preserve their exact datatype IRI; the fixed-width six (`int`/`long`/`short`/`byte`/`unsignedInt`/`unsignedLong`) stay faithful via their dedicated 1:1 tags. *(Was: the six derived types collapsed to the `xsd:integer` encoding, datatype IRI dropped.)* |
| **Validated by** | The green `TermFaithfulnessGateTest` (exhaustive) + `TermFaithfulSparqlTest`'s derived-integer round-trips (`"5"^^xsd:nonNegativeInteger` keeps its IRI, distinct from `"5"^^xsd:integer`). `DatatypeIdentityCharacterizationTest`'s codec-level collapse assertion stays — but reframed as the Dictionary-less *fallback*; the faithful storage is a Sail-level property (the `ProllyValueFactory.createLiteral` eager-collapse was the subtle catch, see [`lexical-fidelity.md`](lexical-fidelity.md) — fixed by keeping non-faithful datatypes as `SimpleLiteral`). |
| **W3C-visible?** | No — result comparison uses term/value equality that does not surface a dropped datatype subtype, and fixtures rarely co-locate `"5"^^xsd:integer` with `"5"^^xsd:positiveInteger`. |

Grounded probe (same lexical `"5"`, inserted alongside `xsd:integer`) — then → now:

| Derived type | Then → Now |
|---|---|
| `int`, `long`, `short`, `byte`, `unsignedInt`, `unsignedLong` | distinct ✓ (always — dedicated tags) |
| `nonNegativeInteger`, `positiveInteger`, `negativeInteger`, `nonPositiveInteger`, `unsignedShort`, `unsignedByte` | over-merge with `xsd:integer` → **distinct ✓** (custom path, 2026-06-12) |

## `DTYPE-2` — whole datatypes cannot be stored (the unwired `encodeCustomLiteral`)

| Field | Value |
|---|---|
| **Spec** | RDF 1.1: a literal may carry *any* datatype IRI. A conformant store must accept all of them. |
| **Invariant (representability)** | Any well-formed literal must be storable, and round-trip to its own `(lexical form, datatype IRI)`. |
| **Port behavior** | **RESOLVED.** `ProllySailConnection.encodeTerm` now routes a non-dedicated datatype through `DictionaryTermEncoder.encodeForWrite` → `TermCodec.encodeCustomLiteral` (datatype IRI interned as a `TermId` + verbatim lexical bytes), and `DictionaryTermResolver` resolves it back on read. Every well-formed literal is storable and round-trips to its own `(lexical, datatype IRI)`. The codec's `TermEncoder.encode` still throws for a non-dedicated datatype **by design** — it has no Dictionary; that throw is the layer boundary, not a gap. *(Was: the write path threw "unsupported datatype"; `encodeCustomLiteral` was built-but-unreachable.)* |
| **Affected (now all storable)** | String-derived XSD: `normalizedString`, `token`, `language`, `Name`, `NCName`, `NMTOKEN` (+ `ENTITY`/`ID`/`IDREF`). Temporal: `dateTimeStamp`, `gMonth`, `gDay`, `gMonthDay`, `duration`, `dayTimeDuration`, `yearMonthDuration`. **All custom (non-XSD) datatypes.** |
| **Validated by** | The green `TermFaithfulnessGateTest` (its `allCases` covers custom + `xsd:duration`/`gMonth`/the string-derived long tail) + `TermFaithfulSparqlTest`'s custom-datatype round-trips. `DatatypeIdentityCharacterizationTest.unsupported_datatypes_throw_at_the_codec_by_design` reframed: the codec throw is the designed layer boundary; the Sail stores it. |
| **W3C-visible?** | Partially — a W3C test using `xsd:duration`/`gMonth` would *error*, not mis-answer; such tests are absent from the green corpus or in its known-failures baseline. The custom-datatype hole is invisible to W3C entirely (its fixtures are XSD-typed). |

This is a different *class* from over/under-merge — it is a **representability** gap (can't store
the data at all) — but it is the larger correctness hole: real RDF routinely uses `xsd:duration`,
`xsd:dateTimeStamp`, and domain-specific custom datatypes, and today they fail on ingest.

## Root cause: one dispatch rule, three symptoms

All three families — `LEXFID-1`, `DTYPE-1`, `DTYPE-2` — are the **same rule**: the `if`-chain in
`TermEncoder.encodeLiteral` (`prolly-codec/.../term/TermEncoder.java`, lines ~64–111). Its shape
is "match a fixed datatype set → value-encode; else throw," and each symptom is one defect of
that shape:

- **`DTYPE-1`** — lines ~82–89 funnel `nonNegativeInteger`/`positiveInteger`/`negativeInteger`/
  `nonPositiveInteger`/`unsignedByte`/`unsignedShort` into `encodeXsdInteger(lit.getLabel())`,
  the *same* call as `XSD.INTEGER` (line 77). The datatype IRI never reaches the bytes. The
  fixed-width six (`BYTE`/`SHORT`/`INT`/`LONG`/`UNSIGNED_INT`/`UNSIGNED_LONG`, lines ~74–76,
  90–92) each get a distinct width-encoder — which is *why* they're preserved.
- **`DTYPE-2`** — the chain's terminal branch (lines ~108–110) is `throw "unsupported datatype"`,
  not a fallback to the general `(datatype-`TermId`, lexical)` path.
- **`LEXFID-1`** — nearly every branch parses the label to a *value* (`parseBoolean`,
  `Byte.parseByte`, `lit.floatValue()`, `encodeXsdInteger`→`BigInteger`) and encodes that,
  dropping the lexical form. The faithful exceptions (`encodeXsdString`, `encodeAnyURI`,
  `encodeDecimal`) are precisely the branches that *don't* parse. (Note: even `encodeDecimal` is
  only *partly* faithful — `BigDecimal` preserves trailing-zero scale, so `"1.0"`≠`"1.00"`, but
  it **does** fold leading-zero/`+`-sign/bare-dot — measured `"01.0"`=`"1.0"` and `".5"`=`"0.5"`.
  So `xsd:string`/`xsd:anyURI` are the *only* truly verbatim-lexical encoders.)

So it is **one rule bug**, and the fix is one rule change (below), not three patches.

## The unifying fix (implemented)

`DTYPE-1`, `DTYPE-2`, and the lexical-form over-merge (`LEXFID-1`) all had **one** root cause and
**one** fix: the encoding must preserve the full identity tuple `(lexical form, datatype IRI)`,
and it must do so for *every* datatype. The fix, **implemented across the campaign**, made the
**`encodeCustomLiteral` shape — `(datatype-IRI-as-TermId, raw lexical bytes)` — the general path**
for typed literals (the dedicated tags that survive each became verbatim-lexical too):

- preserves the datatype IRI exactly (fixes `DTYPE-1`),
- handles every datatype incl. custom + the string/temporal long tail (fixes `DTYPE-2`),
- preserves the lexical form (fixes `LEXFID-1`),
- satisfies the bijection by construction: `bytes = f(datatype-TermId, lexical-bytes)`, and the
  datatype-`TermId` is bijective with the datatype IRI (dictionary), the lexical bytes bijective
  with the lexical form — so the whole encoding is bijective with `(datatype IRI, lexical form)`.

Dedicated per-datatype tags may remain as a *space* optimization **only if** each tag maps to one
exact datatype IRI *and* carries the verbatim lexical form (the current tags fail both: shared
across subtypes, value-based). See [ADR-0043](../../prolly-rdf4j/docs/adr/0043-literal-lexical-fidelity.md)
and `plans/literal-lexical-fidelity.md`.

## Where this lives

- `prolly-codec/.../term/TermEncoder.java` — the datatype dispatch + `isDedicatedDatatype` (the faithful-tag-vs-custom boundary; throws for a non-dedicated datatype, by design — no Dictionary at the codec).
- `prolly-codec/.../term/TermCodec.java` — `encodeCustomLiteral` (the general path, now wired) + the verbatim-lexical per-datatype encoders.
- `prolly-rdf4j/.../value/DictionaryTermEncoder.java` + `DictionaryTermResolver.java` — the Dictionary-backed custom write/read path (datatype IRI interned as a `TermId`).
- `prolly-rdf4j/.../sail/ProllySailConnection.java` — `encodeTerm` (now custom-aware via `DictionaryTermEncoder`).
- `prolly-codec/.../term/DatatypeIdentityCharacterizationTest.java` — reframed: the codec-level collapse/throw is the Dictionary-less fallback / designed boundary; faithfulness is a Sail property.
- `prolly-rdf4j/.../sail/TermFaithfulnessGateTest.java` — the **acceptance gate**, now **ENABLED + green**: exhaustive round-trip over every datatype + a `tries=2000` bijection property. Green ⟺ all datatypes faithful (`LEXFID-1` + `DTYPE-1` + `DTYPE-2` resolved).
