# prolly-rdf — RDF on prolly trees

RDF storage built on content-addressed prolly trees: dictionary-encoded terms, four
maintained SPOC permutation indexes, commit-DAG versioning (branches, tags, merge,
sync), worst-case-optimal triejoin routing for cyclic basic graph patterns, and an
[Eclipse RDF4J](https://rdf4j.org/) Sail as the front door. This repo is the **RDF
ring** over the engine ring at
[prollygraph/prolly-core](https://github.com/prollygraph/prolly-core) — the prolly-tree
engine (chunking, content addressing, structural sharing) lives there; everything
RDF-shaped lives here.

## Conformance and testing, up front

- **SPARQL 1.1 conformance: 174/176 query, 90/90 update** (W3C suites via Eclipse's
  published `rdf4j-*-testsuite` artifacts, run in every gated build). The two known
  query failures are named below, not hidden.
- **3,085 tests, 0 failures** across the ring's seven modules — measured 2026-07-24 on
  the full gated build; `mvn clean install` is always the live count. Two tests are
  deliberately parked with a filed bug (an upstream concurrency choreography needing two
  concurrently-open write transactions); they are `@Disabled` with the reason inline.
- **The known-failures baseline may shrink but never grow** — a conformance ratchet
  enforced by the build (last shrank 2026-06-12, 5 → 2).

**The two known query failures**, from the
[conformance frontier](prolly-rdf4j-compliance/docs/conformance-frontier.md):

| Test | Why it fails |
|---|---|
| `constructwhere04 - CONSTRUCT WHERE` | `CONSTRUCT WHERE` with a `FROM` dataset clause — `FROM`-document resolution is not wired through the Sail. Candidate fix identified. |
| `(pp35) Named Graph 2` | Property-path evaluation across named graphs is not implemented. Feature backlog. |

Full report with methodology and reproduction steps:
[`CONFORMANCE.md`](CONFORMANCE.md).

Maintainer and contact routes: [`MAINTAINERS.md`](MAINTAINERS.md).

## The repo family

Three sibling repos ("rings") under [prollygraph](https://github.com/prollygraph),
versioned in lockstep at `0.2.0-BETA`, each consumable on its own:

| Repo | What it is |
|---|---|
| [`prolly-core`](https://github.com/prollygraph/prolly-core) | The **engine ring**: the prolly tree itself (content-defined chunking via a rolling hash, flatbuffer nodes with zero-copy reads, cursors, diff, three-way merge), the RocksDB- and file-backed durable stores, pack-based sync (in-process and over gRPC), and the many-repos registry primitive. No RDF, no server — the substrate every other ring builds on. |
| `prolly-rdf` (this repo) | The **RDF ring**: everything RDF-shaped, from term codecs up to the RDF4J Sail — the modules below. Depends on the engine ring's `io.github.prollygraph` artifacts. |
| [`prolly-json`](https://github.com/prollygraph/prolly-json) | The **JSON ring**: a versioned JSON document substrate — documents shredded into pointer-addressed leaf rows with byte-deterministic canonicalization, so shared structure shares chunks and diff is O(changed). Depends only on the engine ring and Jackson; development is currently parked (see its README). |

## Modules

| Module | What it is |
|---|---|
| [`prolly-codec`](prolly-codec/README.md) | Term/tuple codecs: RDF terms → order-preserving key bytes (parity-encoded int64s, tagged term encoding, SPOC key composition) |
| [`prolly-rdf`](prolly-rdf/README.md) | The versioned RDF engine: SPOC indexes on prolly trees, the worst-case-optimal `LeapfrogTriejoin`, diff/merge, reachability garbage collection |
| [`prolly-flatsail`](prolly-flatsail/README.md) | An unversioned RocksDB-backed Sail — small, simple, used as a differential oracle against the versioned Sail |
| [`prolly-rdf4j`](prolly-rdf4j/README.md) | The Eclipse RDF4J Sail (`ProllySail`): SPARQL via RDF4J's engine with triejoin routing, commit/branch/tag/merge surfaces, sync |
| [`prolly-urdna2015`](prolly-urdna2015/README.md) | URDNA2015 / RDFC-1.0 canonicalization on the Sail |
| [`prolly-rdf4j-compliance`](prolly-rdf4j-compliance/README.md) | The W3C conformance suite (fixtures arrive via Eclipse's published `rdf4j-*-testsuite` artifacts — none embedded) |
| [`prolly-rdf-dependencies`](prolly-rdf-dependencies/README.md) | The ring BOM: rdf4j-bom + testsuite + jqwik/caffeine/logback alignment, and the convergence pins a standalone build needs |

Background reading is under [`docs/`](docs/README.md) (the chunk store, prior art, the
Dolt bit-compatibility posture); the deep references are
[`prolly-rdf4j/ARCHITECTURE.md`](prolly-rdf4j/ARCHITECTURE.md) and the 74 architecture
decision records under [`prolly-rdf4j/docs/adr/`](prolly-rdf4j/docs/adr/).

[`spec-compliance/`](spec-compliance/README.md) is the invariants catalog (term identity, lexical fidelity,
canonicalization, datatype identity) — the spec corners a result-comparing test suite is
blind to, each entry citing the test that pins it; a rot-guard test keeps the citations
resolving.

**New to the project?** The [landing page](landing-page/index.html) is the two-minute
version — the benefits, the honest limits, and a time-travel demo built from a real
run — before the full path below. The whole doc set builds into a themed static site:
`python3 landing-page/build.py .` (needs `pip install markdown`) renders every tracked
markdown file into a git-ignored `dist/`, landing page as the front door, internal
links rewritten and self-checked.

## Learning the code

The suggested path, shallowest to deepest:

1. **The data structure.** If prolly trees are new to you, start with the engine
   ring's [foundations](https://github.com/prollygraph/prolly-core/blob/main/docs/foundations/the-prolly-tree.md)
   and its live write-path explorer — everything here assumes them.
2. **Run something.** The [embedded quickstart](prolly-rdf4j/docs/getting-started.md)
   opens a versioned Sail in ~20 lines; then work through the
   [13 runnable demos](prolly-rdf4j/README.md#examples--runnable-demos) — each is a
   `main()` you can read top to bottom, and each is CI-locked by a test so the
   narratives can't rot.
3. **The concepts.** The [foundations set](docs/README.md#foundations--the-concepts-everything-else-assumes)
   — start with [rdf-in-five-minutes](docs/foundations/rdf-in-five-minutes.md) if RDF
   is new, then the two Sails, structural sharing, versioning vs MVCC, the triejoin.
4. **One invocation, end to end.** The five
   [anatomy walkthroughs](docs/README.md#anatomy--one-concrete-invocation-end-to-end)
   — a scan, a term, an ingest, a SPARQL query, a versioned query — each following
   real code with the bugs found along the way.
5. **What it can do.** [`prolly-rdf4j/FEATURES.md`](prolly-rdf4j/FEATURES.md) — the
   feature inventory, with a per-feature status legend and an honest known-gaps list.
6. **How it works.** [`prolly-rdf4j/ARCHITECTURE.md`](prolly-rdf4j/ARCHITECTURE.md)
   (the top-level reference), then the
   [design docs](prolly-rdf4j/docs/README.md) and the
   [architecture decision records](prolly-rdf4j/docs/adr/) for any decision you want
   the why behind.
7. **The correctness bar.** [`spec-compliance/`](spec-compliance/README.md) and the
   conformance section below — what "correct" means here and how it's pinned.

Contributing? [`CONTRIBUTING.md`](CONTRIBUTING.md) covers the build gates (they *are*
the build), test conventions, and when a change needs an architecture decision record.

## How the triejoin engages RDF4J's query engine

Readers who know RDF4J will assume its query engine sits above the Sail and defeats any
storage-level join advantage. Here is exactly what happens
([ADR-0065](prolly-rdf4j/docs/adr/0065-triejoin-routing-default-on.md)):

- Routing lives in the Sail's `evaluate()`: the connection inspects the algebra RDF4J
  hands it, extracts basic graph patterns, and routes a `Join` subtree to the
  worst-case-optimal leapfrog triejoin **only when its variable hypergraph is cyclic**.
- **Acyclic, star, and single-pattern queries deliberately fall back to RDF4J's
  bind-join**, which wins on those shapes. The triejoin fires only where
  worst-case-optimal evaluation has an advantage.
- Routing is **on by default** (`prolly.rdf4j.triejoin-enabled`, default-on since
  2026-06-21) with a correctness lock: the W3C SPARQL 1.1 query suite runs flag-on with
  results identical to flag-off, plus a randomized Sail-level agreement property.
- Measured effect (multi-fork JMH, Welch's t): the cyclic triangle query is **2.56×
  faster at 380 edges, 2.81× at 2,000** versus bind-join, and **11.5× on the real
  wiki-Vote graph** (3.2 s vs 36.8 s); the acyclic two-hop control is within noise.

## Conformance, measured

The W3C suite runs in-repo on every build: **SPARQL 1.1 query 174 of 176** (two
baselined known failures — unimplemented features, each classified in the
[conformance frontier](prolly-rdf4j-compliance/docs/conformance-frontier.md); the same
174 pass again with triejoin-cardinality routing forced), **SPARQL 1.1 update 90/90
with an empty baseline**, and the RDF4J store/repository contract suites. The
**known-failures baseline** (`prolly-rdf4j-compliance/src/test/resources/known-failures/`)
is allowed to *shrink but never grow* — a conformance ratchet (it last shrank
2026-06-12, 5 → 2). Full ring, measured at the 2026-07-16 extraction commit: **3,065
tests, 0 failures** (2 deliberately parked with a filed bug: an upstream concurrency
choreography that requires two concurrently-open write transactions — see the test's
`@Disabled` note). Re-measured 2026-07-24 (full gated build, post-onboarding-docs tree):
**3,085 tests, 0 failures**. `mvn clean install` is always the live count.

## Build

Requires JDK 25 and the engine ring installed (until its artifacts are on Maven
Central):

```bash
git clone git@github.com:prollygraph/prolly-core.git && cd prolly-core
mvn -DskipTests install && cd ..
git clone git@github.com:prollygraph/prolly-rdf.git && cd prolly-rdf
mvn clean install   # the full gated build: tests + spotless + license + convergence
```

The quality gates are the build: google-java-format (AOSP) via spotless, Apache-2.0
license headers, `dependencyConvergence` enforcement, CycloneDX SBOMs per module, and
the suites above.

## Status

Version `0.2.0-BETA`, released in lockstep with the engine ring (see
[`RELEASING.md`](RELEASING.md)), evolving **pre-1.0 with no backwards compatibility**: formats are
deterministic and internally consistent (writer and reader always agree), and they
change freely between versions — no defensive readers, no migration shims. Extracted
from a private monorepo on 2026-07-16; the Java packages (`com.earasoft.prolly.*`)
predate the extraction and stay put.

Release history: [`CHANGELOG.md`](CHANGELOG.md). Vulnerability reports:
[`SECURITY.md`](SECURITY.md). Conduct: [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).

Apache-2.0.

## AI Disclosure

This project was developed with AI assistance.

- **Tools used:** Claude Opus 4.7, Claude Opus 4.8, and Claude Fable (Anthropic), and
  Gemini (Google).
- **Scope of use:** AI assisted with writing code, tests, and documentation across the
  ring's modules (the term codecs, the RDF engine, the RDF4J Sail, and the compliance
  suites).
- **Human oversight:** All AI-assisted output is reviewed by a human maintainer before
  it is committed, and the maintainers take full responsibility for everything in this
  repository, regardless of how it was produced.
- **Verification:** All contributions, AI-assisted or not, must pass the full test
  suite and build gates — including the W3C SPARQL query and update compliance suites,
  property-based tests, mutation-testing thresholds, and the cross-language fixture
  characterization against the engine ring.
- **Licensing:** AI-assisted contributions are released under the same
  [Apache-2.0](LICENSE) terms as the rest of the project.
- **Contributions:** If you use AI tools to help prepare a pull request, please say so
  in the PR description, review the output yourself before submitting, and confirm you
  have the right to contribute it under the project license.
