
# ADR-0060: Extract the versioned-store substrate out of prolly-rdf

## Status

Accepted, 2026-06-11. The decision (extract the substrate into its own module) is made; **Q1 (module —
expand the existing `prolly-storage`) decided 2026-06-11**; **Q2 (`indexing` stays in `prolly-rdf`) resolved
2026-06-11 by Phase 0**. **Phase 0 complete** (31 vestigial imports cleansed, compile-proven; the substrate is
provably RDF-free). Execution:
`plans/extract-versioned-store-substrate.md`. Composes with [ADR-0059](0059-extract-prolly-port-core.md)
(extract the Dolt port). Not prolly-json — the build hold does not apply.

## Context

The RDF-free, generic **versioned-store substrate** — `Database` (the versioned store over a content-addressed
chunk store), `storage/` (`RocksNodeStore`, `RocksManifest`), `pool/` (`DirectBufferPool`), `monitor/`,
`sync/`, `GarbageCollector`, the integrity / error-injecting node stores, `VCUtils`, and `indexing/`
(`Table`, `IndexSchema`, the leapfrog join) — currently lives **inside `prolly-rdf`**, the RDF module,
alongside the genuinely RDF-specific layer (`rdf/` and `semantic/` — `GraphPatternEngine`, `QuadPattern`,
`Iri`, the triejoin planner).

The consequence, measured: **~12 modules depend on `prolly-rdf` purely to reach the substrate** — `prolly-json`,
`prolly-csv`, `prolly-fhir`, `prolly-xbrl`, `prolly-parquet`, `prolly-sheets`, `prolly-ml-models`,
`prolly-secrets`, `prolly-feature-flags`, `prolly-audit`, `prolly-urdna2015` — and every one **inherits the
entire RDF query engine + its dependency surface it never uses**. A document store, a CSV loader, and a
feature-flag module should not transitively depend on a SPARQL graph-pattern engine.

### The coupling that looked real is vestigial (measured)

A first check ("does the substrate import RDF?") was scoped to `term|rdf|sparql` and missed `semantic`; a
fuller grep then found **all 17 substrate files import `com.earasoft.prolly.semantic.*`** (the RDF query
engine) — apparently a hard back-edge. A usage check settled it: **0 of the 17 actually use any `semantic`
type.** Every `import com.earasoft.prolly.semantic.*` in the substrate is a **dead wildcard import** (uniform
boilerplate, e.g. `DirectBufferPool` imports it and references nothing from it). With those removed, the
substrate imports **no** RDF (`rdf` / `semantic` / `term` / `rdf4j`) — it is genuinely RDF-free and
import-closed. *(The original "RDF-free" call was right but under-grounded — logged as a refutation; the
lesson is the closure must be measured, not asserted.)*

This decision touches module structure + ~12 consumer poms (high revisitation cost), so it is an ADR.

## Options

| Option | What | Decouples the 12 modules? | Risk |
|---|---|---|---|
| **A — extract substrate to its own module (chosen)** | move the RDF-free substrate out of `prolly-rdf`; both `prolly-rdf` and the 12 consumers depend on it | **yes** | a multi-module file move + pom rewire; packages unchanged |
| B — status quo | substrate stays inside `prolly-rdf` | no | none, but the smell persists |
| C — extract to a separate **repo** now | substrate as a published artifact | yes | premature — settle the in-repo module boundary first (the ADR-0059 lesson) |

## Decision

**Adopt Option A: extract the RDF-free versioned-store substrate from `prolly-rdf` into its own module.**

- **D-1 — The split.** **Substrate (moves to `prolly-storage`):** `Database`, `storage/`, `pool/`, `monitor/`,
  `sync/`, `GarbageCollector`, `IntegrityVerifyingNodeStore`, `ErrorInjectingNodeStore`, `TreeIntegrityChecker`,
  `ParallelReachabilityWalker`, `VCUtils`. **Stays in `prolly-rdf`:** `rdf/`, `semantic/`, **and `indexing/`**
  (Q2, resolved by Phase 0 — the substrate doesn't use it; only the query layer does). The substrate depends down on `prolly-port-core` (the `com.dolthub.prolly` primitives);
  `prolly-rdf` depends on the substrate; the 12 substrate-only consumers repoint to the substrate.

- **D-2 — Delete the vestigial `semantic.*` imports first; that step *reveals* the true graph.** Phase 0 of the
  plan removes the 17 dead wildcard imports (zero behaviour change) so the substrate's import-closure is
  *provably* RDF-free — converting "is it really separable?" into a measured **yes**, and exposing the genuine
  edges between `substrate` / `indexing` / `semantic` / `rdf` that the wildcards hid. The refactor is gated on
  this measurement, not on a guess.

- **D-3 — Packages stay `com.earasoft.prolly.*`; this is a file relocation, not a rename.** Moving
  `Database.java` (package `com.earasoft.prolly`) from `prolly-rdf` to the substrate module keeps its
  fully-qualified name, so **no `import` in moved code or in any of the 12 consumers changes** — only the poms
  (dependency rewire) and the deleted dead imports. No split package results: the top-level
  `com.earasoft.prolly` is wholly substrate (no RDF classes sit there), and `com.earasoft.prolly.{rdf,semantic}`
  are wholly RDF; no sub-package is split across the two modules. (No `module-info.java` exists, so the
  classpath tolerates it regardless.)

- **D-4 — The substrate's tests + gates travel with it.** The substrate tests (the `Database` commit
  compare-and-set / Lincheck proofs, garbage-collection reachability, `RocksNodeStore`, and the
  `core-engine-test-strategy` suite that targets the substrate) move to the new module and carry its mutation /
  jcstress / coverage gates; RDF tests stay in `prolly-rdf`.

- **D-5 — This is the higher-value sibling of ADR-0059, and lower-risk.** ADR-0059 extracts the *port*
  (`com.dolthub`) to a repo; the port is already isolated. *This* extraction is what actually frees the 12
  domain modules from RDF, and it is an **in-repo module split** (no publish/versioning workflow), so it is the
  one to do first. Target onion: `prolly-port-core` → **`prolly-storage`** → `{prolly-rdf, prolly-json,
  prolly-csv, …}` as peers.

## Consequences

**Positive.**
- ~12 domain modules stop transitively depending on the RDF query engine + its dependency surface.
- A clean, named substrate/RDF boundary; the substrate becomes independently testable and (later) repo-
  extractable on its own (sets up an ADR-0059-style cut for the substrate if ever wanted).
- The dead-import deletion is a hygiene + build-clarity win on its own.

**Negative / costs.**
- A multi-module refactor: move ~24 main files + the substrate test suite; rewire ~12+ consumer poms + the BOM;
  the reactor order shifts (the substrate slots between core and prolly-rdf).
- Transitive-dependency surprises possible (a consumer that *did* use something RDF via prolly-rdf must now
  depend on prolly-rdf explicitly) — Phase 0's import cleanup + a full reactor build catch these.

**Neutral.**
- Because packages are unchanged, consumer *source* is untouched — the move is mechanical once the dead imports
  are gone. The risk is in the poms + the reactor, not the code.

## Follow-up / future work

- Execution plan `plans/extract-versioned-store-substrate.md` (Phase 0 cleanse → module create + move → consumer
  rewire → test/gate split → reactor + BOM).
- Optionally, after this lands, revisit ADR-0059 with the substrate as a second repo-extraction candidate.
- **Deferred `-rocksdb` split (considered + declined 2026-06-11).** The module keeps the name `prolly-storage`,
  not `prolly-storage-rocksdb`. Measured at the Phase-1 move: only `storage/` (3 files — `RocksNodeStore`,
  `RocksManifest`, `SharedRocksDb`) is RocksDB-specific; the rest (`Database`, `GarbageCollector`, `sync/`, the
  integrity / error-injecting node stores, `pool/`, `VCUtils`) is engine-neutral over the `NodeStore` interface,
  which lives in `prolly-port-core`. So `-rocksdb` would misname the engine-neutral majority, and renaming now
  re-churns every consumer pom + the BOM for no present gain. **If a second backend ever lands** (LMDB, an
  in-memory store, or object storage), split `storage/`'s 3 files into a `prolly-storage-rocksdb` and keep the
  neutral substrate as `prolly-storage` — the `NodeStore` seam already makes that cut cheap. Until then the
  rename is premature (the no-just-in-case discipline).

## Open questions

- **Q1 — module name — DECIDED 2026-06-11: expand `prolly-storage`.** The substrate moves *into* the existing
  `prolly-storage` module, which already sits at reactor slot 3 between `prolly-port-core` and the engine
  modules and is defined (ADR-0030 / the reactor comment) as *"shared low-level storage primitives between
  prolly-port-core and the engine modules"* — exactly the substrate's role. It has **no internal dependencies**
  (so no cycle when `prolly-rdf` comes to depend on it) and its one file (`SharedRocksDb`) is self-contained.
  This *fulfils* prolly-storage's documented intent rather than adding a module. (The rejected alternative — a
  new `prolly-substrate` — would have left two storage modules.)
- **Q2 — does `indexing` go to the substrate or stay with the query layer? — RESOLVED by Phase 0 (2026-06-11):
  it STAYS in `prolly-rdf`.** Measured after the dead-import cleanse: **no substrate file uses any `indexing`
  type** (`Database`'s `indexing.*` import was itself vestigial), and `indexing`'s only consumer is the RDF
  query layer (`semantic`: `GraphPatternEngine`, the triejoin). So `indexing` is generic relational code whose
  consumer is the query engine — it belongs with the query layer, not the substrate. The substrate's
  import-closure is now provably free of `indexing` as well as `semantic` / `rdf` / `term` / `rdf4j`.
