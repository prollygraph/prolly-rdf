# docs — background reading for the RDF ring

| Doc | What it covers |
|---|---|
| [`the-chunk-store.md`](the-chunk-store.md) | The content-addressed chunk store the ring writes into — the substrate contract everything here assumes |
| [`prior-art.md`](prior-art.md) | Where this design sits relative to Dolt, RDF triple stores, and versioned-graph prior art |
| [`developer-skill-sets.md`](developer-skill-sets.md) | What working on this ring asks of you — competencies per module, ramp difficulty, and where to start given what you already know. |
| [`operator-notes.md`](operator-notes.md) | Running a process that embeds this Sail: index/disk cost, memory during ingest and query, choosing between the two Sails, backup, and upgrade. |
| [`bitcompat-findings.md`](bitcompat-findings.md) | The Dolt bit-compatibility findings (historical record): what parity holds, and why byte-for-byte compatibility is deliberately optional |

## Foundations — the concepts everything else assumes

Read in rough order ([`foundations/`](foundations/); exported from the monorepo's
onboarding set, the RDF-ring slice — engine-side foundations like the prolly tree
itself live in the
[prolly-core docs](https://github.com/prollygraph/prolly-core/blob/main/docs/README.md)):

| Doc | What it explains |
|---|---|
| [rdf-in-five-minutes](foundations/rdf-in-five-minutes.md) | Triples, quads, SPARQL, and the RDF4J Sail service provider interface — the domain both stores plug into |
| [the-two-sails](foundations/the-two-sails.md) | Why this repo ships two Sails (versioned `ProllySail`, flat `RocksDbFlatSail`), and how the storage substrate alone explains which one wins which query |
| [structural-sharing-and-churn](foundations/structural-sharing-and-churn.md) | Why editing one row in a billion-row tree rewrites a handful of nodes — a law, not an optimization |
| [versioning-vs-mvcc](foundations/versioning-vs-mvcc.md) | Why git-style history needs content-addressed immutability, not a database's multi-version reads |
| [the-concurrency-model](foundations/the-concurrency-model.md) | One writer at a time, many readers always — and why an immutable tree makes that cheap |
| [the-leapfrog-join-contract](foundations/the-leapfrog-join-contract.md) | The sorted-inputs precondition of the worst-case-optimal join — and the two real bugs that hid behind passing example tests |
| [the-leapfrog-triejoin](foundations/the-leapfrog-triejoin.md) | From intersecting one variable to binding many — why the triejoin beats chained pairwise joins on cyclic patterns |
| [the-termid-ordering-trap](foundations/the-termid-ordering-trap.md) | An unsigned "flag bit" id stored in a signed-compared column — two correct-looking pieces of code sorting the same bytes opposite ways |
| [rdf-canonicalization](foundations/rdf-canonicalization.md) | Deterministic blank-node labels (URDNA2015 / RDFC-1.0) so semantically identical graphs hash identically — what content-addressed merge needs |
| [the-untrusted-byte-boundary](foundations/the-untrusted-byte-boundary.md) | Where bytes you didn't write enter the system, and why every parser at that edge must reject the malformed without crashing |

## Anatomy — one concrete invocation, end to end

Read in order ([`anatomy/`](anatomy/); exported from the monorepo's onboarding set,
the RDF-ring slice — the engine-side B-series lives in the
[prolly-core docs](https://github.com/prollygraph/prolly-core/blob/main/docs/README.md)):

| Doc | What it walks |
|---|---|
| [A1 · a scan](anatomy/A1-a-scan.md) | `?s ?p ?o` → a stream of decoded statements: index choice, prefix seek, per-row decode, term resolution — and the 50× bug that lived on it |
| [A2 · a term](anatomy/A2-a-term.md) | An RDF `Value` → an 8-byte `TermId`: dictionary encoding, interning, and the two ID schemes (flat counter vs versioned hash) |
| [A3 · an ingest](anatomy/A3-an-ingest.md) | `conn.add(...)` → four committed index keys: transaction buffering, interning, the 4× permutation write, atomic commit |
| [A4 · a SPARQL query](anatomy/A4-a-sparql-query.md) | An HTTP `POST /sparql` → a streamed result set: parse, guard, and RDF4J's engine calling back into the Sail's `getStatements` |
| [A5 · a versioned query](anatomy/A5-a-versioned-query.md) | `?commit=` / `Accept-Datetime` → results as of the past: commit resolution, the `RootMetaTree`, and why snapshots are almost free |

Deeper, per-module: [`../prolly-rdf4j/ARCHITECTURE.md`](../prolly-rdf4j/ARCHITECTURE.md)
(the top-level reference), [`../prolly-rdf4j/docs/`](../prolly-rdf4j/docs/README.md)
(design docs), [`../prolly-rdf4j/docs/adr/`](../prolly-rdf4j/docs/adr/) (74 architecture
decision records — point-in-time records; some reference the private monorepo's work
tracker by name), and [`../spec-compliance/`](../spec-compliance/README.md) (the
invariants catalog). Engine-side foundations live in the
[prolly-core docs](https://github.com/prollygraph/prolly-core/blob/main/docs/README.md).
