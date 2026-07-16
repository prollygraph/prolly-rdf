
# prolly-rdf4j — Feature Set

A versioned RDF triple/quad store presented to applications as an RDF4J
`Sail`. It speaks SPARQL 1.1 over a content-addressed Prolly Tree
substrate, giving Git-style versioning of the graph itself.

This file is the at-a-glance capability map. The authoritative detail lives in [`ARCHITECTURE.md`](ARCHITECTURE.md) and the design docs under [`docs/`](docs/README.md).

## Status legend

| Mark | Meaning |
|------|---------|
| ✅ | Working — implemented and tested |
| 🔵 | Planned — has a phase plan (in the private monorepo's work tracker) |
| 🟡 | Proposed — has an ADR in [`docs/adr/`](docs/adr/), not yet planned/built |
| ❌ | Out of scope — deliberately not pursued |

> Honesty note: this project keeps an explicit "what's missing" list (the Known gaps
> section below). Marks are kept truthful, not aspirational — a feature is ✅ only
> when it is real. (Marks re-verified against the code 2026-07-16.)

## Storage substrate

| Feature | Status | Reference |
|---|---|---|
| Content-addressed Prolly Tree (Merkle DAG), structural sharing | ✅ | `ARCHITECTURE.md` |
| RocksDB-backed `NodeStore` | ✅ | — |
| Off-heap storage via the Panama `MemorySegment` API (final since JDK 22; repo targets 25) — heap flat regardless of dataset size | ✅ | `ARCHITECTURE.md §1` |
| Off-heap `NodeCache` bounds the working set | ✅ | — |

## RDF & query

| Feature | Status | Reference |
|---|---|---|
| Standard RDF4J `Sail` | ✅ | — |
| SPARQL 1.1 query + update (inherited from RDF4J) | ✅ | — |
| Quad store — four indexes: SPOC / POSC / OSPC / CSPO | ✅ | plan 3 |
| Cost-based `IndexPlanner` | ✅ | plan 3 |
| BGP engine + Leapfrog Triejoin | ✅ | plan 4 |
| Type-tagged binary term encoding — full literal / datatype / lang fidelity | ✅ | `ARCHITECTURE.md §4` |
| URDNA2015 / RDFC-1.0 canonicalization — Stage 1 (string-level), shipped as `CanonicalizingProllySail` in `prolly-urdna2015` | ✅ (2026-07-16 re-mark) | [`../prolly-urdna2015/`](../prolly-urdna2015/README.md) |
| Byte-level canonicalizer (Stage 2) | 🔵 | `ARCHITECTURE.md §4.6` |
| RDF-star quoted triples — encoding tags + `ProllyTriple` value layer shipped; end-to-end Sail support | 🔵 | `ARCHITECTURE.md §4.2` |
| Typed-range FILTER index (PT-int) | 🔵 | `ARCHITECTURE.md §4.4` |
| RDFS / OWL reasoning | ❌ | — |
| SPARQL `SERVICE` federation | ❌ | — |

## Versioning

| Feature | Status | Reference |
|---|---|---|
| Git-style commits (RootMetaTree DAG), branches (RefsStore), commit log | ✅ | [`docs/root-meta-tree.md`](docs/root-meta-tree.md) |
| Time-travel snapshot reads (`?commit=<hash>`); Memento-Datetime | ✅ | — |
| Diff between commits | ✅ | — |
| 3-way merge — *byte-set-union; correct only for blank-node-free RDF* | ✅ | — |
| Structural merge — O(changed), not O(source-triples) (`MergeEngine.mergeStructural`) | ✅ (2026-07-16 re-mark; shipped 2026-05) | — |
| Canonicalization-aware RDF merge — correct for blank nodes + reification, with conflict detection | 🔵 | [ADR-0009](docs/adr/0009-canonicalizing-rdf-merge.md) / plan 10 |
| Multi-store — N independently-versioned datasets over one shared chunk pool | 🔵 | [ADR-0008](docs/adr/0008-multi-store-shared-nodestore.md) / plan 9 |

## Provenance & audit

| Feature | Status | Reference |
|---|---|---|
| Per-triple provenance — first-seen commit, opt-in (`ProvenanceIndex`) | ✅ (2026-07-16 re-mark) | [ADR-0001](docs/adr/0001-provenance-index.md) |
| Per-triple event log — the Sail's `eventSinkFactory` SPI hook is shipped; the sink implementation is a private commercial module | 🔵 (SPI ✅) | [ADR-0003](docs/adr/0003-per-triple-event-log.md) |

## Versioning surface as SPARQL

The store's own versioning metadata, projected back into SPARQL as
read-only virtual graphs so a plain SPARQL client can query it.

| Feature | Status | Reference |
|---|---|---|
| Commit log as a virtual RDF graph (`urn:prolly:meta:commits`) | 🟡 | [ADR-0006](docs/adr/0006-commit-log-as-rdf.md) |
| VoID / DCAT dataset statistics as a virtual graph (`urn:prolly:meta:void`) | 🟡 | [ADR-0007](docs/adr/0007-void-dataset-statistics-graph.md) |

## Deployment

| Feature | Status | Reference |
|---|---|---|
| Embeddable — single RDF4J Sail jar, in-process | ✅ | — |
| Service — `prolly-rdf4j-rest` (Spring Boot): `/sparql` + versioning endpoints (lives in the private monorepo, not this repo) | ✅ (private) | — |
| Performance budgets + JMH regression gate in CI (budgets exist in `ARCHITECTURE.md §8`; no CI bench job is wired) | 🔵 | `ARCHITECTURE.md §8` |

## Known gaps (honest list)

- **Merge is byte-set-union — incorrect for RDF with blank nodes /
  reification** until ADR-0009's canonicalization-aware merge lands
  (verified still open 2026-07-16: no canonicalizing merge in the Sail).
  This is the largest unowned correctness gap. The structural merge
  (shipped) makes merge *fast*, not blank-node-*correct*.
- **Engine-port fidelity** — ~~unverified / Go-side stub~~ **resolved
  2026-05-29** (stale claim corrected 2026-07-16): the cross-language
  fixture loop ran; Layers 0–2 parity with Dolt is pinned as
  characterization, and byte-for-byte bit-compatibility is deliberately
  OPTIONAL — see [`../docs/bitcompat-findings.md`](../docs/bitcompat-findings.md).
- **No competitive benchmark** vs TerminusDB on a representative
  workload.
- **Reasoning** (RDFS/OWL) and **`SERVICE` federation** are out of
  scope by design.

## Positioning

A JVM-native, embeddable-or-served, content-addressed **versioned RDF
store with a real SPARQL surface** — strong on versioning, provenance,
and audit; deliberately *not* a feature-breadth competitor to
Stardog / Neptune / GraphDB.
