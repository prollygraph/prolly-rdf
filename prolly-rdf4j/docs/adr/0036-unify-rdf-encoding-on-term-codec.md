
# ADR-0036: Unify the RDF engines on the term-codec / TermId encoding

## Status

Accepted, 2026-05-30. Guides `plans/unify-rdf-encoding-on-term-codec.md`. Unblocks [ADR-0035](0035-worst-case-optimal-join-for-bgps.md) D-5 (triejoin wiring) and re-opens part of [ADR-0034](0034-streaming-triejoin-index-permutations.md) Option C.

**Update 2026-05-30 — outcome so far: correctness, not speed.** The convergence
landed end-to-end (Dictionary→codec; TermId-native `LeapfrogTriejoin`; **proven**
running on ProllySail's real indexes ≡ ProllySail SPARQL). But the Phase-2
re-benchmark settled the D-5 wall-time hope **negatively**: fixed-width `TermId`
keys did *not* close the Step-16 ~80× gap (≈596ms vs MemoryStore's ≈8ms at N=380,
unchanged from raw-IRI). The bottleneck is **structural** — per-query projection
rebuild + allocation-heavy engine — *not* key width. So the value delivered is
**deployability + one encoding**, and the triejoin stays **flag-gated/experimental**
(D-7). A real wall-time win needs *true streaming* (no per-query projection) +
constant-factor work, which is a larger follow-on than this ADR.

**Update 2026-05-30 (later) — the deferral premise is partially OVERTURNED: the
triejoin now wins cyclic joins.** The "larger follow-on" above landed as the
allocation/CPU work in `prolly-rdf/plans/triejoin-performance.md`
Phase 3: triejoin descent allocation **944 → 17.3 MB/query @N=380 (~54×)** via core-primitive
fixes (cached layouts, in-place `compareAt`/`fieldEquals`, reused level-key buffer, self-invalidating
`Cursor.currentKey` cache, in-place `compareFieldAt`), with wall-time ~591 → ~22 ms. A fresh
head-to-head (single fork, @N=380) reframes the go/no-go:

| query | triejoin-native | prolly-sail (bind-join) | winner |
|---|---:|---:|---|
| **triangle** (cyclic) | **21.8 ms** | 40.6 ms | **triejoin ~1.86×** |
| path2 (acyclic) | 13.4 ms | 7.2 ms | bind-join ~1.85× |
| star (acyclic) | 11.9 ms | 7.5 ms | bind-join ~1.6× |

This is textbook WCOJ: the triejoin **beats ProllySail's own bind-join on the cyclic
triangle** (it dodges the O(N²) intermediate blow-up) but **loses on acyclic shapes**
(no blow-up to avoid; the bind-join's simpler per-binding lookup wins). So D-6/D-7's
deferral — premised on "the triejoin doesn't beat the alternatives" — no longer holds
*globally*: there is now a concrete win case. The implication for **Phase 6 wiring** is
**selective routing, not blanket**: a query planner that detects cyclic / multi-way BGPs
routes them to the triejoin and leaves acyclic BGPs on the bind-join. Still flag-gated
(experimental + selective), but the GO signal for cyclic joins is now positive. (Gap to
in-memory MemoryStore narrowed from ~80× to ~11× — and MemoryStore is unversioned/in-RAM,
so the *relevant* comparison is vs the bind-join on the same storage, where the triejoin
now wins where it should.)

**Update 2026-05-31 — the wiring shipped (flag-gated), and the end-to-end win is modest.** The triejoin
is now wired into ProllySail's SPARQL evaluation behind `prolly.rdf4j.triejoin-enabled` (default OFF),
routing only cyclic / multi-way default-graph BGPs (GYO α-acyclicity), per
`plans/triejoin-evaluation-wiring.md`. **Correctness is
locked:** the W3C SPARQL 1.1 query suite with the flag ON is **171/0, identical to the flag-OFF baseline**,
and a randomized agreement property (triangle + 4-cycle over random graphs) confirms flag-ON ≡ flag-OFF.
**Perf, end-to-end through SPARQL:** the cyclic win **survives but dilutes** to ~1.12–1.13× (triangle@380/650)
from the engine-level ~2.17× — the per-result-cell TermId→`Value` decode + `BindingSet` construction is
shared SPARQL-layer overhead that dominates the join-algorithm gap at these sizes; acyclic shows no
regression. **The flag stays default-OFF / experimental** (D-6): safe to expose as an opt-in for cyclic-heavy
workloads, but default-ON awaits a rigorous multi-fork/larger-N confirmation + trimming the decode/`BindingSet`
cost (the next lever now that the algorithm is no longer the end-to-end bottleneck).

## Context

The codebase has **two parallel RDF engines with two encodings**:

- **`prolly-rdf`** (`VersionedQuadStore` + `GraphPatternEngine`/`LeapfrogTriejoin`):
  keys are **raw UTF-8 IRI strings** (`Encoding.IRI`), with its own
  `indexing/DictionaryMap` (a string↔id map) and `Database` versioning. Its query
  path is **dormant** — reachable only from tests + `VersionedQuadStore.queryMulti`.
  It is the **home of the worst-case-optimal-join (WCOJ) triejoin** built in ADR-0035.
- **`prolly-rdf4j`** (`ProllySail`): keys are **dictionary-encoded `TermId`s**
  (`Encoding.Int64`) via `term/Dictionary`, with `CommitLog`/`RootMetaTree`
  versioning and four maintained permutation indexes (SPOC/POSC/OSPC/CSPO). It is
  the **production** engine. It uses `prolly-rdf` for *storage primitives only*
  (`com.earasoft.prolly.storage.*`) — never the semantic engine.

This split is why ADR-0035 D-5 **deferred wiring the triejoin into SPARQL**: the
triejoin reads raw-IRI indexes; ProllySail keys by `TermId`. The mismatch also
means two dictionaries, two on-disk encodings, and a fast WCOJ join that cannot run
on the engine that actually serves queries.

**Two facts make convergence cheap and high-value:**

1. **`prolly-codec` is already the encoding home** — it owns `TermEncoder`,
   `TermCodec`, `TermId`, `HashFunction`, *and* `QuadOrder`. The **only** misplaced
   piece is `Dictionary` (in `prolly-rdf4j`, coupled solely to `obs.SailMetrics`).
2. **ProllySail already maintains a graph-leading permutation, CSPO** (`c,s,p,o`).
   ADR-0034 declined "Option C" (maintained graph-leading permutations) on
   *new-index write-amp cost* — but ProllySail **already pays for** these four.
   Running the triejoin over ProllySail's existing indexes therefore needs **no new
   index**, partially re-opening the wall-time path Option C's cost had closed.

## Options

| Option | Encodings | Triejoin deployable? | Redundancy | On-disk format change | Effort |
|---|---|---|---|---|---|
| **A** — status quo | two (raw-IRI + TermId) | no (D-5 blocked) | two engines, two dicts | none | none |
| **B** — converge on `TermId` (this ADR) | one (TermId) | yes — rides ProllySail's indexes | removed | `prolly-rdf` indexes change | high (phased) |
| **C** — retire `prolly-rdf`'s engine | one (TermId, rdf4j) | only if triejoin re-homed | removed | none | medium, loses the native engine + triejoin home |

## Decision

**Adopt B: one encoding — dictionary-encoded `TermId` — shared by both engines via
`prolly-codec`.** Rejected A (the redundancy + the dormant-but-blocking triejoin is
the smell we are removing) and C (the triejoin is real, oracle-proven work; re-host
it, don't delete it — and C still requires the same `TermId` plumbing).

**D-1 — `Dictionary` moves to `prolly-codec`.** Plus `HashFunctions` /
`CollisionChainExhausted`. It needs only `NodeStore`/`BufferPool`/`StaticMap`/`TermId`
(all ≤ codec). The package stays `com.earasoft.prolly.rdf4j.term`, so **consumers
don't change imports** — and it *consolidates* a package currently split across two
modules. `DictionaryTermResolver` (couples `TermId`→RDF4J `ProllyValue`) stays in
`prolly-rdf4j`. New edge `prolly-rdf → prolly-codec` (acyclic).

**D-2 — Break `Dictionary`'s `SailMetrics` coupling with a minimal codec-level
metrics seam.** A tiny `EncoderMetrics` interface (or a no-op default) in codec;
`SailMetrics` implements/adapts it. Observability is preserved, the module boundary
is honored.

**D-3 — `prolly-rdf`'s engine becomes `TermId`-native; retire its raw-IRI path and
`DictionaryMap`.** `VersionedQuadStore` encodes terms through `TermEncoder`+`Dictionary`,
stores `TermId`-keyed SPOC/POSC, and threads a `dictRoot` through the `Database`
commit. Pre-1.0, this is a clean format break — **no migration shim** (per the
no-BC rule); operators back up + reload.

**D-4 — Accept the loss of index lexical order (the TermId ordering trap).**
`TermId` order is hash-derived, not semantic (the-termid-ordering-trap).
This is *already* how the production engine behaves: `getStatements` is exact-term
seek (encode the bound term → `TermId`), and ORDER BY / range live in RDF4J's
algebra, not the index. So no capability is lost that ProllySail had.

**D-5 — Make the triejoin column-width-aware, then ride ProllySail's maintained
indexes.** `TrieIterator`'s sublinear successor-seek used append-0x00 (correct only
for variable-length keys; `readInt64` ignores the trailing byte). For fixed-width
`Int64`/`TermId` columns it must use **value-increment** (`v+1`). With that, the
triejoin seek-scopes off ProllySail's *existing* POSC/SPOC/CSPO (no new index) with
**fixed-width keys** — directly attacking the two Step-16 wall-time culprits
(per-query full projection + variable-length-key allocation). The gain is **to be
measured, not asserted** (ADR-0035 D-10): full streaming of predicate-bound
patterns still wants CPSO/CPOS (not maintained), so the realistic win is
seek-scoped projection on maintained `TermId` indexes, re-benchmarked.

**D-6 — Wiring into ProllySail stays flag-gated, default OFF** (the operator's
prior call). Convergence makes the wiring mechanical; the W3C suite then runs with
the flag ON as the end-to-end correctness gate (closes ADR-0035 D-5 + the
rdf4j-test-strategy Step 18).

## Consequences

- **Positive:** one encoding, one dictionary, one `TermId` index layout; the WCOJ
  triejoin becomes deployable over the production indexes; fixed-width keys cut
  index size + comparison/allocation cost; the `com.earasoft.prolly.rdf4j.term`
  split package is healed; the dormant-engine smell is gone.
- **Negative / cost:** an on-disk format change for `prolly-rdf` (no migration —
  back-up-and-reload); a real refactor across three modules; lexical index order is
  gone (acceptable per D-4); the wall-time win is *hypothesized*, gated on the
  Phase-2 re-benchmark — it may still lose, in which case the triejoin stays
  flag-gated and we have at least removed the encoding redundancy.
- **Neutral:** versioning models are **not** merged here (`Database` vs
  `CommitLog`/`RootMetaTree`) — out of scope; this ADR unifies *encoding*, the
  enabler, not the version store.

## Follow-up / future work

- If Phase-2 wall-time still loses on predicate-bound cyclic queries, revisit
  maintained **CPSO/CPOS** — but now the cost question is "one more permutation on
  the *existing* maintained set", a smaller ask than ADR-0034's original Option C.
- Merging the two versioning models (`Database` ↔ `CommitLog`/`RootMetaTree`) is a
  separate, larger ADR if the native engine is kept long-term.

## Open questions

- **Q1** — Does `prolly-rdf` keep its own `Database` versioning + 4 maintained
  `TermId` permutations, or does its engine become a *thin* layer that ProllySail
  drives over ProllySail's indexes? Decide after Phase 1 shows the conversion cost.
  **Resolved 2026-05-30 by [ADR-0037](0037-consolidate-rdf-storage-on-rdf4j.md):**
  `prolly-rdf`'s native quad-store engine (`VersionedQuadStore`) is **retired**, not
  kept as a parallel store — its two shipped consumers (`prolly-bom`, `prolly-urdna2015`)
  migrated onto ProllySail, and the store + the Jena adapter + `DictionaryMap` were
  deleted. `Database` (versioning, used by demos + the R-1 suite) and the standalone
  `TermId` WCOJ triejoin engine (the convergence asset) are kept. ProllySail is now the
  sole *versioned* RDF implementation (the unversioned `prolly-flatsail` Sail is a distinct
  fast-path sibling, out of scope — see ADR-0037).
