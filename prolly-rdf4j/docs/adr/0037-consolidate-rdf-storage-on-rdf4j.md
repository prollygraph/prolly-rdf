
# ADR-0037: Consolidate all RDF storage on the RDF4J / ProllySail implementation

## Status

Accepted, 2026-05-30. Guides `plans/consolidate-rdf-on-rdf4j.md`. Resolves [ADR-0036](0036-unify-rdf-encoding-on-term-codec.md) **Q1** (the native engine does not survive as a parallel store) and supersedes that ADR's "neutral note" deferral of the versioning-model question for the BOM/URDNA2015 consumers.

**Outcome (2026-05-30) — landed.** `prolly-bom` (via `ProllySailBomStore`, product = repo) and `prolly-urdna2015` (via `CanonicalizingProllySail`) now ride the RDF4J Sail; `VersionedQuadStore`, `VersionedProllyRdfStore`, `VersionedProllyBomStore`, `DictionaryMap`, and the `prolly-jena` module were deleted; the author seam (`setNextCommitAuthor` + `CommitLog` `a=` token) shipped. Full reactor builds green; the convergence proof (`SailTriejoinOnRealIndexesTest`) still passes. `CommitLogSync` author propagation across push/pull **also landed** (the wire line gained a `<base64Author|->` token; `mergeInto` preserves it). Kept as deferred follow-ons (do not block the retirement): removing `LeapfrogTriejoin`'s `dict==null` raw-IRI branch + the raw-IRI BGP test/demo conversion (entangled with the kept WCOJ engine), and production wiring of `ProllySailBomStore` against the multi-tenant `PerRepoSailRegistry` (the Q1 namespace/permissions policy below).

## Context

The codebase carries **three RDF storage surfaces**, a redundancy [ADR-0036](0036-unify-rdf-encoding-on-term-codec.md) began unwinding at the *encoding* layer:

1. **`prolly-jena`** — an Apache Jena `Graph` adapter (`VersionedProllyGraph`) over `prolly-rdf`'s `VersionedProllyRdfStore`. Raw-IRI encoded. A **leaf** — no reactor module depends on it; the two pom mentions elsewhere are comments.
2. **`prolly-rdf`'s native engine** — `VersionedQuadStore` (raw-IRI keys, `Encoding.IRI`) + `GraphPatternEngine`/`LeapfrogTriejoin`, backed by the **`Database`** git-like versioning model (`commit(branch, added, deleted, author, msg)` → commit hash, plus diff/blame/merge). **Not dormant**: it is the storage substrate for two shipped domain modules —
   - **`prolly-bom`** (`VersionedProllyBomStore`) — versioned Software Bill of Materials store (one commit per ingested SBOM, component-granularity diff/blame/merge), shipped in the server via **`prolly-bom-rest`**.
   - **`prolly-urdna2015`** (`CanonicalizingQuadStore`) — a canonicalize-at-commit wrapper that delegates `commit`/`query`/`merge` to `VersionedQuadStore`.
3. **`prolly-rdf4j`'s `ProllySail`** — the **production** RDF4J Sail. Dictionary-encoded `TermId` keys (per ADR-0036), four maintained permutation indexes, **`CommitLog`/`RootMetaTree`** versioning, a conformance ratchet, and the SPA's commits / diff / blame / provenance surfaces.

ADR-0036 unified the *encoding* (everything moves to `TermId` via `prolly-codec`) and proved the WCOJ triejoin runs on ProllySail's real indexes — but left **Q1 open**: does `prolly-rdf`'s engine survive as a thin parallel store, or not? The deciding fact discovered while scoping this work: the native engine's only remaining *production* role is backing BOM + URDNA2015, and **both ride the `Database` versioning model, not ProllySail's**. So "one RDF implementation" is not a delete — it is a **migration** of those two modules off the native engine, after which the native engine retires.

The operator's directive (2026-05-30): *optimize RDF4J, do not maintain three implementations; `prolly-bom` and `prolly-urdna2015` should use the RDF4J implementation.*

## Options

| Option | RDF storage engines | BOM/URDNA2015 substrate | Versioning models live | Encodings | Effort |
|---|---|---|---|---|---|
| **A** — status quo | 3 (jena, native, ProllySail) | native `VersionedQuadStore` | 2 (`Database` + `CommitLog`) | 2 (raw-IRI + TermId) | none |
| **B** — migrate consumers to ProllySail, retire native engine + jena (this ADR) | 1 (ProllySail) | ProllySail (per-product repo) | 1 for RDF (`CommitLog`); `Database` kept only for demos/versioning suite | 1 (TermId) | high (phased) |
| **C** — keep native engine solely for BOM/URDNA2015 | 2 (native + ProllySail) | native | 2 | 2 | low, but perpetuates the split |

## Decision

**Adopt B.** ProllySail becomes the **sole *versioned* RDF storage implementation**; `prolly-bom` and `prolly-urdna2015` migrate onto it; the native `VersionedQuadStore` engine and the Jena adapter retire. Rejected A (the redundancy is the smell) and C (keeping the native engine *only* for two consumers is the worst of both — two engines, two encodings, two versioning models, forever).

> **Scope clarification (2026-05-30).** "Sole RDF implementation" means **sole *versioned* engine**. The
> project also ships `prolly-flatsail` — an *unversioned* RDF4J Sail storing quads as plain sorted RocksDB
> keys (no Merkle tree, no history), the deliberate fast/simple sibling of ProllySail, sharing the
> `prolly-codec` term codecs. That is a **distinct capability**, not the redundant duplication this ADR
> retires, so it is correctly out of scope and stays. Read every "sole RDF implementation" claim here and
> in the plan as "sole *versioned* RDF implementation."

**D-1 — ProllySail is the single *versioned* RDF storage surface.** All *versioned* RDF triples/quads in the project live in a ProllySail-backed store. The native raw-IRI versioned engine has no production consumer after the migration. (The unversioned `prolly-flatsail` Sail is the separate fast-path, untouched.)

**D-2 — A BOM "product" maps to a multi-tenant *repo*.** BOM models each product as an independent versioned history; ProllySail v2.0 is single-branch-per-instance, so "product = branch" cannot ride one live Sail. Reuse the shipped multi-tenant repo hosting ([ADR-0016](0016-multi-tenant-repo-hosting.md)): **product → repo**, isolation + warm-set LRU + per-repo `CommitLog`/`RootMetaTree` come for free; product lifecycle = repo create/drop. Rejected "product = named graph in one repo" (collapses per-product history independence — a commit would span all products) and "Sail-per-product" (duplicates what the repo registry already solves, scales poorly).

**D-3 — Add an author seam to ProllySail's in-process commit.** ProllySail today plumbs only a one-shot commit *message* (`setNextCommitMessage`); author arrives from the HTTP context, not the programmatic path. BOM needs per-commit author. Add a companion `setNextCommitAuthor` (and persist author in the `CommitLog` entry), so a non-HTTP caller can set both. Minimal, symmetric with the existing message seam.

**D-4 — BOM keeps its `BomStore` interface; the implementation swaps to a ProllySail backing.** A new `ProllySailBomStore` writes/reads components via an RDF4J `RepositoryConnection`, resolves a product's repo through the registry, and re-points the existing component-granularity **diff** (read components at both tips via `openSnapshotAt`, compare PURLs) and **blame** (walk `CommitLog.entries()` first-parent, read component snapshots — the logic already in `VersionedProllyBomStore.blame`) at ProllySail snapshots + commit log. **No dependency on `prolly-rdf4j-rest`** — BOM's diff/blame are component-level and computed in BOM from snapshots, so the REST module's `TripleDiff` is not pulled in.

**D-5 — URDNA2015 becomes a transparent Sail wrapper.** Replace `CanonicalizingQuadStore` with `CanonicalizingProllySail` (wraps a `ProllySail`, canonicalizes added/deleted statements at `SailConnection.commit()`, preserves the fail-closed time-budget + `NonCanonicalizableException` semantics from the whitepaper). The canonicalize-at-commit contract is unchanged; only the delegate changes.

**D-6 — Keep `Database`, the TermId WCOJ triejoin engine, and the storage primitives; retire the rest of the native RDF surface.** Kept: `Database` (the prolly-tree versioning engine — used by the 4 demos + the R-1 versioning-algebra suite, independent of RDF), the `TermId` triejoin engine (`LeapfrogTriejoin`/`TrieIterator`/`GraphPatternEngine`/`SelectivityVariableOrder` — the ADR-0036 convergence asset, with its raw-IRI `dict==null` branch removed once no raw-IRI caller remains), and `com.earasoft.prolly.storage.*`. Retired: `VersionedQuadStore`, `VersionedProllyRdfStore`, `DictionaryMap`, the `prolly-jena` module, and the raw-IRI query path + its tests.

**D-7 — No migration shim (pre-1.0).** Per the no-backwards-compat rule, BOM/URDNA2015 data does not auto-migrate from the old `Database`-backed layout to the new ProllySail/`CommitLog` layout. Operators back up and re-ingest (an operator-run one-shot reload, not runtime code). The on-disk format break is accepted.

## Consequences

- **Positive:** one RDF engine, one encoding (`TermId`), one RDF versioning model (`CommitLog`); BOM + URDNA2015 inherit ProllySail's maintained permutation indexes, provenance, conformance ratchet, and the SPA's commits/diff/blame surfaces; the dormant-engine + Jena-adapter maintenance cost is gone; ADR-0036 Q1 is settled.
- **Negative / cost:** a real multi-module migration (BOM store rebuild, URDNA2015 Sail wrapper, ~9 integration suites + the BOM REST test re-pointed); a small author seam in ProllySail + `CommitLog`; an on-disk format break for BOM/URDNA2015 with no auto-migration (back-up-and-reload); per-product **repo proliferation** (bounded by the warm-set LRU, but many products = many repos — operationally new for BOM).
- **Neutral:** `Database` survives as a *versioning* primitive (demos + R-1 suite), so "retire the native engine" is precise — the RDF *facade* and raw-IRI *query path* go, the version-store substrate the demos exercise stays. The two-versioning-models question ADR-0036 flagged is **resolved for RDF** (RDF is `CommitLog`-only after this) but `Database` is not deleted.

## Follow-up / future work

- Wiring the kept `TermId` WCOJ triejoin into ProllySail's `EvaluationStrategy` (flag-gated) remains the ADR-0036 Phase-3 follow-on — unaffected by this consolidation.
- Whether BOM exposes its own product-lifecycle REST or simply reuses `/repos` admin endpoints is deferred to the plan's Phase 1.

## Open questions

- **Q1** — Does BOM need a dedicated repo *namespace* (e.g. `bom/<product>`) to avoid colliding with user RDF repos in the same registry, or is a naming convention enough? Decide during Phase 1 wiring.
