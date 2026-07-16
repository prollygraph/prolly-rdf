
# On-disk / wire format contract (scaffold)

**Status: scaffold.** This section will specify the port's **own** format — the byte-level
contract a reader and writer must agree on — as distinct from the RDF-semantics invariants
in [`../semantics/`](../semantics/). Pre-1.0 the format evolves freely (no backwards-compat
readers, per `CLAUDE.md`); byte-for-byte parity with Dolt is **optional / deferred**. So
this contract is *internal consistency* (writer and reader always agree), not external
parity.

Until the prose lands, the format is pinned by these tests — the authoritative source:

| Area | What it fixes | Pinned by |
|---|---|---|
| **Term encoding** | One tag byte + per-type payload layout for every RDF/XSD term. | `prolly-codec/.../term/TermCodecStringsTest`, `TermCodecBoundaryTest`, `TermCodec*Test` (numerics, dates, IRIs/BNodes). |
| **Term → bytes dispatch** | RDF4J `Value` routes to the correct `TermCodec` call. | `prolly-codec/.../term/TermEncoderTest`. |
| **SPOC key** | The 4-`TermId` index tuple: 42 bytes (4×8 data + 4×2 offsets + 2 count). | `prolly-codec/.../index/SpocKeyTest`. |
| **Quad orderings** | The SPOC/POSC/OSPC/CSPO column permutations + role mapping. | `prolly-codec/.../index/QuadOrderTest`. |
| **Cross-language parity (Layers 0–2)** | The parity that *does* hold vs Dolt, as characterization (not a contract). | `CrossLanguageFixtureTest` (engine repo); see [`../../docs/bitcompat-findings.md`](../../docs/bitcompat-findings.md). |

## To fill in (per-file backlog)

- `term-encoding.md` — the tag-byte table + payload layout per XSD type; the
  signed-vs-unsigned `TermId` ordering trap (already documented in
  the termid-ordering-trap explainer in the private monorepo's doc tree — link, don't duplicate).
- `spoc-key.md` — the 42-byte tuple, the offset table, and the `fromTuple` fixed-offset
  read vs the generic `getFieldSegment` table read (the subtlety `SpocKeyTest` pins).
- `quad-order.md` — the four permutations, why each index exists, the `QuadRole` mapping.
- `node-framing.md` — the prolly node wire shape (chunk boundaries, level framing).

Each file follows the catalog's table form from [`../README.md`](../README.md): an ID, the
rule, the port behavior with a `file:line`, and the validating test.

## Where this lives

- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/term/TermCodec.java` — term byte layouts.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/index/SpocKey.java` — the index key tuple.
- `prolly-codec/src/main/java/com/earasoft/prolly/rdf4j/index/QuadOrder.java` — the column permutations.
- [`../../docs/bitcompat-findings.md`](../../docs/bitcompat-findings.md) — what parity with Dolt holds and what doesn't.
